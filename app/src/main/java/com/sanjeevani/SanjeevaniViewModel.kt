package com.sanjeevani

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanjeevani.engine.SanjeevaniEngine
import com.sanjeevani.model.EmergencyType
import com.sanjeevani.model.FSMState
import com.sanjeevani.model.GuidanceState
import com.sanjeevani.model.VoiceScript
import com.sanjeevani.model.VoiceUrgency
import com.sanjeevani.perception.MediaPipeController
import com.sanjeevani.tts.KokoroState
import com.sanjeevani.tts.KokoroTTS
import com.sanjeevani.voice.ContextAwareVoiceCompanion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// Keywords the user can say instead of tapping "No Response"
private val NO_RESPONSE_KEYWORDS = setOf(
    "no response", "not responding", "unconscious", "no response found",
    "not conscious", "unresponsive", "he's not responding", "she's not responding"
)

private val RESPONSIVE_KEYWORDS = setOf(
    "yes", "she moved", "he's awake", "responsive", "she's breathing"
)

private const val RESPONSIVENESS_PROMPT =
    "Tap her shoulders firmly and shout her name. Is she responding?"
private const val RESPONSIVE_CONFIRMATION =
    "Good, she's conscious. Keep her still and call 1 1 2 now."
private const val NO_RESPONSE_CONFIRMATION =
    "No response. Starting C P R guidance now."

private const val OPENING_PROMPT =
    "Don't worry — you can do this. Tell me what's happening."
private const val VOICE_EMERGENCY_CONFIRMED =
    "This is serious, but you can do it. I'll guide you every step of the way. Let's start."
private const val OPENING_LISTEN_TIMEOUT_MS = 8_000L
private const val SPEECH_RATE = 1.10f

// Ordered so immediately life-threatening CPR phrases win before broader matches.
private val OPENING_EMERGENCY_KEYWORDS = listOf(
    EmergencyType.CPR to setOf(
        "not breathing", "isn't breathing", "is not breathing", "stopped breathing",
        "he collapsed", "she collapsed", "collapsed", "cardiac arrest", "heart stopped"
    ),
    EmergencyType.HEART_ATTACK to setOf(
        "heart attack", "chest pain", "chest hurts", "pain in the chest"
    ),
    EmergencyType.FAST_STROKE to setOf(
        "stroke", "face droop", "face is drooping", "slurred speech", "arm weakness"
    ),
    EmergencyType.ALLERGIC_REACTION to setOf(
        "allergic reaction", "severe allergy", "anaphylaxis", "epipen", "epi pen"
    )
)

class SanjeevaniViewModel : ViewModel() {

    private val engine = SanjeevaniEngine()
    private var mediaPipe: MediaPipeController? = null
    private var tts: TextToSpeech? = null           // Android TTS — fallback
    private var kokoro: KokoroTTS? = null           // Kokoro neural TTS — primary
    private var speechRecognizer: SpeechRecognizer? = null
    private var appContext: Context? = null

    // Expose Kokoro state so UI can show "Loading voice model…" during first launch
    val kokoroState: StateFlow<KokoroState> get() = kokoro?.state ?: MutableStateFlow(KokoroState.Idle)

    private val _guidanceState = MutableStateFlow(GuidanceState())
    val guidanceState: StateFlow<GuidanceState> = _guidanceState

    // Expose whether speech listening is active so UI can show a mic indicator
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    // Expose whether AI is speaking — drives the SanjeevaniVoiceIndicator
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private var lastSpokenText = ""
    private var lastSpokenTs = 0L
    private var isInitialized = false
    private var listeningActive = false
    private var androidTtsReady = false
    private var openingPromptSpoken = false
    private var openingVoiceTriageActive = false
    private var openingVoiceDeadlineMs = 0L
    private var openingTimeoutJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var responsivenessListenRunnable: Runnable? = null
    private var hasSpokenResponsivenessPrompt = false
    private var companionListening = false
    private var lastGuidanceInstruction: String? = null

    fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            isInitialized = true
            appContext = context.applicationContext
            initialize(context)
        }
    }

    private fun initialize(context: Context) {
        mediaPipe = MediaPipeController(context).also { it.initialize() }

        // Android TTS — always available, used as fallback while Kokoro loads
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTtsReady = true
                tts?.language = Locale.US
                tts?.setSpeechRate(SPEECH_RATE)
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
                    override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) { _isSpeaking.value = false }
                })
                speakOpeningPromptIfReady()
            }
        }

        // Kokoro neural TTS — starts loading in the background.
        // kokoro.zip must exist in assets (run setup_kokoro.sh first).
        try {
            kokoro = KokoroTTS(context).also { tts ->
                tts.onSpeakingChanged = { speaking -> _isSpeaking.value = speaking }
                tts.initialize()
            }
        } catch (_: Exception) {
            // assets/kokoro.zip not present — stay on Android TTS
        }

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { _isListening.value = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { _isListening.value = false }

                    override fun onResults(results: Bundle?) {
                        listeningActive = false
                        _isListening.value = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val handled = handleSpeechResult(matches, isPartial = false)
                        if (!handled) restartOpeningListenerIfNeeded()
                    }

                    override fun onError(error: Int) {
                        listeningActive = false
                        companionListening = false
                        _isListening.value = false
                        if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            restartOpeningListenerIfNeeded()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        handleSpeechResult(matches, isPartial = true)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }

        beginOpeningVoiceTriage()
    }

    private fun handleSpeechResult(matches: List<String>?, isPartial: Boolean): Boolean {
        if (matches.isNullOrEmpty()) return false
        val normalizedMatches = matches.map { it.lowercase(Locale.US) }
        val topResult = normalizedMatches[0]
        val state = _guidanceState.value.fsmState
        if (state == FSMState.RESPONSIVENESS_CHECK &&
            NO_RESPONSE_KEYWORDS.any { topResult.contains(it) }
        ) {
            finishResponsivenessListening()
            speak(NO_RESPONSE_CONFIRMATION, SystemClock.elapsedRealtime())
            engine.deferGuidance(2_500L)
            confirmNoResponse()
            return true
        }
        if (state == FSMState.RESPONSIVENESS_CHECK &&
            RESPONSIVE_KEYWORDS.any { topResult.contains(it) }
        ) {
            finishResponsivenessListening()
            speak(RESPONSIVE_CONFIRMATION, SystemClock.elapsedRealtime())
            engine.onPatientResponsive()
            return true
        }

        if (openingVoiceTriageActive &&
            (state == FSMState.IDLE || state == FSMState.SCENE_ASSESSMENT)
        ) {
            val emergencyType = OPENING_EMERGENCY_KEYWORDS.firstNotNullOfOrNull { (type, keywords) ->
                type.takeIf { normalizedMatches.any { phrase -> keywords.any(phrase::contains) } }
            }
            if (emergencyType != null) {
                onVoiceEmergencyDetected(emergencyType)
                return true
            }
        }

        if (companionListening) {
            // Partial recognition often arrives as a single unfinished word. Only
            // interrupt early when it already maps to a known intent; otherwise
            // wait for the recognizer's final result.
            if (isPartial && ContextAwareVoiceCompanion.classify(topResult) ==
                ContextAwareVoiceCompanion.Intent.UNKNOWN
            ) return false
            companionListening = false
            stopListening()
            val response = ContextAwareVoiceCompanion.resolve(
                transcript = topResult,
                guidance = _guidanceState.value,
                lastInstruction = lastGuidanceInstruction
            )
            speak(response.text, SystemClock.elapsedRealtime(), force = true)
            return true
        }
        return false
    }

    private fun beginOpeningVoiceTriage() {
        if (openingVoiceTriageActive) return
        openingVoiceTriageActive = true
        openingVoiceDeadlineMs = SystemClock.elapsedRealtime() + OPENING_LISTEN_TIMEOUT_MS
        speakOpeningPromptIfReady()
        startOpeningListenerIfPermitted()
        openingTimeoutJob = viewModelScope.launch {
            delay(OPENING_LISTEN_TIMEOUT_MS)
            if (openingVoiceTriageActive) {
                openingVoiceTriageActive = false
                stopListening()
                engine.showEmergencySelection()
            }
        }
    }

    private fun speakOpeningPromptIfReady() {
        if (!openingVoiceTriageActive || openingPromptSpoken || !androidTtsReady) return
        openingPromptSpoken = true
        speak(OPENING_PROMPT, SystemClock.elapsedRealtime())
    }

    private fun startOpeningListenerIfPermitted() {
        val context = appContext ?: return
        if (!openingVoiceTriageActive ||
            SystemClock.elapsedRealtime() >= openingVoiceDeadlineMs ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        startListening()
    }

    private fun restartOpeningListenerIfNeeded() {
        if (!openingVoiceTriageActive || SystemClock.elapsedRealtime() >= openingVoiceDeadlineMs) return
        viewModelScope.launch {
            delay(100L)
            startOpeningListenerIfPermitted()
        }
    }

    private fun finishOpeningVoiceTriage() {
        openingVoiceTriageActive = false
        openingTimeoutJob?.cancel()
        openingTimeoutJob = null
        speechRecognizer?.cancel()
        listeningActive = false
        _isListening.value = false
    }

    private fun onVoiceEmergencyDetected(type: EmergencyType) {
        finishOpeningVoiceTriage()
        setUserSelectedEmergency(type)
        // Allow this complete acknowledgement to finish before step guidance starts.
        engine.deferGuidance(6_000L)
        speak(VOICE_EMERGENCY_CONFIRMED, SystemClock.elapsedRealtime())
    }

    fun onAudioPermissionGranted() {
        startOpeningListenerIfPermitted()
    }

    fun onResponsivenessCheckEntered() {
        if (hasSpokenResponsivenessPrompt) return
        hasSpokenResponsivenessPrompt = true
        speak(RESPONSIVENESS_PROMPT, SystemClock.elapsedRealtime())

        responsivenessListenRunnable?.let(mainHandler::removeCallbacks)
        responsivenessListenRunnable = Runnable {
            if (_guidanceState.value.fsmState == FSMState.RESPONSIVENESS_CHECK) {
                startListening()
            }
        }.also { mainHandler.postDelayed(it, 2_000L) }
    }

    private fun finishResponsivenessListening() {
        responsivenessListenRunnable?.let(mainHandler::removeCallbacks)
        responsivenessListenRunnable = null
        speechRecognizer?.cancel()
        listeningActive = false
        _isListening.value = false
    }

    fun startListening() {
        val ctx = appContext ?: return
        if (listeningActive || speechRecognizer == null ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        listeningActive = true
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    /** Starts a one-question contextual listening turn without allowing TTS feedback. */
    fun startVoiceCompanion() {
        val state = _guidanceState.value.fsmState
        if (state == FSMState.IDLE || state == FSMState.SCENE_ASSESSMENT ||
            state == FSMState.TRIAGE_DETECTION || state == FSMState.RESPONSIVENESS_CHECK
        ) return
        tts?.stop()
        kokoro?.stop()
        companionListening = true
        startListening()
        if (!listeningActive) companionListening = false
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        companionListening = false
        listeningActive = false
        _isListening.value = false
    }

    fun processFrame(bitmap: Bitmap, timestampMs: Long, width: Int, height: Int) {
        val mp = mediaPipe ?: return
        viewModelScope.launch {
            val frame = mp.processFrame(bitmap, timestampMs, width, height)
            val guidance = engine.process(frame)
            _guidanceState.value = guidance

            if (!listeningActive) {
                // Voice script takes priority over plain text for step transitions
                guidance.voiceScript?.let { script ->
                    lastGuidanceInstruction = script.segments.firstOrNull()?.text
                    speakScript(script, timestampMs)
                } ?: guidance.voiceText?.let { text ->
                    lastGuidanceInstruction = text
                    speak(text, timestampMs, urgency = guidance.voiceUrgency)
                }
            }
        }
    }

    fun confirmNoResponse() {
        engine.confirmNoResponse()
    }

    fun onEmergencySelected(type: EmergencyType) {
        finishOpeningVoiceTriage()
        setUserSelectedEmergency(type)
        // Speak a greeting immediately when the user selects an emergency type
        val greeting = when (type) {
            EmergencyType.CPR -> "C P R mode activated. I will guide you step by step."
            EmergencyType.HEART_ATTACK -> "Heart attack mode. I will guide you now."
            EmergencyType.FAST_STROKE -> "Stroke assessment mode. Follow my instructions."
            EmergencyType.ALLERGIC_REACTION -> "Allergic reaction mode. Follow my instructions."
            EmergencyType.UNKNOWN -> null
        }
        greeting?.let { speak(it, System.currentTimeMillis()) }
    }

    private fun setUserSelectedEmergency(type: EmergencyType) {
        hasSpokenResponsivenessPrompt = false
        responsivenessListenRunnable?.let(mainHandler::removeCallbacks)
        responsivenessListenRunnable = null
        engine.setUserSelectedEmergency(type)
    }

    fun onStrokeSpeechResult(positive: Boolean) {
        engine.onStrokeSpeechResult(positive)
    }

    private fun speak(
        text: String,
        nowMs: Long,
        force: Boolean = false,
        urgency: VoiceUrgency = VoiceUrgency.NORMAL
    ) {
        val interval = when (_guidanceState.value.emergencyType) {
            EmergencyType.ALLERGIC_REACTION, EmergencyType.HEART_ATTACK -> 5000L
            else -> 2500L
        }
        if (!force && text == lastSpokenText && nowMs - lastSpokenTs < interval) return
        lastSpokenText = text
        lastSpokenTs = nowMs

        val k = kokoro
        if (k != null && k.state.value is KokoroState.Ready) {
            tts?.stop()
            k.speak(text, urgency)
        } else {
            val speechParams = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, speechParams, "sanjeevani_$nowMs")
        }
    }

    private fun speakScript(script: VoiceScript, nowMs: Long) {
        val key = script.segments.firstOrNull()?.text ?: return
        val interval = 2500L
        if (key == lastSpokenText && nowMs - lastSpokenTs < interval) return
        lastSpokenText = key
        lastSpokenTs = nowMs

        val k = kokoro
        if (k != null && k.state.value is KokoroState.Ready) {
            tts?.stop()
            k.speakScript(script)
        } else {
            // Fallback: speak all segments as a single concatenated string
            val combined = script.segments.joinToString(". ") { it.text }
            val speechParams = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            tts?.speak(combined, TextToSpeech.QUEUE_FLUSH, speechParams, "sanjeevani_$nowMs")
        }
    }

    override fun onCleared() {
        super.onCleared()
        responsivenessListenRunnable?.let(mainHandler::removeCallbacks)
        mediaPipe?.close()
        tts?.stop()
        tts?.shutdown()
        kokoro?.shutdown()
        speechRecognizer?.destroy()
    }
}
