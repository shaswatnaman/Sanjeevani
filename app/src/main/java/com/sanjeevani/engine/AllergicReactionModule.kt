package com.sanjeevani.engine

import android.graphics.PointF
import com.sanjeevani.model.*

// Timing ported from iOS AllergicReactionView.swift
// t=0s  LAY_FLAT      "Lay them flat on the ground"
// t=17s RAISE_LEGS    "Raise their legs above heart level"
// t=20s               Chair message (if sitting up, 8s window)
// t=38s SAFE_POSITION "Move them to safe position" (green)
// t=48s EPIPEN_READY  "Get the EpiPen ready"
// t=51s EPIPEN_INJECT "Inject EpiPen into outer thigh — hold for ten seconds"
// t=66s MONITORING    "Monitor breathing and stay with them. Help is on the way."

private val PHASE_TRANSITIONS_MS = mapOf(
    AllergicPhase.LAY_FLAT      to 0L,
    AllergicPhase.RAISE_LEGS    to 17_000L,
    AllergicPhase.SAFE_POSITION to 38_000L,
    AllergicPhase.EPIPEN_READY  to 48_000L,
    AllergicPhase.EPIPEN_INJECT to 51_000L,
    AllergicPhase.MONITORING    to 66_000L
)

private val PHASE_VOICE = mapOf(
    AllergicPhase.LAY_FLAT      to "Lay them flat on the ground. Do not let them stand up.",
    AllergicPhase.RAISE_LEGS    to "Raise their legs above heart level. This helps blood flow to the brain.",
    AllergicPhase.SAFE_POSITION to "They are stable. Move them to the recovery position.",
    AllergicPhase.EPIPEN_READY  to "Get the EpiPen ready. Remove the blue safety cap.",
    AllergicPhase.EPIPEN_INJECT to "Inject EpiPen into the outer thigh. Hold for ten seconds.",
    AllergicPhase.MONITORING    to "Monitor breathing and stay with them. Help is on the way. Call 1 1 2 now if not already done."
)

class AllergicReactionModule : EmergencyModule {

    private var startMs = -1L
    private var currentPhase = AllergicPhase.LAY_FLAT
    private var lastSpokenPhase: AllergicPhase? = null

    override fun process(frame: PerceptionFrame, nowMs: Long): ModuleResult {
        if (startMs < 0) startMs = nowMs
        val elapsed = nowMs - startMs

        val newPhase = phaseForElapsed(elapsed)
        val phaseChanged = newPhase != currentPhase
        currentPhase = newPhase

        val voiceText = if (phaseChanged || lastSpokenPhase == null) {
            lastSpokenPhase = currentPhase
            PHASE_VOICE[currentPhase]
        } else null

        val progress = progressInPhase(elapsed)

        val lsy = frame.poseLandmarks?.getOrNull(11)?.y ?: 0f
        val rsy = frame.poseLandmarks?.getOrNull(12)?.y ?: 0f

        val overlay = AROverlaySpec(
            statusText = overlayText(currentPhase),
            statusColorGreen = currentPhase == AllergicPhase.SAFE_POSITION ||
                               currentPhase == AllergicPhase.MONITORING,
            guidanceText = PHASE_VOICE[currentPhase] ?: "",
            emergencyType = EmergencyType.ALLERGIC_REACTION,
            phaseProgress = progress,
            showEpiPenMarker = currentPhase == AllergicPhase.EPIPEN_READY ||
                               currentPhase == AllergicPhase.EPIPEN_INJECT,
            thighTarget = frame.poseLandmarks?.let { rightThighOf(it) },
            // sternumTarget intentionally null: sternum sphere is for CPR only
            leftShoulderY = lsy,
            rightShoulderY = rsy
        )

        val isComplete = elapsed > 90_000L  // 90s: hand off to MONITORING indefinitely

        return ModuleResult(voiceText, overlay, isComplete)
    }

    override fun reset() {
        startMs = -1L
        currentPhase = AllergicPhase.LAY_FLAT
        lastSpokenPhase = null
    }

    private fun phaseForElapsed(elapsedMs: Long): AllergicPhase {
        return PHASE_TRANSITIONS_MS.entries
            .filter { it.value <= elapsedMs }
            .maxByOrNull { it.value }
            ?.key ?: AllergicPhase.LAY_FLAT
    }

    private fun progressInPhase(elapsedMs: Long): Float {
        val phases = AllergicPhase.values()
        val idx = phases.indexOf(currentPhase)
        val phaseStart = PHASE_TRANSITIONS_MS[currentPhase] ?: 0L
        val phaseEnd = if (idx + 1 < phases.size) PHASE_TRANSITIONS_MS[phases[idx + 1]] ?: 90_000L else 90_000L
        val duration = (phaseEnd - phaseStart).toFloat()
        return if (duration <= 0f) 1f else ((elapsedMs - phaseStart).toFloat() / duration).coerceIn(0f, 1f)
    }

    private fun overlayText(phase: AllergicPhase): String = when (phase) {
        AllergicPhase.LAY_FLAT      -> "Lay flat"
        AllergicPhase.RAISE_LEGS    -> "Raise legs"
        AllergicPhase.SAFE_POSITION -> "✓ Safe position"
        AllergicPhase.EPIPEN_READY  -> "EpiPen ready"
        AllergicPhase.EPIPEN_INJECT -> "Inject EpiPen"
        AllergicPhase.MONITORING    -> "Monitoring"
    }

    // Right outer thigh midpoint: between right hip (24) and right knee (26).
    // Matches HALO's right_upLeg_joint injection site.
    private fun rightThighOf(pose: List<NormalizedLandmark>): PointF? {
        if (pose.size < 27) return null
        val hip = pose[24]; val knee = pose[26]
        if (hip.visibility < 0.4f || knee.visibility < 0.4f) return null
        return PointF((hip.x + knee.x) / 2f, (hip.y + knee.y) / 2f)
    }
}
