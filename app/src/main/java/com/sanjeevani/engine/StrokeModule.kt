package com.sanjeevani.engine

import android.graphics.PointF
import com.sanjeevani.model.*

// FAST stroke assessment: Face, Arms, Speech, Time
// States: INTRO → FACE → ARM → SPEECH → RESULT

private enum class FastStep { INTRO, FACE, ARM, SPEECH, RESULT }

private val STEP_DURATION_MS = mapOf(
    FastStep.INTRO  to 3_000L,
    FastStep.FACE   to 10_000L,
    FastStep.ARM    to 12_000L,
    FastStep.SPEECH to 0L,   // waits for external speech input
    FastStep.RESULT to 0L
)

class StrokeModule : EmergencyModule {

    private var startMs = -1L
    private var stepStartMs = -1L
    private var currentStep = FastStep.INTRO
    private var lastSpokenStep: FastStep? = null

    private var facePositiveCount = 0
    private var armDriftDetected = false
    private var speechPositive = false
    private var speechInputReceived = false

    override fun process(frame: PerceptionFrame, nowMs: Long): ModuleResult {
        if (startMs < 0) { startMs = nowMs; stepStartMs = nowMs }
        val stepElapsed = nowMs - stepStartMs

        when (currentStep) {
            FastStep.INTRO -> {
                if (stepElapsed > STEP_DURATION_MS[FastStep.INTRO]!!) advanceStep(nowMs)
            }
            FastStep.FACE -> {
                assessFaceAsymmetry(frame)
                if (stepElapsed > STEP_DURATION_MS[FastStep.FACE]!!) advanceStep(nowMs)
            }
            FastStep.ARM -> {
                assessArmDrift(frame)
                if (stepElapsed > STEP_DURATION_MS[FastStep.ARM]!!) advanceStep(nowMs)
            }
            FastStep.SPEECH -> {
                // Waits for onSpeechResult() — no time limit
            }
            FastStep.RESULT -> {
                // Terminal state
            }
        }

        val stepChanged = currentStep != lastSpokenStep
        val voiceText = if (stepChanged) {
            lastSpokenStep = currentStep
            voiceForStep(currentStep)
        } else null

        val positiveCount = countPositives()
        val overlay = buildOverlay(frame, positiveCount)

        val isComplete = currentStep == FastStep.RESULT

        return ModuleResult(voiceText, overlay, isComplete)
    }

    fun onSpeechResult(positive: Boolean) {
        if (currentStep == FastStep.SPEECH) {
            speechPositive = positive
            speechInputReceived = true
            currentStep = FastStep.RESULT
            lastSpokenStep = null  // force re-voice on next frame
        }
    }

    override fun reset() {
        startMs = -1L; stepStartMs = -1L
        currentStep = FastStep.INTRO
        lastSpokenStep = null
        facePositiveCount = 0
        armDriftDetected = false
        speechPositive = false
        speechInputReceived = false
    }

    private fun advanceStep(nowMs: Long) {
        val steps = FastStep.values()
        val next = steps.getOrNull(steps.indexOf(currentStep) + 1) ?: FastStep.RESULT
        currentStep = next
        stepStartMs = nowMs
    }

    private fun assessFaceAsymmetry(frame: PerceptionFrame) {
        val pose = frame.poseLandmarks ?: return
        if (pose.size <= 12) return
        val ls = pose[11]; val rs = pose[12]
        if (ls.visibility < 0.4f || rs.visibility < 0.4f) return
        val asymmetry = kotlin.math.abs(ls.y - rs.y)
        if (asymmetry > 0.07f) facePositiveCount++
    }

    private fun assessArmDrift(frame: PerceptionFrame) {
        val pose = frame.poseLandmarks ?: return
        if (pose.size <= 16) return
        val le = pose[13]; val re = pose[14]
        val lw = pose[15]; val rw = pose[16]
        if (le.visibility < 0.4f || re.visibility < 0.4f) return
        val lDroop = if (lw.visibility > 0.4f) lw.y - le.y else 0f
        val rDroop = if (rw.visibility > 0.4f) rw.y - re.y else 0f
        if (kotlin.math.abs(lDroop - rDroop) > 0.12f) armDriftDetected = true
    }

    private fun countPositives(): Int {
        var n = 0
        if (facePositiveCount > 5) n++
        if (armDriftDetected) n++
        if (speechInputReceived && speechPositive) n++
        return n
    }

    private fun voiceForStep(step: FastStep): String = when (step) {
        FastStep.INTRO   -> "I will now guide you through the FAST stroke test. Watch their face, arms, and speech."
        FastStep.FACE    -> "Ask them to smile. Look for drooping or unevenness on one side of their face."
        FastStep.ARM     -> "Ask them to raise both arms. Watch if one arm drifts downward."
        FastStep.SPEECH  -> "Ask them to repeat a simple phrase. Tap the button below: speech clear or slurred?"
        FastStep.RESULT  -> {
            val n = countPositives()
            when {
                n >= 2 -> "Two or more FAST signs detected. This is likely a stroke. Call 1 1 2 immediately. Do not give food or water."
                n == 1 -> "One FAST sign detected. Keep them calm, note the time, and call 1 1 2. Watch for worsening."
                else   -> "No clear FAST signs at this time. Keep them calm and monitor closely."
            }
        }
    }

    private fun buildOverlay(frame: PerceptionFrame, positiveCount: Int): AROverlaySpec {
        val pose = frame.poseLandmarks
        val lsy = pose?.getOrNull(11)?.y ?: 0f
        val rsy = pose?.getOrNull(12)?.y ?: 0f
        val isHighRisk = positiveCount >= 2

        val statusText = when (currentStep) {
            FastStep.INTRO   -> "FAST Test"
            FastStep.FACE    -> "Face — check smile"
            FastStep.ARM     -> "Arms — check drift"
            FastStep.SPEECH  -> "Speech — check clarity"
            FastStep.RESULT  -> if (isHighRisk) "⚠ Stroke likely" else "Low risk"
        }

        return AROverlaySpec(
            statusText = statusText,
            statusColorGreen = currentStep == FastStep.RESULT && !isHighRisk,
            emergencyType = EmergencyType.FAST_STROKE,
            phaseProgress = stepProgress(currentStep),
            leftShoulderY = lsy,
            rightShoulderY = rsy,
            guidanceText = voiceForStep(currentStep)
        )
    }

    private fun stepProgress(step: FastStep): Float {
        return when (step) {
            FastStep.INTRO   -> 0.1f
            FastStep.FACE    -> 0.35f
            FastStep.ARM     -> 0.6f
            FastStep.SPEECH  -> 0.8f
            FastStep.RESULT  -> 1.0f
        }
    }

    fun getStrokeSignals(): StrokeSignals = StrokeSignals(
        faceAsymmetryScore = (facePositiveCount / 10f).coerceIn(0f, 1f),
        armDriftDetected = armDriftDetected,
        speechPrompted = speechInputReceived,
        positiveTestCount = countPositives()
    )
}
