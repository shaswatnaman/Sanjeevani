package com.sanjeevani.model

import android.graphics.PointF

// ─── Enums ───────────────────────────────────────────────────────────────────

enum class EmergencyType { CPR, FAST_STROKE, CHOKING, UNKNOWN }

enum class SpatialAction {
    MOVE_LEFT, MOVE_RIGHT, MOVE_UP, MOVE_DOWN,
    CORRECT, TRACKING_LOST, UNSURE
}

enum class RateStatus { TOO_SLOW, GOOD, TOO_FAST, INSUFFICIENT_DATA }

enum class ConfidenceLevel { HIGH, MEDIUM, LOW, NONE }

enum class FSMState {
    IDLE,
    SCENE_ASSESSMENT,
    RESPONSIVENESS_CHECK,
    EMERGENCY_ESCALATION,
    CPR_POSITIONING,
    HAND_POSITIONING,
    POSTURE_CHECK,
    COMPRESSION_ACTIVE,
    CPR_PAUSE,
    CPR_SUCCESS
}

// ─── Perception ──────────────────────────────────────────────────────────────

data class NormalizedLandmark(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val visibility: Float = 1f
)

data class PerceptionFrame(
    val timestamp: Long,
    val poseLandmarks: List<NormalizedLandmark>?,   // 33 body landmarks
    val leftHandLandmarks: List<NormalizedLandmark>?,  // 21 landmarks
    val rightHandLandmarks: List<NormalizedLandmark>?, // 21 landmarks
    val imageWidth: Int,
    val imageHeight: Int
)

// ─── Scene ───────────────────────────────────────────────────────────────────

data class SpatialState(
    val sternumTarget: PointF? = null,
    val handMidpoint: PointF? = null,
    val errorVector: PointF? = null,
    val errorMagnitude: Float = Float.MAX_VALUE,
    val correctiveAction: SpatialAction = SpatialAction.UNSURE
)

data class TemporalState(
    val compressionRateBPM: Float = 0f,
    val rateStatus: RateStatus = RateStatus.INSUFFICIENT_DATA,
    val compressionCount: Int = 0,
    val elbowAngleDegrees: Float = 180f
)

data class ConfidenceState(
    val poseConfidence: Float = 0f,
    val handsConfidence: Float = 0f,
    val patientConfidence: Float = 0f,
    val overall: ConfidenceLevel = ConfidenceLevel.NONE
)

// ─── Guidance ────────────────────────────────────────────────────────────────

data class AROverlaySpec(
    val sternumTarget: PointF? = null,
    val leftHandCenter: PointF? = null,
    val rightHandCenter: PointF? = null,
    val arrowFrom: PointF? = null,
    val arrowTo: PointF? = null,
    val statusText: String = "",
    val statusColorGreen: Boolean = false,
    val compressionRate: Float? = null,
    val skeletonLines: List<Pair<PointF, PointF>> = emptyList(),
    val guidanceText: String = ""
)

data class GuidanceState(
    val fsmState: FSMState = FSMState.IDLE,
    val emergencyType: EmergencyType = EmergencyType.UNKNOWN,
    val spatial: SpatialState = SpatialState(),
    val temporal: TemporalState = TemporalState(),
    val confidence: ConfidenceState = ConfidenceState(),
    val overlay: AROverlaySpec = AROverlaySpec(),
    val voiceText: String? = null,
    val isPatientDetected: Boolean = false,
    val isRescuerDetected: Boolean = false
)
