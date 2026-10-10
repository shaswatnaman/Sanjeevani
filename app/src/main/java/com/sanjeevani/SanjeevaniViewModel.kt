package com.sanjeevani

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanjeevani.engine.*
import com.sanjeevani.model.*
import com.sanjeevani.perception.MediaPipeController
import com.sanjeevani.tts.*
import com.sanjeevani.llm.LlmState
import com.sanjeevani.voice.SessionDialogue
import com.sanjeevani.voice.TurnGate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

class SanjeevaniViewModel : ViewModel() {
    private val engine = SanjeevaniEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val gate = TurnGate()
    private var context: Context? = null
    @Volatile private var mediaPipe: MediaPipeController? = null
    private val visionExecutor = Executors.newSingleThreadExecutor()
    private val visionDispatcher = visionExecutor.asCoroutineDispatcher()
    @Volatile private var cleared = false
    private var tts: TextToSpeech? = null
    private var kokoro: KokoroTTS? = null
    private var recognizer: SpeechRecognizer? = null
    private var ready = false
    private var initialized = false
    private var suspended = false
    private var muted = false
    private var firstPrompt = false
    private var timeout: Job? = null
    private var completion: (() -> Unit)? = null
    private var utteranceId: String? = null
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private val pendingFrame = AtomicBoolean(false)
    private var turnActive = false
    private var lastGuidanceSpeech = -10000L
    private val _guidanceState = MutableStateFlow(GuidanceState())
    val guidanceState: StateFlow<GuidanceState> = _guidanceState
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking
    private val _voiceStatus = MutableStateFlow("Starting guidance")
    val voiceStatus: StateFlow<String> = _voiceStatus
    private val _muted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _muted
    val kokoroState: StateFlow<KokoroState> get() = kokoro?.state ?: MutableStateFlow(KokoroState.Idle)
    // Free-form generated medical instructions are intentionally outside the decision/playback path.
    val llmState: StateFlow<LlmState> = MutableStateFlow(LlmState.Missing)

