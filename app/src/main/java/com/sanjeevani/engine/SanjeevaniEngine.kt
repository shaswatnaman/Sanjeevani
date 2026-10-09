package com.sanjeevani.engine

import android.graphics.PointF
import com.sanjeevani.model.*
import kotlin.math.sqrt

private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_WRIST = 15
private const val RIGHT_WRIST = 16

class SanjeevaniEngine {

    private val spatialReasoner = SpatialReasoner()
    private val compressionDetector = CompressionDetector()
    private val emergencyFSM = EmergencyFSM()
    private val patientDetector = PatientDetector()

    private var lastVoiceText: String? = null
    private var lastVoiceTs = 0L
    private val MIN_VOICE_INTERVAL_MS = 2500L

    fun process(frame: PerceptionFrame): GuidanceState {
        val nowMs = frame.timestamp

        // ── Perception confidence ─────────────────────────────────────────────
        val poseConf = frame.poseLandmarks
            ?.map { it.visibility }
            ?.average()
            ?.toFloat() ?: 0f

        val handsConf = if (frame.leftHandLandmarks != null || frame.rightHandLandmarks != null) 0.8f else 0.2f

        val confidence = ConfidenceState(
            poseConfidence = poseConf,
            handsConfidence = handsConf,
            patientConfidence = 0f,
            overall = when {
                poseConf > 0.75f && handsConf > 0.6f -> ConfidenceLevel.HIGH
                poseConf > 0.50f -> ConfidenceLevel.MEDIUM
                else -> ConfidenceLevel.LOW
            }
        )

        // ── Patient detection ─────────────────────────────────────────────────
        val (isPatient, patientConf) = patientDetector.isPatientLying(frame.poseLandmarks)

        // ── Spatial reasoning ─────────────────────────────────────────────────
        val spatial = spatialReasoner.compute(
            frame.poseLandmarks,
            frame.leftHandLandmarks,
            frame.rightHandLandmarks,
            nowMs
        )

        // ── Compression detection (feed wrist Y position) ─────────────────────
        val wristY = frame.poseLandmarks?.let { pose ->
            if (pose.size > RIGHT_WRIST) {
                val lw = pose[LEFT_WRIST]
                val rw = pose[RIGHT_WRIST]
                val vis = maxOf(lw.visibility, rw.visibility)
                if (vis > 0.4f) (lw.y + rw.y) / 2f else null
            } else null
        }
        wristY?.let { compressionDetector.addSample(it, nowMs) }
        val temporal = compressionDetector.getTemporalState(nowMs)

        // ── Build initial guidance state ───────────────────────────────────────
        val isRescuer = !isPatient && poseConf > 0.5f

        val prelimGuidance = GuidanceState(
            fsmState = emergencyFSM.getState(),
            spatial = spatial,
            temporal = temporal,
            confidence = confidence,
            isPatientDetected = isPatient,
            isRescuerDetected = isRescuer
        )

        // ── FSM transition ─────────────────────────────────────────────────────
        val transition = emergencyFSM.process(prelimGuidance, nowMs)

        // ── Voice throttling ───────────────────────────────────────────────────
        val voiceText = transition.voiceText?.let { text ->
            if (text != lastVoiceText || nowMs - lastVoiceTs > MIN_VOICE_INTERVAL_MS) {
                lastVoiceText = text
                lastVoiceTs = nowMs
                text
            } else null
        }

        // ── Build AR overlay ───────────────────────────────────────────────────
        val overlay = buildOverlay(transition.newState, spatial, temporal, frame)

        return GuidanceState(
            fsmState = transition.newState,
            emergencyType = EmergencyType.CPR,
            spatial = spatial,
            temporal = temporal,
            confidence = confidence,
            overlay = overlay,
            voiceText = voiceText,
            isPatientDetected = isPatient,
            isRescuerDetected = isRescuer
        )
    }

    private fun buildOverlay(
        state: FSMState,
        spatial: SpatialState,
        temporal: TemporalState,
        frame: PerceptionFrame
    ): AROverlaySpec {
        val isCorrect = spatial.correctiveAction == SpatialAction.CORRECT

        val statusText = when (state) {
            FSMState.IDLE -> "Scanning..."
            FSMState.SCENE_ASSESSMENT -> "Emergency detected"
            FSMState.RESPONSIVENESS_CHECK -> "Check responsiveness"
            FSMState.EMERGENCY_ESCALATION -> "Call 112 / 108"
            FSMState.CPR_POSITIONING -> "Position yourself"
            FSMState.HAND_POSITIONING -> if (isCorrect) "✓ Good position" else "Adjust hands"
            FSMState.POSTURE_CHECK -> "Straighten arms"
            FSMState.COMPRESSION_ACTIVE -> when (temporal.rateStatus) {
                RateStatus.GOOD -> "✓ Good rhythm"
                RateStatus.TOO_SLOW -> "⚡ Push faster"
                RateStatus.TOO_FAST -> "↓ Slow down"
                RateStatus.INSUFFICIENT_DATA -> "Begin compressions"
            }
            else -> ""
        }

        val skeletonLines = buildSkeletonLines(frame.poseLandmarks)

        return AROverlaySpec(
            sternumTarget = spatial.sternumTarget,
            leftHandCenter = spatial.errorVector?.let { spatial.handMidpoint },
            rightHandCenter = null,
            arrowFrom = if (!isCorrect) spatial.handMidpoint else null,
            arrowTo = if (!isCorrect) spatial.sternumTarget else null,
            statusText = statusText,
            statusColorGreen = isCorrect || state == FSMState.COMPRESSION_ACTIVE && temporal.rateStatus == RateStatus.GOOD,
            compressionRate = if (state == FSMState.COMPRESSION_ACTIVE) temporal.compressionRateBPM else null,
            skeletonLines = skeletonLines,
            guidanceText = actionToText(spatial.correctiveAction)
        )
    }

    private fun actionToText(action: SpatialAction): String = when (action) {
        SpatialAction.MOVE_LEFT -> "← Move Left"
        SpatialAction.MOVE_RIGHT -> "Move Right →"
        SpatialAction.MOVE_UP -> "↑ Move Up"
        SpatialAction.MOVE_DOWN -> "↓ Move Down"
        SpatialAction.CORRECT -> ""
        SpatialAction.TRACKING_LOST -> "Hold camera steady"
        SpatialAction.UNSURE -> ""
    }

    private fun buildSkeletonLines(
        pose: List<NormalizedLandmark>?
    ): List<Pair<PointF, PointF>> {
        pose ?: return emptyList()
        val bones = listOf(
            11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
            11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28
        )
        return bones.mapNotNull { (a, b) ->
            if (a < pose.size && b < pose.size) {
                val la = pose[a]; val lb = pose[b]
                if (la.visibility > 0.5f && lb.visibility > 0.5f)
                    Pair(PointF(la.x, la.y), PointF(lb.x, lb.y))
                else null
            } else null
        }
    }

    fun confirmNoResponse(nowMs: Long) = emergencyFSM.confirmNoResponse(nowMs)
    fun getFSMState() = emergencyFSM.getState()
}
