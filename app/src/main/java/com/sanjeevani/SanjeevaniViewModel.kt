package com.sanjeevani

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanjeevani.engine.SanjeevaniEngine
import com.sanjeevani.model.EmergencyType
import com.sanjeevani.model.FSMState
import com.sanjeevani.model.GuidanceState
import com.sanjeevani.perception.MediaPipeController
import com.sanjeevani.tts.KokoroState
import com.sanjeevani.tts.KokoroTTS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

// Keywords the user can say instead of tapping "No Response"
private val NO_RESPONSE_KEYWORDS = setOf(
    "no response", "not responding", "unconscious", "no response found",
    "not conscious", "unresponsive", "he's not responding", "she's not responding"
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

    private var lastSpokenText = ""
    private var lastSpokenTs = 0L
    private var isInitialized = false
    private var listeningActive = false

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
                tts?.language = Locale.US
                tts?.setSpeechRate(0.9f)
            }
        }

        // Kokoro neural TTS — starts loading in the background.
        // kokoro.zip must exist in assets (run setup_kokoro.sh first).
        try {
            kokoro = KokoroTTS(context).also { it.initialize() }
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
                        handleSpeechResult(matches)
                    }

                    override fun onError(error: Int) {
                        listeningActive = false
                        _isListening.value = false
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val partial = matches[0].lowercase()
                            if (NO_RESPONSE_KEYWORDS.any { partial.contains(it) }) {
                                handleSpeechResult(matches)
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun handleSpeechResult(matches: List<String>?) {
        if (matches.isNullOrEmpty()) return
        val topResult = matches[0].lowercase()
        val state = _guidanceState.value.fsmState
        if (state == FSMState.RESPONSIVENESS_CHECK &&
            NO_RESPONSE_KEYWORDS.any { topResult.contains(it) }
        ) {
            confirmNoResponse()
        }
    }

    fun startListening() {
        val ctx = appContext ?: return
        if (listeningActive) return
        listeningActive = true
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        listeningActive = false
        _isListening.value = false
    }

    fun processFrame(bitmap: Bitmap, timestampMs: Long, width: Int, height: Int) {
        val mp = mediaPipe ?: return
        viewModelScope.launch {
            val frame = mp.processFrame(bitmap, timestampMs, width, height)
            val guidance = engine.process(frame)
            _guidanceState.value = guidance

            guidance.voiceText?.let { text ->
                speak(text, timestampMs)
            }
        }
    }

    fun confirmNoResponse() {
        engine.confirmNoResponse(System.currentTimeMillis())
    }

    fun onEmergencySelected(type: EmergencyType) {
        engine.setUserSelectedEmergency(type)
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

    fun onStrokeSpeechResult(positive: Boolean) {
        engine.onStrokeSpeechResult(positive)
    }

    private fun speak(text: String, nowMs: Long) {
        val interval = when (_guidanceState.value.emergencyType) {
            EmergencyType.ALLERGIC_REACTION, EmergencyType.HEART_ATTACK -> 5000L
            else -> 2500L
        }
        if (text == lastSpokenText && nowMs - lastSpokenTs < interval) return
        lastSpokenText = text
        lastSpokenTs = nowMs

        val k = kokoro
        if (k != null && k.state.value is KokoroState.Ready) {
            // Kokoro is ready — use neural TTS (much better quality)
            tts?.stop()   // silence any Android TTS still playing
            k.speak(text)
        } else {
            // Fall back to Android TTS while Kokoro is loading (or unavailable)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sanjeevani_$nowMs")
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPipe?.close()
        tts?.stop()
        tts?.shutdown()
        kokoro?.shutdown()
        speechRecognizer?.destroy()
    }
}
