package com.sanjeevani.engine

import com.sanjeevani.model.RateStatus
import com.sanjeevani.model.TemporalState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

private const val BUFFER_SIZE = 90           // 3s at 30fps
private const val MIN_EVENT_GAP_MS = 300L    // max ~200 BPM
private const val RATE_WINDOW_MS = 10_000L   // rolling 10s window

class CompressionDetector {

    private val wristYBuffer = ArrayDeque<Float>(BUFFER_SIZE)
    private val timestampBuffer = ArrayDeque<Long>(BUFFER_SIZE)
    private val compressionEvents = ArrayDeque<Long>()

    fun addSample(wristY: Float, timestampMs: Long) {
        if (wristYBuffer.size >= BUFFER_SIZE) {
            wristYBuffer.removeFirst()
            timestampBuffer.removeFirst()
        }
        wristYBuffer.addLast(wristY)
        timestampBuffer.addLast(timestampMs)

        if (wristYBuffer.size >= 15) detectCompressions(timestampMs)
    }

    private fun detectCompressions(nowMs: Long) {
        val signal = lowPassFilter(wristYBuffer.toFloatArray())
        val lastIdx = signal.size - 1

        // Detect local maximum (peak in Y = maximum downward displacement)
        val peakIdx = lastIdx - 2
        if (peakIdx < 2) return

        val isPeak = signal[peakIdx] > signal[peakIdx - 1] &&
                     signal[peakIdx] > signal[peakIdx + 1] &&
                     signal[peakIdx] > signal[peakIdx - 2] &&
                     signal[peakIdx] > signal[peakIdx + 2]

        if (!isPeak) return

        val peakTs = timestampBuffer.getOrNull(peakIdx) ?: return
        val prominence = signal[peakIdx] - signal.minOrNull()!!

        // Ignore shallow movements (prominence < 5% of image height)
        if (prominence < 0.05f) return

        val lastEvent = compressionEvents.lastOrNull() ?: 0L
        if (peakTs - lastEvent > MIN_EVENT_GAP_MS) {
            compressionEvents.addLast(peakTs)
            pruneOldEvents(nowMs)
        }
    }

    private fun pruneOldEvents(nowMs: Long) {
        while (compressionEvents.isNotEmpty() && nowMs - compressionEvents.first() > RATE_WINDOW_MS) {
            compressionEvents.removeFirst()
        }
    }

    fun getTemporalState(nowMs: Long): TemporalState {
        pruneOldEvents(nowMs)
        val bpm = calculateBPM(nowMs)
        return TemporalState(
            compressionRateBPM = bpm,
            rateStatus = classifyRate(bpm),
            compressionCount = compressionEvents.size
        )
    }

    private fun calculateBPM(nowMs: Long): Float {
        val recent = compressionEvents.filter { nowMs - it < RATE_WINDOW_MS }
        if (recent.size < 2) return 0f
        val durationMs = recent.last() - recent.first()
        if (durationMs <= 0) return 0f
        return (recent.size - 1) * 60_000f / durationMs
    }

    private fun classifyRate(bpm: Float): RateStatus = when {
        bpm == 0f -> RateStatus.INSUFFICIENT_DATA
        bpm < 100f -> RateStatus.TOO_SLOW
        bpm > 120f -> RateStatus.TOO_FAST
        else -> RateStatus.GOOD
    }

    fun reset() {
        wristYBuffer.clear()
        timestampBuffer.clear()
        compressionEvents.clear()
    }

    // Simple low-pass FIR approximation (Hanning window, 5-tap)
    private fun lowPassFilter(input: FloatArray): FloatArray {
        if (input.size < 5) return input
        val out = FloatArray(input.size)
        val kernel = floatArrayOf(0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f)
        for (i in 2 until input.size - 2) {
            out[i] = input[i - 2] * kernel[0] + input[i - 1] * kernel[1] +
                     input[i] * kernel[2] + input[i + 1] * kernel[3] +
                     input[i + 2] * kernel[4]
        }
        // Fill edges
        out[0] = input[0]; out[1] = input[1]
        out[input.size - 1] = input[input.size - 1]
        out[input.size - 2] = input[input.size - 2]
        return out
    }
}

private fun ArrayDeque<Float>.toFloatArray(): FloatArray = FloatArray(size) { this[it] }
