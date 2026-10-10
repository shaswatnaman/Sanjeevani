package com.sanjeevani

import com.sanjeevani.engine.*
import com.sanjeevani.model.*
import com.sanjeevani.voice.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowTest {
    private fun assessment() = EmergencyFSM().also { it.setUserSelectedEmergency(EmergencyType.HEART_ATTACK, 1000) }
    private fun position() = assessment().also { it.answer(Answer.NO, 1100); it.answer(Answer.NO, 1200) }
    @Test fun concernIsNotCardiacArrest() {
        val f = assessment()
        assertEquals(FSMState.RESPONSIVENESS_CHECK, f.getState())
        repeat(100) { f.process(GuidanceState(isPatientDetected = true), 2000L+it*1000) }
        assertEquals(FSMState.RESPONSIVENESS_CHECK, f.getState())
    }
    @Test fun responsivenessRequiresAnswerAndNormalBreathingIsNotCPR() {
        val f = assessment()
        f.answer(Answer.NO, 1100)
        assertEquals(FSMState.BREATHING_ASSESSMENT, f.getState())
        f.answer(Answer.YES, 1200)
        assertEquals(FSMState.HEART_ATTACK_CONSCIOUS, f.getState())
        assertTrue(f.breathingNormally == true)
    }
    @Test fun responsivePatientDoesNotStartCPR() {
        val f = assessment(); f.answer(Answer.YES, 1100)
        assertEquals(FSMState.HEART_ATTACK_CONSCIOUS, f.getState())
        assertFalse(f.answer(Answer.READY, 1200))
    }
    @Test fun staleAndDuplicateAnswersAreRejected() {
        val f = assessment(); val v = f.version
        assertTrue(f.answer(Answer.NO, 1100, v))
        assertFalse(f.answer(Answer.NO, 1101, v))
        assertEquals(FSMState.BREATHING_ASSESSMENT, f.getState())
    }
    @Test fun timersCannotAdvanceManualSteps() {
        val f = position(); f.answer(Answer.READY, 1300)
        repeat(50) { f.process(GuidanceState(), 2000L+it*1000) }
        assertEquals(FSMState.HAND_POSITIONING, f.getState())
        f.answer(Answer.READY, 60000)
        f.process(GuidanceState(), 90000)
        assertEquals(FSMState.POSTURE_CHECK, f.getState())
    }
    @Test fun stablePositionRequiresConfidenceAndFreshContinuousEvidence() {
        val f = position()
        val good = GuidanceState(isPatientDetected = true, confidence = ConfidenceState(patientConfidence = .9f))
        f.process(good, 2000)
        f.process(good, 4000) // Gap resets the hold.
        assertEquals(FSMState.CPR_POSITIONING, f.getState())
        repeat(10) { f.process(good, 4100L+it*100) }
        assertEquals(FSMState.POSITION_CONFIRMED, f.getState())
        val version = f.version
        repeat(20) { f.process(GuidanceState(), 6000L+it*100) }
        assertEquals(version, f.version)
        assertEquals(1, f.history.count { it.to == FSMState.POSITION_CONFIRMED })
    }
    @Test fun missingOrLowConfidenceNeverConfirms() {
        val f = position()
        repeat(50) { f.process(GuidanceState(isPatientDetected = true,
            confidence = ConfidenceState(patientConfidence = .4f)), 2000L+it*100) }
        assertEquals(FSMState.CPR_POSITIONING, f.getState())
    }
    @Test fun duplicateFramesCannotAccumulateHold() {
        val f = position()
        val g = GuidanceState(isPatientDetected = true, confidence = ConfidenceState(patientConfidence = 1f))
        repeat(100) { f.process(g, 2000) }
        assertEquals(FSMState.CPR_POSITIONING, f.getState())
    }
    @Test fun completedStepsStayCompletedUntilExplicitSafetyReassessment() {
        val f = position()
        repeat(3) { f.answer(Answer.READY, 1400L+it*100) }
        assertEquals(FSMState.COMPRESSION_ACTIVE, f.getState())
        repeat(50) { f.process(GuidanceState(), 2000L+it*100) }
        assertEquals(FSMState.COMPRESSION_ACTIVE, f.getState())
        f.answer(Answer.CHANGED, 8000)
        assertEquals(FSMState.RESPONSIVENESS_CHECK, f.getState())
        assertNull(f.responding)
        assertTrue(f.history.last().reason.contains("reassess"))
    }
    @Test fun uncertaintyAndRepeatDoNotMutateWorkflow() {
        val f = assessment(); val v = f.version
        listOf(Answer.REPEAT, Answer.UNKNOWN, Answer.UNCERTAIN).forEach { assertFalse(f.answer(it, 1100)) }
        assertEquals(v, f.version)
    }
    @Test fun everyTransitionHasReasonAndMonotonicVersion() {
        val f = position(); repeat(3) { f.answer(Answer.READY, 1400L+it*100) }
        f.history.forEachIndexed { i, r -> assertEquals(i+1L, r.version); assertTrue(r.reason.isNotBlank()) }
    }
    @Test fun handHoldIsSeparateFromPositionHistory() {
        val f = position(); f.answer(Answer.READY, 1300)
        val g = GuidanceState(spatial = SpatialState(correctiveAction = SpatialAction.CORRECT),
            confidence = ConfidenceState(poseConfidence = .9f, handsConfidence = .9f))
        repeat(11) { f.process(g, 2000L+it*100) }
        assertEquals(FSMState.POSTURE_CHECK, f.getState())
    }
    @Test fun turnGateInvalidatesOldAudioRecognitionAndAIResults() {
        val gate = TurnGate(); val token = gate.invalidate()
        assertTrue(gate.accepts(token, 1, 1))
        assertFalse(gate.accepts(token, 1, 2))
        gate.invalidate()
        assertFalse(gate.accepts(token, 1, 1))
    }
    @Test fun contextAwareTranscriptMatrix() {
        val r = FSMState.RESPONSIVENESS_CHECK; val b = FSMState.BREATHING_ASSESSMENT
        val cases = listOf(
            Triple("yes", r, Answer.YES), Triple("no", r, Answer.NO),
            Triple("No, she isn't responding", r, Answer.NO),
            Triple("she is not responsive", r, Answer.NO),
            Triple("yes she is responding", r, Answer.YES),
            Triple("she's breathing", r, Answer.UNKNOWN),
            Triple("no response", b, Answer.UNKNOWN),
            Triple("not breathing normally", b, Answer.NO),
            Triple("gasping", b, Answer.NO),
            Triple("she is breathing normally", b, Answer.YES),
            Triple("I don't know", b, Answer.UNCERTAIN),
            Triple("can you repeat", r, Answer.REPEAT),
            Triple("yesterday", r, Answer.UNKNOWN),
            Triple("yes no response", r, Answer.UNCERTAIN)
        )
        cases.forEach { (text, state, answer) -> assertEquals(text, answer, SessionDialogue.interpret(text, state).answer) }
    }
    @Test fun lowConfidenceClarifiesAndOpeningConcernIsNotAnswer() {
        assertEquals(Answer.UNCERTAIN, SessionDialogue.interpret("yes", FSMState.RESPONSIVENESS_CHECK, .2f).answer)
        assertEquals(EmergencyType.HEART_ATTACK,
            SessionDialogue.interpret("I think she is having a heart attack", FSMState.IDLE).concern)
        assertEquals(Answer.UNKNOWN, SessionDialogue.interpret("heart attack", FSMState.BREATHING_ASSESSMENT).answer)
    }
    @Test fun questionsDoNotAuthorizeAClinicalTransition() {
        val f=assessment()
        val result=SessionDialogue.interpret("how fast",f.getState())
        assertEquals(Answer.QUESTION,result.answer)
        assertFalse(f.answer(result.answer,1100))
        assertTrue(SessionDialogue.help("how fast",f.getState(),f.instruction()).contains("responding"))
    }
    @Test fun compressionActiveDialogueInterpretsPlainYesNo() {
        val active = FSMState.COMPRESSION_ACTIVE
        assertEquals(Answer.YES, SessionDialogue.interpret("yes", active).answer)
        assertEquals(Answer.NO, SessionDialogue.interpret("no", active).answer)
        assertEquals(Answer.YES, SessionDialogue.interpret("showing signs", active).answer)
        assertEquals(Answer.NO, SessionDialogue.interpret("no signs", active).answer)
        // Recovery phrases bypass the response-check path and still return CHANGED.
        assertEquals(Answer.CHANGED, SessionDialogue.interpret("she woke up", active).answer)
    }
}
