package com.music.bitchord.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size as AndroidSize
import android.view.HapticFeedbackConstants
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fullscreen camera QR Code scanner dialog for pairing LuminaChord Android with Desktop Windows.
 */
@Composable
fun DesktopQrCameraScanner(
    onCodeScanned: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    var camera by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var invalidHint by remember { mutableStateOf<String?>(null) }
    val scanned = remember { AtomicBoolean(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@addListener
                        val resSelector = ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    AndroidSize(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build()

                        val preview = Preview.Builder()
                            .setResolutionSelector(resSelector)
                            .build()
                            .also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val qrReader = QRCodeReader()
                        val hints = mapOf(
                            DecodeHintType.TRY_HARDER to true,
                            DecodeHintType.ALSO_INVERTED to true,
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        )

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (scanned.get()) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val decodedText = decodeQrFromImage(imageProxy, qrReader, hints)
                            if (decodedText != null) {
                                previewView.post {
                                    if (!scanned.get()) {
                                        val accepted = onCodeScanned(decodedText)
                                        if (accepted) {
                                            scanned.set(true)
                                            triggerHaptic(context, view)
                                            onDismiss()
                                        } else {
                                            invalidHint = "Bukan QR pairing LuminaChord"
                                        }
                                    }
                                }
                            }
                            imageProxy.close()
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        try {
                            cameraProvider.unbindAll()
                            camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis,
                            )
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Dark mask with transparent cutout in the center for the viewfinder
            val boxSizeDp = 260.dp
            val density = context.resources.displayMetrics.density
            val boxSizePx = boxSizeDp.value * density

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(alpha = 0.99f),
            ) {
                drawRect(color = Color.Black.copy(alpha = 0.65f))

                val left = (size.width - boxSizePx) / 2f
                val top = (size.height - boxSizePx) / 2f

                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(boxSizePx, boxSizePx),
                    cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                    blendMode = BlendMode.Clear,
                )
            }

            // Viewfinder Box Overlay with Animated Scanner Line
            Box(
                modifier = Modifier
                    .size(boxSizeDp)
                    .align(Alignment.Center)
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), RoundedCornerShape(20.dp)),
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "scanner")
                val scanLinePos by infiniteTransition.animateFloat(
                    initialValue = 0.05f,
                    targetValue = 0.95f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "scanLine",
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val y = size.height * scanLinePos
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xFF64B5F6),
                                Color(0xFF2196F3),
                                Color(0xFF64B5F6),
                                Color.Transparent,
                            ),
                        ),
                        start = Offset(10f, y),
                        end = Offset(size.width - 10f, y),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
            }

            // Top Header: Title & Close Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 40.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                ) {
                    Icon(Icons.Rounded.Close, "Tutup", tint = Color.White)
                }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "Pindai QR Code Desktop",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        "Arahkan kamera ke QR Code di layar Windows",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }

                IconButton(
                    onClick = {
                        val cam = camera
                        if (cam != null && cam.cameraInfo.hasFlashUnit()) {
                            val next = !isTorchOn
                            cam.cameraControl.enableTorch(next)
                            isTorchOn = next
                        }
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                ) {
                    Icon(
                        if (isTorchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        "Senter",
                        tint = if (isTorchOn) Color(0xFFFFD54F) else Color.White,
                    )
                }
            }

            // Bottom Instruction / Warning Pill
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 54.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        null,
                        tint = if (invalidHint != null) Color(0xFFFF8A80) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        invalidHint ?: "Scanning otomatis saat kode terdeteksi",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (invalidHint != null) Color(0xFFFF8A80) else Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun decodeQrFromImage(
    imageProxy: ImageProxy,
    qrReader: QRCodeReader,
    hints: Map<DecodeHintType, Any>,
): String? {
    return try {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride

        val yBytes = if (rowStride == width) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bytes
        } else {
            val bytes = ByteArray(width * height)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(bytes, y * width, width)
            }
            bytes
        }

        val source = PlanarYUVLuminanceSource(
            yBytes,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        qrReader.reset()
        val result = qrReader.decode(bitmap, hints)
        result?.text?.takeIf(String::isNotBlank)
    } catch (_: Exception) {
        null
    }
}

private fun triggerHaptic(context: Context, view: android.view.View) {
    try {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            v?.vibrate(80)
        }
    } catch (_: Exception) {}
}
