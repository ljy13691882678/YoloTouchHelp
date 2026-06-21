package com.yolotouchhelp.aimbot.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class BezierCurveView(context: Context) : View(context) {

    var controlOffset = 0.3f
        set(value) { field = value; invalidate() }
    var randomSpread = 0.1f
        set(value) { field = value; invalidate() }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 1f || h < 1f) return

        val pad = 16f
        val sx = pad; val sy = h - pad
        val ex = w - pad; val ey = pad
        val dx = ex - sx; val dy = ey - sy

        // Reference straight line
        canvas.drawLine(sx, sy, ex, ey, refPaint)

        // Control points based on controlOffset
        val off = controlOffset.coerceIn(0.05f, 0.5f)
        val spread = randomSpread.coerceIn(0f, 0.5f)
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val perpX = -dy / dist
        val perpY = dx / dist
        val shift = dist * spread * 0.5f

        val p1x = sx + dx * off + perpX * shift
        val p1y = sy + dy * off + perpY * shift
        val p2x = sx + dx * (1f - off) - perpX * shift
        val p2y = sy + dy * (1f - off) - perpY * shift

        val path = Path().apply {
            moveTo(sx, sy)
            cubicTo(p1x, p1y, p2x, p2y, ex, ey)
        }
        canvas.drawPath(path, curvePaint)
    }
}

