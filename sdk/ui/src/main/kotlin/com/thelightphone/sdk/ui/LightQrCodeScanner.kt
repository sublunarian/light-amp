package com.thelightphone.sdk.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner: live camera preview, dimmed overlay with viewfinder,
 * top-bar back, and [onScanned] with the decoded string.
 *
 * Host apps must declare [Manifest.permission.CAMERA].
 *
 * When handling [onScanned], defer navigation to a [LaunchedEffect] (or similar)
 * and pop the scanner before pushing the next screen, e.g. `goBack()` then `navigateTo(...)`.
 */
@Composable
fun LightQrCodeScanner(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Scan QR Code",
    checkCameraPermission: suspend () -> Result<Boolean>,
    launchCameraPermissionRequest: suspend () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = LightThemeTokens.colors
    var launchedPermissionRequest by remember { mutableStateOf(false) }
    var uiState by remember { mutableStateOf(LightQrUiState.Loading) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val permissionCheck = checkCameraPermission()
            if (permissionCheck.isFailure) {
                uiState = LightQrUiState.PermissionError
            } else if (permissionCheck.getOrNull() == false) {
                if (!launchedPermissionRequest) {
                    launchCameraPermissionRequest()
                    launchedPermissionRequest = true
                } else {
                    uiState = LightQrUiState.PermissionDenied
                }
            } else {
                uiState = LightQrUiState.Active
            }
        }
    }

    val scannedOnce = remember { AtomicBoolean(false) }
    val onScannedState = rememberUpdatedState(onScanned)
    val onBackState = rememberUpdatedState(onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        if (uiState == LightQrUiState.Active) {
            QrCameraPreview(
                onQrCode = { value ->
                    if (scannedOnce.compareAndSet(false, true)) {
                        onScannedState.value(value)
                    }
                },
                lifecycleOwner = lifecycleOwner,
                modifier = Modifier.fillMaxSize(),
            )
            QrViewfinderOverlay(
                frameColor = colors.content,
                scrimColor = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { onBackState.value() },
                    ),
                    center = LightTopBarCenter.Text(title),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
            }

            if (uiState != LightQrUiState.Active) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uiState == LightQrUiState.Loading) {
                        CircularProgressIndicator()
                    } else {
                        val message = if (uiState == LightQrUiState.PermissionDenied) {
                            "Camera permission is required to scan QR codes."
                        } else {
                            "Error: unable to request camera permission!"
                        }
                        LightText(
                            text = message,
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCameraPreview(
    onQrCode: (String) -> Unit,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val onQrCodeState = rememberUpdatedState(onQrCode)

    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }

    DisposableEffect(lifecycleOwner, cameraController, barcodeScanner) {
        val analyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
            mainExecutor,
        ) { result ->
            if (result.getThrowable(barcodeScanner) != null) {
                return@MlKitAnalyzer
            }

            val barcodes = result.getValue(barcodeScanner) ?: return@MlKitAnalyzer
            if (barcodes.isEmpty()) {
                return@MlKitAnalyzer
            }

            val value = barcodes
                .asSequence()
                .mapNotNull { it.rawValue ?: it.displayValue }
                .firstOrNull { it.isNotBlank() }

            if (value != null) {
                onQrCodeState.value(value)
            }
        }
        cameraController.setImageAnalysisAnalyzer(mainExecutor, analyzer)

        onDispose {
            cameraController.clearImageAnalysisAnalyzer()
            cameraController.unbind()
            barcodeScanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                controller = cameraController
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {
                            bindCamera(cameraController, lifecycleOwner)
                        }

                        override fun onViewDetachedFromWindow(view: View) {
                            cameraController.unbind()
                        }
                    },
                )
            }
        },
        update = { previewView ->
            if (previewView.isAttachedToWindow) {
                previewView.post {
                    bindCamera(cameraController, lifecycleOwner)
                }
            }
        },
    )
}

private fun bindCamera(
    cameraController: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
) {
    runCatching {
        cameraController.bindToLifecycle(lifecycleOwner)
    }
}

@Composable
private fun QrViewfinderOverlay(
    frameColor: Color,
    scrimColor: Color,
    modifier: Modifier = Modifier,
    frameSizeFraction: Float = 0.62f,
) {
    Canvas(modifier = modifier) {
        val frameSize = size.minDimension * frameSizeFraction
        val left = (size.width - frameSize) / 2f
        val top = (size.height - frameSize) / 2f
        val right = left + frameSize
        val bottom = top + frameSize

        drawRect(scrimColor, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(scrimColor, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(scrimColor, topLeft = Offset(0f, top), size = Size(left, frameSize))
        drawRect(scrimColor, topLeft = Offset(right, top), size = Size(size.width - right, frameSize))

        drawRoundRect(
            color = frameColor,
            topLeft = Offset(left, top),
            size = Size(frameSize, frameSize),
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = 3f),
        )
    }
}

private enum class LightQrUiState {
    Loading, PermissionError, PermissionDenied, Active
}
