package com.xunlei.ai.view

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.google.android.material.button.MaterialButton
import com.xunlei.ai.model.AreaConfig

class AreaSettingsView(context: Context) : View(context) {

    var onConfirm: ((List<AreaConfig>) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    enum class State { CLOSED, OPEN, CONFIRMING, CANCELLING }
    var state: State = State.CLOSED
        private set

    // Background screenshot bitmap
    private var backgroundBitmap: Bitmap? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        backgroundBitmap = bitmap
        invalidate()
    }

    private val areas = mutableListOf<AreaConfig>().apply {
        add(AreaConfig(name = "开火区", color = Color.WHITE))
        add(AreaConfig(name = "触发区", color = Color.parseColor("#FF1976D2")))
        add(AreaConfig(name = "瞄准区", color = Color.WHITE))
        add(AreaConfig(name = "摇杆范围", color = Color.parseColor("#FF4CAF50")))
        add(AreaConfig(name = "开镜区", color = Color.parseColor("#FFFF9800")))
    }

    private val joystickCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF4CAF50")
    }
    private val joystickCenterBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }

    private val overlayPaint = Paint().apply { color = Color.parseColor("#AA000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(24f, 12f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * resources.displayMetrics.density
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.parseColor("#44000000"))
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val okButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1976D2")
    }
    private val cancelButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#666666")
    }

    private var buttonWidth = 0; private var buttonHeight = 0
    private var buttonsBottom = 0f
    private val okButtonRect = RectF()
    private val cancelButtonRect = RectF()

    private enum class TouchMode { IDLE, DRAG_REGION, EDGE_RESIZE, PINCH_SCALE }
    private var touchMode = TouchMode.IDLE
    private var activeAreaIndex = -1
    private var activeEdge = Edge.NONE
    private var downX = 0f; private var downY = 0f
    private var lastTouchX = 0f; private var lastTouchY = 0f
    private var moved = false
    private var pinchLockedArea = -1  // 锁定缩放目标区域，手势中断后不切换
    private var areasInitialized = false  // 是否已通过 setAreas 设置过位置

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var initialSpan = 0f
            private var initialAreaWidth = 0
            private var initialAreaHeight = 0

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // 缩放手势中断后重新开始，锁定上次的区域不切换
                if (pinchLockedArea >= 0 && pinchLockedArea < areas.size) {
                    activeAreaIndex = pinchLockedArea
                    touchMode = TouchMode.PINCH_SCALE
                    initialSpan = detector.currentSpan
                    initialAreaWidth = areas[pinchLockedArea].width
                    initialAreaHeight = areas[pinchLockedArea].height
                    return true
                }

                val focusX = detector.focusX
                val focusY = detector.focusY

                // 优先选包含焦点区域的，否则选最近的
                var bestIdx = -1
                var bestDist = Float.MAX_VALUE
                for (i in areas.indices) {
                    val area = areas[i]
                    if (focusX >= area.x && focusX <= area.right &&
                        focusY >= area.y && focusY <= area.bottom) {
                        bestIdx = i
                        break
                    }
                    val cx = area.centerX.toFloat()
                    val cy = area.centerY.toFloat()
                    val dist = (focusX - cx) * (focusX - cx) + (focusY - cy) * (focusY - cy)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = i
                    }
                }

                if (bestIdx >= 0) {
                    activeAreaIndex = bestIdx
                    pinchLockedArea = bestIdx
                    touchMode = TouchMode.PINCH_SCALE
                    // 记录手势开始时的跨度和区域尺寸，用累计比计算
                    initialSpan = detector.currentSpan
                    initialAreaWidth = areas[bestIdx].width
                    initialAreaHeight = areas[bestIdx].height
                    return true
                }
                return false
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (touchMode != TouchMode.PINCH_SCALE || activeAreaIndex < 0) {
                    return false
                }

                if (initialSpan <= 0f) return false
                val ratio = detector.currentSpan / initialSpan
                if (ratio.isNaN() || ratio.isInfinite()) return false
                if (ratio <= 0.01f || ratio >= 100f) return false

                val area = areas[activeAreaIndex]
                // 用累计跨度比（当前跨度/初始跨度）替代增量 scaleFactor，避免手指靠近极限时缩不动
                val newWidth = (initialAreaWidth * ratio).toInt().coerceIn(120, width)
                val newHeight = (initialAreaHeight * ratio).toInt().coerceIn(120, height)

                // 摇杆范围区域：宽高保持一致（正方形）
                val finalWidth: Int
                val finalHeight: Int
                if (area.name == "摇杆范围") {
                    val size = newWidth.coerceAtMost(newHeight)
                    finalWidth = size
                    finalHeight = size
                } else {
                    finalWidth = newWidth
                    finalHeight = newHeight
                }

                // 以区域中心为基准缩放，并限制在视图边界内，防止放大后部分区域跑出屏幕
                val centerX = area.x + area.width / 2
                val centerY = area.y + area.height / 2
                area.width = finalWidth
                area.height = finalHeight
                area.x = (centerX - finalWidth / 2).coerceIn(0, width - finalWidth)
                area.y = (centerY - finalHeight / 2).coerceIn(0, height - finalHeight)
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                touchMode = TouchMode.IDLE
                activeAreaIndex = -1
            }
        }
    )

    private enum class Edge { NONE, TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private val edgeThreshold get() = (24 * resources.displayMetrics.density).toInt()
    private val cornerRadius get() = (12 * resources.displayMetrics.density).toFloat()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (!areasInitialized) {
            val columns = 3
            val areaWidth = (w * 0.22f).toInt()
            val areaHeight = (h * 0.18f).toInt()
            val spacingX = (24 * resources.displayMetrics.density).toInt()
            val spacingY = (28 * resources.displayMetrics.density).toInt()
            val rows = ((areas.size + columns - 1) / columns).coerceAtLeast(1)
            val totalWidth = areaWidth * columns + spacingX * (columns - 1)
            val totalHeight = areaHeight * rows + spacingY * (rows - 1)
            val startX = (w - totalWidth) / 2
            val startY = (h - totalHeight) / 2

            areas.forEachIndexed { i, area ->
                val row = i / columns
                val col = i % columns
                area.width = areaWidth
                area.height = areaHeight
                area.x = startX + col * (areaWidth + spacingX)
                area.y = startY + row * (areaHeight + spacingY)
                // 摇杆范围区域：宽高保持一致（正方形）
                if (area.name == "摇杆范围") {
                    val size = areaWidth.coerceAtMost(areaHeight)
                    area.width = size
                    area.height = size
                }
            }
        }

        buttonWidth = (140 * resources.displayMetrics.density).toInt()
        buttonHeight = (48 * resources.displayMetrics.density).toInt()
        val buttonSpacing = (24 * resources.displayMetrics.density).toInt()
        buttonsBottom = h - (60 * resources.displayMetrics.density)

        val totalButtonsWidth = buttonWidth * 2 + buttonSpacing
        val buttonsLeft = (w - totalButtonsWidth) / 2

        okButtonRect.set(buttonsLeft.toFloat(), buttonsBottom - buttonHeight, buttonsLeft + buttonWidth.toFloat(), buttonsBottom)
        cancelButtonRect.set((buttonsLeft + buttonWidth + buttonSpacing).toFloat(), buttonsBottom - buttonHeight, (buttonsLeft + totalButtonsWidth).toFloat(), buttonsBottom)
    }

    override fun onDraw(canvas: Canvas) {
        if (state == State.CLOSED) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw background screenshot first (fit to screen)
        if (backgroundBitmap != null && !backgroundBitmap!!.isRecycled) {
            val srcRect = Rect(0, 0, backgroundBitmap!!.width, backgroundBitmap!!.height)
            val dstRect = Rect(0, 0, width, height)
            canvas.drawBitmap(backgroundBitmap!!, srcRect, dstRect, bgPaint)
        } else {
            canvas.drawColor(Color.parseColor("#AA000000"))
        }

        // Dim overlay except area regions
        val layerId = canvas.saveLayer(0f, 0f, w, h, null)
        if (backgroundBitmap != null) {
            // Semi-transparent overlay on top of background
            canvas.drawColor(Color.parseColor("#88000000"))
        } else {
            canvas.drawColor(Color.parseColor("#AA000000"))
        }
        for (area in areas) {
            canvas.drawRoundRect(area.toRectF(), cornerRadius, cornerRadius, clearPaint)
        }
        canvas.restoreToCount(layerId)

        for (area in areas) {
            borderPaint.color = area.color
            canvas.drawRoundRect(area.toRectF(), cornerRadius, cornerRadius, borderPaint)

            // 摇杆范围区域绘制圆形中心点
            if (area.name == "摇杆范围") {
                val centerX = area.centerX.toFloat()
                val centerY = area.centerY.toFloat()
                val radius = (area.width.coerceAtMost(area.height) * 0.08f).coerceAtLeast(8f)
                canvas.drawCircle(centerX, centerY, radius, joystickCenterPaint)
                canvas.drawCircle(centerX, centerY, radius, joystickCenterBorderPaint)
            }

            textPaint.color = area.color
            val textY = area.bottom + textPaint.textSize + 8f
            canvas.drawText(area.name, area.x.toFloat(), textY, textPaint)
        }

        canvas.drawText("拖动色块移动区域，双指捏合或拖拽描边调整大小", 20f, 20f + instructionPaint.textSize, instructionPaint)

        val buttonRadius = (8 * resources.displayMetrics.density).toFloat()

        canvas.drawRoundRect(okButtonRect, buttonRadius, buttonRadius, okButtonPaint)
        canvas.drawText("确定", okButtonRect.centerX(), okButtonRect.centerY() + buttonTextPaint.textSize / 3, buttonTextPaint)

        canvas.drawRoundRect(cancelButtonRect, buttonRadius, buttonRadius, cancelButtonPaint)
        canvas.drawText("取消", cancelButtonRect.centerX(), cancelButtonRect.centerY() + buttonTextPaint.textSize / 3, buttonTextPaint)
    }

    private fun isInCenterZone(area: AreaConfig, x: Float, y: Float): Boolean {
        val paddingX = area.width * 0.25f
        val paddingY = area.height * 0.25f
        return x >= area.x + paddingX && x <= area.right - paddingX &&
                y >= area.y + paddingY && y <= area.bottom - paddingY
    }

    private fun isInResizeZone(area: AreaConfig, x: Float, y: Float): Boolean {
        val innerPaddingX = area.width * 0.1f
        val innerPaddingY = area.height * 0.1f
        val outerPaddingX = area.width * 0.35f  // 向外扩大20%
        val outerPaddingY = area.height * 0.35f
        if (x >= area.x && x <= area.right && y >= area.y && y <= area.bottom) {
            return !isInCenterZone(area, x, y)
        }
        return x >= area.x - outerPaddingX && x <= area.right + outerPaddingX &&
                y >= area.y - outerPaddingY && y <= area.bottom + outerPaddingY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state != State.OPEN) return false

        scaleGestureDetector.onTouchEvent(event)
        if (touchMode == TouchMode.PINCH_SCALE) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastTouchX = event.x; lastTouchY = event.y
                moved = false
                activeAreaIndex = -1
                activeEdge = Edge.NONE

                if (okButtonRect.contains(event.x, event.y) || cancelButtonRect.contains(event.x, event.y)) {
                    return true
                }

                for (i in areas.indices) {
                    val area = areas[i]

                    if (isInCenterZone(area, event.x, event.y)) {
                        activeAreaIndex = i
                        touchMode = TouchMode.DRAG_REGION
                        return true
                    }

                    if (isInResizeZone(area, event.x, event.y)) {
                        val edge = getEdgeAt(area, event.x.toInt(), event.y.toInt())
                        if (edge != Edge.NONE) {
                            activeAreaIndex = i
                            activeEdge = edge
                            touchMode = TouchMode.EDGE_RESIZE
                            return true
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二个手指按下时取消拖动/边缘调整，但不要打断已进行的缩放
                if (touchMode == TouchMode.DRAG_REGION || touchMode == TouchMode.EDGE_RESIZE) {
                    touchMode = TouchMode.IDLE
                    moved = false
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchMode == TouchMode.IDLE) return true

                val dx = (event.x - lastTouchX).toInt()
                val dy = (event.y - lastTouchY).toInt()

                if (!moved) {
                    // 只要有位移就标记为已移动，不再需要累计阈值
                    if (event.x != downX || event.y != downY) moved = true
                }

                if (touchMode == TouchMode.DRAG_REGION && activeAreaIndex >= 0 && moved) {
                    val area = areas[activeAreaIndex]
                    area.x = (area.x + dx).coerceIn(0, width - area.width)
                    area.y = (area.y + dy).coerceIn(0, height - area.height)
                    invalidate()
                } else if (touchMode == TouchMode.EDGE_RESIZE && activeAreaIndex >= 0 && moved) {
                    resizeArea(activeAreaIndex, activeEdge, dx, dy)
                    invalidate()
                }

                lastTouchX = event.x; lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    if (okButtonRect.contains(event.x, event.y)) {
                        performConfirm(); return true
                    }
                    if (cancelButtonRect.contains(event.x, event.y)) {
                        performCancel(); return true
                    }
                }
                touchMode = TouchMode.IDLE
                activeAreaIndex = -1
                activeEdge = Edge.NONE
                pinchLockedArea = -1
                return true
            }
        }
        return true
    }

    private fun getEdgeAt(area: AreaConfig, x: Int, y: Int): Edge {
        val l = area.x; val t = area.y
        val r = area.right; val b = area.bottom
        // 阈值不超过区域尺寸的1/3，避免小区域上角检测覆盖整条边
        val th = edgeThreshold.coerceAtMost(area.width / 3).coerceAtMost(area.height / 3)
        if (th <= 0) return Edge.NONE

        // 向外扩大20%的检测范围
        val outerExt = ((area.width * 0.20f).toInt()).coerceAtMost(area.width / 3).coerceAtMost(area.height / 3)

        // 边检测优先于角检测，排除角重叠区
        val nearTop = y in t - outerExt..t + th
        val nearBottom = y in b - th..b + outerExt
        val nearLeft = x in l - outerExt..l + th
        val nearRight = x in r - th..r + outerExt

        val onEdgeOnlyX = x in l + th until r - th
        val onEdgeOnlyY = y in t + th until b - th

        if (nearTop && onEdgeOnlyX) return Edge.TOP
        if (nearBottom && onEdgeOnlyX) return Edge.BOTTOM
        if (nearLeft && onEdgeOnlyY) return Edge.LEFT
        if (nearRight && onEdgeOnlyY) return Edge.RIGHT

        // 角：必须在两个方向的重叠区内
        if (nearLeft && nearTop) return Edge.TOP_LEFT
        if (nearRight && nearTop) return Edge.TOP_RIGHT
        if (nearLeft && nearBottom) return Edge.BOTTOM_LEFT
        if (nearRight && nearBottom) return Edge.BOTTOM_RIGHT
        return Edge.NONE
    }

    private fun resizeArea(areaIdx: Int, edge: Edge, dx: Int, dy: Int) {
        val area = areas[areaIdx]
        val minSize = 120

        // 摇杆范围区域：宽高保持一致（正方形）
        if (area.name == "摇杆范围") {
            val delta = when (edge) {
                Edge.TOP, Edge.LEFT -> -dx.coerceAtMost(-dy)  // 取较大的缩小量
                Edge.BOTTOM, Edge.RIGHT -> dx.coerceAtLeast(dy)  // 取较大的增大量
                Edge.TOP_LEFT -> (-dx).coerceAtLeast(-dy)
                Edge.TOP_RIGHT -> dx.coerceAtLeast(-dy)
                Edge.BOTTOM_LEFT -> (-dx).coerceAtLeast(dy)
                Edge.BOTTOM_RIGHT -> dx.coerceAtLeast(dy)
                Edge.NONE -> 0
            }

            val newSize = (area.width + delta).coerceIn(minSize, width.coerceAtMost(height))
            val centerX = area.x + area.width / 2
            val centerY = area.y + area.height / 2
            area.width = newSize
            area.height = newSize
            area.x = (centerX - newSize / 2).coerceIn(0, width - newSize)
            area.y = (centerY - newSize / 2).coerceIn(0, height - newSize)
            return
        }

        when (edge) {
            Edge.TOP -> {
                val newY = (area.y + dy).coerceIn(0, area.bottom - minSize)
                val change = area.y - newY
                area.y = newY
                area.height += change
            }
            Edge.BOTTOM -> area.height = (area.height + dy).coerceIn(minSize, height - area.y)
            Edge.LEFT -> {
                val newX = (area.x + dx).coerceIn(0, area.right - minSize)
                val change = area.x - newX
                area.x = newX
                area.width += change
            }
            Edge.RIGHT -> area.width = (area.width + dx).coerceIn(minSize, width - area.x)
            Edge.TOP_LEFT -> { resizeArea(areaIdx, Edge.TOP, dx, dy); resizeArea(areaIdx, Edge.LEFT, dx, dy) }
            Edge.TOP_RIGHT -> { resizeArea(areaIdx, Edge.TOP, dx, dy); resizeArea(areaIdx, Edge.RIGHT, dx, dy) }
            Edge.BOTTOM_LEFT -> { resizeArea(areaIdx, Edge.BOTTOM, dx, dy); resizeArea(areaIdx, Edge.LEFT, dx, dy) }
            Edge.BOTTOM_RIGHT -> { resizeArea(areaIdx, Edge.BOTTOM, dx, dy); resizeArea(areaIdx, Edge.RIGHT, dx, dy) }
            Edge.NONE -> {}
        }
    }

    fun open() {
        state = State.OPEN
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 1f
        scaleY = 1f
        animate().alpha(1f).setDuration(200).start()
        invalidate()
    }

    fun close(confirmed: Boolean) {
        state = if (confirmed) State.CONFIRMING else State.CANCELLING
        animate().alpha(0f).setDuration(150).withEndAction {
            visibility = View.GONE
            state = State.CLOSED
            if (confirmed) onConfirm?.invoke(areas.toList())
            else onCancel?.invoke()
        }.start()
    }

    private fun performConfirm() = close(confirmed = true)
    private fun performCancel() = close(confirmed = false)

    fun setAreas(savedAreas: List<AreaConfig>) {
        savedAreas.forEachIndexed { i, config ->
            if (i < areas.size) {
                areas[i].x = config.x
                areas[i].y = config.y
                areas[i].width = config.width
                areas[i].height = config.height
            }
        }
        areasInitialized = true
        invalidate()
    }

    fun getAreas(): List<AreaConfig> = areas.toList()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

