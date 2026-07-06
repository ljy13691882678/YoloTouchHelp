package com.xunlei.ai.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout.LayoutParams as FrameLayoutParams
import kotlin.math.abs
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import com.xunlei.ai.R

class GuiPanelView(context: Context) : MaterialCardView(ContextThemeWrapper(context, R.style.Theme_XunleiAI)) {

    var onEnabledChanged: ((Boolean) -> Unit)? = null
    var onSpeedChanged: ((Float) -> Unit)? = null
    var onRangeChanged: ((Int) -> Unit)? = null
    var onConfidenceChanged: ((Float) -> Unit)? = null
    var onModelSelected: ((Int) -> Unit)? = null
    var onTriggerEnabled: ((Boolean) -> Unit)? = null
    var onTriggerReactionSpeed: ((Int) -> Unit)? = null
    var onTriggerCooldown: ((Int) -> Unit)? = null
    var onTriggerUpFluctuation: ((Int) -> Unit)? = null
    var onTriggerDownFluctuation: ((Int) -> Unit)? = null
    var onTriggerTouchDuration: ((Int) -> Unit)? = null
    var onTriggerTouchRange: ((Int) -> Unit)? = null
    var onTriggerShowArea: ((Boolean) -> Unit)? = null
    var onTestCircle: (() -> Unit)? = null
    var onToggleModel: ((Boolean) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onAimOffsetYRatioChanged: ((Float) -> Unit)? = null
    var onAimSwayAmplitudeChanged: ((Int) -> Unit)? = null
    var onAimPredictionMultiplierChanged: ((Float) -> Unit)? = null
    var onTriggerOffsetYRatioChanged: ((Float) -> Unit)? = null
    var onKiChanged: ((Float) -> Unit)? = null
    var onKdChanged: ((Float) -> Unit)? = null
    var onPidSamplePeriodMsChanged: ((Int) -> Unit)? = null
    var onAimTouchDisplay: ((Boolean) -> Unit)? = null
    var onAimTouchSize: ((Int) -> Unit)? = null
    var onAimHoldEnabled: ((Boolean) -> Unit)? = null
    var onAimModeChanged: ((Int) -> Unit)? = null
    var onBezierDurationChanged: ((Int) -> Unit)? = null
    var onBezierControlOffsetChanged: ((Float) -> Unit)? = null
    var onBezierRandomSpreadChanged: ((Float) -> Unit)? = null
    var onCaptureRangeEnabled: ((Boolean) -> Unit)? = null
    var onShowCaptureRangeChanged: ((Boolean) -> Unit)? = null
    var onShowDetectionBoxChanged: ((Boolean) -> Unit)? = null
    var onShowCenterDotChanged: ((Boolean) -> Unit)? = null
    var onShowLockRayChanged: ((Boolean) -> Unit)? = null
    var onShowDetectionClassIdsChanged: ((Set<Int>) -> Unit)? = null
    var onAreaSettingsToggle: (() -> Unit)? = null
    var onRecordEnabledChanged: ((Boolean) -> Unit)? = null
    var onAutoSaveDatasetChanged: ((Boolean) -> Unit)? = null
    var onAimClassesChanged: ((Set<Int>) -> Unit)? = null
    var onPriorityClassChanged: ((Int) -> Unit)? = null
    var onClassAimOffsetChanged: ((Int, Float) -> Unit)? = null
    var onBoxAimRatioChanged: ((Float) -> Unit)? = null
    var onClassBoxAimRatioChanged: ((Int, Float) -> Unit)? = null
    var onClassTriggerOffsetChanged: ((Int, Float) -> Unit)? = null
    var onTriggerClassesChanged: ((Set<Int>) -> Unit)? = null
    var onRecoilEnabledChanged: ((Boolean) -> Unit)? = null
    var onRecoilStrengthChanged: ((Float) -> Unit)? = null
    var onConvergeThreshChanged: ((Int) -> Unit)? = null
    var onTargetLostToleranceChanged: ((Int) -> Unit)? = null
    var onLockBoxThresholdChanged: ((Float) -> Unit)? = null
    var onLockCenterWeightChanged: ((Float) -> Unit)? = null
    var onMoveSmoothChanged: ((Float) -> Unit)? = null
    var onDeadzoneHoldFramesChanged: ((Int) -> Unit)? = null
    var onEdgeReturnStrengthChanged: ((Float) -> Unit)? = null
    var onAutoStopEnabledChanged: ((Boolean) -> Unit)? = null
    var onAutoTriggerAdsEnabledChanged: ((Boolean) -> Unit)? = null
    var onAutoTriggerAdsRangeChanged: ((Float) -> Unit)? = null
    var onTouchOrientationModeChanged: ((Int) -> Unit)? = null
    var onKalmanPredictEnabledChanged: ((Boolean) -> Unit)? = null
    var onKalmanMaxMissedChanged: ((Int) -> Unit)? = null
    var onKalmanProcessNoiseChanged: ((Float) -> Unit)? = null
    var onKalmanMeasureNoiseChanged: ((Float) -> Unit)? = null
    var onKalmanBoxSmoothChanged: ((Float) -> Unit)? = null
    var onKalmanMatchIouThresholdChanged: ((Float) -> Unit)? = null
    var onPanelDrag: ((Int, Int) -> Unit)? = null
    var onPanelResize: ((Int, Int) -> Unit)? = null

    var aimbotEnabled = false
    var speed = 0.3f
    var range = 300
    var confidence = 0.50f
    var modelIndex = 0
    var modelNames: List<String> = emptyList()
    var aimOffsetYRatio = 0f
    var aimSwayAmplitude = 0
    var aimPredictionMultiplier = 0.7f
    var ki = 0.02f
    var kd = 0.08f
    var pidSamplePeriodMs = 8
    var aimTouchDisplay = false
    var aimTouchSize = 20
    var aimHoldEnabled = false
    var aimMode = 0
    var bezierDuration = 30
    var bezierControlOffset = 0.3f
    var bezierRandomSpread = 0.1f
    var showCaptureRange = false
    var showDetectionBox = false
    var showCenterDot = false
    var showLockRay = false
    var showDetectionClassIds: Set<Int> = emptySet()
    var triggerEnabled = false
    var triggerReactionSpeed = 100
    var triggerCooldown = 200
    var triggerUpFluctuation = 3
    var triggerDownFluctuation = 3
    var triggerTouchDuration = 10
    var triggerTouchRange = 100
    var triggerShowArea = false
    var triggerOffsetYRatio = 0f
    var modelRunning = false
    var recordEnabled = false
    var classMap: Map<Int, String> = emptyMap()
    var aimClasses: MutableSet<Int> = mutableSetOf()
    var priorityClass: Int = -1
    var classAimOffsets: MutableMap<Int, Float> = mutableMapOf()
    var boxAimRatio = 0.5f
    var classBoxAimRatios: MutableMap<Int, Float> = mutableMapOf()
    var classTriggerOffsets: MutableMap<Int, Float> = mutableMapOf()
    var triggerClasses: MutableSet<Int> = mutableSetOf()
    var autoSaveDataset = false
    var recoilEnabled = false
    var recoilStrength = 0f
    var kalmanPredictEnabled = false
    var kalmanMaxMissed = 5
    var kalmanProcessNoise = 70f
    var kalmanMeasureNoise = 110f
    var kalmanBoxSmooth = 0.60f
    var kalmanMatchIouThreshold = 0.20f
    var convergeThresh = 10
    var targetLostTolerance = 2
    var lockBoxThreshold = 0.35f
    var lockCenterWeight = 0.3f
    var moveSmooth = 0.35f
    var deadzoneHoldFrames = 3
    var edgeReturnStrength = 0.25f
    var autoStopEnabled = false
    var autoTriggerAdsEnabled = false
    var autoTriggerAdsRange = 180f
    var touchOrientationMode = 0
    var activeTab = 0

    private val webView: WebView
    private var pageLoaded = false
    private var pageReady = false
    private val resizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 99, 102, 241); strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 99, 102, 241); strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE }
    private var gestureMode = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dragHandleHeight = dp(18)

    init {
        radius = dp(18).toFloat()
        cardElevation = dp(14).toFloat()
        setCardBackgroundColor(Color.TRANSPARENT)
        strokeWidth = 0
        webView = createWebView()
        addView(webView, FrameLayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dragHandleHeight })
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val gripWidth = dp(38).toFloat()
        val gripY = dragHandleHeight * 0.5f
        val gripStart = (width - gripWidth) * 0.5f
        canvas.drawLine(gripStart, gripY, gripStart + gripWidth, gripY, handlePaint)
        val inset = dp(11).toFloat()
        val size = dp(28).toFloat()
        canvas.drawLine(width - inset - size, height - inset, width - inset, height - inset - size, resizePaint)
        canvas.drawLine(width - inset - size * 0.55f, height - inset, width - inset, height - inset - size * 0.55f, resizePaint)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return handlePanelGesture(event, interceptOnly = true) || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return handlePanelGesture(event, interceptOnly = false) || super.onTouchEvent(event)
    }

    private fun handlePanelGesture(event: MotionEvent, interceptOnly: Boolean): Boolean {
        val handleSize = dp(44).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                gestureMode = when {
                    event.x >= width - handleSize && event.y >= height - handleSize -> 2
                    event.y <= dragHandleHeight -> 1
                    else -> 0
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureMode == 0) return false
                val movedFarEnough = abs(event.rawX - downRawX) >= touchSlop || abs(event.rawY - downRawY) >= touchSlop
                if (!movedFarEnough) return false
                if (interceptOnly) {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()
                if (abs(dx) > 0 || abs(dy) > 0) {
                    if (gestureMode == 2) onPanelResize?.invoke(dx, dy) else onPanelDrag?.invoke(dx, dy)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = gestureMode != 0
                gestureMode = 0
                return handled
            }
        }
        return false
    }

    fun buildUI() {
        if (!pageLoaded) {
            pageLoaded = true
            pageReady = false
            webView.loadUrl(FLOAT_MENU_ASSET)
            return
        }
        pushState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            addJavascriptInterface(FloatMenuBridge(), "FloatMenuHost")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == FLOAT_MENU_ASSET) {
                        pageReady = true
                        pushState()
                    }
                }
            }
        }
    }

    private fun pushState() {
        if (!pageReady) return
        val script = "window.renderState(${buildStateJson().toString()});"
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun buildStateJson(): JSONObject {
        return JSONObject().apply {
            put("activeTab", activeTab.coerceIn(0, 3))
            put("modelRunning", modelRunning)
            put("aimbotEnabled", aimbotEnabled)
            put("speed", speed.toDouble())
            put("range", range)
            put("confidence", confidence.toDouble())
            put("modelIndex", modelIndex.coerceIn(0, (modelNames.size - 1).coerceAtLeast(0)))
            put("modelNames", JSONArray(modelNames))
            put("aimOffsetYRatio", aimOffsetYRatio.toDouble())
            put("aimSwayAmplitude", aimSwayAmplitude)
            put("aimPredictionMultiplier", aimPredictionMultiplier.toDouble())
            put("ki", ki.toDouble())
            put("kd", kd.toDouble())
            put("pidSamplePeriodMs", pidSamplePeriodMs)
            put("aimMode", aimMode)
            put("bezierDuration", bezierDuration)
            put("bezierControlOffset", bezierControlOffset.toDouble())
            put("bezierRandomSpread", bezierRandomSpread.toDouble())
            put("aimHoldEnabled", aimHoldEnabled)
            put("convergeThresh", convergeThresh)
            put("showCaptureRange", showCaptureRange)
            put("showDetectionBox", showDetectionBox)
            put("showCenterDot", showCenterDot)
            put("showLockRay", showLockRay)
            put("showDetectionClassIds", JSONArray(showDetectionClassIds.toList()))
            put("triggerEnabled", triggerEnabled)
            put("triggerReactionSpeed", triggerReactionSpeed)
            put("triggerCooldown", triggerCooldown)
            put("triggerUpFluctuation", triggerUpFluctuation)
            put("triggerDownFluctuation", triggerDownFluctuation)
            put("triggerTouchDuration", triggerTouchDuration)
            put("triggerTouchRange", triggerTouchRange)
            put("triggerOffsetYRatio", triggerOffsetYRatio.toDouble())
            put("recordEnabled", recordEnabled)
            put("autoSaveDataset", autoSaveDataset)
            put("recoilEnabled", recoilEnabled)
            put("recoilStrength", recoilStrength.toDouble())
            put("kalmanPredictEnabled", kalmanPredictEnabled)
            put("kalmanMaxMissed", kalmanMaxMissed)
            put("kalmanProcessNoise", kalmanProcessNoise.toDouble())
            put("kalmanMeasureNoise", kalmanMeasureNoise.toDouble())
            put("kalmanBoxSmooth", kalmanBoxSmooth.toDouble())
            put("kalmanMatchIouThreshold", kalmanMatchIouThreshold.toDouble())
            put("targetLostTolerance", targetLostTolerance)
            put("lockBoxThreshold", lockBoxThreshold.toDouble())
            put("lockCenterWeight", lockCenterWeight.toDouble())
            put("moveSmooth", moveSmooth.toDouble())
            put("deadzoneHoldFrames", deadzoneHoldFrames)
            put("edgeReturnStrength", edgeReturnStrength.toDouble())
            put("autoStopEnabled", autoStopEnabled)
            put("autoTriggerAdsEnabled", autoTriggerAdsEnabled)
            put("autoTriggerAdsRange", autoTriggerAdsRange.toDouble())
            put("touchOrientationMode", touchOrientationMode)
            put("boxAimRatio", boxAimRatio.toDouble())
            put("priorityClass", priorityClass)
            put("hasMultipleClasses", classMap.size > 1)
            put("classEntries", buildClassEntries())
        }
    }

    private fun buildClassEntries(): JSONArray {
        val aimSelected = effectiveSelection(aimClasses)
        val triggerSelected = effectiveSelection(triggerClasses)
        val entries = JSONArray()
        for ((id, name) in classMap.entries.sortedBy { it.key }) {
            entries.put(
                JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("aimSelected", id in aimSelected)
                    put("triggerSelected", id in triggerSelected)
                    put("showDetectionSelected", id in (if (showDetectionClassIds.isEmpty()) classMap.keys else showDetectionClassIds))
                    put("prioritySelected", priorityClass == id)
                    put("aimOffset", (classAimOffsets[id] ?: 0f).toDouble())
                    put("boxAimRatio", (classBoxAimRatios[id] ?: 0.5f).toDouble())
                    put("triggerOffset", (classTriggerOffsets[id] ?: 0f).toDouble())
                }
            )
        }
        return entries
    }

    private fun effectiveSelection(source: Set<Int>): Set<Int> {
        return if (source.isEmpty()) classMap.keys.toSet() else source
    }

    private fun updateShowDetectionClass(id: Int, enabled: Boolean) {
        if (showDetectionClassIds.isEmpty()) {
            showDetectionClassIds = classMap.keys.toMutableSet()
        }
        if (enabled) showDetectionClassIds = showDetectionClassIds + id
        else showDetectionClassIds = showDetectionClassIds - id
        onShowDetectionClassIdsChanged?.invoke(showDetectionClassIds.toSet())
        pushState()
    }

    private fun updateAimClass(id: Int, enabled: Boolean) {
        val allIds = classMap.keys.sorted()
        if (allIds.size <= 1) return
        if (aimClasses.isEmpty()) aimClasses = allIds.toMutableSet()
        if (!enabled && aimClasses.size <= 1 && id in aimClasses) return
        if (enabled) aimClasses.add(id) else aimClasses.remove(id)
        onAimClassesChanged?.invoke(aimClasses.toSet())
        pushState()
    }

    private fun updateTriggerClass(id: Int, enabled: Boolean) {
        val allIds = classMap.keys.sorted()
        if (allIds.size <= 1) return
        if (triggerClasses.isEmpty()) triggerClasses = allIds.toMutableSet()
        if (!enabled && triggerClasses.size <= 1 && id in triggerClasses) return
        if (enabled) triggerClasses.add(id) else triggerClasses.remove(id)
        onTriggerClassesChanged?.invoke(triggerClasses.toSet())
        pushState()
    }

    private inner class FloatMenuBridge {

        @JavascriptInterface
        fun closePanel() {
            post { onClose?.invoke() }
        }

        @JavascriptInterface
        fun toggleModelRunning() {
            post {
                modelRunning = !modelRunning
                onToggleModel?.invoke(modelRunning)
                pushState()
            }
        }

        @JavascriptInterface
        fun setTab(index: Int) {
            post {
                activeTab = index.coerceIn(0, 3)
            }
        }

        @JavascriptInterface
        fun runAction(action: String) {
            post {
                when (action) {
                    "testCircle" -> onTestCircle?.invoke()
                    "openAreaSettings" -> onAreaSettingsToggle?.invoke()
                }
            }
        }

        @JavascriptInterface
        fun selectModel(index: Int) {
            post {
                val safeIndex = index.coerceIn(0, (modelNames.size - 1).coerceAtLeast(0))
                if (modelIndex != safeIndex) {
                    modelIndex = safeIndex
                    onModelSelected?.invoke(safeIndex)
                }
                pushState()
            }
        }

        @JavascriptInterface
        fun setPriorityClass(classId: Int) {
            post {
                priorityClass = classId
                onPriorityClassChanged?.invoke(classId)
                pushState()
            }
        }

        @JavascriptInterface
        fun setAimClass(classId: Int, enabled: Boolean) {
            post { updateAimClass(classId, enabled) }
        }

        @JavascriptInterface
        fun setTriggerClass(classId: Int, enabled: Boolean) {
            post { updateTriggerClass(classId, enabled) }
        }

        @JavascriptInterface
        fun setShowDetectionClass(classId: Int, enabled: Boolean) {
            post { updateShowDetectionClass(classId, enabled) }
        }

        @JavascriptInterface
        fun setClassAimOffset(classId: Int, value: Float) {
            post {
                classAimOffsets[classId] = value
                onClassAimOffsetChanged?.invoke(classId, value)
            }
        }

        @JavascriptInterface
        fun setClassBoxAimRatio(classId: Int, value: Float) {
            post {
                classBoxAimRatios[classId] = value
                onClassBoxAimRatioChanged?.invoke(classId, value)
            }
        }

        @JavascriptInterface
        fun setClassTriggerOffset(classId: Int, value: Float) {
            post {
                classTriggerOffsets[classId] = value
                onClassTriggerOffsetChanged?.invoke(classId, value)
            }
        }

        @JavascriptInterface
        fun setBoolean(key: String, value: Boolean) {
            post {
                when (key) {
                    "aimbotEnabled" -> {
                        aimbotEnabled = value
                        onEnabledChanged?.invoke(value)
                    }
                    "aimHoldEnabled" -> {
                        aimHoldEnabled = value
                        onAimHoldEnabled?.invoke(value)
                    }
                    "recoilEnabled" -> {
                        recoilEnabled = value
                        onRecoilEnabledChanged?.invoke(value)
                    }
                    "triggerEnabled" -> {
                        triggerEnabled = value
                        onTriggerEnabled?.invoke(value)
                    }
                    "autoStopEnabled" -> {
                        autoStopEnabled = value
                        onAutoStopEnabledChanged?.invoke(value)
                    }
                    "autoTriggerAdsEnabled" -> {
                        autoTriggerAdsEnabled = value
                        onAutoTriggerAdsEnabledChanged?.invoke(value)
                        pushState()
                    }
                    "showCaptureRange" -> {
                        showCaptureRange = value
                        onShowCaptureRangeChanged?.invoke(value)
                    }
                    "showDetectionBox" -> {
                        showDetectionBox = value
                        onShowDetectionBoxChanged?.invoke(value)
                    }
                    "showCenterDot" -> {
                        showCenterDot = value
                        onShowCenterDotChanged?.invoke(value)
                    }
                    "showLockRay" -> {
                        showLockRay = value
                        onShowLockRayChanged?.invoke(value)
                    }
                    "recordEnabled" -> {
                        recordEnabled = value
                        onRecordEnabledChanged?.invoke(value)
                    }
                    "autoSaveDataset" -> {
                        autoSaveDataset = value
                        onAutoSaveDatasetChanged?.invoke(value)
                    }
                    "kalmanPredictEnabled" -> {
                        kalmanPredictEnabled = value
                        onKalmanPredictEnabledChanged?.invoke(value)
                        pushState()
                    }
                    "aimTouchDisplay" -> {
                        aimTouchDisplay = value
                        onAimTouchDisplay?.invoke(value)
                    }
                }
            }
        }

        @JavascriptInterface
        fun setInt(key: String, value: Int) {
            post {
                when (key) {
                    "range" -> {
                        range = value
                        onRangeChanged?.invoke(value)
                    }
                    "triggerReactionSpeed" -> {
                        triggerReactionSpeed = value
                        onTriggerReactionSpeed?.invoke(value)
                    }
                    "triggerCooldown" -> {
                        triggerCooldown = value
                        onTriggerCooldown?.invoke(value)
                    }
                    "triggerUpFluctuation" -> {
                        triggerUpFluctuation = value
                        onTriggerUpFluctuation?.invoke(value)
                    }
                    "triggerDownFluctuation" -> {
                        triggerDownFluctuation = value
                        onTriggerDownFluctuation?.invoke(value)
                    }
                    "triggerTouchDuration" -> {
                        triggerTouchDuration = value
                        onTriggerTouchDuration?.invoke(value)
                    }
                    "aimSwayAmplitude" -> {
                        aimSwayAmplitude = value
                        onAimSwayAmplitudeChanged?.invoke(value)
                    }
                    "aimMode" -> {
                        aimMode = value
                        onAimModeChanged?.invoke(value)
                        pushState()
                    }
                    "bezierDuration" -> {
                        bezierDuration = value
                        onBezierDurationChanged?.invoke(value)
                    }
                    "kalmanMaxMissed" -> {
                        kalmanMaxMissed = value
                        onKalmanMaxMissedChanged?.invoke(value)
                    }
                    "convergeThresh" -> {
                        convergeThresh = value
                        onConvergeThreshChanged?.invoke(value)
                    }
                    "targetLostTolerance" -> {
                        targetLostTolerance = value
                        onTargetLostToleranceChanged?.invoke(value)
                    }
                    "deadzoneHoldFrames" -> {
                        deadzoneHoldFrames = value
                        onDeadzoneHoldFramesChanged?.invoke(value)
                    }
                    "pidSamplePeriodMs" -> {
                        pidSamplePeriodMs = value
                        onPidSamplePeriodMsChanged?.invoke(value)
                    }
                    "touchOrientationMode" -> {
                        touchOrientationMode = value
                        onTouchOrientationModeChanged?.invoke(value)
                        pushState()
                    }
                    "aimTouchSize" -> {
                        aimTouchSize = value
                        onAimTouchSize?.invoke(value)
                    }
                }
            }
        }

        @JavascriptInterface
        fun setFloat(key: String, value: Float) {
            post {
                when (key) {
                    "speed" -> {
                        speed = value
                        onSpeedChanged?.invoke(value)
                    }
                    "confidence" -> {
                        confidence = value
                        onConfidenceChanged?.invoke(value)
                    }
                    "recoilStrength" -> {
                        recoilStrength = value
                        onRecoilStrengthChanged?.invoke(value)
                    }
                    "ki" -> {
                        ki = value
                        onKiChanged?.invoke(value)
                    }
                    "kd" -> {
                        kd = value
                        onKdChanged?.invoke(value)
                    }
                    "bezierControlOffset" -> {
                        bezierControlOffset = value
                        onBezierControlOffsetChanged?.invoke(value)
                    }
                    "bezierRandomSpread" -> {
                        bezierRandomSpread = value
                        onBezierRandomSpreadChanged?.invoke(value)
                    }
                    "aimOffsetYRatio" -> {
                        aimOffsetYRatio = value
                        onAimOffsetYRatioChanged?.invoke(value)
                    }
                    "aimPredictionMultiplier" -> {
                        aimPredictionMultiplier = value
                        onAimPredictionMultiplierChanged?.invoke(value)
                    }
                    "boxAimRatio" -> {
                        boxAimRatio = value
                        onBoxAimRatioChanged?.invoke(value)
                    }
                    "autoTriggerAdsRange" -> {
                        autoTriggerAdsRange = value
                        onAutoTriggerAdsRangeChanged?.invoke(value)
                    }
                    "kalmanProcessNoise" -> {
                        kalmanProcessNoise = value
                        onKalmanProcessNoiseChanged?.invoke(value)
                    }
                    "kalmanMeasureNoise" -> {
                        kalmanMeasureNoise = value
                        onKalmanMeasureNoiseChanged?.invoke(value)
                    }
                    "kalmanBoxSmooth" -> {
                        kalmanBoxSmooth = value
                        onKalmanBoxSmoothChanged?.invoke(value)
                    }
                    "kalmanMatchIouThreshold" -> {
                        kalmanMatchIouThreshold = value
                        onKalmanMatchIouThresholdChanged?.invoke(value)
                    }
                    "lockBoxThreshold" -> {
                        lockBoxThreshold = value
                        onLockBoxThresholdChanged?.invoke(value)
                    }
                    "lockCenterWeight" -> {
                        lockCenterWeight = value
                        onLockCenterWeightChanged?.invoke(value)
                    }
                    "moveSmooth" -> {
                        moveSmooth = value
                        onMoveSmoothChanged?.invoke(value)
                    }
                    "edgeReturnStrength" -> {
                        edgeReturnStrength = value
                        onEdgeReturnStrengthChanged?.invoke(value)
                    }
                    "triggerOffsetYRatio" -> {
                        triggerOffsetYRatio = value
                        onTriggerOffsetYRatioChanged?.invoke(value)
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val FLOAT_MENU_ASSET = "file:///android_asset/float_menu.html"
    }
}
