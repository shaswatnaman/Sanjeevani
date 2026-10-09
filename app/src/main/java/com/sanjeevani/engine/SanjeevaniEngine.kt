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
    private var openingVoiceTriagePending = true
    private var latestFrameTimestampMs = 0L
    private var guidanceDeferredUntilMs = 0L
    private var lastSpatialVoiceTs = 0L
    private var hasSpokenCompressionStart = false
    private var lastObservedCprStep: CPRStep? = null
    private var paceVoiceTs = 0L
    private var pendingCompressionMilestone = 0
    private var lastAnnouncedCompressionMilestone = 0

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
        latestFrameTimestampMs = nowMs

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

        val selectedTypeAtFrameStart = emergencyFSM.getConfirmedEmergencyType()
        val cprGuidanceActive = (selectedTypeAtFrameStart == EmergencyType.CPR ||
            selectedTypeAtFrameStart == EmergencyType.HEART_ATTACK) &&
            emergencyFSM.getState() != FSMState.RESPONSIVENESS_CHECK

        // CPR guidance must remain silent until an emergency has been selected.
        if (cprGuidanceActive) cprWorkflow.update(nowMs, isPatient)
        val positionReady = cprGuidanceActive && cprWorkflow.isPositionLocked

        // ── CPR step voice — speak when step changes ──────────────────────────
        val cprStep = cprWorkflow.currentStep
        if (cprStep != lastObservedCprStep) {
            val prevStep = lastObservedCprStep
            lastObservedCprStep = cprStep
            hasSpokenCompressionStart = false
            // Reset compression detection only when entering compressions fresh (not from a 30-count reminder)
            if (cprStep == CPRStep.COMPRESSIONS && prevStep != CPRStep.MAINTAIN_RHYTHM) {
                compressionDetector.reset()
                cprWorkflow.updateCompressionMetrics(0, 0f)
                paceVoiceTs = 0L
                pendingCompressionMilestone = 0
                lastAnnouncedCompressionMilestone = 0
            }
        }
        // stepVoiceScript is set for step transitions that use segmented voice delivery.
        // stepVoice is for simple single-utterance steps.
        var stepVoiceScript: VoiceScript? = null
        val stepVoice: String? = if (!cprGuidanceActive || nowMs < guidanceDeferredUntilMs) null else when {
            cprStep != lastAnnouncedStep -> {
                lastAnnouncedStep = cprStep
                when (cprStep) {
                    CPRStep.POSITION_CHECK -> if (positionReady) {
                        lastIsLying = true
                        "Great — she's in position. Let's begin CPR."
                    } else {
                        lastIsLying = false
                        "Roll her onto her back on a firm, flat surface."
                    }
                    CPRStep.KNEEL_BESIDE ->
                        "Good. Now kneel beside the person and position your knees near their body."
                    CPRStep.HAND_PLACEMENT -> {
                        stepVoiceScript = VoiceScript(listOf(
                            VoiceSegment("Place one hand in the center of the chest.", VoiceUrgency.NORMAL, 300),
                            VoiceSegment("Heel of the hand on the blue marker.", VoiceUrgency.NORMAL, 300),
                            VoiceSegment("Stack the other hand on top and interlace your fingers.", VoiceUrgency.CALM, 0)
                        ))
                        null
                    }
                    CPRStep.BODY_POSITION -> {
                        stepVoiceScript = VoiceScript(listOf(
                            VoiceSegment("Lock your arms straight.", VoiceUrgency.NORMAL, 300),
                            VoiceSegment("Shoulders directly above your hands.", VoiceUrgency.NORMAL, 300),
                            VoiceSegment("Don't bend your elbows.", VoiceUrgency.CALM, 0)
                        ))
                        null
                    }
                    CPRStep.COMPRESSIONS -> {
                        stepVoiceScript = VoiceScript(listOf(
                            VoiceSegment("Begin compressions now.", VoiceUrgency.URGENT, 200),
                            VoiceSegment("Push hard and fast!", VoiceUrgency.URGENT, 200),
                            VoiceSegment("At least 2 inches deep. 100 to 120 per minute.", VoiceUrgency.NORMAL, 0)
                        ))
                        null
                    }
                    CPRStep.MAINTAIN_RHYTHM ->
                        "30 compressions done. Give 2 rescue breaths if trained. Otherwise keep compressing."
                }
            }
            // During POSITION_CHECK, speak once when patient lies down
            cprStep == CPRStep.POSITION_CHECK && positionReady && !lastIsLying -> {
                lastIsLying = true
                "Great — she's in position. Let's begin CPR."
            }
            cprStep == CPRStep.POSITION_CHECK && !positionReady -> {
                lastIsLying = false
                "Roll her onto her back on a firm, flat surface."
            }
            else -> {
                if (!isPatient) lastIsLying = false
                null
            }
        }

        // Trigger MAINTAIN_RHYTHM step after every 30 compressions
        val compressionCount = cprWorkflow.compressionCount
        if (cprWorkflow.currentStep == CPRStep.COMPRESSIONS &&
            compressionCount > 0 && compressionCount % 30 == 0 &&
            compressionCount > lastAnnouncedCompressionMilestone + 25
        ) {
            cprWorkflow.triggerMaintainRhythm(nowMs)
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
                when {
                    lw.visibility > 0.4f && lw.visibility >= rw.visibility -> lw.y
                    rw.visibility > 0.4f -> rw.y
                    else -> null
                }
            } else null
        }
        wristY?.let { compressionDetector.addSample(it, nowMs) }
        val temporal = compressionDetector.getTemporalState(nowMs)
        cprWorkflow.updateCompressionMetrics(
            temporal.compressionCount,
            temporal.compressionRateBPM
        )

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
        // During the opening eight-second voice window, keep the FSM in its opening
        // state. Speech selection or the timeout explicitly releases this gate.
        val transition = if (openingVoiceTriagePending &&
            (emergencyFSM.getState() == FSMState.IDLE ||
                emergencyFSM.getState() == FSMState.SCENE_ASSESSMENT)
        ) {
            FSMTransitionResult(emergencyFSM.getState(), null)
        } else {
            emergencyFSM.process(prelimGuidance, nowMs)
        }

        // ── Module dispatch ────────────────────────────────────────────────────
        val confirmedType = emergencyFSM.getConfirmedEmergencyType()
        val moduleResult: ModuleResult? = when (transition.newState) {
            FSMState.ALLERGIC_PROTOCOL      -> allergicModule.process(frame, nowMs)
            FSMState.STROKE_FAST_TEST       -> strokeModule.process(frame, nowMs)
            // In this app, the Heart Attack card represents the cardiac-emergency
            // entry point into the guided CPR sequence. Do not dispatch the separate
            // conscious-patient aspirin/sitting protocol here.
            FSMState.HEART_ATTACK_CONSCIOUS -> null
            else -> null
        }

        // ── Spatial action voice (hand placement corrections) ─────────────────
        val compressionStartVoice = "Press hard and fast! Keep going — don't stop!"
        val compressionStartVoicePending = cprWorkflow.currentStep == CPRStep.COMPRESSIONS &&
            spatial.correctiveAction == SpatialAction.CORRECT &&
            !hasSpokenCompressionStart
        val spatialActionVoice: String? = if (cprWorkflow.currentStep == CPRStep.HAND_PLACEMENT ||
            cprWorkflow.currentStep == CPRStep.COMPRESSIONS
        ) {
            when {
                compressionStartVoicePending -> compressionStartVoice
                spatial.correctiveAction == SpatialAction.MOVE_LEFT ->
                    "A little to the left — follow the blue marker."
                spatial.correctiveAction == SpatialAction.MOVE_RIGHT ->
                    "Shift right — keep your hands on the blue circle."
                spatial.correctiveAction == SpatialAction.MOVE_UP ->
                    "Move up slightly — center on the blue marker."
                spatial.correctiveAction == SpatialAction.MOVE_DOWN ->
                    "Come down a bit — you're above the target."
                else -> null
            }
        } else null

        val throttledSpatialActionVoice = spatialActionVoice?.takeIf { voice ->
            if (voice == compressionStartVoice) return@takeIf true
            if (nowMs - lastSpatialVoiceTs >= 3_000L) {
                lastSpatialVoiceTs = nowMs
                true
            } else {
                false
            }
        }

        val currentBpm = cprWorkflow.currentBpm
        if (cprWorkflow.currentStep == CPRStep.COMPRESSIONS &&
            compressionCount > 0 && compressionCount % 10 == 0 &&
            compressionCount > lastAnnouncedCompressionMilestone
        ) {
            pendingCompressionMilestone = compressionCount
        }
        val paceVoice: String? = if (
            cprWorkflow.currentStep == CPRStep.COMPRESSIONS &&
            nowMs - paceVoiceTs >= 4_000L
        ) {
            when {
                pendingCompressionMilestone > lastAnnouncedCompressionMilestone -> {
                    val milestone = pendingCompressionMilestone
                    pendingCompressionMilestone = 0
                    lastAnnouncedCompressionMilestone = milestone
                    paceVoiceTs = nowMs
                    "$milestone compressions. Keep going!"
                }
                currentBpm in 0.1f..<90f -> {
                    paceVoiceTs = nowMs
                    "Faster! Push harder — aim for 100 per minute."
                }
                currentBpm > 130f -> {
                    paceVoiceTs = nowMs
                    "Slow down slightly — steady rhythm."
                }
                else -> null
            }
        } else null

        // ── Voice throttling ───────────────────────────────────────────────────
        // Priority: step script > step voice > pace > spatial correction > module > FSM transition
        val activeScript = if (nowMs < guidanceDeferredUntilMs) null else stepVoiceScript
        val rawVoice = if (nowMs < guidanceDeferredUntilMs) null
        else stepVoice ?: paceVoice ?: throttledSpatialActionVoice ?: moduleResult?.voiceText ?: transition.voiceText
        val voiceText = rawVoice?.let { text ->
            if (text != lastVoiceText || nowMs - lastVoiceTs > MIN_VOICE_INTERVAL_MS) {
                lastVoiceText = text
                lastVoiceTs = nowMs
                text
            } else null
        }
        // Throttle scripts the same way (use first segment text as the key)
        val voiceScript = activeScript?.let { script ->
            val key = script.segments.firstOrNull()?.text ?: return@let null
            if (key != lastVoiceText || nowMs - lastVoiceTs > MIN_VOICE_INTERVAL_MS) {
                lastVoiceText = key
                lastVoiceTs = nowMs
                script
            } else null
        }
        if (voiceText == compressionStartVoice) {
            hasSpokenCompressionStart = true
        }
        // Assign urgency based on voice type
        val voiceUrgency = when {
            voiceScript != null -> VoiceUrgency.NORMAL   // script manages its own urgency per segment
            paceVoice != null && voiceText == paceVoice -> VoiceUrgency.URGENT
            throttledSpatialActionVoice != null && voiceText == throttledSpatialActionVoice -> VoiceUrgency.URGENT
            else -> VoiceUrgency.NORMAL
        }

        // ── Build AR overlay ───────────────────────────────────────────────────
        // Never show CPR Step 1 while the app is still observing or asking what
        // happened. CPR cards begin only after CPR is explicitly selected and the
        // responsiveness check has completed.
        val showCprProtocol = (confirmedType == EmergencyType.CPR ||
            confirmedType == EmergencyType.HEART_ATTACK) &&
            transition.newState !in setOf(
                FSMState.IDLE,
                FSMState.SCENE_ASSESSMENT,
                FSMState.TRIAGE_DETECTION,
                FSMState.RESPONSIVENESS_CHECK
            )
        val moduleOverlay = moduleResult?.overlay?.copy(
            skeletonLines = buildSkeletonLines(frame.poseLandmarks),
            jointPoints = buildJointPoints(frame.poseLandmarks),
            faceMeshPoints = buildFaceMeshPoints(frame.faceLandmarks)
        )
        val overlay = moduleOverlay
            ?: if (showCprProtocol) {
                buildOverlay(transition.newState, spatial, temporal, frame, confirmedType, positionReady)
            } else {
                buildObservationOverlay(frame, confirmedType)
            }

        val effectiveType = if (confirmedType != EmergencyType.UNKNOWN) confirmedType else EmergencyType.CPR

        return GuidanceState(
            fsmState = transition.newState,
            emergencyType = effectiveType,
            spatial = spatial,
            temporal = temporal,
            confidence = confidence,
            overlay = overlay,
            voiceText = if (voiceScript == null) voiceText else null,
            voiceScript = voiceScript,
            voiceUrgency = voiceUrgency,
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
                    "Ensure person is on their back on a firm, flat surface, with one arm on each side. Roll them over if needed."
                stepCardStatus = if (isLyingNow) "✓ Ready for CPR →" else "Help person lie down"
                stepCardBgColor = if (isLyingNow)
                    Color.rgb(34, 139, 34)          // 0xFF228B22 — person is lying correctly
                else
                    Color.rgb(185, 40, 40)          // 0xFFB92828 — not yet in position
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
                stepCardInstruction = "Place one hand on the blue marker in the center of the chest."
                stepCardStatus = "Position one hand on blue marker"
                stepCardBgColor = Color.argb(210, 190, 155, 0)   // iOS .yellow (dark enough for white text)
                stepCardIcon = "🤚"
            }
            CPRStep.BODY_POSITION -> {
                stepCardTitle = "4. Body Position"
                stepCardInstruction = "Lock your arms straight. Shoulders directly above your hands. Don't bend your elbows."
                stepCardStatus = "Straighten arms"
                stepCardBgColor = Color.argb(210, 220, 110, 0)   // orange
                stepCardIcon = "💪"
            }
            CPRStep.COMPRESSIONS -> {
                stepCardTitle = "5. Chest Compressions"
                stepCardInstruction = "Push hard and fast - at least 2 inches deep"
                stepCardStatus = "${cprWorkflow.compressionCount} compressions | " +
                    "${cprWorkflow.currentBpm.toInt()} BPM"
                stepCardBgColor = Color.argb(210, 0, 100, 215)   // iOS .blue
                stepCardIcon = "❤️"
            }
            CPRStep.MAINTAIN_RHYTHM -> {
                stepCardTitle = "6. Maintain Rhythm"
                stepCardInstruction = "30 compressions done. Give 2 rescue breaths if trained. Otherwise keep compressing."
                stepCardStatus = "Continue compressions"
                stepCardBgColor = Color.argb(210, 0, 100, 215)   // blue
                stepCardIcon = "🔄"
            }
        }

        // Blue chest sphere shows from Step 3 onward
        val showSternum = cprStep == CPRStep.HAND_PLACEMENT || cprStep == CPRStep.BODY_POSITION ||
            cprStep == CPRStep.COMPRESSIONS || cprStep == CPRStep.MAINTAIN_RHYTHM
        val sternumTarget = if (showSternum) spatial.sternumTarget else null
        val handPlacementCorrection = if (
            cprStep == CPRStep.HAND_PLACEMENT &&
            spatial.correctiveAction != SpatialAction.CORRECT &&
            spatial.correctiveAction != SpatialAction.TRACKING_LOST
        ) {
            actionToText(spatial.correctiveAction)
        } else {
            ""
        }

        return AROverlaySpec(
            sternumTarget = sternumTarget,
            statusColorGreen = cprStep == CPRStep.COMPRESSIONS &&
                spatial.correctiveAction == SpatialAction.CORRECT,
            guidanceText = handPlacementCorrection,
            skeletonLines = skeletonLines,
            jointPoints = jointPoints,
            faceMeshPoints = faceMeshPoints,
            stepCardTitle = stepCardTitle,
            stepCardInstruction = stepCardInstruction,
            stepCardStatus = stepCardStatus,
            stepCardBgColor = stepCardBgColor,
            stepCardIcon = stepCardIcon,
            compressionCount = cprWorkflow.compressionCount,
            compressionRate = cprWorkflow.currentBpm,
            elapsedSecs = cprWorkflow.elapsedSecs,
            showVitalsPanel = (cprStep == CPRStep.COMPRESSIONS || cprStep == CPRStep.MAINTAIN_RHYTHM),
            showCPRBadge = true,
            emergencyType = emergencyType
        )
    }

    /** Camera/skeleton-only overlay used before a protocol has been confirmed. */
    private fun buildObservationOverlay(
        frame: PerceptionFrame,
        emergencyType: EmergencyType
    ): AROverlaySpec = AROverlaySpec(
        skeletonLines = buildSkeletonLines(frame.poseLandmarks),
        jointPoints = buildJointPoints(frame.poseLandmarks),
        faceMeshPoints = buildFaceMeshPoints(frame.faceLandmarks),
        emergencyType = emergencyType
    )

    private fun actionToText(action: SpatialAction): String = when (action) {
        SpatialAction.MOVE_LEFT -> "← Move Left"
        SpatialAction.MOVE_RIGHT -> "Move Right →"
        SpatialAction.MOVE_UP -> "↑ Move Up"
        SpatialAction.MOVE_DOWN -> "↓ Move Down"
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

    fun confirmNoResponse() = emergencyFSM.confirmNoResponse(currentEngineTimeMs())
    fun onPatientResponsive() = emergencyFSM.onPatientResponsive(currentEngineTimeMs())
    fun getFSMState() = emergencyFSM.getState()

    fun setUserSelectedEmergency(type: EmergencyType) {
        openingVoiceTriagePending = false
        val nowMs = currentEngineTimeMs()
        emergencyFSM.setUserSelectedEmergency(type, nowMs)
        spatialReasoner.reset()
        if (type != EmergencyType.ALLERGIC_REACTION) allergicModule.reset()
        if (type != EmergencyType.FAST_STROKE) strokeModule.reset()
        if (type != EmergencyType.HEART_ATTACK) heartAttackModule.reset()
        if (type == EmergencyType.CPR || type == EmergencyType.HEART_ATTACK) {
            cprWorkflow.reset()
            lastAnnouncedStep = null
            lastIsLying = false
            lastSpatialVoiceTs = 0L
            hasSpokenCompressionStart = false
            lastObservedCprStep = null
            paceVoiceTs = 0L
            pendingCompressionMilestone = 0
            lastAnnouncedCompressionMilestone = 0
            compressionDetector.reset()
        }
    }

    fun showEmergencySelection() {
        if (!openingVoiceTriagePending) return
        openingVoiceTriagePending = false
        emergencyFSM.setUserSelectedEmergency(EmergencyType.UNKNOWN, currentEngineTimeMs())
    }

    fun deferGuidance(durationMs: Long) {
        guidanceDeferredUntilMs = currentEngineTimeMs() + durationMs
    }

    private fun currentEngineTimeMs(): Long =
        latestFrameTimestampMs.takeIf { it > 0L } ?: android.os.SystemClock.elapsedRealtime()

    fun onStrokeSpeechResult(positive: Boolean) = strokeModule.onSpeechResult(positive)
}
