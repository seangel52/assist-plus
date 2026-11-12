package com.havoice.assistant

import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Frames a barcode must read the same way, in a row, before it counts. A single frame can
 * misread a product barcode as another valid one (a real 12-digit code came through as a
 * different code with three digits changed, check digit and all), so one read is never trusted.
 */
const val SCAN_CONFIRM_FRAMES = 3

/**
 * The back camera with barcode reading on every frame, and corner marks showing the scan target;
 * [onCodes] gets each frame's codes that are wholly inside the target (see [ScanFraming]), to go
 * through a [ScanGate]. The camera is bound
 * with the preview's view port, so each frame's crop is exactly what the preview shows; it's
 * bound again when the screen turns, since the preview's shape changes. Needs the camera permission.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun BarcodeCamera(onCodes: (List<String>) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val rotation = LocalConfiguration.current.orientation
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    }
    DisposableEffect(owner, rotation) {
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
            val degrees = frame.imageInfo.rotationDegrees
            val crop = frame.cropRect.let { ScanFraming.Box(it.left, it.top, it.right, it.bottom) }
            val visible = ScanFraming.upright(crop, frame.width, frame.height, degrees)
            scanner.process(InputImage.fromMediaImage(image, degrees))
                .addOnSuccessListener { codes ->
                    onCodes(
                        codes.filter { code ->
                            code.boundingBox?.let { ScanFraming.inTarget(ScanFraming.Box(it.left, it.top, it.right, it.bottom), visible) } == true
                        }.mapNotNull { it.rawValue },
                    )
                }
                .addOnCompleteListener { frame.close() }
        }
        val future = ProcessCameraProvider.getInstance(context)
        // After layout, so the preview knows its size and can give the view port to crop to.
        future.addListener({
            view.post {
                view.display?.rotation?.let { analysis.targetRotation = it }
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                    .apply { view.viewPort?.let(::setViewPort) }.build()
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            analysis.clearAnalyzer()
            scanner.close()
            executor.shutdown()
        }
    }
    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        ScanCorners(Modifier.fillMaxSize())
    }
}

/** White corner marks around the scan target, the area a barcode must be wholly inside. */
@Composable
private fun ScanCorners(modifier: Modifier) {
    Canvas(modifier) {
        val t = ScanFraming.target(ScanFraming.Box(0, 0, size.width.toInt(), size.height.toInt()))
        val arm = minOf(t.width, t.height) * 0.18f
        val stroke = 4.dp.toPx()
        val corners = listOf(
            Offset(t.left.toFloat(), t.top.toFloat()) to Offset(1f, 1f),
            Offset(t.right.toFloat(), t.top.toFloat()) to Offset(-1f, 1f),
            Offset(t.left.toFloat(), t.bottom.toFloat()) to Offset(1f, -1f),
            Offset(t.right.toFloat(), t.bottom.toFloat()) to Offset(-1f, -1f),
        )
        for ((at, toward) in corners) {
            drawLine(Color.White, at, at + Offset(arm * toward.x, 0f), stroke, StrokeCap.Round)
            drawLine(Color.White, at, at + Offset(0f, arm * toward.y), stroke, StrokeCap.Round)
        }
    }
}
