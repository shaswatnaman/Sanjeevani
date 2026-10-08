package com.sanjeevani.engine

import com.sanjeevani.model.NormalizedLandmark
import kotlin.math.abs

private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24

class PatientDetector {

    fun isPatientLying(pose: List<NormalizedLandmark>?): Pair<Boolean, Float> {
        pose ?: return Pair(false, 0f)
        if (pose.size <= RIGHT_HIP) return Pair(false, 0f)

        val ls = pose[LEFT_SHOULDER]
        val rs = pose[RIGHT_SHOULDER]
        val lh = pose[LEFT_HIP]
        val rh = pose[RIGHT_HIP]

        val avgVis = (ls.visibility + rs.visibility + lh.visibility + rh.visibility) / 4f
        if (avgVis < 0.4f) return Pair(false, 0f)

        // Horizontal span of shoulders
        val shoulderSpanX = abs(ls.x - rs.x)
        // Vertical span of shoulders
        val shoulderSpanY = abs(ls.y - rs.y)
        // Hip span X
        val hipSpanX = abs(lh.x - rh.x)

        // A lying person has large X shoulder span and small Y shoulder span
        val isHorizontal = shoulderSpanX > 0.12f && shoulderSpanY < shoulderSpanX * 0.6f

        // Original iOS algorithm: horizontalComponent > verticalComponent * 0.7
        val horizontalComponent = shoulderSpanX
        val verticalComponent = shoulderSpanY + 0.001f  // avoid div zero
        val isHorizontalIOS = horizontalComponent > (verticalComponent * 0.7f)

        val confidence = if (isHorizontal && isHorizontalIOS) avgVis else 0f
        return Pair(confidence > 0.4f, confidence)
    }
}
