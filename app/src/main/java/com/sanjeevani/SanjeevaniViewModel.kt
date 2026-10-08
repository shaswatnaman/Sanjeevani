package com.sanjeevani

import android.content.Context
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanjeevani.engine.SanjeevaniEngine
import com.sanjeevani.model.GuidanceState
import com.sanjeevani.model.FSMState
import com.sanjeevani.perception.MediaPipeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class SanjeevaniViewModel : ViewModel() {

    private val engine = SanjeevaniEngine()
    private var mediaPipe: MediaPipeController? = null
    private var tts: TextToSpeech? = null

    private val _guidanceState = MutableStateFlow(GuidanceState())
    val guidanceState: StateFlow<GuidanceState> = _guidanceState

    private var lastSpokenText = ""
    private var lastSpokenTs = 0L
    private val MIN_VOICE_INTERVAL_MS = 2500L

    fun initialize(context: Context) {
        mediaPipe = MediaPipeController(context).also { it.initialize() }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
                tts?.language = Locale.US  // fallback to English
                tts?.setSpeechRate(0.9f)
            }
        }
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

    private fun speak(text: String, nowMs: Long) {
        if (text == lastSpokenText && nowMs - lastSpokenTs < MIN_VOICE_INTERVAL_MS) return
        lastSpokenText = text
        lastSpokenTs = nowMs
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sanjeevani_$nowMs")
    }

    override fun onCleared() {
        super.onCleared()
        mediaPipe?.close()
        tts?.stop()
        tts?.shutdown()
    }
}
