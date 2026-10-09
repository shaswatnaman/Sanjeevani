package com.sanjeevani

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sanjeevani.model.EmergencyType
import com.sanjeevani.model.FSMState
import com.sanjeevani.model.GuidanceState
import com.sanjeevani.tts.KokoroState
import com.sanjeevani.ui.AROverlayView
import com.sanjeevani.ui.EmergencySelectionScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val viewModel: SanjeevaniViewModel by viewModels()
    private lateinit var cameraExecutor: ExecutorService

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.ensureInitialized(applicationContext)
            startCamera()
        }
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onAudioPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.ensureInitialized(applicationContext)
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        // Pre-request mic permission so SpeechRecognizer is ready when needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SanjeevaniScreen(viewModel)
            }
        }
    }

    internal var previewView: PreviewView? = null
    internal var cameraPreview: Preview? = null

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { p ->
                previewView?.let { p.setSurfaceProvider(it.surfaceProvider) }
            }
            cameraPreview = preview
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
        val raw = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        val degrees = imageProxy.imageInfo.rotationDegrees
        return if (degrees == 0) raw else {
            val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun SanjeevaniScreen(viewModel: SanjeevaniViewModel) {
    val guidance by viewModel.guidanceState.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val kokoroState by viewModel.kokoroState.collectAsStateWithLifecycle()
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity

    LaunchedEffect(guidance.fsmState) {
        if (guidance.fsmState == FSMState.RESPONSIVENESS_CHECK) {
            viewModel.onResponsivenessCheckEntered()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview — previewView + cameraPreview wired on every recomposition
        // to ensure setSurfaceProvider is called even if camera initialised before first frame
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            update = { view ->
                activity?.let { act ->
                    act.previewView = view
                    act.cameraPreview?.setSurfaceProvider(view.surfaceProvider)
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

        // AI speaking indicator — top-center, matches HaloVoiceIndicator
        SanjeevaniVoiceIndicator(
            isSpeaking = isSpeaking,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
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

        // State indicator (top left) + Kokoro voice status
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
            // Show voice model status (only while loading/extracting)
            val voiceLabel = when (kokoroState) {
                is KokoroState.Extracting -> "🔊 Loading voice model…"
                is KokoroState.Loading    -> "🔊 Initializing voice…"
                is KokoroState.Error      -> "🔊 TTS fallback"
                else -> null
            }
            voiceLabel?.let {
                Surface(color = Color(0x99000000), shape = MaterialTheme.shapes.small) {
                    Text(
                        text = it,
                        color = Color(0xFFFFCC00),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // "No Response" confirm — tap button OR say "no response" into mic
        if (guidance.fsmState == FSMState.RESPONSIVENESS_CHECK) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Mic button — tap to speak "no response"
                val micBg = if (isListening) Color(0xFFE53935) else Color(0x99000000)
                val micLabel = if (isListening) "🎙 Listening…" else "🎙 Say \"No Response\""
                Button(
                    onClick = {
                        if (isListening) viewModel.stopListening()
                        else viewModel.startListening()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = micBg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(micLabel, color = Color.White, fontSize = 14.sp)
                }
                // Tap button fallback
                Button(
                    onClick = { viewModel.confirmNoResponse() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("No Response — Start CPR Guidance", color = Color.White, fontSize = 16.sp)
                }
            }
        }

        // Voice-first triage gets eight seconds before the tap-button fallback appears.
        val showSelectionScreen = guidance.fsmState == FSMState.TRIAGE_DETECTION
        if (showSelectionScreen) {
            EmergencySelectionScreen(
                classifierSuggestion = guidance.classifierSignal?.emergencyType,
                classifierConfidence = guidance.classifierSignal?.confidence ?: 0f,
                onEmergencySelected = { type -> viewModel.onEmergencySelected(type) }
            )
        }

        // Stroke speech buttons (shown during FAST speech step)
        if (guidance.fsmState == FSMState.STROKE_FAST_TEST) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 40.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.onStrokeSpeechResult(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("✓ Speech Clear", color = Color.White, fontSize = 14.sp)
                }
                Button(
                    onClick = { viewModel.onStrokeSpeechResult(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("✗ Speech Slurred", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // One-turn, state-aware voice help during active guidance. Tapping first
        // stops guidance audio so speech recognition cannot hear the app itself.
        val voiceCompanionAvailable = guidance.fsmState !in setOf(
            FSMState.IDLE,
            FSMState.SCENE_ASSESSMENT,
            FSMState.TRIAGE_DETECTION,
            FSMState.RESPONSIVENESS_CHECK
        )
        if (voiceCompanionAvailable) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (isListening) viewModel.stopListening()
                    else viewModel.startVoiceCompanion()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 112.dp),
                containerColor = if (isListening) Color(0xFFE53935) else Color(0xFF075985),
                contentColor = Color.White,
                icon = { Text(if (isListening) "■" else "🎙", fontSize = 18.sp) },
                text = { Text(if (isListening) "Listening…" else "Ask Sanjeevani") }
            )
        }
    }
}

@Composable
fun SanjeevaniVoiceIndicator(isSpeaking: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = isSpeaking,
        enter = fadeIn(tween(300)) + scaleIn(tween(300)),
        exit = fadeOut(tween(300)) + scaleOut(tween(300)),
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(90.dp)
                .border(2.dp, Color(0xFF4ADE80), CircleShape)
                .clip(CircleShape)
                .background(Color(0xCC000000))
                .padding(10.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 5 animated wave bars — staggered like HaloVoiceIndicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(28.dp)
                ) {
                    repeat(5) { i ->
                        val infiniteTransition = rememberInfiniteTransition(label = "wave_bar_$i")
                        val barHeightFraction by infiniteTransition.animateFloat(
                            initialValue = 0.15f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                                initialStartOffset = StartOffset(i * 80)
                            ),
                            label = "bar_$i"
                        )
                        val barHeight = (4 + 20 * barHeightFraction).dp
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(barHeight)
                                .background(Color(0xFF4ADE80), RoundedCornerShape(2.dp))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Sanjeevani",
                    color = Color(0xFF4ADE80),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun stateLabel(state: FSMState): String = when (state) {
    FSMState.IDLE -> "Scanning"
    FSMState.SCENE_ASSESSMENT -> "Emergency detected"
    FSMState.TRIAGE_DETECTION -> "Select emergency"
    FSMState.RESPONSIVENESS_CHECK -> "Check response"
    FSMState.EMERGENCY_ESCALATION -> "Call 112 now"
    FSMState.CPR_POSITIONING -> "Position rescuer"
    FSMState.HAND_POSITIONING -> "Hand placement"
    FSMState.POSTURE_CHECK -> "Posture check"
    FSMState.COMPRESSION_ACTIVE -> "CPR Active"
    FSMState.CPR_PAUSE -> "Paused"
    FSMState.CPR_SUCCESS -> "Done"
    FSMState.STROKE_FAST_TEST -> "FAST Test"
    FSMState.HEART_ATTACK_CONSCIOUS -> "Heart Attack"
    FSMState.ALLERGIC_PROTOCOL -> "Allergic Reaction"
}
