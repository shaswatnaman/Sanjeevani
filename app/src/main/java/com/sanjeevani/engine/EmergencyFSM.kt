package com.sanjeevani.engine

import com.sanjeevani.model.*

data class FSMTransitionResult(
    val newState: FSMState,
    val voiceText: String?,
    val requiresUserConfirmation: Boolean = false
)

class EmergencyFSM {

    private var currentState = FSMState.IDLE
    private var stateEnteredAt = 0L
    private var handsCorrectSince = 0L
    private var confirmedEmergencyType = EmergencyType.UNKNOWN

    fun getState() = currentState
    fun getConfirmedEmergencyType() = confirmedEmergencyType

    fun process(
        guidance: GuidanceState,
        nowMs: Long
    ): FSMTransitionResult {
        return when (currentState) {
            FSMState.IDLE -> processIdle(guidance, nowMs)
            FSMState.SCENE_ASSESSMENT -> processSceneAssessment(guidance, nowMs)
            FSMState.TRIAGE_DETECTION -> processTriageDetection(guidance, nowMs)
            FSMState.RESPONSIVENESS_CHECK -> processResponsivenessCheck(nowMs)
            FSMState.EMERGENCY_ESCALATION -> processEscalation(nowMs)
            FSMState.CPR_POSITIONING -> processCPRPositioning(guidance, nowMs)
            FSMState.HAND_POSITIONING -> processHandPositioning(guidance, nowMs)
            FSMState.POSTURE_CHECK -> processPostureCheck(guidance, nowMs)
            FSMState.COMPRESSION_ACTIVE -> processCompressionActive(guidance, nowMs)
            FSMState.STROKE_FAST_TEST -> FSMTransitionResult(currentState, null)
            FSMState.HEART_ATTACK_CONSCIOUS -> FSMTransitionResult(currentState, null)
            FSMState.ALLERGIC_PROTOCOL -> FSMTransitionResult(currentState, null)
            else -> FSMTransitionResult(currentState, null)
        }
    }

    private fun processIdle(guidance: GuidanceState, nowMs: Long): FSMTransitionResult {
        if (guidance.isPatientDetected) {
            transition(FSMState.SCENE_ASSESSMENT, nowMs)
            return FSMTransitionResult(
                FSMState.SCENE_ASSESSMENT,
                "I can see someone who may need help. Call 1 1 2 immediately. I will guide you while help is on the way.",
                requiresUserConfirmation = true
            )
        }
        return FSMTransitionResult(FSMState.IDLE, null)
    }

    private fun processSceneAssessment(guidance: GuidanceState, nowMs: Long): FSMTransitionResult {
        if (guidance.isPatientDetected && nowMs - stateEnteredAt > 2000L) {
            // Route based on classifier signal
            val signal = guidance.classifierSignal
            if (signal != null && signal.confidence > 0.4f && signal.suggestedByCamera) {
                transition(FSMState.TRIAGE_DETECTION, nowMs)
                return FSMTransitionResult(
                    FSMState.TRIAGE_DETECTION,
                    "I can see someone in distress. Please confirm what is happening.",
                    requiresUserConfirmation = true
                )
            }
            // Default: treat as unresponsive → CPR path
            transition(FSMState.RESPONSIVENESS_CHECK, nowMs)
            return FSMTransitionResult(
                FSMState.RESPONSIVENESS_CHECK,
                "Tap their shoulder and call their name. Are they responding?",
                requiresUserConfirmation = true
            )
        }
        return FSMTransitionResult(FSMState.SCENE_ASSESSMENT, null)
    }

    private fun processTriageDetection(guidance: GuidanceState, nowMs: Long): FSMTransitionResult {
        // Waits for setUserSelectedEmergency() or times out to CPR after 20s
        if (nowMs - stateEnteredAt > 20_000L) {
            // Timeout: fall through to responsiveness check
            transition(FSMState.RESPONSIVENESS_CHECK, nowMs)
            return FSMTransitionResult(
                FSMState.RESPONSIVENESS_CHECK,
                "Tap their shoulder and call their name. Are they responding?",
                requiresUserConfirmation = true
            )
        }
        return FSMTransitionResult(FSMState.TRIAGE_DETECTION, null)
    }

    private fun processResponsivenessCheck(nowMs: Long): FSMTransitionResult {
        // User must confirm via voice or button; transition triggered by confirmNoResponse()
        return FSMTransitionResult(FSMState.RESPONSIVENESS_CHECK, null)
    }

    private fun processEscalation(nowMs: Long): FSMTransitionResult {
        if (nowMs - stateEnteredAt > 3000L) {
            transition(FSMState.CPR_POSITIONING, nowMs)
            return FSMTransitionResult(
                FSMState.CPR_POSITIONING,
                "Kneel beside them. I will show you exactly where to place your hands."
            )
        }
        return FSMTransitionResult(FSMState.EMERGENCY_ESCALATION, null)
    }

