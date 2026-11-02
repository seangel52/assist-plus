package com.havoice.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** What a scan does: add to what's on hand (the default), set what's on hand, or throw away. */
enum class InventoryAction(val label: String, val verb: String) {
    ADD("Add", "add"),
    UPDATE("Update", "update"),
    DISCARD("Discard", "discard"),
}

/** One scanned item in the list under the scanner. */
data class InventoryScan(
    val id: Long,
    val barcode: String,
    val action: InventoryAction,
    val amount: String,
    val status: Status,
    val text: String,
) {
    enum class Status { WORKING, DONE, NEEDS_YOU, FAILED }
}

/**
 * Inventory mode: scans barcodes one after another with the camera kept up. Each scan goes
 * straight to the inventory through a Home Assistant script (no AI per scan, so it's instant);
 * a barcode the inventory doesn't know waits in the list with a different beep. The camera
 * fills half the screen (top in portrait, left in landscape), the controls the other half.
 */
class InventoryActivity : ComponentActivity() {
    private lateinit var settings: HaSettings
    private var client: HaClient? = null
    private val connecting = Mutex()
    private val tones by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = HaSettings(this)
        settings.inventoryMode = true
        AppLog.i("Inventory mode opened (location ${settings.inventoryLocation})")
        setContent { HaVoiceTheme { InventoryScreen() } }
    }

    override fun onStop() {
        super.onStop()
        // Like the Assist sheet: leaving closes it; holding the side button reopens inventory mode.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.close()
        tones.release()
    }

    private suspend fun connected(): HaClient = connecting.withLock {
        client ?: HaClient(settings.baseUrl, settings.token).also {
            it.connect()
            client = it
        }
    }

    /** Sends one scan to Home Assistant's inventory_scan script and returns its answer. */
    private suspend fun send(barcode: String, action: InventoryAction, amount: String, unit: String): JSONObject = try {
        connected().runScript(
            "inventory_scan",
            JSONObject()
                .put("barcode", barcode)
                .put("action", action.verb)
                .put("amount", amount.toDoubleOrNull() ?: 1.0)
                .put("unit", unit)
                .put("location", settings.inventoryLocation),
        )
    } catch (e: HaException) {
        client?.close()
        client = null
        JSONObject().put("status", "error").put("message", e.message)
    }

    private fun beep(kind: InventoryScan.Status) {
        when (kind) {
            InventoryScan.Status.DONE -> tones.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
            // A different, two-note sound: this one needs the user.
            InventoryScan.Status.NEEDS_YOU -> tones.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            InventoryScan.Status.FAILED -> tones.startTone(ToneGenerator.TONE_PROP_NACK, 300)
            InventoryScan.Status.WORKING -> Unit
        }
    }

    private fun backToAssistant() {
        settings.inventoryMode = false
        AppLog.i("Inventory mode closed; back to the assistant")
        startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Composable
    private fun InventoryScreen() {
        val context = LocalContext.current
        val colors = LocalHaColors.current
        var cameraAllowed by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
        LaunchedEffect(Unit) { if (!cameraAllowed) askCamera.launch(Manifest.permission.CAMERA) }

        var action by remember { mutableStateOf(InventoryAction.ADD) }
        var advanced by remember { mutableStateOf(false) }
        var location by remember { mutableStateOf(settings.inventoryLocation) }
        var queueUnknowns by remember { mutableStateOf(settings.inventoryQueueUnknowns) }
        // A scan waiting for its amount (Advanced, or Update, which always asks).
        var pending by remember { mutableStateOf<String?>(null) }
        var flash by remember { mutableIntStateOf(0) }
        val scans = remember { mutableStateListOf<InventoryScan>() }
        val gate = remember { ScanGate() }
        val asksAmount = advanced || action == InventoryAction.UPDATE

        fun submit(barcode: String, amount: String, unit: String) {
            val id = System.nanoTime()
            val chosen = action
            scans.add(0, InventoryScan(id, barcode, chosen, amount, InventoryScan.Status.WORKING, "…"))
            lifecycleScope.launch {
                val answer = send(barcode, chosen, amount, unit)
                val (status, text) = when (answer.optString("status")) {
                    "done" -> InventoryScan.Status.DONE to answer.optString("summary").ifBlank { answer.optString("product", barcode) }
                    "unknown" -> InventoryScan.Status.NEEDS_YOU to "Not in the inventory yet" +
                        (answer.optString("suggestion").takeIf { it.isNotBlank() }?.let { ": maybe $it?" } ?: "")
                    else -> InventoryScan.Status.FAILED to (answer.optString("message").ifBlank { "Didn't go through" })
                }
                AppLog.i("Inventory ${chosen.verb} $barcode x$amount$unit at $location: $status ($text)")
                val index = scans.indexOfFirst { it.id == id }
                if (index >= 0) scans[index] = scans[index].copy(status = status, text = text)
                beep(status)
                if (status == InventoryScan.Status.DONE) flash++
            }
        }

        fun onScan(barcode: String) {
            if (pending != null) return
            if (asksAmount) pending = barcode else submit(barcode, "1", "")
        }

        Surface(color = colors.surfaceDefault, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val landscape = maxWidth > maxHeight
                val camera: @Composable (Modifier) -> Unit = { modifier ->
                    CameraPane(cameraAllowed, action, location, flash, onCodes = { codes -> gate.offer(codes)?.let(::onScan) }, modifier = modifier)
                }
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    Controls(
                        action = action, onAction = { action = it; if (it == InventoryAction.UPDATE) advanced = true },
                        advanced = asksAmount, onAdvanced = { if (action != InventoryAction.UPDATE) advanced = it },
                        location = location, onLocation = { location = it; settings.inventoryLocation = it },
                        queueUnknowns = queueUnknowns, onQueueUnknowns = { queueUnknowns = it; settings.inventoryQueueUnknowns = it },
                        pending = pending,
                        onAmount = { amount, unit -> pending?.let { submit(it, amount, unit) }; pending = null },
                        onCancelAmount = { pending = null; gate.forget() },
                        scans = scans,
                        modifier = modifier,
                    )
                }
                if (landscape) {
                    Row(Modifier.fillMaxSize()) {
                        camera(Modifier.weight(0.55f).fillMaxHeight())
                        controls(Modifier.weight(0.45f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        camera(Modifier.weight(0.5f).fillMaxWidth())
                        controls(Modifier.weight(0.5f).fillMaxWidth())
                    }
                }
            }
        }
    }

    @Composable
    private fun CameraPane(
        allowed: Boolean,
        action: InventoryAction,
        location: String,
        flash: Int,
        onCodes: (List<String>) -> Unit,
        modifier: Modifier,
    ) {
        Box(modifier.background(Color.Black)) {
            if (allowed) {
                BarcodeCamera(onCodes, Modifier.fillMaxSize())
            } else {
                Text(
                    "Allow the camera to scan barcodes.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(HaDimens.Space4),
                )
            }
            // What a scan does right now, so Discard is never on by surprise.
            val (label, tint) = when (action) {
                InventoryAction.ADD -> "Adding to $location" to Color(0xCC1B5E20)
                InventoryAction.UPDATE -> "Updating amounts in $location" to Color(0xCC0D47A1)
                InventoryAction.DISCARD -> "Discarding" to Color(0xCCB71C1C)
            }
            Text(
                label,
                color = Color.White,
                fontWeight = FontWeight.W600,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(HaDimens.Space3)
                    .clip(RoundedCornerShape(HaRadius.XL))
                    .background(tint)
                    .padding(horizontal = HaDimens.Space4, vertical = HaDimens.Space2),
            )
            ScanCheck(flash, Modifier.align(Alignment.Center))
        }
    }

    /** A check mark that shows for a moment after each item goes through. */
    @Composable
    private fun ScanCheck(flash: Int, modifier: Modifier) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(flash) {
            if (flash == 0) return@LaunchedEffect
            visible = true
            delay(700)
            visible = false
        }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
            Box(
                Modifier.size(120.dp).clip(CircleShape).background(Color(0xDD2E7D32)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MdiIcons.Check, contentDescription = "Added", tint = Color.White, modifier = Modifier.size(80.dp))
            }
        }
    }

    /**
     * The back camera with barcode reading on every frame. It stays bound across rotation (the
     * screen isn't recreated); only the frames' rotation is updated.
     */
    @OptIn(ExperimentalGetImage::class)
    @Composable
    private fun BarcodeCamera(onCodes: (List<String>) -> Unit, modifier: Modifier) {
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val rotation = LocalConfiguration.current.orientation
        val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
        val analysis = remember {
            ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        }
        LaunchedEffect(rotation) { view.display?.rotation?.let { analysis.targetRotation = it } }
        DisposableEffect(owner) {
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_QR_CODE,
                ).build(),
            )
            val executor = Executors.newSingleThreadExecutor()
            analysis.setAnalyzer(executor) { frame ->
                val image = frame.image
                if (image == null) {
                    frame.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes -> onCodes(codes.mapNotNull { it.rawValue }) }
                    .addOnCompleteListener { frame.close() }
            }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                runCatching { future.get().unbindAll() }
                analysis.clearAnalyzer()
                scanner.close()
                executor.shutdown()
            }
        }
        AndroidView(factory = { view }, modifier = modifier)
    }

    @Composable
    private fun Controls(
        action: InventoryAction,
        onAction: (InventoryAction) -> Unit,
        advanced: Boolean,
        onAdvanced: (Boolean) -> Unit,
        location: String,
        onLocation: (String) -> Unit,
        queueUnknowns: Boolean,
        onQueueUnknowns: (Boolean) -> Unit,
        pending: String?,
        onAmount: (String, String) -> Unit,
        onCancelAmount: () -> Unit,
        scans: List<InventoryScan>,
        modifier: Modifier,
    ) {
        val colors = LocalHaColors.current
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(HaDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(HaDimens.Space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Inventory", style = HaText.HeadlineMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = ::backToAssistant, modifier = Modifier.heightIn(min = FINGER)) { Text("Assistant") }
            }

            if (pending != null) {
                AmountInput(pending, action, onAmount, onCancelAmount)
            }

            // Where items go; stays set between scans and visits.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(HaDimens.Space2)) {
                LOCATIONS.forEach { place -> BigToggle(place, place == location, { onLocation(place) }) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(HaDimens.Space2), modifier = Modifier.fillMaxWidth()) {
                BigToggle("Advanced", advanced, { onAdvanced(!advanced) }, Modifier.weight(1f))
                BigToggle("Update", action == InventoryAction.UPDATE, {
                    onAction(if (action == InventoryAction.UPDATE) InventoryAction.ADD else InventoryAction.UPDATE)
                }, Modifier.weight(1f))
                BigToggle("Discard", action == InventoryAction.DISCARD, {
                    onAction(if (action == InventoryAction.DISCARD) InventoryAction.ADD else InventoryAction.DISCARD)
                }, Modifier.weight(1f), danger = true)
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = FINGER)) {
                Column(Modifier.weight(1f)) {
                    Text(if (queueUnknowns) "Queue new items" else "Work new items now", color = colors.textPrimary)
                    Text(
                        if (queueUnknowns) "Keep scanning; deal with them in the list" else "Stop on each one to sort it out",
                        style = HaText.BodyMedium,
                        color = colors.textSecondary,
                    )
                }
                Switch(checked = queueUnknowns, onCheckedChange = onQueueUnknowns)
            }

            scans.take(MAX_SHOWN).forEach { ScanRow(it) }
        }
    }

    /** Amount for the scan just made: a count, or a weight in a chosen unit. */
    @Composable
    private fun AmountInput(barcode: String, action: InventoryAction, onAmount: (String, String) -> Unit, onCancel: () -> Unit) {
        val colors = LocalHaColors.current
        var amount by remember(barcode) { mutableStateOf("") }
        var unit by remember(barcode) { mutableStateOf("") }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(HaRadius.XL)).background(colors.fillPrimaryQuiet).padding(HaDimens.Space3),
            verticalArrangement = Arrangement.spacedBy(HaDimens.Space2),
        ) {
            Text(
                if (action == InventoryAction.UPDATE) "How much is on hand now? ($barcode)" else "How much? ($barcode)",
                color = colors.textPrimary,
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = HaText.Body.copy(fontSize = 22.sp),
                placeholder = { Text("1") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(HaDimens.Space2), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                UNITS.forEach { (code, label) -> BigToggle(label, unit == code, { unit = code }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(HaDimens.Space2), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = FINGER)) { Text("Cancel") }
                Button(
                    onClick = { onAmount(amount.ifBlank { "1" }, unit) },
                    modifier = Modifier.weight(2f).heightIn(min = FINGER),
                ) { Text("${action.label} ${amount.ifBlank { "1" }}${UNITS.toMap()[unit]?.let { if (unit.isEmpty()) "" else " $it" } ?: ""}") }
            }
        }
    }

    @Composable
    private fun ScanRow(scan: InventoryScan) {
        val colors = LocalHaColors.current
        val (mark, tint) = when (scan.status) {
            InventoryScan.Status.WORKING -> "…" to colors.textSecondary
            InventoryScan.Status.DONE -> "✓" to Color(0xFF2E7D32)
            InventoryScan.Status.NEEDS_YOU -> "?" to Color(0xFFE65100)
            InventoryScan.Status.FAILED -> "!" to Color(0xFFC62828)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HaDimens.Space3)) {
            Text(mark, color = tint, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(scan.text, color = colors.textPrimary, maxLines = 2)
                Text(
                    "${scan.action.label} ${scan.amount} · ${scan.barcode}",
                    style = HaText.BodyMedium,
                    color = colors.textSecondary,
                )
            }
        }
    }

    /** A finger-sized on/off button. */
    @Composable
    private fun BigToggle(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
        val colors = LocalHaColors.current
        val fill = when {
            !on -> colors.surfaceLow
            danger -> colors.fillDangerLoud
            else -> colors.fillPrimaryLoud
        }
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = fill,
                contentColor = if (on) Color.White else colors.textPrimary,
            ),
            shape = RoundedCornerShape(HaRadius.XL),
            modifier = modifier.heightIn(min = FINGER),
        ) { Text(label, fontSize = 16.sp, maxLines = 1) }
    }

    private companion object {
        /** About the size of a fingertip. */
        val FINGER = 56.dp
        const val MAX_SHOWN = 30
        val LOCATIONS = listOf("Fridge", "Freezer", "Pantry", "Bathroom", "Other")
        val UNITS = listOf("" to "each", "g" to "g", "oz" to "oz", "lb" to "lb", "ml" to "ml", "fl oz" to "fl oz")
    }
}
