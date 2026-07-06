package com.xunlei.ai.util

import android.content.Intent
import android.media.projection.MediaProjection
import android.view.View
import android.view.WindowManager

object ProjectionHolder {
    var resultCode: Int = -1
    var resultData: Intent? = null
    var mediaProjection: MediaProjection? = null

    // 模型列表（由 MainActivity 设置，供 FloatService 读取）
    data class ModelEntry(
        val filename: String,
        val displayName: String,
        val precision: String,
        val inputSize: Int,
        val outputSize: Int,
        val description: String,
        val classes: Map<Int, String> = emptyMap(),
        val sourcePath: String? = null
    )

    var modelList: List<ModelEntry> = emptyList()
    var selectedModelIndex: Int = 0

    // 触摸方案（0=Uinput, 1=InputManager）
    var selectedTouchMethod: Int = 0

    // 服务状态（0=待机, 1=运行, 2=推理中）
    var currentState: Int = 0
    var currentModelName: String = ""

    // 设置变更后需要重新加载模型
    var needsModelReload: Boolean = false

    // 上次通知的模型索引，用于 FloatService 检测 MainActivity 的模型切换
    var lastNotifiedModelIndex: Int = 0

    // 状态变更回调（替代广播，兼容 Android 14+ 广播限制）
    private var stateListener: ((Int, String) -> Unit)? = null

    // 模型索引变更回调（悬浮窗→主页面同步）
    private var modelIndexListener: ((Int) -> Unit)? = null

    fun setStateListener(listener: (Int, String) -> Unit) {
        stateListener = listener
    }

    fun removeStateListener() {
        stateListener = null
    }

    fun setModelIndexListener(listener: (Int) -> Unit) {
        modelIndexListener = listener
    }

    fun removeModelIndexListener() {
        modelIndexListener = null
    }

    fun notifyModelIndexChanged(index: Int) {
        selectedModelIndex = index
        modelIndexListener?.invoke(index)
    }

    fun updateState(state: Int, modelName: String) {
        currentState = state
        currentModelName = modelName
        stateListener?.invoke(state, modelName)
    }

    // 对 FloatService 创建的所有覆盖层视图的引用，用于从 Activity 直接移除
    var floatBallView: View? = null
    var overlayCanvasView: View? = null
    var guiPanelView: View? = null
    var triggerOverlayView: View? = null
    var touchDisplayView: View? = null
    var areaSettingsView: View? = null

    fun clearViews(wm: WindowManager) {
        fun remove(v: View?) {
            if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
        }
        remove(floatBallView); floatBallView = null
        remove(overlayCanvasView); overlayCanvasView = null
        remove(guiPanelView); guiPanelView = null
        remove(triggerOverlayView); triggerOverlayView = null
        remove(touchDisplayView); touchDisplayView = null
        remove(areaSettingsView); areaSettingsView = null
    }
}

