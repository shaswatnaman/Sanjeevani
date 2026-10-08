package com.sanjeevani

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sanjeevani.model.FSMState
import com.sanjeevani.model.GuidanceState
import com.sanjeevani.ui.AROverlayView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val viewModel: SanjeevaniViewModel by viewModels()
    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        viewModel.initialize(applicationContext)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SanjeevaniScreen(viewModel)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(720, 1280))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val bitmap = imageProxyToBitmap(imageProxy)
                viewModel.processFrame(
                    bitmap,
                    imageProxy.imageInfo.timestamp / 1_000_000L,
                    imageProxy.width,
                    imageProxy.height
                )
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                // Preview surface is set via AndroidView in Compose — see SanjeevaniScreen
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun SanjeevaniScreen(viewModel: SanjeevaniViewModel) {
    val guidance by viewModel.guidanceState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // AR Overlay
        AndroidView(
            factory = { ctx -> AROverlayView(ctx) },
            update = { view -> view.overlaySpec = guidance.overlay },
            modifier = Modifier.fillMaxSize()
        )

        // Emergency call button (top right)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Button(
                onClick = { /* launch dialer 112 */ },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text("📞 Call 112", color = Color.White, fontSize = 14.sp)
            }
        }

        // State indicator (top left)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Surface(
                color = Color(0x99000000),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = stateLabel(guidance.fsmState),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // "No Response" confirm button (shown during responsiveness check)
        if (guidance.fsmState == FSMState.RESPONSIVENESS_CHECK) {
            Button(
                onClick = { viewModel.confirmNoResponse() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp)
                    .fillMaxWidth()
            ) {
                Text("No Response — Start CPR Guidance", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

private fun stateLabel(state: FSMState): String = when (state) {
    FSMState.IDLE -> "Scanning"
    FSMState.SCENE_ASSESSMENT -> "Emergency detected"
    FSMState.RESPONSIVENESS_CHECK -> "Check response"
    FSMState.EMERGENCY_ESCALATION -> "Call 112 now"
    FSMState.CPR_POSITIONING -> "Position rescuer"
    FSMState.HAND_POSITIONING -> "Hand placement"
    FSMState.POSTURE_CHECK -> "Posture check"
    FSMState.COMPRESSION_ACTIVE -> "CPR Active"
    FSMState.CPR_PAUSE -> "Paused"
    FSMState.CPR_SUCCESS -> "Done"
}
