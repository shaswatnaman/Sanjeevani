package com.sanjeevani.engine

enum class CPRStep {
    POSITION_CHECK,   // 1. Position Check — red
    KNEEL_BESIDE,     // 2. Kneel Beside Person — orange
    HAND_PLACEMENT,   // 3. Hand Placement — yellow (blue sphere appears)
    BODY_POSITION,    // 4. Body Position — rescuer locks arms straight
    COMPRESSIONS,     // 5. Chest Compressions — blue (auto counter)
    MAINTAIN_RHYTHM   // 6. Maintain Rhythm — 30:2 reminder, auto-returns to COMPRESSIONS
}

// CPR step progression:
//   Step 1: require patient flat for 3 s → Step 2
//   Step 2: show for 5 s                 → Step 3
//   Step 3: show for 15 s                → Step 4
//   Step 4: show for 5 s (posture lock)  → Step 5
//   Step 5: active compressions          → Step 6 (triggered every 30 compressions by SanjeevaniEngine)
//   Step 6: 4 s reminder                 → back to Step 5
class CPRWorkflow {

    var currentStep: CPRStep = CPRStep.POSITION_CHECK
        private set

    private var stepStartMs = 0L
    private var lastUpdateMs = 0L
    private var pendingAdvanceMs = -1L   // when to fire delayed advance
    private var pendingTarget: CPRStep? = null

    var compressionCount: Int = 0
        private set

    var currentBpm: Float = 0f
        private set

    // Step 1 is monotonic. Once the detector accepts a correct flat posture,
    // transient landmark loss must never send the user back to that step.
    var isPositionLocked: Boolean = false
        private set

    val elapsedSecs: Int
        get() = if (currentStep == CPRStep.COMPRESSIONS && stepStartMs > 0L)
            ((lastUpdateMs - stepStartMs) / 1000L).toInt().coerceAtLeast(0)
        else 0

    // Call every frame.  patientLying = result of PatientDetector.
    fun update(nowMs: Long, patientLying: Boolean) {
        lastUpdateMs = nowMs
        if (stepStartMs == 0L) stepStartMs = nowMs

        if (currentStep == CPRStep.POSITION_CHECK && patientLying) {
            isPositionLocked = true
        }

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
                if (isPositionLocked && pendingTarget == null) {
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
                if (elapsed >= 15 && pendingTarget == null) {
                    advanceTo(CPRStep.BODY_POSITION, nowMs)
                }
            }
            CPRStep.BODY_POSITION -> {
                if (elapsed >= 5 && pendingTarget == null) {
                    advanceTo(CPRStep.COMPRESSIONS, nowMs)
                }
            }
            CPRStep.COMPRESSIONS -> { /* CompressionDetector supplies count and BPM. */ }
            CPRStep.MAINTAIN_RHYTHM -> {
                if (elapsed >= 4 && pendingTarget == null) {
                    advanceTo(CPRStep.COMPRESSIONS, nowMs)
                }
            }
        }
    }

    fun updateCompressionMetrics(count: Int, bpm: Float) {
        if (currentStep == CPRStep.COMPRESSIONS || currentStep == CPRStep.MAINTAIN_RHYTHM) {
            compressionCount = count.coerceAtLeast(0)
            currentBpm = bpm.coerceAtLeast(0f)
        } else {
            compressionCount = 0
            currentBpm = 0f
        }
    }

    fun reset() {
        currentStep = CPRStep.POSITION_CHECK
        stepStartMs = 0L
        lastUpdateMs = 0L
        pendingAdvanceMs = -1L
        pendingTarget = null
        compressionCount = 0
        currentBpm = 0f
        isPositionLocked = false
    }

    private fun scheduledAdvance(step: CPRStep, fireAtMs: Long) {
        pendingTarget = step
        pendingAdvanceMs = fireAtMs
    }

    fun triggerMaintainRhythm(nowMs: Long) {
        if (currentStep == CPRStep.COMPRESSIONS) advanceTo(CPRStep.MAINTAIN_RHYTHM, nowMs)
    }

    private fun advanceTo(step: CPRStep, nowMs: Long) {
        val prev = currentStep
        currentStep = step
        stepStartMs = nowMs
        // Reset compression metrics only when entering compressions fresh (not from a 30-count reminder).
        if (step == CPRStep.COMPRESSIONS && prev != CPRStep.MAINTAIN_RHYTHM) {
            compressionCount = 0
            currentBpm = 0f
        }
    }
}