    private fun processCPRPositioning(guidance: GuidanceState, nowMs: Long): FSMTransitionResult {
        if (guidance.isRescuerDetected && nowMs - stateEnteredAt > 3000L) {
            transition(FSMState.HAND_POSITIONING, nowMs)
            return FSMTransitionResult(
                FSMState.HAND_POSITIONING,
                "Place both hands on the center of their chest. Follow the circle on screen."
            )
        }
        return FSMTransitionResult(FSMState.CPR_POSITIONING, null)
    }

    private fun processHandPositioning(guidance: GuidanceState, nowMs: Long): FSMTransitionResult {
        val action = guidance.spatial.correctiveAction
        val mag = guidance.spatial.errorMagnitude

        // Track how long hands have been in correct position
        if (action == SpatialAction.CORRECT) {
            if (handsCorrectSince == 0L) handsCorrectSince = nowMs
            if (nowMs - handsCorrectSince > 800L) {
                transition(FSMState.POSTURE_CHECK, nowMs)
                handsCorrectSince = 0L
                return FSMTransitionResult(
                    FSMState.POSTURE_CHECK,
                    "Good position. Now straighten your arms and lean directly over them."
                )
            }
        } else {
            handsCorrectSince = 0L
        }

        val voice = when (action) {
            SpatialAction.MOVE_LEFT -> "Move your hands slightly left."
            SpatialAction.MOVE_RIGHT -> "Move your hands slightly right."
            SpatialAction.MOVE_UP -> "Move your hands slightly upward."
            SpatialAction.MOVE_DOWN -> "Move your hands slightly downward."
            SpatialAction.CORRECT -> "Good. Keep your hands here."
            SpatialAction.TRACKING_LOST -> "Hold the phone steady — I'm tracking your hands."
            else -> null
        }
        return FSMTransitionResult(FSMState.HAND_POSITIONING, voice)
    }

    private fun processPostureCheck(guidance: GuidanceState, nowMs: Long): FSMTransitionResult {
        // Transition to compression after 2s of being in this state
        if (nowMs - stateEnteredAt > 2000L) {
            transition(FSMState.COMPRESSION_ACTIVE, nowMs)
            return FSMTransitionResult(
                FSMState.COMPRESSION_ACTIVE,
                "Begin compressions. Push hard and fast. Aim for 100 per minute."
            )
        }
        return FSMTransitionResult(FSMState.POSTURE_CHECK, null)
    }

    private fun processCompressionActive(guidance: GuidanceState, nowMs: Long): FSMTransitionResult {
        val voice = when (guidance.temporal.rateStatus) {
            RateStatus.TOO_SLOW -> "Push faster. Aim for 100 compressions per minute."
            RateStatus.TOO_FAST -> "Slow down slightly. Aim for 100 to 120 per minute."
            RateStatus.GOOD -> null   // no need to speak when good
            RateStatus.INSUFFICIENT_DATA -> null
        }
        // Check if hands drifted
        val driftVoice = if (guidance.spatial.correctiveAction != SpatialAction.CORRECT &&
            guidance.spatial.correctiveAction != SpatialAction.TRACKING_LOST &&
            guidance.spatial.correctiveAction != SpatialAction.UNSURE) {
            "Reposition — bring hands back to center."
        } else null

        return FSMTransitionResult(FSMState.COMPRESSION_ACTIVE, voice ?: driftVoice)
    }

    // Called when user verbally confirms no response
    fun confirmNoResponse(nowMs: Long) {
        if (currentState == FSMState.RESPONSIVENESS_CHECK) {
            transition(FSMState.EMERGENCY_ESCALATION, nowMs)
        }
    }

    // Called when user selects emergency type from the selection screen (works from any state)
    fun setUserSelectedEmergency(type: EmergencyType, nowMs: Long) {
        confirmedEmergencyType = type
        val nextState = when (type) {
            EmergencyType.CPR              -> FSMState.RESPONSIVENESS_CHECK
            EmergencyType.FAST_STROKE      -> FSMState.STROKE_FAST_TEST
            EmergencyType.HEART_ATTACK     -> FSMState.HEART_ATTACK_CONSCIOUS
            EmergencyType.ALLERGIC_REACTION -> FSMState.ALLERGIC_PROTOCOL
            EmergencyType.UNKNOWN          -> FSMState.TRIAGE_DETECTION
        }
        transition(nextState, nowMs)
    }

    private fun transition(newState: FSMState, nowMs: Long) {
        currentState = newState
        stateEnteredAt = nowMs
    }
}
