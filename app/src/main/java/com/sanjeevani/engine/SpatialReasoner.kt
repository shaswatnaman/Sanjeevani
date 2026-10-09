package com.sanjeevani.engine

import android.graphics.PointF
import com.sanjeevani.model.NormalizedLandmark
import com.sanjeevani.model.SpatialAction
import com.sanjeevani.model.SpatialState
import kotlin.math.abs
import kotlin.math.sqrt

// MediaPipe Pose landmark indices
private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24

// MediaPipe Hand landmark indices (palm keypoints for center estimation)
private const val INDEX_FINGER_MCP = 5
private const val MIDDLE_FINGER_MCP = 9
private const val RING_FINGER_MCP = 13
private const val PINKY_MCP = 17

private const val STERNUM_TORSO_RATIO = 0.28f   // 28% from shoulder midpoint toward hips (CPR point)
private const val CORRECTION_THRESHOLD = 0.04f
private const val HYSTERESIS = 0.01f
private const val MIN_ACTION_CHANGE_MS = 500L
// If two hands are detected and their centers are more than this apart, they are NOT stacked.
// 0.08 in normalized coords ≈ 58px at 720px wide — more than one hand-width apart = wrong.
private const val HAND_STACK_THRESHOLD = 0.08f

class SpatialReasoner {

    private var lastAction = SpatialAction.UNSURE
    private var lastActionChangeTs = 0L
    private val smoothedErrorX = ExponentialMovingAverage(alpha = 0.3f)
    private val smoothedErrorY = ExponentialMovingAverage(alpha = 0.3f)
    // Heavy smoothing on the sphere position to eliminate jitter
    private val smoothedSternumX = ExponentialMovingAverage(alpha = 0.08f)
    private val smoothedSternumY = ExponentialMovingAverage(alpha = 0.08f)
    private var lastSternumX: Float? = null
    private var lastSternumY: Float? = null

    fun reset() {
        smoothedSternumX.reset()
        smoothedSternumY.reset()
        smoothedErrorX.reset()
        smoothedErrorY.reset()
        lastSternumX = null
        lastSternumY = null
        lastAction = SpatialAction.UNSURE
    }

    fun compute(
        poseLandmarks: List<NormalizedLandmark>?,
        leftHand: List<NormalizedLandmark>?,
        rightHand: List<NormalizedLandmark>?,
        nowMs: Long
    ): SpatialState {
        val target = estimateSternumTarget(poseLandmarks)
            ?: return SpatialState(correctiveAction = SpatialAction.TRACKING_LOST)

        val (handCenter, handSpread) = estimateBothHandsCenterAndSpread(leftHand, rightHand)
            ?: (estimateWristCenter(poseLandmarks) to 0f)
        if (handCenter == null) return SpatialState(
            sternumTarget = target,
            correctiveAction = SpatialAction.TRACKING_LOST
        )

        val rawDx = handCenter.x - target.x
        val rawDy = handCenter.y - target.y
        val smoothDx = smoothedErrorX.update(rawDx)
        val smoothDy = smoothedErrorY.update(rawDy)
        val mag = sqrt(smoothDx * smoothDx + smoothDy * smoothDy)

        val action = computeAction(smoothDx, smoothDy, mag, handSpread, nowMs)

        return SpatialState(
            sternumTarget = target,
            handMidpoint = handCenter,
            errorVector = PointF(smoothDx, smoothDy),
            errorMagnitude = mag,
            correctiveAction = action
        )
    }

