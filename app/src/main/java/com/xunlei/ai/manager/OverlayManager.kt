package com.xunlei.ai.manager

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import com.xunlei.ai.view.TouchDisplayView
import com.xunlei.ai.view.AreaSettingsView
import com.xunlei.ai.model.AreaConfig
import com.xunlei.ai.util.ProjectionHolder

class OverlayManager(
    private val context: Context,
    private val wm: WindowManager,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int,
    private val dp: (Int) -> Int
) {
    companion object {
        private const val TAG = "OverlayManager"
    }

    // Touch display overlay
    private var touchDisplayView: TouchDisplayView? = null
    private var touchDisplayAdded = false

    // Area settings overlay
    private var areaSettingsView: AreaSettingsView? = null
    private var areaSettingsAdded = false

    // Callbacks
    var onAreaSettingsConfirm: ((List<AreaConfig>) -> Unit)? = null
    var onAreaSettingsCancel: (() -> Unit)? = null

    fun setupTouchDisplayView(aimTouchSize: Int = 20) {
        if (touchDisplayAdded) return
        val size = dp(aimTouchSize) * 2
        touchDisplayView = TouchDisplayView(context)
        ProjectionHolder.touchDisplayView = touchDisplayView
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
        touchDisplayView!!.dotRadius = dp(aimTouchSize).toFloat()
        wm.addView(touchDisplayView, p)
        touchDisplayAdded = true
        touchDisplayView!!.alpha = 0f
    }

    fun updateTouchDisplayVisibility(show: Boolean) {
        if (touchDisplayAdded) {
            val lp = touchDisplayView?.layoutParams as? WindowManager.LayoutParams
            if (lp != null) {
                lp.flags = if (show) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                touchDisplayView?.alpha = if (show) 1f else 0f
                try { wm.updateViewLayout(touchDisplayView, lp) } catch (_: Exception) {}
            }
        }
    }

    fun updateTouchDisplaySize(px: Int) {
        val p = dp(px)
        touchDisplayView?.dotRadius = p.toFloat()
        if (touchDisplayAdded) {
            val lp = touchDisplayView?.layoutParams as? WindowManager.LayoutParams
            if (lp != null) {
                lp.width = p * 2
                lp.height = p * 2
                wm.updateViewLayout(touchDisplayView, lp)
            }
        }
    }

    fun setupAreaSettingsView(savedAreas: List<AreaConfig>) {
        areaSettingsView = AreaSettingsView(context)
        ProjectionHolder.areaSettingsView = areaSettingsView
        val params = WindowManager.LayoutParams(
            MATCH_PARENT,
            MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        areaSettingsView?.apply {
            onConfirm = { areas ->
                onAreaSettingsConfirm?.invoke(areas)
                removeAreaSettingsView()
            }
            onCancel = {
                onAreaSettingsCancel?.invoke()
                removeAreaSettingsView()
            }
        }
        wm.addView(areaSettingsView!!, params)
        areaSettingsAdded = true
        areaSettingsView!!.visibility = View.GONE
    }

    fun removeAreaSettingsView() {
        try {
            if (areaSettingsAdded) {
                wm.removeView(areaSettingsView)
                areaSettingsAdded = false
                ProjectionHolder.areaSettingsView = null
            }
        } catch (_: Exception) {}
    }

    fun showAreaSettings(savedAreas: List<AreaConfig>) {
        if (areaSettingsAdded) removeAreaSettingsView()
        setupAreaSettingsView(savedAreas)
        if (savedAreas.isNotEmpty()) areaSettingsView?.setAreas(savedAreas)
        areaSettingsView?.open()
    }

    fun cleanup() {
        try {
            if (touchDisplayAdded) {
                wm.removeView(touchDisplayView)
                touchDisplayAdded = false
            }
        } catch (_: Exception) {}
        removeAreaSettingsView()
    }
}

