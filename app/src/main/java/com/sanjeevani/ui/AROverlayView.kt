package com.sanjeevani.ui

import android.content.Context
import android.graphics.*
import android.view.View
import com.sanjeevani.model.AROverlaySpec
import com.sanjeevani.model.EmergencyType

class AROverlayView(context: Context) : View(context) {

    var overlaySpec: AROverlaySpec? = null
        set(value) {
            field = value
            invalidate()
        }

    // ── Paints ────────────────────────────────────────────────────────────────

    private val targetPaintGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val targetPaintRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val targetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 255, 0); style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 80, 0); style = Paint.Style.STROKE
        strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
    }
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 200, 255); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val handDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.FILL
    }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 48f; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val guidancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 200, 0); textSize = 44f; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val ratePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 56f; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val rateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0); style = Paint.Style.FILL
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD
    }
    private val progressBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255); style = Paint.Style.FILL
    }
    private val epiPenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 140, 0); style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val epiPenFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 140, 0); style = Paint.Style.FILL
    }
    private val shoulderBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }

    private var epiPenPulseRadius = 40f
    private var epiPenPulseGrowing = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spec = overlaySpec ?: return

        // ── Multi-emergency overlays (drawn first, behind CPR elements) ────────
        drawEmergencyBadge(canvas, spec)
        drawEpiPenMarker(canvas, spec)
        drawPhaseProgressBar(canvas, spec)
        drawStrokeAsymmetryBars(canvas, spec)

        // ── Skeleton ─────────────────────────────────────────────────────────
        spec.skeletonLines.forEach { (from, to) ->
            canvas.drawLine(from.x * width, from.y * height, to.x * width, to.y * height, skeletonPaint)
        }

        // ── Sternum target circle ─────────────────────────────────────────────
        spec.sternumTarget?.let { t ->
            val px = t.x * width
            val py = t.y * height
            val isCorrect = spec.statusColorGreen
            val targetPaint = if (isCorrect) targetPaintGreen else targetPaintRed
            canvas.drawCircle(px, py, 50f, targetFillPaint)
            canvas.drawCircle(px, py, 50f, targetPaint)
            canvas.drawCircle(px, py, 8f, if (isCorrect) targetPaintGreen else targetPaintRed)
        }

        // ── Hand marker ───────────────────────────────────────────────────────
        spec.leftHandCenter?.let { h ->
            canvas.drawCircle(h.x * width, h.y * height, 20f, handDotPaint)
        }

        // ── Correction arrow ──────────────────────────────────────────────────
        spec.arrowFrom?.let { from ->
            spec.arrowTo?.let { to ->
                val fx = from.x * width; val fy = from.y * height
                val tx = to.x * width; val ty = to.y * height
                canvas.drawLine(fx, fy, tx, ty, arrowPaint)
                drawArrowHead(canvas, fx, fy, tx, ty)
            }
        }

        // ── Status badge (top) ─────────────────────────────────────────────────
        if (spec.statusText.isNotEmpty()) {
            val textWidth = statusTextPaint.measureText(spec.statusText)
            val bx = (width - textWidth) / 2f - 16f
            statusTextPaint.color = if (spec.statusColorGreen) Color.GREEN else Color.rgb(255, 200, 0)
            canvas.drawText(spec.statusText, bx + 16f, 80f, statusTextPaint)
        }

        // ── Guidance direction text (center) ──────────────────────────────────
        if (spec.guidanceText.isNotEmpty()) {
            val tw = guidancePaint.measureText(spec.guidanceText)
            canvas.drawText(spec.guidanceText, (width - tw) / 2f, height * 0.75f, guidancePaint)
        }

        // ── Compression rate badge (bottom) ───────────────────────────────────
        spec.compressionRate?.let { bpm ->
            val bpmText = if (bpm > 0f) "CPR: ${bpm.toInt()} BPM" else "Begin compressions"
            val tw = ratePaint.measureText(bpmText)
            val bx = (width - tw) / 2f - 20f
            val by = height - 60f
            canvas.drawRoundRect(bx, by - 60f, bx + tw + 40f, by + 10f, 16f, 16f, rateBgPaint)
            ratePaint.color = when {
                bpm in 100f..120f -> Color.GREEN
                bpm > 0f -> Color.rgb(255, 180, 0)
                else -> Color.WHITE
            }
            canvas.drawText(bpmText, bx + 20f, by, ratePaint)
        }
    }

    private fun emergencyColor(type: EmergencyType): Int = when (type) {
        EmergencyType.FAST_STROKE       -> Color.rgb(167, 139, 250)
        EmergencyType.HEART_ATTACK      -> Color.rgb(249, 115, 22)
        EmergencyType.ALLERGIC_REACTION -> Color.rgb(78, 204, 168)
        else -> Color.TRANSPARENT
    }

    private fun drawEmergencyBadge(canvas: Canvas, spec: AROverlaySpec) {
        val type = spec.emergencyType
        if (type == EmergencyType.UNKNOWN || type == EmergencyType.CPR) return
        val color = emergencyColor(type)
        val label = when (type) {
            EmergencyType.FAST_STROKE       -> "STROKE"
            EmergencyType.HEART_ATTACK      -> "HEART ATTACK"
            EmergencyType.ALLERGIC_REACTION -> "ALLERGIC"
            else -> return
        }
        val tw = badgeTextPaint.measureText(label)
        val padding = 20f; val pillH = 52f
        val left = 16f; val top = 110f
        val right = left + tw + padding * 2f
        val bottom = top + pillH
        badgePaint.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawRoundRect(left, top, right, bottom, pillH / 2f, pillH / 2f, badgePaint)
        canvas.drawText(label, left + padding, top + pillH * 0.68f, badgeTextPaint)
    }

    private fun drawEpiPenMarker(canvas: Canvas, spec: AROverlaySpec) {
        if (!spec.showEpiPenMarker) return
        val target = spec.sternumTarget ?: return
        val px = target.x * width
        val py = target.y * height

        // Pulsing animation
        if (epiPenPulseGrowing) {
            epiPenPulseRadius += 2f
            if (epiPenPulseRadius > 70f) epiPenPulseGrowing = false
        } else {
            epiPenPulseRadius -= 2f
            if (epiPenPulseRadius < 35f) epiPenPulseGrowing = true
        }

        canvas.drawCircle(px, py, epiPenPulseRadius, epiPenFillPaint)
        canvas.drawCircle(px, py, epiPenPulseRadius, epiPenPaint)
        postInvalidateOnAnimation()
    }

    private fun drawPhaseProgressBar(canvas: Canvas, spec: AROverlaySpec) {
        val progress = spec.phaseProgress
        if (progress <= 0f) return
        val barY = height * 0.85f
        val barH = 10f
        val color = emergencyColor(spec.emergencyType)
        if (color == Color.TRANSPARENT) return

        canvas.drawRoundRect(0f, barY, width.toFloat(), barY + barH, barH / 2f, barH / 2f, progressTrackPaint)
        progressBarPaint.color = color
        val fillWidth = (width * progress).coerceAtLeast(0f)
        if (fillWidth > 0f) {
            canvas.drawRoundRect(0f, barY, fillWidth, barY + barH, barH / 2f, barH / 2f, progressBarPaint)
        }
    }

    private fun drawStrokeAsymmetryBars(canvas: Canvas, spec: AROverlaySpec) {
        if (spec.emergencyType != EmergencyType.FAST_STROKE) return
        val lsy = spec.leftShoulderY; val rsy = spec.rightShoulderY
        if (lsy == 0f && rsy == 0f) return

        val color = emergencyColor(EmergencyType.FAST_STROKE)
        shoulderBarPaint.color = Color.argb(180, Color.red(color), Color.green(color), Color.blue(color))

        val barTop = height * 0.3f; val barBottom = height * 0.7f
        // Left shoulder column
        val lx = width * 0.25f
        canvas.drawLine(lx, barTop, lx, barBottom, shoulderBarPaint)
        // Marker at shoulder Y
        val lyPx = lsy * height
        canvas.drawCircle(lx, lyPx, 12f, shoulderBarPaint)

        // Right shoulder column
        val rx = width * 0.75f
        canvas.drawLine(rx, barTop, rx, barBottom, shoulderBarPaint)
        val ryPx = rsy * height
        canvas.drawCircle(rx, ryPx, 12f, shoulderBarPaint)
    }

    private fun drawArrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1f) return
        val ux = dx / len; val uy = dy / len
        val size = 30f
        val ax = x2 - size * ux + size * 0.5f * (-uy)
        val ay = y2 - size * uy + size * 0.5f * ux
        val bx = x2 - size * ux - size * 0.5f * (-uy)
        val by = y2 - size * uy - size * 0.5f * ux
        canvas.drawLine(x2, y2, ax, ay, arrowPaint)
        canvas.drawLine(x2, y2, bx, by, arrowPaint)
    }
}
