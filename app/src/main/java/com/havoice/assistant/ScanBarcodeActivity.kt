package com.havoice.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Scans one barcode for the Assist sheet with the app's own camera, and returns it once it has
 * read the same way in [SCAN_CONFIRM_FRAMES] frames in a row. (Google's scanner screen, used
 * before, returns its first read, and a first read can be a different valid barcode.)
 */
class ScanBarcodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HaVoiceTheme { ScanScreen() } }
    }

    @androidx.compose.runtime.Composable
    private fun ScanScreen() {
        val context = LocalContext.current
        var allowed by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
        LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
        val gate = remember { ScanGate(confirmFrames = SCAN_CONFIRM_FRAMES) }
        var done by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (allowed) {
                BarcodeCamera(
                    onCodes = { codes ->
                        val code = gate.offer(codes)
                        if (code != null && !done) {
                            done = true
                            AppLog.i("Barcode scanned: $code")
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
                            finish()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                if (allowed) "Hold the barcode in view" else "Allow the camera to scan barcodes.",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(HaDimens.Space3)
                    .clip(RoundedCornerShape(HaRadius.XL))
                    .background(Color(0x99000000))
                    .padding(horizontal = HaDimens.Space4, vertical = HaDimens.Space2),
            )
            Button(onClick = { finish() }, modifier = Modifier.align(Alignment.BottomCenter).padding(HaDimens.Space4)) {
                Text("Cancel")
            }
        }
    }

    /** Opens the scanner; the result is the scanned code, or null if it was closed without one. */
    class Contract : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: Context, input: Unit) = Intent(context, ScanBarcodeActivity::class.java)
        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_CODE).takeIf { resultCode == RESULT_OK }
    }

    companion object {
        private const val EXTRA_CODE = "code"
    }
}