    fun ensureInitialized(ctx: Context) {
        if (initialized) return
        initialized = true; context = ctx.applicationContext
        audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // GPU delegate creation and detectAsync must use the same dedicated thread.
        // Voice/UI startup does not wait for the vision models.
        visionExecutor.execute {
            mediaPipe = MediaPipeController(ctx.applicationContext).also { it.initialize() }
        }
        kokoro = KokoroTTS(ctx).also { it.initialize() }
        tts = TextToSpeech(ctx) { status ->
            handler.post {
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.US
                    tts?.setSpeechRate(1.05f)
                    tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { handler.post { if (id == utteranceId) _isSpeaking.value = true } }
                        override fun onDone(id: String?) { handler.post { finishSpeech(id, true) } }
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(id: String?) { handler.post { finishSpeech(id, false) } }
                    })
                    if (!firstPrompt && !suspended) { firstPrompt = true; publish(engine.snapshot(), forcePrompt = true) }
                } else { _voiceStatus.value = "Voice unavailable • use buttons"; showSelectionFallback() }
            }
        }
    }
    private fun cancelTurn() {
        gate.invalidate()
        timeout?.cancel(); timeout = null
        completion = null; utteranceId = null
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        tts?.stop(); kokoro?.stop()
        _isListening.value = false; _isSpeaking.value = false; turnActive = false
        focus?.let { audio?.abandonAudioFocusRequest(it) }; focus = null
    }
    private fun waitingStatus() = when (engine.getFSMState()) {
        FSMState.CPR_POSITIONING, FSMState.HAND_POSITIONING -> "Waiting for camera • manual fallback available"
        FSMState.COMPRESSION_ACTIVE -> "Pacing cue • count is an estimate"
        else -> "Tap microphone or an answer"
    }
    private fun publish(g: GuidanceState, forcePrompt: Boolean = false) {
        val changed = g.stateVersion != _guidanceState.value.stateVersion
        _guidanceState.value = g
        if (changed) cancelTurn()
        if (suspended || muted) return
        val instruction = if (forcePrompt || changed) engine.instruction() else g.voiceText
        if (instruction != null && (changed || forcePrompt || (!turnActive && SystemClock.elapsedRealtime()-lastGuidanceSpeech >= 4000))) {
            speak(instruction, engine.expectsAnswer())
        } else if (!turnActive) _voiceStatus.value = waitingStatus()
    }
    private fun speak(text: String, listenAfter: Boolean) {
        cancelTurn()
        if (suspended || muted) return
        val version = engine.stateVersion
        val token = gate.invalidate()
        val id = "$version:$token"
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && gate.accepts(token, version, engine.stateVersion)) {
                    cancelTurn(); _voiceStatus.value = "Audio paused • follow dispatcher"
                }
            }, handler).build()
        if (audio?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _voiceStatus.value = "Audio unavailable • use buttons"; return
        }
        focus = request; turnActive = true
        utteranceId = id
        android.util.Log.d("SanjeevaniVoice", "speech requested id=$id")
        _voiceStatus.value = "Preparing speech"
        lastGuidanceSpeech = SystemClock.elapsedRealtime()
        completion = {
            if (gate.accepts(token, version, engine.stateVersion) && !suspended && !muted) {
                if (listenAfter) startListening() else _voiceStatus.value = waitingStatus()
            }
        }
        timeout = viewModelScope.launch {
            delay(30000)
            if (utteranceId == id) { cancelTurn(); _voiceStatus.value = "Speech timed out • use buttons" }
        }
        // Android TTS has lower first-word latency; Kokoro remains the fallback if unavailable.
        if (ready) {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH,
                Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }, id)
            if (result == TextToSpeech.ERROR) finishSpeech(id, false)
            else _voiceStatus.value = "Speaking"
        } else if (kokoro?.state?.value is KokoroState.Ready) {
            _isSpeaking.value = true; _voiceStatus.value = "Speaking"
            kokoro?.speak(text, onComplete = { success -> handler.post { finishSpeech(id, success) } })
        } else finishSpeech(id, false)
    }
    private fun finishSpeech(id: String?, success: Boolean) {
        if (id == null || id != utteranceId) return
        android.util.Log.d("SanjeevaniVoice", "speech completed id=$id success=$success")
        timeout?.cancel(); timeout = null; utteranceId = null
        _isSpeaking.value = false; turnActive = false
        focus?.let { audio?.abandonAudioFocusRequest(it) }; focus = null
        val done = completion; completion = null
        if (success) done?.invoke() else {
            _voiceStatus.value = "Speech unavailable • read the instruction or use buttons"
            if (engine.getFSMState() == FSMState.IDLE) showSelectionFallback()
        }
    }
    fun startListening() {
        cancelTurn()
        val ctx = context ?: return
        if (suspended || muted) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            _voiceStatus.value = "Microphone unavailable • use buttons"
            showSelectionFallback(); return
        }
        val token = gate.invalidate()
        val version = engine.stateVersion
        fun valid() = gate.accepts(token, version, engine.stateVersion) && !suspended && !muted
        turnActive = true
        _voiceStatus.value = "Starting microphone"
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!valid()) return
                        _isListening.value = true; _voiceStatus.value = "Listening"
                        android.util.Log.d("SanjeevaniVoice", "recognizer ready version=$version token=$token")
                        armTimeout(token, version, 8000)
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        if (valid()) { _isListening.value = false; _voiceStatus.value = "Processing response" }
                    }
                    override fun onPartialResults(partialResults: Bundle?) { /* Display/clinical decisions use finals only. */ }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) {
                        if (!valid()) return
                        cancelTurn()
                        _voiceStatus.value = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required • use buttons"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service needs network • use buttons"
                            else -> "No clear speech • tap microphone to retry"
                        }
                        showSelectionFallback()
                    }
                    override fun onResults(results: Bundle?) {
                        if (!valid()) return
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
                        cancelTurn()
                        interpret(text, confidence, version)
                    }
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            }
            armTimeout(token, version, 10000) // Includes recognizer startup failure.
        } catch (_: Exception) {
            cancelTurn(); _voiceStatus.value = "Microphone unavailable • use buttons"; showSelectionFallback()
        }
    }
    private fun armTimeout(token: Long, version: Long, duration: Long) {
        timeout?.cancel()
        timeout = viewModelScope.launch {
            delay(duration)
            if (gate.accepts(token, version, engine.stateVersion)) {
                cancelTurn(); _voiceStatus.value = "No speech detected • use buttons or retry"; showSelectionFallback()
            }
        }
    }
    private fun showSelectionFallback() {
        if (engine.getFSMState() in setOf(FSMState.IDLE, FSMState.SCENE_ASSESSMENT)) {
            engine.showEmergencySelection()
            _guidanceState.value = engine.snapshot().copy(voiceText = null)
        }
    }
    private fun interpret(text: String, confidence: Float, version: Long) {
        if (version != engine.stateVersion) return
        val parsed = SessionDialogue.interpret(text, engine.getFSMState(), confidence)
        engine.recordUtterance(text, confidence, parsed.answer, parsed.concern)
        if (parsed.concern != null) { onEmergencySelected(parsed.concern); return }
        if (engine.answer(parsed.answer, version)) { publish(engine.snapshot()); return }
        val response = when (parsed.answer) {
            Answer.REPEAT -> engine.instruction()
            Answer.QUESTION -> SessionDialogue.help(text, engine.getFSMState(), engine.instruction())
            Answer.UNCERTAIN -> if (engine.getFSMState() == FSMState.BREATHING_ASSESSMENT)
                "If you cannot tell whether breathing is normal, tell the dispatcher immediately. Gasping is not normal breathing. Follow their instructions."
                else "It's okay to be unsure. " + engine.instruction()
            else -> "I didn't get a clear answer. " + engine.instruction()
        }
        // One bounded retry only; silence/error always leaves working tap controls.
        speak(response, engine.expectsAnswer())
    }
    fun answer(answer: Answer, expectedVersion: Long = _guidanceState.value.stateVersion) {
        cancelTurn()
        if (engine.answer(answer, expectedVersion)) publish(engine.snapshot())
    }
    fun onEmergencySelected(type: EmergencyType) {
        cancelTurn(); engine.setUserSelectedEmergency(type); publish(engine.snapshot())
    }
    fun resetForNewEmergency() {
        cancelTurn(); engine.resetForNewEmergency(); _guidanceState.value = GuidanceState()
    }
    fun confirmNoResponse() = answer(Answer.NO)
    fun onStrokeSpeechResult(positive: Boolean) { engine.onStrokeSpeechResult(positive) }
    fun onResponsivenessCheckEntered() { /* Entry speech belongs to engine version, never UI recomposition. */ }
    fun startVoiceCompanion() = startListening()
    fun stopListening() { cancelTurn(); _voiceStatus.value = waitingStatus() }
    fun onAudioPermissionGranted() {
        if (firstPrompt && !turnActive && engine.expectsAnswer()) startListening()
    }
    fun toggleMuted() {
        muted = !muted; _muted.value = muted; cancelTurn()
        if (muted) _voiceStatus.value = "Muted • follow dispatcher"
        else publish(engine.snapshot(), forcePrompt = true)
    }
    fun suspendSession() { suspended = true; cancelTurn(); engine.invalidateVision(); _voiceStatus.value = "Paused" }
    fun resumeSession() {
        val wasSuspended = suspended; suspended = false
        if (ready && (!firstPrompt || wasSuspended) && !muted) {
            firstPrompt = true; publish(engine.snapshot(), forcePrompt = true)
        }
    }
    fun processFrame(bitmap: Bitmap, timestampMs: Long, width: Int, height: Int) {
        if (cleared) return
        if (!pendingFrame.compareAndSet(false, true)) return
        val capturedVersion = _guidanceState.value.stateVersion
        viewModelScope.launch {
            try {
                if (!suspended && capturedVersion == engine.stateVersion) mediaPipe?.let { mp ->
                    val frame = withContext(visionDispatcher) { mp.processFrame(bitmap, timestampMs, width, height) }
                    if (!suspended && capturedVersion == engine.stateVersion) publish(engine.process(frame))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                engine.invalidateVision()
                android.util.Log.w("SanjeevaniCamera", "Vision result unavailable", error)
                if (!turnActive) _voiceStatus.value = "Camera unavailable • use manual controls"
            } finally { pendingFrame.set(false) }
        }
    }
    override fun onCleared() {
        cleared = true
        cancelTurn(); tts?.shutdown(); kokoro?.shutdown()
        visionExecutor.execute { mediaPipe?.close(); mediaPipe = null }
        visionExecutor.shutdown()
        super.onCleared()
    }
}
