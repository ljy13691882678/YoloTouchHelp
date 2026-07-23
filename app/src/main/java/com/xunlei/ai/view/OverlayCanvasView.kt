package com.xunlei.ai.view

import android.content.Context
import android.graphics.*
import android.view.View
import com.xunlei.ai.model.DetectionInfo

/**
 * Full-screen transparent overlay
 * 1. Capture range corners
 * 2. Detection boxes with class labels
 */
class OverlayCanvasView(context: Context) : View(context) {

    var rangeRadius: Int = 300
    var detections: List<DetectionInfo> = emptyList()
    var aimbotEnabled: Boolean = false
    var showCaptureRange: Boolean = false
    var showDetectionBox: Boolean = false
    var showCenterDot: Boolean = false
    var showLockRay: Boolean = false
    var enabledClassIds: Set<Int>? = null
    private var lockRayX = Float.NaN
    private var lockRayY = Float.NaN

    // FPS & temperature stats
    var fps: String = ""
    var temperature: String = ""
    var inferRunning: Boolean = false
    private val paintStats = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        textSize = 28f
        typeface = Typeface.MONOSPACE
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#AA000000"))
    }
    private val paintStatsLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FFFFFF")
        textSize = 22f
        setShadowLayer(3f, 0f, 1f, Color.parseColor("#88000000"))
    }

    // Use the same soft blue-violet palette as the HTML UI.
    private val classColors = intArrayOf(
        Color.parseColor("#6366F1"),
        Color.parseColor("#5B67F0"),
        Color.parseColor("#818CF8"),
        Color.parseColor("#38BDF8"),
        Color.parseColor("#A78BFA"),
        Color.parseColor("#2DD4BF"),
        Color.parseColor("#F472B6"),
        Color.parseColor("#60A5FA"),
    )

    private fun colorForClass(classId: Int): Int =
        classColors[classId % classColors.size]

    private val paintCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAB7C6FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }

    private val paintBoxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintBoxDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeJoin = Paint.Join.ROUND
    }

    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C46366F1")
        style = Paint.Style.FILL
    }

    private val paintLockRay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFD54F")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintLockRayOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66000000")
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        val dets = detections

        // Draw FPS and temperature in top-center
        if (fps.isNotEmpty() || temperature.isNotEmpty()) {
            val statText = buildString {
                append(if (inferRunning) "运行中" else "已停止")
                append("  ")
                if (fps.isNotEmpty()) append("FPS:$fps")
                if (temperature.isNotEmpty()) {
                    if (fps.isNotEmpty()) append("  ")
                    append("$temperature")
                }
            }
            val statW = paintStats.measureText(statText)
            val fm = paintStats.fontMetrics
            val statH = fm.descent - fm.ascent
            val padX = 16f
            val padY = 8f
            val bgLeft = (width - statW) / 2f - padX
            val bgTop = 24f
            val bgRight = (width + statW) / 2f + padX
            val bgBottom = bgTop + statH + padY * 2

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#66000000")
            }
            canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, 14f, 14f, bgPaint)
            canvas.drawText(statText, (width - statW) / 2f, bgTop + padY - fm.ascent, paintStats)
        }

        if (showCaptureRange) {
            val cx = width / 2f
            val cy = height / 2f
            val half = rangeRadius.toFloat()
            drawCornerBox(canvas, cx - half, cy - half, cx + half, cy + half)
            if (showCenterDot) canvas.drawCircle(cx, cy, 4f, paintCenter)
        }

        if (showDetectionBox && dets.isNotEmpty()) {
            val cx = width / 2f
            val cy = height / 2f
            val rangeSq = (rangeRadius * rangeRadius).toFloat()

            for (det in dets) {
                if (enabledClassIds != null && det.classId !in enabledClassIds!!) continue
                val rect = det.rect
                val boxCx = (rect.left + rect.right) * 0.5f
                val boxCy = (rect.top + rect.bottom) * 0.5f
                val dx = boxCx - cx
                val dy = boxCy - cy
                val inRange = dx * dx + dy * dy <= rangeSq
                val color = colorForClass(det.classId)
                val round = 14f

                // Draw a subtle fill plus a rounded outline so the overlay matches the new HTML cards.
                val p = if (inRange) paintBox else paintBoxDim
                p.color = if (inRange) color else Color.argb(120, Color.red(color), Color.green(color), Color.blue(color))
                paintBoxFill.color = if (inRange) {
                    Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))
                } else {
                    Color.argb(14, Color.red(color), Color.green(color), Color.blue(color))
                }
                canvas.drawRoundRect(rect, round, round, paintBoxFill)
                canvas.drawRoundRect(rect, round, round, p)

                // Use a compact pill label to reduce clutter.
                val label = det.className
                val textW = paintLabel.measureText(label)
                val fm = paintLabel.fontMetrics
                val textH = fm.descent - fm.ascent
                val padX = 10f
                val padY = 6f
                val bgLeft = rect.left
                val desiredTop = rect.top - textH - padY * 2 - 8f
                val bgTop = desiredTop.coerceAtLeast(8f)
                val bgRight = bgLeft + textW + padX * 2
                val bgBottom = bgTop + textH + padY * 2
                paintLabelBg.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, 12f, 12f, paintLabelBg)
                val textBaseY = bgTop + padY - fm.ascent
                canvas.drawText(label, bgLeft + padX, textBaseY, paintLabel)
            }
        }

        if (showLockRay && !lockRayX.isNaN() && !lockRayY.isNaN()) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawLine(cx, cy, lockRayX, lockRayY, paintLockRayOutline)
            canvas.drawLine(cx, cy, lockRayX, lockRayY, paintLockRay)
        }
    }

    private fun drawCornerBox(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val cornerLen = 36f
        val cr = 10f
        val p = paintCorner

        val boxW = r - l
        val boxH = b - t
        if (boxW < cornerLen * 2 + cr * 2 || boxH < cornerLen * 2 + cr * 2) return

        canvas.drawLine(l, t + cr, l, t + cornerLen, p)
        canvas.drawLine(l + cr, t, l + cornerLen, t, p)
        canvas.drawLine(r - cr, t, r - cornerLen, t, p)
        canvas.drawLine(r, t + cr, r, t + cornerLen, p)
        canvas.drawLine(l, b - cr, l, b - cornerLen, p)
        canvas.drawLine(l + cr, b, l + cornerLen, b, p)
        canvas.drawLine(r - cr, b, r - cornerLen, b, p)
        canvas.drawLine(r, b - cr, r, b - cornerLen, p)
    }

    fun updateDetections(dets: List<DetectionInfo>) {
        detections = dets
        postInvalidateOnAnimation()
    }

    fun updateLockRay(targetX: Float?, targetY: Float?) {
        lockRayX = targetX ?: Float.NaN
        lockRayY = targetY ?: Float.NaN
        postInvalidateOnAnimation()
    }
}

