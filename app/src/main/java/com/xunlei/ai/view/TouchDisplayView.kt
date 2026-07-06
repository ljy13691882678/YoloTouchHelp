package com.xunlei.ai.view

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class TouchDisplayView(context: Context) : View(context) {

    var onPositionChanged: ((left: Int, top: Int) -> Unit)? = null
    var dotRadius: Float = 20f
        set(v) { field = v; postInvalidate() }
    var showDot: Boolean = false
        set(v) { field = v; postInvalidate() }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FF0000")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FF0000")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, fillPaint)
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, borderPaint)
        canvas.drawText("触摸点", w / 2f, h / 2f + 10f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                    moved = true
                    val lp = layoutParams as? android.view.WindowManager.LayoutParams
                    if (lp != null) {
                        lp.x += dx; lp.y += dy
                        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                        wm?.updateViewLayout(this, lp)
                        onPositionChanged?.invoke(lp.x, lp.y)
                    }
                    downX = event.rawX; downY = event.rawY
                }
            }
        }
        return true
    }
}
