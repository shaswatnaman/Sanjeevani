package com.sanjeevani.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.sanjeevani.model.VoiceScript
import com.sanjeevani.model.VoiceSegment
import com.sanjeevani.model.VoiceUrgency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private const val MAX_PLAYBACK_VOLUME = 1.0f
private const val TARGET_GUIDANCE_PEAK = 0.94f
private const val MAX_GUIDANCE_GAIN = 1.6f

sealed class KokoroState {
    object Idle      : KokoroState()
    object Extracting : KokoroState()  // first launch: unzipping from assets (~30 s)
    object Loading   : KokoroState()   // loading model into memory (~2 s)
    object Ready     : KokoroState()
    data class Error(val message: String) : KokoroState()
}

class KokoroTTS(private val context: Context) {

    private val _state = MutableStateFlow<KokoroState>(KokoroState.Idle)
    val state: StateFlow<KokoroState> = _state

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    // Called on the IO thread when speaking starts (true) or ends (false).
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    private var engine: OfflineTts? = null
    private var playTrack: AudioTrack? = null
    private var currentJob: Job? = null

    // Coroutine scope tied to this instance
    private val scope = CoroutineScope(Dispatchers.IO)

    fun initialize() {
        scope.launch { initAsync() }
    }

    private suspend fun initAsync() {
        val modelDir = File(context.filesDir, "kokoro")
        if (!isReady(modelDir)) {
            _state.value = KokoroState.Extracting
            try {
                extract(modelDir)
            } catch (e: Exception) {
                _state.value = KokoroState.Error("Extraction failed: ${e.message}")
                return
            }
        }
        _state.value = KokoroState.Loading
        try {
            loadEngine(modelDir)
            _state.value = KokoroState.Ready
        } catch (e: Exception) {
            _state.value = KokoroState.Error("Model load failed: ${e.message}")
        }
    }

    private fun isReady(dir: File) =
        File(dir, "model.onnx").exists() &&
        File(dir, "voices.bin").exists() &&
        File(dir, "tokens.txt").exists() &&
        File(dir, "espeak-ng-data").isDirectory

    private fun extract(modelDir: File) {
        modelDir.deleteRecursively()
        modelDir.mkdirs()
        val buf = ByteArray(65536)
        ZipInputStream(context.assets.open("kokoro.zip")).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(modelDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun loadEngine(modelDir: File) {
        val kokoroConfig = OfflineTtsKokoroModelConfig(
            model   = File(modelDir, "model.onnx").absolutePath,
            voices  = File(modelDir, "voices.bin").absolutePath,
            tokens  = File(modelDir, "tokens.txt").absolutePath,
            dataDir = File(modelDir, "espeak-ng-data").absolutePath,
        )
        val modelConfig = OfflineTtsModelConfig(
            kokoro     = kokoroConfig,
            numThreads = 4,
            debug      = false,
            provider   = "cpu",
        )
        val config = OfflineTtsConfig(model = modelConfig)
        // null AssetManager = load from absolute file paths (not from app assets)
        engine = OfflineTts(assetManager = null, config = config)
    }

    private fun speedFor(urgency: VoiceUrgency) = when (urgency) {
        VoiceUrgency.CALM   -> 0.85f
        VoiceUrgency.NORMAL -> 1.0f
        VoiceUrgency.URGENT -> 1.15f
    }

    // Speak a single utterance asynchronously with optional urgency.
    fun speak(text: String, urgency: VoiceUrgency = VoiceUrgency.NORMAL) {
        val eng = engine ?: return
        currentJob?.cancel()
        currentJob = scope.launch {
            setSpeaking(true)
            try {
                val audio = eng.generate(text = text, sid = 0, speed = speedFor(urgency))
                if (!isActive) return@launch
                play(boostForGuidance(audio.samples), audio.sampleRate)
                // Estimate playback duration and wait so isSpeaking stays true
                val durationMs = audio.samples.size.toLong() * 1000L / audio.sampleRate
                delay(durationMs + 150L)
            } catch (_: Exception) { }
            finally { setSpeaking(false) }
        }
    }

    // Speak a sequence of segments with pauses between them. Cancels any current speech.
    fun speakScript(script: VoiceScript) {
        val eng = engine ?: return
        currentJob?.cancel()
        currentJob = scope.launch {
            setSpeaking(true)
            try {
                for (segment in script.segments) {
                    if (!isActive) break
                    val audio = eng.generate(text = segment.text, sid = 0, speed = speedFor(segment.urgency))
                    if (!isActive) break
                    play(boostForGuidance(audio.samples), audio.sampleRate)
                    val durationMs = audio.samples.size.toLong() * 1000L / audio.sampleRate
                    delay(durationMs + segment.pauseAfterMs)
                }
            } catch (_: Exception) { }
            finally { setSpeaking(false) }
        }
    }

    private fun setSpeaking(value: Boolean) {
        _isSpeaking.value = value
        onSpeakingChanged?.invoke(value)
    }

    fun stop() {
        currentJob?.cancel()
        playTrack?.stop()
    }

    // Kokoro output can be conservative in amplitude. Normalize quiet utterances toward
    // a strong speech peak while limiting gain to avoid clipping or harsh distortion.
    private fun boostForGuidance(samples: FloatArray): FloatArray {
        val peak = samples.maxOfOrNull { kotlin.math.abs(it) } ?: return samples
        if (peak <= 0f || peak >= TARGET_GUIDANCE_PEAK) return samples
        val gain = (TARGET_GUIDANCE_PEAK / peak).coerceAtMost(MAX_GUIDANCE_GAIN)
        return FloatArray(samples.size) { index ->
            (samples[index] * gain).coerceIn(-1f, 1f)
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        playTrack?.apply { stop(); release() }
        playTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
            .also { track ->
                track.setVolume(MAX_PLAYBACK_VOLUME)
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                track.play()
            }
    }

    fun shutdown() {
        playTrack?.apply { stop(); release() }
        engine?.release()
        engine = null
    }
}
