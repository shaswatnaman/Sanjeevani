package com.sanjeevani.engine

enum class CPRStep {
    POSITION_CHECK,  // 1. Position Check  — red
    KNEEL_BESIDE,    // 2. Kneel Beside Person — orange
    HAND_PLACEMENT,  // 3. Hand Placement — yellow (blue sphere appears)
    COMPRESSIONS     // 5. Chest Compressions — blue (auto counter)
}

// Mirrors iOS CardiacArrestView step progression exactly:
//   Step 1: show until patient lying detected OR 15 s, then wait 3 s
//   Step 2: show for 5 s
//   Step 3: show for 15 s (blue sphere visible)
//   Step 5: count compressions at 120/min = 1 every 0.5 s
class CPRWorkflow {

    var currentStep: CPRStep = CPRStep.POSITION_CHECK
        private set

    private var stepStartMs = 0L
    private var pendingAdvanceMs = -1L   // when to fire delayed advance
    private var pendingTarget: CPRStep? = null

    val compressionCount: Int
        get() = if (currentStep == CPRStep.COMPRESSIONS && stepStartMs > 0L)
            ((System.currentTimeMillis() - stepStartMs) / 500L).toInt().coerceAtLeast(0)
        else 0

    val elapsedSecs: Int
        get() = if (currentStep == CPRStep.COMPRESSIONS && stepStartMs > 0L)
            ((System.currentTimeMillis() - stepStartMs) / 1000L).toInt()
        else 0

    // Call every frame.  patientLying = result of PatientDetector.
    fun update(nowMs: Long, patientLying: Boolean) {
        if (stepStartMs == 0L) stepStartMs = nowMs

        // Fire delayed advance
        if (pendingTarget != null && pendingAdvanceMs > 0L && nowMs >= pendingAdvanceMs) {
            advanceTo(pendingTarget!!, nowMs)
            pendingTarget = null
            pendingAdvanceMs = -1L
            return
        }

        val elapsed = (nowMs - stepStartMs) / 1000L

        when (currentStep) {
            CPRStep.POSITION_CHECK -> {
                // Advance when patient lying detected OR after 15 s (matching iOS timeout)
                if ((patientLying || elapsed >= 15) && pendingTarget == null) {
                    // iOS: wait 3 s after safe position achieved before advancing
                    scheduledAdvance(CPRStep.KNEEL_BESIDE, nowMs + 3_000L)
                }
            }
            CPRStep.KNEEL_BESIDE -> {
                // iOS: auto-progress to Step 3 after 5 s
                if (elapsed >= 5 && pendingTarget == null) {
                    advanceTo(CPRStep.HAND_PLACEMENT, nowMs)
                }
            }
            CPRStep.HAND_PLACEMENT -> {
                // iOS: auto-progress to Step 5 after 15 s
                if (elapsed >= 15 && pendingTarget == null) {
                    advanceTo(CPRStep.COMPRESSIONS, nowMs)
                }
            }
            CPRStep.COMPRESSIONS -> { /* timer drives compressionCount via getter */ }
        }
    }

    fun reset() {
        currentStep = CPRStep.POSITION_CHECK
        stepStartMs = 0L
        pendingAdvanceMs = -1L
        pendingTarget = null
    }

    private fun scheduledAdvance(step: CPRStep, fireAtMs: Long) {
        pendingTarget = step
        pendingAdvanceMs = fireAtMs
    }

    private fun advanceTo(step: CPRStep, nowMs: Long) {
        currentStep = step
        stepStartMs = nowMs
    }
}
