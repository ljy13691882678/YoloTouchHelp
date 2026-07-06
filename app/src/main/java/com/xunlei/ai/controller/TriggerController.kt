package com.xunlei.ai.controller
import com.xunlei.ai.model.DetectionInfo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.xunlei.ai.model.AreaConfig
import com.xunlei.ai.injector.TouchInjectorInterface
import com.xunlei.ai.view.TriggerOverlayView
import com.xunlei.ai.util.ProjectionHolder

class TriggerController(
    private val context: Context,
    private val wm: WindowManager,
    private val touchClient: () -> TouchInjectorInterface?,
    private val savedAreas: () -> List<AreaConfig>,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int,
    private val dp: (Int) -> Int
) {
    companion object {
        private const val TAG = "TriggerController"
        private const val AREA_INDEX_FIRE = 0
        private const val AREA_INDEX_TRIGGER = 1
    }

    // Trigger settings
    var triggerEnabled = false
    var triggerReactionSpeed = 100
    var triggerCooldown = 200
    var triggerUpFluct = 3
    var triggerDownFluct = 3
    var triggerTouchDuration = 10
    var triggerTouchRange = 100
    var triggerShowArea = false
    var autoStopEnabled = false
    var autoTriggerAdsEnabled = false
    var autoTriggerAdsRange = 180f
    var triggerOffsetYRatio = 0f
    var triggerClasses: MutableSet<Int> = mutableSetOf()
    var classTriggerOffsets: Map<Int, Float> = emptyMap()

    // Trigger overlay
    private var triggerOverlay: TriggerOverlayView? = null
    private var triggerOverlayAdded = false
    private var triggerAreaX = 0
    private var triggerAreaY = 0
    private var lastTriggerMs = 0L
    var triggerFired = false

    val mainHandler = Handler(Looper.getMainLooper())

    fun setupTriggerOverlay() {
        if (triggerOverlayAdded) return
        triggerOverlay = TriggerOverlayView(context)
        ProjectionHolder.triggerOverlayView = triggerOverlay
        val size = dp(triggerTouchRange.coerceAtLeast(30))
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = screenWidth() / 2 - size / 2
        p.y = screenHeight() / 2 - size / 2
        triggerAreaX = p.x
        triggerAreaY = p.y
        triggerOverlay!!.areaSize = size
        triggerOverlay!!.onPositionChanged = { l, t -> triggerAreaX = l; triggerAreaY = t }
        wm.addView(triggerOverlay!!, p)
        triggerOverlayAdded = true
        triggerOverlay!!.alpha = 0f
        Log.d(TAG, "trigger overlay at ($triggerAreaX,$triggerAreaY) size=$size")
    }

    fun updateTriggerOverlayVisibility() {
        val ov = triggerOverlay ?: return
        val p = ov.layoutParams as? WindowManager.LayoutParams ?: return
        if (triggerShowArea) {
            ov.alpha = 1f
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            ov.alpha = 0f
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.updateViewLayout(ov, p) } catch (_: Exception) {}
    }

    fun updateTriggerOverlaySize() {
        val ov = triggerOverlay ?: return
        val p = ov.layoutParams as? WindowManager.LayoutParams ?: return
        val size = dp(triggerTouchRange.coerceAtLeast(30))
        p.width = size
        p.height = size
        ov.areaSize = size
        try { wm.updateViewLayout(ov, p) } catch (_: Exception) {}
    }

    fun fireTriggerTap() {
        val fireArea = savedAreas().getOrNull(AREA_INDEX_FIRE)
        if (fireArea != null) {
            val rndX = fireArea.x + (Math.random() * fireArea.width).toInt()
            val rndY = fireArea.y + (Math.random() * fireArea.height).toInt()
            Log.d(TAG, "trigger fire! area=(${fireArea.x},${fireArea.y} ${fireArea.width}x${fireArea.height}) tap=($rndX,$rndY)")
            touchClient()?.triggerTap(rndX, rndY, triggerTouchDuration.coerceIn(1, 50))
        } else {
            val size = dp(triggerTouchRange.coerceAtLeast(30))
            val px = size / 2
            val cx = triggerAreaX + size / 2
            val cy = triggerAreaY + size / 2
            val rndX = cx + ((Math.random() - 0.5) * 2 * px).toInt()
            val rndY = cy + ((Math.random() - 0.5) * 2 * px).toInt()
            Log.d(TAG, "trigger fire (legacy)! tap=($rndX,$rndY)")
            touchClient()?.triggerTap(rndX, rndY, triggerTouchDuration.coerceIn(1, 50))
        }
    }

    fun processTrigger(lastDetections: List<DetectionInfo>, centerX: Float, centerY: Float, hasDetects: Boolean) {
        if (!triggerEnabled || !hasDetects || touchClient()?.isConnected() != true) return

        val fingerOnFire = touchClient()?.isFingerInFireZone() ?: false
        if (fingerOnFire) return

        val triggerDets = if (triggerClasses.isEmpty()) lastDetections
            else lastDetections.filter { it.classId in triggerClasses }
        val liveTriggerDets = triggerDets.filter { it.missedFrames <= 0 }

        if (liveTriggerDets.isEmpty()) return

        val onTarget = selectTriggerTarget(liveTriggerDets, centerX, centerY) != null

        if (onTarget) {
            val now = System.currentTimeMillis()
            if (!triggerFired) {
                // 第一发：准心进入目标时开始计时，反应速度后开枪
                if (lastTriggerMs == 0L) lastTriggerMs = now
                if (now - lastTriggerMs >= triggerReactionSpeed.coerceIn(10, 500)) {
                    triggerFired = true
                    // 自动急停：开枪前松开摇杆区域的手指
                    if (autoStopEnabled) {
                        val lifted = touchClient()?.liftJoystickFinger() ?: false
                        Log.d(TAG, "autoStop: liftJoystickFinger=$lifted")
                    }
                    lastTriggerMs = now
                    fireTriggerTap()
                }
            } else {
                // 第二发起：使用冷却时间
                val cd = triggerCooldown.coerceIn(10, 1000)
                if (now - lastTriggerMs >= cd) {
                    // 自动急停：开枪前松开摇杆区域的手指
                    if (autoStopEnabled) {
                        val lifted = touchClient()?.liftJoystickFinger() ?: false
                        Log.d(TAG, "autoStop: liftJoystickFinger=$lifted")
                    }
                    lastTriggerMs = now
                    fireTriggerTap()
                }
            }
        } else {
            // 准心离开目标，重置扳机状态
            triggerFired = false
            lastTriggerMs = 0L
        }
    }

    fun resetState(releaseTouch: Boolean = true) {
        triggerFired = false
        lastTriggerMs = 0L
        if (releaseTouch) {
            try {
                touchClient()?.triggerUp()
            } catch (_: Exception) {}
        }
    }

    fun hasAdsTarget(lastDetections: List<DetectionInfo>, centerX: Float, centerY: Float): Boolean {
        if (!triggerEnabled || !autoTriggerAdsEnabled) return false
        val triggerDets = if (triggerClasses.isEmpty()) lastDetections
            else lastDetections.filter { it.classId in triggerClasses }
        return selectTriggerTarget(triggerDets.filter { it.missedFrames <= 0 }, centerX, centerY) != null
    }

    private fun selectTriggerTarget(detections: List<DetectionInfo>, centerX: Float, centerY: Float): DetectionInfo? {
        var best: DetectionInfo? = null
        var bestMetric = Float.MAX_VALUE
        for (det in detections) {
            val metric = if (autoTriggerAdsEnabled) {
                val distance = distanceToDetectionBox(det, centerX, centerY)
                if (distance > autoTriggerAdsRange.coerceIn(20f, 500f)) continue
                distance * distance
            } else {
                if (!isCrosshairInside(det, centerX, centerY)) continue
                val dx = det.rect.centerX() - centerX
                val dy = det.rect.centerY() - centerY
                dx * dx + dy * dy
            }
            if (metric < bestMetric) {
                bestMetric = metric
                best = det
            }
        }
        return best
    }

    private fun isCrosshairInside(det: DetectionInfo, centerX: Float, centerY: Float): Boolean {
        val r = det.rect
        val classOff = classTriggerOffsets[det.classId] ?: triggerOffsetYRatio
        val extendY = r.height() * (-classOff)
        return centerX >= r.left && centerX <= r.right && centerY >= r.top && centerY <= r.bottom + extendY
    }

    private fun distanceToDetectionBox(det: DetectionInfo, centerX: Float, centerY: Float): Float {
        val r = det.rect
        val dx = when {
            centerX < r.left -> r.left - centerX
            centerX > r.right -> centerX - r.right
            else -> 0f
        }
        val dy = when {
            centerY < r.top -> r.top - centerY
            centerY > r.bottom -> centerY - r.bottom
            else -> 0f
        }
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun cleanup() {
        try {
            resetState(releaseTouch = true)
            if (triggerOverlayAdded) {
                wm.removeView(triggerOverlay)
                triggerOverlayAdded = false
            }
        } catch (_: Exception) {}
    }
}

