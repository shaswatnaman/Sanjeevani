package com.sanjeevani.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

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

    private var engine: OfflineTts? = null
    private var playTrack: AudioTrack? = null

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

    // Speak asynchronously. Returns immediately; playback happens on IO thread.
    fun speak(text: String) {
        val eng = engine ?: return
        scope.launch {
            try {
                val audio = eng.generate(text = text, sid = 0, speed = 1.0f)
                play(audio.samples, audio.sampleRate)
            } catch (_: Exception) { /* engine may have been released */ }
        }
    }

    fun stop() {
        playTrack?.stop()
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