    private fun estimateSternumTarget(pose: List<NormalizedLandmark>?): PointF? {
        pose ?: return lastKnown()
        if (pose.size <= RIGHT_HIP) return lastKnown()
        val ls = pose[LEFT_SHOULDER]
        val rs = pose[RIGHT_SHOULDER]
        val lh = pose[LEFT_HIP]
        val rh = pose[RIGHT_HIP]

        val shoulderVis = (ls.visibility + rs.visibility) / 2f
        if (shoulderVis < 0.25f) return lastKnown()

        val shoulderMidX = (ls.x + rs.x) / 2f
        val shoulderMidY = (ls.y + rs.y) / 2f
        val hipMidX = (lh.x + rh.x) / 2f
        val hipMidY = (lh.y + rh.y) / 2f

        val rawX = shoulderMidX + (hipMidX - shoulderMidX) * STERNUM_TORSO_RATIO
        val rawY = shoulderMidY + (hipMidY - shoulderMidY) * STERNUM_TORSO_RATIO

        val sx = smoothedSternumX.update(rawX)
        val sy = smoothedSternumY.update(rawY)
        lastSternumX = sx
        lastSternumY = sy
        return PointF(sx, sy)
    }

    private fun lastKnown(): PointF? {
        val x = lastSternumX ?: return null
        val y = lastSternumY ?: return null
        return PointF(x, y)
    }

    // Returns center + spread between the two palms (0f if only one hand detected).
    private fun estimateBothHandsCenterAndSpread(
        left: List<NormalizedLandmark>?,
        right: List<NormalizedLandmark>?
    ): Pair<PointF, Float>? {
        val lc = left?.let { palmCenter(it) }
        val rc = right?.let { palmCenter(it) }
        return when {
            lc != null && rc != null -> {
                val cx = (lc.x + rc.x) / 2f
                val cy = (lc.y + rc.y) / 2f
                val spread = sqrt((lc.x - rc.x).let { it * it } + (lc.y - rc.y).let { it * it })
                Pair(PointF(cx, cy), spread)
            }
            lc != null -> Pair(lc, 0f)
            rc != null -> Pair(rc, 0f)
            else -> null
        }
    }

    private fun palmCenter(hand: List<NormalizedLandmark>): PointF {
        if (hand.size <= PINKY_MCP) return PointF(hand[0].x, hand[0].y)
        val x = (hand[INDEX_FINGER_MCP].x + hand[MIDDLE_FINGER_MCP].x +
                 hand[RING_FINGER_MCP].x + hand[PINKY_MCP].x) / 4f
        val y = (hand[INDEX_FINGER_MCP].y + hand[MIDDLE_FINGER_MCP].y +
                 hand[RING_FINGER_MCP].y + hand[PINKY_MCP].y) / 4f
        return PointF(x, y)
    }

    private fun estimateWristCenter(pose: List<NormalizedLandmark>?): PointF? {
        pose ?: return null
        if (pose.size <= 16) return null
        val lw = pose[15]  // LEFT_WRIST
        val rw = pose[16]  // RIGHT_WRIST
        if (lw.visibility < 0.4f && rw.visibility < 0.4f) return null
        return PointF((lw.x + rw.x) / 2f, (lw.y + rw.y) / 2f)
    }

    private fun computeAction(dx: Float, dy: Float, mag: Float, spread: Float, nowMs: Long): SpatialAction {
        val newAction = when {
            // Hands are detected but not stacked — regardless of center position
            spread > HAND_STACK_THRESHOLD -> SpatialAction.STACK_HANDS
            mag < CORRECTION_THRESHOLD - HYSTERESIS -> SpatialAction.CORRECT
            abs(dx) > abs(dy) -> if (dx > 0) SpatialAction.MOVE_LEFT else SpatialAction.MOVE_RIGHT
            else -> if (dy > 0) SpatialAction.MOVE_UP else SpatialAction.MOVE_DOWN
        }
        if (newAction == lastAction) return lastAction
        if (nowMs - lastActionChangeTs < MIN_ACTION_CHANGE_MS) return lastAction
        lastAction = newAction
        lastActionChangeTs = nowMs
        return newAction
    }
}

class ExponentialMovingAverage(private val alpha: Float) {
    private var value: Float? = null
    fun update(newValue: Float): Float {
        value = value?.let { alpha * newValue + (1 - alpha) * it } ?: newValue
        return value!!
    }
    fun reset() { value = null }
}
