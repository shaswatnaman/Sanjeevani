package com.sanjeevani.engine

import android.graphics.Color
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
    private val classifier = EmergencyClassifier()
    private val allergicModule = AllergicReactionModule()
    private val strokeModule = StrokeModule()
    private val heartAttackModule = HeartAttackModule()
    private val cprWorkflow = CPRWorkflow()

    private var lastVoiceText: String? = null
    private var lastVoiceTs = 0L
    private var lastTriageState = TriageState.OBSERVING
    private var classifierSignal: ClassifierSignal? = null
    private var lastAnnouncedStep: CPRStep? = null
    private var lastIsLying = false

    // Step card timing
    private var compressionStartMs = 0L
    private var lastCompressionCount = 0

    private val MIN_VOICE_INTERVAL_MS: Long
        get() = when (emergencyFSM.getConfirmedEmergencyType()) {
            EmergencyType.ALLERGIC_REACTION, EmergencyType.HEART_ATTACK -> 5000L
            else -> 2500L
        }

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

        // ── Patient detection (2D + world landmarks) ─────────────────────────
        val (isPatient, patientConf) = patientDetector.isPatientLying(frame.poseLandmarks, frame.worldLandmarks)

        // ── CPR workflow (time-based, mirrors iOS CardiacArrestView exactly) ──
        cprWorkflow.update(nowMs, isPatient)

        // ── CPR step voice — speak when step changes ──────────────────────────
        val cprStep = cprWorkflow.currentStep
        val stepVoice: String? = when {
            cprStep != lastAnnouncedStep -> {
                lastAnnouncedStep = cprStep
                when (cprStep) {
                    CPRStep.POSITION_CHECK ->
                        "Starting C P R guidance. Ensure the person is on their back on a firm, flat surface."
                    CPRStep.KNEEL_BESIDE ->
                        "Good. Now kneel beside the person and position your knees near their body."
                    CPRStep.HAND_PLACEMENT ->
                        "Place the heel of one hand in the center of the chest. Put your other hand on top. Interlace fingers. Follow the blue marker."
                    CPRStep.COMPRESSIONS ->
                        "Begin chest compressions now. Push hard and fast, at least 2 inches deep. 100 to 120 compressions per minute."
                }
            }
            // During POSITION_CHECK, speak once when patient lies down
            cprStep == CPRStep.POSITION_CHECK && isPatient && !lastIsLying -> {
                lastIsLying = true
                "Good, person is now lying flat. Stay still for a moment."
            }
            else -> {
                if (!isPatient) lastIsLying = false
                null
            }
        }

        // ── Visual classification ─────────────────────────────────────────────
        classifier.addFrame(frame.poseLandmarks)
        val fsmState = emergencyFSM.getState()
        if (fsmState == FSMState.SCENE_ASSESSMENT || fsmState == FSMState.TRIAGE_DETECTION) {
            classifierSignal = classifier.classify()
        }
        val triageState = when {
            fsmState == FSMState.TRIAGE_DETECTION -> TriageState.CAMERA_SUGGESTS
            emergencyFSM.getConfirmedEmergencyType() != EmergencyType.UNKNOWN -> TriageState.USER_CONFIRMED
            else -> TriageState.OBSERVING
        }

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
            emergencyType = emergencyFSM.getConfirmedEmergencyType(),
            spatial = spatial,
            temporal = temporal,
            confidence = confidence,
            isPatientDetected = isPatient,
            isRescuerDetected = isRescuer,
            triageState = triageState,
            classifierSignal = classifierSignal
        )

        // ── FSM transition ─────────────────────────────────────────────────────
        val transition = emergencyFSM.process(prelimGuidance, nowMs)

        // ── Module dispatch ────────────────────────────────────────────────────
        val confirmedType = emergencyFSM.getConfirmedEmergencyType()
        val moduleResult: ModuleResult? = when (transition.newState) {
            FSMState.ALLERGIC_PROTOCOL      -> allergicModule.process(frame, nowMs)
            FSMState.STROKE_FAST_TEST       -> strokeModule.process(frame, nowMs)
            FSMState.HEART_ATTACK_CONSCIOUS -> null  // Use CPR step cards (mirrors iOS Cardiac Arrest)
            else -> null
        }

        // ── Spatial action voice (hand placement corrections) ─────────────────
        val spatialActionVoice: String? = if (cprWorkflow.currentStep == CPRStep.HAND_PLACEMENT ||
            cprWorkflow.currentStep == CPRStep.COMPRESSIONS
        ) {
            when (spatial.correctiveAction) {
                SpatialAction.STACK_HANDS  -> "Stack both hands on top of each other on the blue marker."
                SpatialAction.MOVE_LEFT    -> "Move hands left toward the blue marker."
                SpatialAction.MOVE_RIGHT   -> "Move hands right toward the blue marker."
                SpatialAction.MOVE_UP      -> "Move hands up toward the blue marker."
                SpatialAction.MOVE_DOWN    -> "Move hands down toward the blue marker."
                else -> null
            }
        } else null

        // ── Voice throttling ───────────────────────────────────────────────────
        // Priority: step change > spatial correction > module > FSM transition
        val rawVoice = stepVoice ?: spatialActionVoice ?: moduleResult?.voiceText ?: transition.voiceText
        val voiceText = rawVoice?.let { text ->
            if (text != lastVoiceText || nowMs - lastVoiceTs > MIN_VOICE_INTERVAL_MS) {
                lastVoiceText = text
                lastVoiceTs = nowMs
                text
            } else null
        }

        // ── Build AR overlay ───────────────────────────────────────────────────
        val overlay = moduleResult?.overlay
            ?: buildOverlay(transition.newState, spatial, temporal, frame, confirmedType, isPatient)

        val effectiveType = if (confirmedType != EmergencyType.UNKNOWN) confirmedType else EmergencyType.CPR

        return GuidanceState(
            fsmState = transition.newState,
            emergencyType = effectiveType,
            spatial = spatial,
            temporal = temporal,
            confidence = confidence,
            overlay = overlay,
            voiceText = voiceText,
            isPatientDetected = isPatient,
            isRescuerDetected = isRescuer,
            triageState = triageState,
            classifierSignal = classifierSignal
        )
    }

    private fun buildOverlay(
        state: FSMState,
        spatial: SpatialState,
        temporal: TemporalState,
        frame: PerceptionFrame,
        emergencyType: EmergencyType = EmergencyType.CPR,
        isPatientLying: Boolean = false
    ): AROverlaySpec {
        val cprStep = cprWorkflow.currentStep
        val skeletonLines = buildSkeletonLines(frame.poseLandmarks)
        val jointPoints = buildJointPoints(frame.poseLandmarks)
        val faceMeshPoints = buildFaceMeshPoints(frame.faceLandmarks)

        // iOS exact step card data (CardiacArrestView.swift CPRStep enum)
        val stepCardTitle: String
        val stepCardInstruction: String
        val stepCardStatus: String
        val stepCardBgColor: Int
        val stepCardIcon: String

        val isLyingNow = isPatientLying

        when (cprStep) {
            CPRStep.POSITION_CHECK -> {
                stepCardTitle = "1. Position Check"
                stepCardInstruction = if (isLyingNow)
                    "Person is on their back on firm surface. Ready to begin CPR."
                else
                    "Ensure person is on their back on firm, flat surface. Roll them over if needed."
                stepCardStatus = if (isLyingNow) "✓ Ready for CPR" else "Help person lie down"
                stepCardBgColor = if (isLyingNow)
                    Color.argb(210, 34, 139, 34)    // green — person is lying correctly
                else
                    Color.argb(210, 185, 40, 40)    // red — not yet in position
                stepCardIcon = "👤"
            }
            CPRStep.KNEEL_BESIDE -> {
                stepCardTitle = "2. Kneel Beside Person"
                stepCardInstruction = "Kneel beside the person. Position knees near body, shoulder-width apart."
                stepCardStatus = "Get into position"
                stepCardBgColor = Color.argb(210, 220, 110, 0)   // iOS .orange
                stepCardIcon = "🧎"
            }
            CPRStep.HAND_PLACEMENT -> {
                stepCardTitle = "3. Hand Placement"
                stepCardInstruction = "Place hands on top of blue marker. Heel of one hand in center, other hand on top. Interlace fingers."
                stepCardStatus = "Position hands on blue marker"
                stepCardBgColor = Color.argb(210, 190, 155, 0)   // iOS .yellow (dark enough for white text)
                stepCardIcon = "🤚"
            }
            CPRStep.COMPRESSIONS -> {
                stepCardTitle = "5. Chest Compressions"
                stepCardInstruction = "Push hard and fast - at least 2 inches deep"
                stepCardStatus = "100-120 compressions per minute"
                stepCardBgColor = Color.argb(210, 0, 100, 215)   // iOS .blue
                stepCardIcon = "❤️"
            }
        }

        // Blue chest sphere only shows from Step 3 onward (when hand placement begins)
        val showSternum = cprStep == CPRStep.HAND_PLACEMENT || cprStep == CPRStep.COMPRESSIONS
        val sternumTarget = if (showSternum) spatial.sternumTarget else null

        return AROverlaySpec(
            sternumTarget = sternumTarget,
            skeletonLines = skeletonLines,
            jointPoints = jointPoints,
            faceMeshPoints = faceMeshPoints,
            stepCardTitle = stepCardTitle,
            stepCardInstruction = stepCardInstruction,
            stepCardStatus = stepCardStatus,
            stepCardBgColor = stepCardBgColor,
            stepCardIcon = stepCardIcon,
            compressionCount = cprWorkflow.compressionCount,
            elapsedSecs = cprWorkflow.elapsedSecs,
            showVitalsPanel = true,
            showCPRBadge = true,
            emergencyType = emergencyType
        )
    }

    private fun actionToText(action: SpatialAction): String = when (action) {
        SpatialAction.MOVE_LEFT -> "← Move Left"
        SpatialAction.MOVE_RIGHT -> "Move Right →"
        SpatialAction.MOVE_UP -> "↑ Move Up"
        SpatialAction.MOVE_DOWN -> "↓ Move Down"
        SpatialAction.STACK_HANDS -> "⊕ Stack hands"
        SpatialAction.CORRECT -> ""
        SpatialAction.TRACKING_LOST -> "Hold camera steady"
        SpatialAction.UNSURE -> ""
    }

    // iOS-matching skeleton bones (mirrors Bones.swift — no face/finger clutter)
    private val SKELETON_BONES = listOf(
        // Neck: nose → shoulders (approximates iOS neck_1 → spine_7 → shoulders)
        0 to 11, 0 to 12,
        // Torso
        11 to 12,             // shoulder bar
        11 to 23, 12 to 24,   // torso sides
        23 to 24,             // hip bar
        // Left arm: shoulder → elbow → wrist
        11 to 13, 13 to 15,
        // Right arm: shoulder → elbow → wrist
        12 to 14, 14 to 16,
        // Left leg: hip → knee → ankle → foot
        23 to 25, 25 to 27, 27 to 29,
        // Right leg: hip → knee → ankle → foot
        24 to 26, 26 to 28, 28 to 30
    )

    // iOS-matching heatmap (mirrors BodySkeleton.swift heatmapColor groups)
    // head=red, spine/torso=red-orange, shoulders/upper-arms=orange-yellow,
    // forearms/hands=cyan, upper-legs=blue-violet, lower-legs/feet=magenta
    private fun jointColor(index: Int): Int {
        return when (index) {
            0 -> android.graphics.Color.rgb(255, 80, 80)          // nose — red
            in 1..10 -> android.graphics.Color.rgb(255, 60, 60)   // face — red (hidden from rendering)
            11, 12 -> android.graphics.Color.rgb(255, 160, 40)    // shoulders — orange-yellow
            13, 14 -> android.graphics.Color.rgb(255, 200, 50)    // elbows — yellow
            15, 16 -> android.graphics.Color.rgb(50, 220, 220)    // wrists — cyan
            17, 18, 19, 20, 21, 22 -> android.graphics.Color.rgb(50, 200, 220) // fingers — teal
            23, 24 -> android.graphics.Color.rgb(255, 120, 50)    // hips — orange
            25, 26 -> android.graphics.Color.rgb(160, 80, 255)    // knees — blue-violet
            27, 28 -> android.graphics.Color.rgb(200, 60, 255)    // ankles — violet
            29, 30, 31, 32 -> android.graphics.Color.rgb(255, 60, 200) // feet — magenta
            else -> android.graphics.Color.WHITE
        }
    }

    private fun buildSkeletonLines(
        pose: List<NormalizedLandmark>?
    ): List<Pair<PointF, PointF>> {
        pose ?: return emptyList()
        return SKELETON_BONES.mapNotNull { (a, b) ->
            if (a < pose.size && b < pose.size) {
                val la = pose[a]; val lb = pose[b]
                if (la.visibility > 0.3f && lb.visibility > 0.3f)
                    Pair(PointF(la.x, la.y), PointF(lb.x, lb.y))
                else null
            } else null
        }
    }

    private fun buildJointPoints(
        pose: List<NormalizedLandmark>?
    ): List<Pair<PointF, Int>> {
        pose ?: return emptyList()
        return pose.mapIndexedNotNull { i, lm ->
            // iOS hides face sub-joints (1-10) and finger MCPs (17-22)
            val hidden = i in 1..10 || i in 17..22
            if (!hidden && lm.visibility > 0.3f)
                Pair(PointF(lm.x, lm.y), jointColor(i))
            else null
        }
    }

    // Sample key face contour landmarks from the 478-point FaceLandmarker output.
    // Indices follow MediaPipe Face Mesh topology: face oval + eyes + lips + nose.
    private val FACE_CONTOUR_INDICES = listOf(
        // Face oval (36 points)
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
        397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
        172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
        // Left eye
        33, 7, 163, 144, 145, 153, 154, 155, 133, 246, 161, 160, 159, 158, 157, 173,
        // Right eye
        263, 249, 390, 373, 374, 380, 381, 382, 362, 466, 388, 387, 386, 385, 384, 398,
        // Lips outer
        61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146,
        // Nose bridge
        168, 6, 197, 195, 5, 4, 1, 19, 94, 2
    )

    private fun buildFaceMeshPoints(face: List<NormalizedLandmark>?): List<PointF> {
        face ?: return emptyList()
        return FACE_CONTOUR_INDICES.mapNotNull { i ->
            if (i < face.size) PointF(face[i].x, face[i].y) else null
        }
    }

    fun confirmNoResponse(nowMs: Long) = emergencyFSM.confirmNoResponse(nowMs)
    fun getFSMState() = emergencyFSM.getState()

    fun setUserSelectedEmergency(type: EmergencyType) {
        val nowMs = System.currentTimeMillis()
        emergencyFSM.setUserSelectedEmergency(type, nowMs)
        spatialReasoner.reset()
        if (type != EmergencyType.ALLERGIC_REACTION) allergicModule.reset()
        if (type != EmergencyType.FAST_STROKE) strokeModule.reset()
        if (type != EmergencyType.HEART_ATTACK) heartAttackModule.reset()
        if (type == EmergencyType.CPR || type == EmergencyType.HEART_ATTACK) {
            cprWorkflow.reset()
            lastAnnouncedStep = null
            lastIsLying = false
        }
    }

    fun onStrokeSpeechResult(positive: Boolean) = strokeModule.onSpeechResult(positive)
}
