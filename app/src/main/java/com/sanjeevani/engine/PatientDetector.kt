package com.sanjeevani.engine

import com.sanjeevani.model.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.sqrt

private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24

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

        // 2D screen-space detection (original iOS algorithm)
        val shoulderSpanX = abs(ls.x - rs.x)
        val shoulderSpanY = abs(ls.y - rs.y)
        val isHorizontal = shoulderSpanX > 0.12f && shoulderSpanY < shoulderSpanX * 0.6f
        val isHorizontalIOS = shoulderSpanX > (shoulderSpanY + 0.001f) * 0.7f

        // 3D world-landmark refinement: a lying person has near-zero Y-difference between
        // shoulders and hips (both at ground level in world space, Y=up in MediaPipe world coords).
        var worldConfirmsLying = false
        if (worldLandmarks != null && worldLandmarks.size > RIGHT_HIP) {
            val wls = worldLandmarks[LEFT_SHOULDER]
            val wrs = worldLandmarks[RIGHT_SHOULDER]
            val wlh = worldLandmarks[LEFT_HIP]
            val wrh = worldLandmarks[RIGHT_HIP]
            // World Y is up; standing: hip Y ≈ -1m, shoulder Y ≈ 0.5m; lying: both near same Y
            val shoulderWorldY = (wls.y + wrs.y) / 2f
            val hipWorldY = (wlh.y + wrh.y) / 2f
            val verticalDiff = abs(shoulderWorldY - hipWorldY)
            // Lying flat: torso is horizontal → vertical diff < 0.3m; standing: ~1.2m
            worldConfirmsLying = verticalDiff < 0.4f
        }

        val detected = (isHorizontal && isHorizontalIOS) || worldConfirmsLying
        val confidence = if (detected) avgVis else 0f
        return Pair(confidence > 0.4f, confidence)
    }
}
