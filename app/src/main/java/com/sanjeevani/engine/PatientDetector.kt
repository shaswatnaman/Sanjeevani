package com.sanjeevani.engine

import com.sanjeevani.model.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.sqrt

private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24

// If one shoulder is this much more visible than the other, person is likely on their side.
// Side-lying: the bottom shoulder is partially occluded → lower visibility score.
private const val SIDE_LYING_VIS_ASYMMETRY = 0.25f
// Max allowed shoulder-to-shoulder world Y difference for lying FLAT (vs on side).
// On back: both shoulders press equally into ground → near-zero. On side: one is ~0.2m higher.
private const val MAX_SHOULDER_WORLD_Y_DIFF = 0.18f

class PatientDetector {

    fun isPatientLying(
        pose: List<NormalizedLandmark>?,
        worldLandmarks: List<NormalizedLandmark>? = null
    ): Pair<Boolean, Float> {
        pose ?: return Pair(false, 0f)
        if (pose.size <= RIGHT_HIP) return Pair(false, 0f)

        val ls = pose[LEFT_SHOULDER]
        val rs = pose[RIGHT_SHOULDER]
        val lh = pose[LEFT_HIP]
        val rh = pose[RIGHT_HIP]

        val avgVis = (ls.visibility + rs.visibility + lh.visibility + rh.visibility) / 4f
        if (avgVis < 0.4f) return Pair(false, 0f)

        // If one shoulder is significantly less visible than the other, person is on their side.
        // This is a negative gate — if triggered, we know they are NOT lying flat.
        val visAsymmetry = abs(ls.visibility - rs.visibility)
        if (visAsymmetry > SIDE_LYING_VIS_ASYMMETRY) return Pair(false, 0f)

        // 2D screen-space detection (original iOS algorithm)
        val shoulderSpanX = abs(ls.x - rs.x)
        val shoulderSpanY = abs(ls.y - rs.y)
        val isHorizontal = shoulderSpanX > 0.12f && shoulderSpanY < shoulderSpanX * 0.6f
        val isHorizontalIOS = shoulderSpanX > (shoulderSpanY + 0.001f) * 0.7f

        // 3D world-landmark refinement
        var worldConfirmsLying = false
        if (worldLandmarks != null && worldLandmarks.size > RIGHT_HIP) {
            val wls = worldLandmarks[LEFT_SHOULDER]
            val wrs = worldLandmarks[RIGHT_SHOULDER]
            val wlh = worldLandmarks[LEFT_HIP]
            val wrh = worldLandmarks[RIGHT_HIP]

            // Torso must be horizontal (shoulder mid-Y ≈ hip mid-Y in world space)
            val shoulderWorldY = (wls.y + wrs.y) / 2f
            val hipWorldY = (wlh.y + wrh.y) / 2f
            val torsoVerticalDiff = abs(shoulderWorldY - hipWorldY)

            // Both shoulders must be at the same world height — rules out side-lying.
            // On back: both at ground level (≈same Y). On side: top shoulder is higher.
            val shoulderTilt = abs(wls.y - wrs.y)

            worldConfirmsLying = torsoVerticalDiff < 0.4f && shoulderTilt < MAX_SHOULDER_WORLD_Y_DIFF
        }

        val detected = (isHorizontal && isHorizontalIOS) || worldConfirmsLying
        val confidence = if (detected) avgVis else 0f
        return Pair(confidence > 0.4f, confidence)
    }
}
