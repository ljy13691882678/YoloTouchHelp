package com.xunlei.ai.view

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * MD3 风格悬浮按钮
 *
 * 应用图标 + 圆角裁剪，使用 View 自带 elevation 渲染阴影。
 */
class FloatBallView(context: Context) : View(context) {

    var onClickCallback: (() -> Unit)? = null
    var onMoveCallback: ((dx: Int, dy: Int) -> Unit)? = null

    private val paintIcon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private var clipPath = Path()
    private var iconBitmap: Bitmap? = null
    private var compositedBitmap: Bitmap? = null  // 预合成位图，避免闪烁

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    init {
        elevation = dp(6).toFloat()
        try {
            val pkg = context.packageName
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            val dr = context.resources
            val iconRes = dr.getIdentifier("ic_launcher", "mipmap", pkg)
            if (iconRes != 0) {
                val d = dr.getDrawable(iconRes, context.theme)
                iconBitmap = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    ?: Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888).also {
                        val c = Canvas(it)
                        d.setBounds(0, 0, c.width, c.height)
                        d.draw(c)
                    }
            }
        } catch (_: Exception) {}
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = dp(6).toFloat()
        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
        buildCompositedBitmap(w, h)
    }

    private fun buildCompositedBitmap(w: Int, h: Int) {
        compositedBitmap?.recycle()
        compositedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(compositedBitmap!!)
        val cx = w / 2f; val cy = h / 2f; val r = w / 2f
        c.drawCircle(cx, cy, r, paintBg)
        iconBitmap?.let { bmp ->
            c.save()
            c.clipPath(clipPath)
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = Rect(0, 0, w, h)
            c.drawBitmap(bmp, src, dst, paintIcon)
            c.restore()
        } ?: run {
            c.drawCircle(cx, cy, r * 0.30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY })
        }
    }

    override fun onDraw(canvas: Canvas) {
        compositedBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                    moved = true
                    onMoveCallback?.invoke(dx, dy)
                    downX = event.rawX
                    downY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onClickCallback?.invoke()
            }
        }
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

