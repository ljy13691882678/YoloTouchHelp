package com.xunlei.ai.service
import com.xunlei.ai.R
import com.xunlei.ai.model.DetectionInfo

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin
import com.xunlei.ai.controller.AimController
import com.xunlei.ai.controller.TriggerController
import com.xunlei.ai.manager.InferenceManager
import com.xunlei.ai.manager.OverlayManager
import com.xunlei.ai.manager.ConfigManager
import com.xunlei.ai.view.OverlayCanvasView
import com.xunlei.ai.view.VolumeKeyController
import com.xunlei.ai.view.TriggerOverlayView
import com.xunlei.ai.view.TouchDisplayView
import com.xunlei.ai.view.AreaSettingsView
import com.xunlei.ai.model.AreaConfig
import com.xunlei.ai.injector.TouchInjectorInterface
import com.xunlei.ai.injector.RootInjectorClient
import com.xunlei.ai.injector.ShizukuInjectorClient
import com.xunlei.ai.injector.InjectorCallback
import com.xunlei.ai.inference.JniCallBack
import com.xunlei.ai.inference.KalmanObjectTracker
import com.xunlei.ai.util.ProjectionHolder

class FloatService : Service() {

    companion object {
        const val TAG = "FloatService"
        const val CH_ID = "aimbot_ch"
        const val TOUCH_ORIENTATION_AUTO = 0
        const val TOUCH_ORIENTATION_RIGHT = 1
        const val TOUCH_ORIENTATION_LEFT = 2
        const val ACTION_SET_PARAM = "com.xunlei.ai.SET_PARAM"
        const val EXTRA_KEY = "param_key"
        const val EXTRA_VALUE = "param_value"
        private const val AIM_TOUCH_INTERVAL_MS = 8L
    }

    private lateinit var wm: WindowManager
    private lateinit var overlayView: OverlayCanvasView
    private var volumeKeyView: VolumeKeyController? = null

    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false

    var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var captureVirtualDisplay: android.hardware.display.VirtualDisplay? = null
    var mediaRecorder: android.media.MediaRecorder? = null
    private var recordSurface: Surface? = null
    var recordEnabled = false
    var autoSaveDataset = false
    private var datasetCounter = -1 // -1 = 未初始化，首次保存时扫描目录
    private val datasetDir: java.io.File by lazy {
        java.io.File(getExternalFilesDir(null), "dataset").apply { mkdirs() }
    }

    private var captureW = 0; private var captureH = 0 // natural display size for ImageReader + coords
    // 使用 Display.getRealSize() 获取完整屏幕尺寸（包括挖孔区域），
    // 避免 displayMetrics 可能受安全区影响
    private val screenSize: Point get() { val p = Point(); wm.defaultDisplay.getRealSize(p); return p }
    private val screenWidth get() = screenSize.x
    private val screenHeight get() = screenSize.y
    private val screenDensity get() = resources.displayMetrics.densityDpi
    private val executor = Executors.newSingleThreadExecutor()
    private val inferRunning = AtomicBoolean(false)
    val aimbotOn = AtomicBoolean(false)
    var isServiceReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val detectionBuffer = Array(20) { DetectionInfo(RectF(), -1, "") }
    private var lastDetections: List<DetectionInfo> = emptyList()
    private var centerX = 0f; private var centerY = 0f
    private var cachedRange = 0f; private var cachedRangePx = 0

    var touchClient: TouchInjectorInterface? = null
    private val safeTouchClient = SafeTouchClient()
    private var currentSpeed = 0.3f; private var currentConfidence = 0.50f
    private var modelRunning = false
    private var lastModelIndex = 0
    var currentClasses: Map<Int, String> = emptyMap()

    // Class filtering for aimbot
    private var aimClasses: MutableSet<Int> = mutableSetOf() // empty = all
    private var priorityClass: Int = -1
    private var classAimOffsets: Map<Int, Float> = emptyMap() // per-class Y offset
    private var boxAimRatio = 0.5f // 0=top, 0.5=center, 1=bottom
    private var classBoxAimRatios: Map<Int, Float> = emptyMap() // per-class box aim ratio
    private var classTriggerOffsets: Map<Int, Float> = emptyMap() // per-class trigger Y offset
    private var triggerClasses: MutableSet<Int> = mutableSetOf() // empty = all

    // PID auto-aim state
    private var aimOffsetYRatio = 0f; private var aimSwayAmplitude = 0
    private var aimPredictionMultiplier = 0.7f; private var triggerOffsetYRatio = 0f
    private var kp = 0.015f; private var ki = 0f; private var kd = 0.008f
    private var pidSamplePeriodMs = 8
    private var aimHoldEnabled = false
    private var recoilEnabled = false
    private var recoilStrength = 0f
    private var kalmanPredictEnabled = false
    private var kalmanMaxMissed = 5
    private var kalmanProcessNoise = 70f
    private var kalmanMeasureNoise = 110f
    private var kalmanBoxSmooth = 0.60f
    private var kalmanMatchIouThreshold = 0.20f
    private var showLockRay = false
    private var showDetectionClassIds: MutableSet<Int> = mutableSetOf()
    private var targetLostTolerance = 2
    private var lockBoxThreshold = 0.35f
    private var lockCenterWeight = 0.3f
    private var moveSmooth = 0.35f
    private var deadzoneHoldFrames = 3
    private var edgeReturnStrength = 0.25f

    // Bezier aim state
    private var aimMode = 0 // 0=PID, 1=Bezier
    private var bezierDuration = 30; private var bezierControlOffset = 0.3f; private var bezierRandomSpread = 0.1f
    private var convergeThresh = 10f

    // Hold-to-fire (按住激发) state — uses trigger slot, separate from aim slot
    // Touch display overlay

    // FPS 统计
    private var fpsFrameCount = 0
    private var fpsLastTime = 0L
    private var currentFps = 0f
    private var currentTemperature = ""

    // 温度读取
    private val thermalZones = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone5/temp"  // common CPU sensor
    )
    private fun readCpuTemperature(): String {
        for (path in thermalZones) {
            try {
                val temp = java.io.File(path).readText().trim().toIntOrNull()
                if (temp != null && temp in 100..150000) {
                    return "${"%.1f".format(temp / 1000f)}°C"
                }
            } catch (_: Exception) {}
        }
        return ""
    }
    private var touchDisplayEnabled = false
    private var touchDisplayView: TouchDisplayView? = null
    private var touchDisplayAdded = false

    // Area settings overlay
    private var areaSettingsView: AreaSettingsView? = null
    private var areaSettingsAdded = false
    private val savedAreas = mutableListOf<AreaConfig>()

    // Area index constants — magic number prevention
    private val AREA_INDEX_FIRE = 0
    private val AREA_INDEX_TRIGGER = 1
    private val AREA_INDEX_AIM = 2
    private val AREA_INDEX_JOYSTICK = 3
    private val AREA_INDEX_ADS = 4

    // Device resolution for uinput — auto-detected by detect_touch_device() in native code.
    // Hardcoded defaults are NOT used; pass placeholder 0 values.
    var deviceAbsMaxX = 0
    var deviceAbsMaxY = 0

    private var triggerEnabled = false; private var triggerReactionSpeed = 100; private var triggerCooldown = 200
    private var triggerUpFluct = 3; private var triggerDownFluct = 3
    private var triggerTouchDuration = 10; private var triggerTouchRange = 100; private var triggerShowArea = false
    private var autoStopEnabled = false
    private var autoTriggerAdsEnabled = false
    private var autoTriggerAdsRange = 180f
    private var touchOrientationMode = TOUCH_ORIENTATION_AUTO

private var triggerOverlay: TriggerOverlayView? = null
    private var triggerOverlayAdded = false
    private var triggerAreaX = 0; private var triggerAreaY = 0
    private var lastTriggerMs = 0L
    private var triggerFired = false // 扳机是否已射过第一发（第二发起用冷却时间）
    val hasDetects = AtomicBoolean(false)

    // Controllers and Managers
    private lateinit var aimController: AimController
    private lateinit var triggerController: TriggerController
    private lateinit var inferenceManager: InferenceManager
    private lateinit var overlayManager: OverlayManager
    private val kalmanTracker = KalmanObjectTracker()

    override fun onCreate() {
        super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ConfigManager.init(this); loadConfigToService()
        createNotificationChannel(); startForeground(1, buildNotification())
        initControllers()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun initControllers() {
        aimController = AimController(
            service = this,
            touchClient = { safeTouchClient },
            savedAreas = { savedAreas }
        )
        triggerController = TriggerController(
            context = this,
            wm = wm,
            touchClient = { safeTouchClient },
            savedAreas = { savedAreas },
            screenWidth = { screenWidth },
            screenHeight = { screenHeight },
            dp = { dp(it) }
        )
        inferenceManager = InferenceManager(
            service = this,
            aimController = aimController,
            triggerController = triggerController,
            overlayCanvasView = { if (overlayAdded) overlayView else null }
        )
        overlayManager = OverlayManager(
            context = this,
            wm = wm,
            screenWidth = { screenWidth },
            screenHeight = { screenHeight },
            dp = { dp(it) }
        )
        // Load config to controllers
        loadConfigToControllers()
    }

    private fun loadConfigToControllers() {
        // AimController
        aimController.kp = kp
        aimController.ki = ki
        aimController.kd = kd
        aimController.pidSamplePeriodMs = pidSamplePeriodMs
        aimController.aimMode = aimMode
        aimController.bezierDuration = bezierDuration
        aimController.bezierControlOffset = bezierControlOffset
        aimController.bezierRandomSpread = bezierRandomSpread
        aimController.convergeThresh = convergeThresh
        aimController.aimOffsetYRatio = aimOffsetYRatio
        aimController.aimSwayAmplitude = aimSwayAmplitude
        aimController.aimPredictionMultiplier = aimPredictionMultiplier
        aimController.aimHoldEnabled = aimHoldEnabled
        aimController.recoilEnabled = recoilEnabled
        aimController.recoilStrength = recoilStrength
        aimController.showLockRay = showLockRay
        aimController.targetLostTolerance = targetLostTolerance
        aimController.lockBoxThreshold = lockBoxThreshold
        aimController.lockCenterWeight = lockCenterWeight
        aimController.moveSmooth = moveSmooth
        aimController.deadzoneHoldFrames = deadzoneHoldFrames
        aimController.edgeReturnStrength = edgeReturnStrength
        aimController.aimClasses = aimClasses.toMutableSet()
        aimController.priorityClass = priorityClass
        aimController.classAimOffsets = classAimOffsets
        aimController.boxAimRatio = boxAimRatio
        aimController.classBoxAimRatios = classBoxAimRatios

        // TriggerController
        triggerController.triggerEnabled = triggerEnabled
        triggerController.triggerReactionSpeed = triggerReactionSpeed
        triggerController.triggerCooldown = triggerCooldown
        triggerController.triggerUpFluct = triggerUpFluct
        triggerController.triggerDownFluct = triggerDownFluct
        triggerController.triggerTouchDuration = triggerTouchDuration
        triggerController.triggerTouchRange = triggerTouchRange
        triggerController.triggerShowArea = triggerShowArea
        triggerController.autoStopEnabled = autoStopEnabled
        triggerController.autoTriggerAdsEnabled = autoTriggerAdsEnabled
        triggerController.autoTriggerAdsRange = autoTriggerAdsRange
        triggerController.triggerOffsetYRatio = triggerOffsetYRatio
        triggerController.triggerClasses = triggerClasses.toMutableSet()
        triggerController.classTriggerOffsets = classTriggerOffsets

        applyKalmanConfig(resetTracker = false)
    }

    private fun applyKalmanConfig(resetTracker: Boolean = true) {
        kalmanTracker.maxMissed = kalmanMaxMissed
        kalmanTracker.processNoise = kalmanProcessNoise
        kalmanTracker.measureNoise = kalmanMeasureNoise
        kalmanTracker.boxSmooth = kalmanBoxSmooth
        kalmanTracker.matchIouThreshold = kalmanMatchIouThreshold
        if (resetTracker) kalmanTracker.reset()
    }

    private fun loadConfigToService() {
        val cfg = ConfigManager.getConfig()
        kp = cfg.speed; currentSpeed = cfg.speed
        pidSamplePeriodMs = cfg.pidSamplePeriodMs
        currentConfidence = cfg.confidence
        triggerEnabled = cfg.triggerEnabled
        triggerReactionSpeed = cfg.triggerReactionSpeed
        triggerCooldown = cfg.triggerCooldown
        triggerUpFluct = cfg.triggerUpFluctuation
        triggerDownFluct = cfg.triggerDownFluctuation
        triggerTouchDuration = cfg.triggerTouchDuration
        triggerTouchRange = cfg.triggerTouchRange
        triggerShowArea = cfg.triggerShowArea
        autoStopEnabled = cfg.autoStopEnabled
        touchOrientationMode = cfg.touchOrientationMode
        aimHoldEnabled = cfg.aimHoldEnabled
        recoilEnabled = cfg.recoilEnabled
        recoilStrength = cfg.recoilStrength
        kalmanPredictEnabled = cfg.kalmanPredictEnabled
        kalmanMaxMissed = cfg.kalmanMaxMissed
        kalmanProcessNoise = cfg.kalmanProcessNoise
        kalmanMeasureNoise = cfg.kalmanMeasureNoise
        kalmanBoxSmooth = cfg.kalmanBoxSmooth
        kalmanMatchIouThreshold = cfg.kalmanMatchIouThreshold
        aimPredictionMultiplier = cfg.aimPredictionMultiplier
        aimOffsetYRatio = cfg.aimOffsetYRatio
        aimSwayAmplitude = cfg.aimSwayAmplitude
        showLockRay = cfg.showLockRay
        showDetectionClassIds = cfg.showDetectionClassIds.toMutableSet()
        targetLostTolerance = cfg.targetLostTolerance
        lockBoxThreshold = cfg.lockBoxThreshold
        lockCenterWeight = cfg.lockCenterWeight
        moveSmooth = cfg.moveSmooth
        deadzoneHoldFrames = cfg.deadzoneHoldFrames
        edgeReturnStrength = cfg.edgeReturnStrength
        triggerOffsetYRatio = cfg.triggerOffsetYRatio
        autoTriggerAdsEnabled = cfg.autoTriggerAdsEnabled
        autoTriggerAdsRange = cfg.autoTriggerAdsRange
        ki = cfg.ki; kd = cfg.kd

        aimMode = cfg.aimMode
        bezierDuration = cfg.bezierDuration
        bezierControlOffset = cfg.bezierControlOffset
        bezierRandomSpread = cfg.bezierRandomSpread
        convergeThresh = cfg.convergeThresh.toFloat()
        touchDisplayEnabled = cfg.aimTouchDisplay
        cachedRangePx = cfg.range.coerceIn(50, 800)
        aimbotOn.set(cfg.aimbotEnabled)
        aimClasses = cfg.aimClasses.toMutableSet()
        priorityClass = cfg.priorityClass
        classAimOffsets = cfg.classAimOffsets
        boxAimRatio = cfg.boxAimRatio
        classBoxAimRatios = cfg.classBoxAimRatios
        classTriggerOffsets = cfg.classTriggerOffsets
        triggerClasses = cfg.triggerClasses.toMutableSet()
        savedAreas.clear()
        savedAreas.addAll(cfg.areas)
        // 确保有5个区域（兼容旧配置）
        while (savedAreas.size < 5) {
            savedAreas.add(AreaConfig(
                name = when (savedAreas.size) {
                    0 -> "开火区"; 1 -> "触发区"; 2 -> "瞄准区"; 3 -> "摇杆范围"
                    else -> "开镜区"
                },
                color = when (savedAreas.size) {
                    1 -> android.graphics.Color.parseColor("#FF1976D2")
                    3 -> android.graphics.Color.parseColor("#FF4CAF50")
                    4 -> android.graphics.Color.parseColor("#FFFF9800")
                    else -> android.graphics.Color.WHITE
                }
            ))
        }

        JniCallBack.setConfidence(cfg.confidence)
        val cfgIdx = cfg.modelIndex
        if (cfgIdx !in 0 until ProjectionHolder.modelList.size) {
            ConfigManager.updateConfig { modelIndex = 0 }
        }
        ProjectionHolder.selectedModelIndex = if (cfgIdx in 0 until ProjectionHolder.modelList.size) cfgIdx else 0
    }

    private fun handleParamChange(intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val valueStr = intent.getStringExtra(EXTRA_VALUE) ?: return
        if (!isServiceReady) {
            // Service not ready yet - parameter saved to ConfigManager by caller, will apply on start
            return
        }
        if (!::overlayView.isInitialized) return
        mainHandler.post {
            try {
                when (key) {
                    "aimbotEnabled" -> {
                        val on = valueStr.toBoolean()
                        aimbotOn.set(on); overlayView.aimbotEnabled = on
                        ConfigManager.updateConfig { aimbotEnabled = on }
                        if (on) { aimController.aimingState.maxDragDist = (screenWidth.coerceAtMost(screenHeight) * 0.2f).coerceIn(100f, 600f) }
                        else { clearAimSession("aimbot_disabled", clearVisualTargets = false) }
                    }
                    "speed" -> { kp = valueStr.toFloat(); currentSpeed = kp; aimController.kp = kp; ConfigManager.updateConfig { speed = kp } }
                    "range" -> { val px = valueStr.toInt(); overlayView.rangeRadius = px; overlayView.postInvalidate(); ConfigManager.updateConfig { range = px } }
                    "confidence" -> { currentConfidence = valueStr.toFloat(); JniCallBack.setConfidence(currentConfidence); ConfigManager.updateConfig { confidence = currentConfidence } }
                    "triggerEnabled" -> { triggerEnabled = valueStr.toBoolean(); triggerController.triggerEnabled = triggerEnabled; ConfigManager.updateConfig { triggerEnabled = triggerEnabled } }
                    "triggerReactionSpeed" -> { triggerReactionSpeed = valueStr.toInt(); triggerController.triggerReactionSpeed = triggerReactionSpeed; ConfigManager.updateConfig { triggerReactionSpeed = triggerReactionSpeed } }
                    "triggerCooldown" -> { triggerCooldown = valueStr.toInt(); triggerController.triggerCooldown = triggerCooldown; ConfigManager.updateConfig { triggerCooldown = triggerCooldown } }
                    "recoilEnabled" -> { recoilEnabled = valueStr.toBoolean(); aimController.recoilEnabled = recoilEnabled; ConfigManager.updateConfig { recoilEnabled = recoilEnabled } }
                    "recoilStrength" -> { recoilStrength = valueStr.toFloat(); aimController.recoilStrength = recoilStrength; ConfigManager.updateConfig { recoilStrength = recoilStrength } }
                    "aimMode" -> { aimMode = valueStr.toInt(); aimController.aimMode = aimMode; ConfigManager.updateConfig { aimMode = aimMode } }
                    "aimHoldEnabled" -> { aimHoldEnabled = valueStr.toBoolean(); aimController.aimHoldEnabled = aimHoldEnabled; ConfigManager.updateConfig { aimHoldEnabled = aimHoldEnabled } }
                    "kalmanPredictEnabled" -> { kalmanPredictEnabled = valueStr.toBoolean(); if (!kalmanPredictEnabled) kalmanTracker.reset(); ConfigManager.updateConfig { kalmanPredictEnabled = kalmanPredictEnabled } }
                    "modelRunning" -> {
                        val newState = if (valueStr == "toggle") !modelRunning else valueStr.toBoolean()
                        modelRunning = newState
                        if (modelRunning && !inferRunning.get()) startInferLoop()
                        else if (!modelRunning) { if (!recordEnabled) { inferRunning.set(false); broadcastState(1) } }
                        // Broadcast state change so MainActivity can update UI
                        broadcastState(if (modelRunning && inferRunning.get()) 2 else 1,
                            ProjectionHolder.currentModelName)
                    }
                    "showCaptureRange" -> { overlayView.showCaptureRange = valueStr.toBoolean(); overlayView.postInvalidate(); ConfigManager.updateConfig { showCaptureRange = valueStr.toBoolean() } }
                    "showDetectionBox" -> { overlayView.showDetectionBox = valueStr.toBoolean(); overlayView.postInvalidate(); ConfigManager.updateConfig { showDetectionBox = valueStr.toBoolean() } }
                    "showCenterDot" -> { overlayView.showCenterDot = valueStr.toBoolean(); overlayView.postInvalidate(); ConfigManager.updateConfig { showCenterDot = valueStr.toBoolean() } }
                    "showLockRay" -> { showLockRay = valueStr.toBoolean(); aimController.showLockRay = showLockRay; overlayView.showLockRay = showLockRay; if (!showLockRay) overlayView.updateLockRay(null, null); ConfigManager.updateConfig { showLockRay = showLockRay } }
                    "aimOffsetYRatio" -> { aimOffsetYRatio = valueStr.toFloat(); aimController.aimOffsetYRatio = aimOffsetYRatio; ConfigManager.updateConfig { aimOffsetYRatio = aimOffsetYRatio } }
                    "triggerOffsetYRatio" -> { triggerOffsetYRatio = valueStr.toFloat(); triggerController.triggerOffsetYRatio = triggerOffsetYRatio; ConfigManager.updateConfig { triggerOffsetYRatio = triggerOffsetYRatio } }
                    "ki" -> { ki = valueStr.toFloat(); aimController.ki = ki; ConfigManager.updateConfig { ki = ki } }
                    "kd" -> { kd = valueStr.toFloat(); aimController.kd = kd; ConfigManager.updateConfig { kd = kd } }
                    "pidSamplePeriodMs" -> { pidSamplePeriodMs = valueStr.toInt(); aimController.pidSamplePeriodMs = pidSamplePeriodMs; ConfigManager.updateConfig { pidSamplePeriodMs = pidSamplePeriodMs } }
                    "bezierDuration" -> { bezierDuration = valueStr.toInt(); aimController.bezierDuration = bezierDuration; ConfigManager.updateConfig { bezierDuration = bezierDuration } }
                    "bezierControlOffset" -> { bezierControlOffset = valueStr.toFloat(); aimController.bezierControlOffset = bezierControlOffset; ConfigManager.updateConfig { bezierControlOffset = bezierControlOffset } }
                    "bezierRandomSpread" -> { bezierRandomSpread = valueStr.toFloat(); aimController.bezierRandomSpread = bezierRandomSpread; ConfigManager.updateConfig { bezierRandomSpread = bezierRandomSpread } }
                    "convergeThresh" -> { convergeThresh = valueStr.toFloat(); aimController.convergeThresh = convergeThresh; ConfigManager.updateConfig { convergeThresh = valueStr.toInt() } }
                    "targetLostTolerance" -> { targetLostTolerance = valueStr.toInt(); aimController.targetLostTolerance = targetLostTolerance; ConfigManager.updateConfig { targetLostTolerance = targetLostTolerance } }
                    "lockBoxThreshold" -> { lockBoxThreshold = valueStr.toFloat(); aimController.lockBoxThreshold = lockBoxThreshold; ConfigManager.updateConfig { lockBoxThreshold = lockBoxThreshold } }
                    "lockCenterWeight" -> { lockCenterWeight = valueStr.toFloat(); aimController.lockCenterWeight = lockCenterWeight; ConfigManager.updateConfig { lockCenterWeight = lockCenterWeight } }
                    "moveSmooth" -> { moveSmooth = valueStr.toFloat(); aimController.moveSmooth = moveSmooth; ConfigManager.updateConfig { moveSmooth = moveSmooth } }
                    "deadzoneHoldFrames" -> { deadzoneHoldFrames = valueStr.toInt(); aimController.deadzoneHoldFrames = deadzoneHoldFrames; ConfigManager.updateConfig { deadzoneHoldFrames = deadzoneHoldFrames } }
                    "edgeReturnStrength" -> { edgeReturnStrength = valueStr.toFloat(); aimController.edgeReturnStrength = edgeReturnStrength; ConfigManager.updateConfig { edgeReturnStrength = edgeReturnStrength } }
                    "autoStopEnabled" -> { autoStopEnabled = valueStr.toBoolean(); triggerController.autoStopEnabled = autoStopEnabled; ConfigManager.updateConfig { autoStopEnabled = autoStopEnabled } }
                    "autoTriggerAdsEnabled" -> { autoTriggerAdsEnabled = valueStr.toBoolean(); triggerController.autoTriggerAdsEnabled = autoTriggerAdsEnabled; ConfigManager.updateConfig { autoTriggerAdsEnabled = autoTriggerAdsEnabled } }
                    "autoTriggerAdsRange" -> { autoTriggerAdsRange = valueStr.toFloat(); triggerController.autoTriggerAdsRange = autoTriggerAdsRange; ConfigManager.updateConfig { autoTriggerAdsRange = autoTriggerAdsRange } }
                    "touchOrientationMode" -> { touchOrientationMode = valueStr.toInt(); applyTouchOrientationConfig(); ConfigManager.updateConfig { touchOrientationMode = touchOrientationMode } }
                    "kalmanMaxMissed" -> { kalmanMaxMissed = valueStr.toInt(); applyKalmanConfig(); ConfigManager.updateConfig { kalmanMaxMissed = kalmanMaxMissed } }
                    "kalmanProcessNoise" -> { kalmanProcessNoise = valueStr.toFloat(); applyKalmanConfig(); ConfigManager.updateConfig { kalmanProcessNoise = kalmanProcessNoise } }
                    "kalmanMeasureNoise" -> { kalmanMeasureNoise = valueStr.toFloat(); applyKalmanConfig(); ConfigManager.updateConfig { kalmanMeasureNoise = kalmanMeasureNoise } }
                    "kalmanBoxSmooth" -> { kalmanBoxSmooth = valueStr.toFloat(); applyKalmanConfig(); ConfigManager.updateConfig { kalmanBoxSmooth = kalmanBoxSmooth } }
                    "kalmanMatchIouThreshold" -> { kalmanMatchIouThreshold = valueStr.toFloat(); applyKalmanConfig(); ConfigManager.updateConfig { kalmanMatchIouThreshold = kalmanMatchIouThreshold } }
                    "aimPredictionMultiplier" -> { aimPredictionMultiplier = valueStr.toFloat(); aimController.aimPredictionMultiplier = aimPredictionMultiplier; ConfigManager.updateConfig { aimPredictionMultiplier = aimPredictionMultiplier } }
                    "boxAimRatio" -> { boxAimRatio = valueStr.toFloat(); aimController.boxAimRatio = boxAimRatio; ConfigManager.updateConfig { boxAimRatio = boxAimRatio } }
                    "priorityClass" -> {
                        priorityClass = valueStr.toIntOrNull() ?: -1
                        aimController.priorityClass = priorityClass
                        ConfigManager.updateConfig { priorityClass = this@FloatService.priorityClass }
                    }
                    else -> {
                        // Class-related parameters (prefixed keys)
                        when {
                            key.startsWith("aimClass_") -> {
                                val classId = key.removePrefix("aimClass_").toIntOrNull() ?: return@post
                                val enabled = valueStr == "1"
                                val allIds = currentClasses.keys.sorted()
                                if (allIds.size <= 1) return@post
                                if (aimClasses.isEmpty()) aimClasses = allIds.toMutableSet()
                                if (enabled) aimClasses.add(classId) else { if (aimClasses.size > 1) aimClasses.remove(classId) }
                                aimController.aimClasses = aimClasses.toMutableSet()
                                ConfigManager.updateConfig { aimClasses = this@FloatService.aimClasses }
                            }
                            key.startsWith("triggerClass_") -> {
                                val classId = key.removePrefix("triggerClass_").toIntOrNull() ?: return@post
                                val enabled = valueStr == "1"
                                val allIds = currentClasses.keys.sorted()
                                if (allIds.size <= 1) return@post
                                if (triggerClasses.isEmpty()) triggerClasses = allIds.toMutableSet()
                                if (enabled) triggerClasses.add(classId) else { if (triggerClasses.size > 1) triggerClasses.remove(classId) }
                                triggerController.triggerClasses = triggerClasses.toMutableSet()
                                ConfigManager.updateConfig { triggerClasses = this@FloatService.triggerClasses }
                            }
                            key.startsWith("showDetectionClass_") -> {
                                val classId = key.removePrefix("showDetectionClass_").toIntOrNull() ?: return@post
                                val enabled = valueStr == "1"
                                val allIds = currentClasses.keys.sorted()
                                if (showDetectionClassIds.isEmpty()) showDetectionClassIds = allIds.toMutableSet()
                                if (enabled) showDetectionClassIds.add(classId) else { if (showDetectionClassIds.size > 1) showDetectionClassIds.remove(classId) }
                                overlayView.enabledClassIds = showDetectionClassIds.toSet()
                                overlayView.postInvalidate()
                                ConfigManager.updateConfig { showDetectionClassIds = this@FloatService.showDetectionClassIds }
                            }
                            key.startsWith("classAimOffset_") -> {
                                val classId = key.removePrefix("classAimOffset_").toIntOrNull() ?: return@post
                                val value = valueStr.toFloatOrNull() ?: return@post
                                classAimOffsets = classAimOffsets.toMutableMap().apply { put(classId, value) }
                                aimController.classAimOffsets = classAimOffsets
                                ConfigManager.updateConfig { classAimOffsets = this@FloatService.classAimOffsets }
                            }
                            key.startsWith("classBoxAimRatio_") -> {
                                val classId = key.removePrefix("classBoxAimRatio_").toIntOrNull() ?: return@post
                                val value = valueStr.toFloatOrNull() ?: return@post
                                classBoxAimRatios = classBoxAimRatios.toMutableMap().apply { put(classId, value) }
                                aimController.classBoxAimRatios = classBoxAimRatios
                                ConfigManager.updateConfig { classBoxAimRatios = this@FloatService.classBoxAimRatios }
                            }
                            key.startsWith("classTriggerOffset_") -> {
                                val classId = key.removePrefix("classTriggerOffset_").toIntOrNull() ?: return@post
                                val value = valueStr.toFloatOrNull() ?: return@post
                                classTriggerOffsets = classTriggerOffsets.toMutableMap().apply { put(classId, value) }
                                triggerController.classTriggerOffsets = classTriggerOffsets
                                ConfigManager.updateConfig { classTriggerOffsets = this@FloatService.classTriggerOffsets }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleParamChange error: key=$key, value=$valueStr, ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "RELOAD_MODEL") {
            Log.d(TAG, "收到RELOAD_MODEL, useCpuInference=${ConfigManager.getConfig().useCpuInference}")
            val entry = ProjectionHolder.modelList.getOrNull(ProjectionHolder.selectedModelIndex)
            if (entry != null) {
                loadModel(entry.filename)
                Log.d(TAG, "模型重新加载完成 (CPU推理设置变更), 后端=${JniCallBack.getBackend()}")
            }
            return START_STICKY
        }
        if (intent?.action == "SYNC_MODEL") {
            val idx = ProjectionHolder.selectedModelIndex
            if (idx != lastModelIndex) {
                val entry = ProjectionHolder.modelList.getOrNull(idx)
                if (entry != null) {
                    lastModelIndex = idx; loadModel(entry.filename)
                    // GUI panel removed - settings now in main page
                    Log.d(TAG, "同步模型切换: index=$idx, ${entry.displayName}")
                }
            }
            return START_STICKY
        }
        if (intent?.action == "ACTION_TEST_CIRCLE") {
            mainHandler.post { performTestCircle() }
            return START_STICKY
        }
        if (intent?.action == "ACTION_AREA_SETTINGS") {
            mainHandler.post { showAreaSettings() }
            return START_STICKY
        }
        if (intent?.action == "ACTION_AREA_IMAGE") {
            val imagePath = intent.getStringExtra("areaImagePath")
            if (imagePath != null) {
                mainHandler.post { showAreaSettingsWithImage(imagePath) }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_SET_PARAM) {
            handleParamChange(intent)
            return START_STICKY
        }
        if (intent?.action == "STOP") {
            if (mediaRecorder != null) toggleRecording(false)
            inferRunning.set(false); executor.shutdown()
            cleanupViews()
            touchClient?.stopGeteventListener()
            touchClient?.destroyRemote()
            touchClient?.disconnect()
            mediaProjection?.stop()
            try { stopForeground(true) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        val code = ProjectionHolder.resultCode; val data = ProjectionHolder.resultData
        if (data != null) {
            try {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = manager.getMediaProjection(code, data); setupImageReader()
            } catch (e: Exception) { Log.e(TAG, "projection创建失败: ${e.message}") }
        }
        setupOverlay(); setupVolumeKeyController(); initTouchInjector()

        lastModelIndex = ProjectionHolder.selectedModelIndex
        // Load classes map for current model
        val entry = ProjectionHolder.modelList.getOrNull(ProjectionHolder.selectedModelIndex)
        currentClasses = entry?.classes ?: emptyMap()
        if (aimClasses.isEmpty() && currentClasses.isNotEmpty()) aimClasses = currentClasses.keys.toMutableSet()
        if (triggerClasses.isEmpty() && currentClasses.isNotEmpty()) triggerClasses = currentClasses.keys.toMutableSet()
        Log.d(TAG, "启动模型类别: $currentClasses, aimClasses=$aimClasses, triggerClasses=$triggerClasses")

        isServiceReady = true
        ProjectionHolder.updateState(1, JniCallBack.getBackend())
        return START_NOT_STICKY
    }

    private fun broadcastState(state: Int, modelName: String? = null) {
        ProjectionHolder.updateState(state, modelName ?: ProjectionHolder.currentModelName)
    }

    private fun cleanupViews() {

        try { if (overlayAdded) { wm.removeView(overlayView); overlayAdded = false } } catch (_: Exception) {}
        try { if (volumeKeyView != null) { wm.removeView(volumeKeyView); volumeKeyView = null } } catch (_: Exception) {}
        try { if (triggerOverlayAdded) { wm.removeView(triggerOverlay); triggerOverlayAdded = false } } catch (_: Exception) {}
        try { if (touchDisplayAdded) { wm.removeView(touchDisplayView); touchDisplayAdded = false } } catch (_: Exception) {}
        try { if (areaSettingsAdded) { wm.removeView(areaSettingsView); areaSettingsAdded = false } } catch (_: Exception) {}
        isServiceReady = false
    }


    private fun setupOverlay() {
        overlayView = OverlayCanvasView(this)
        ProjectionHolder.overlayCanvasView = overlayView
        val cfg = ConfigManager.getConfig()
        overlayView.aimbotEnabled = cfg.aimbotEnabled
        overlayView.showCaptureRange = cfg.showCaptureRange
        overlayView.showDetectionBox = cfg.showDetectionBox
        overlayView.showCenterDot = cfg.showCenterDot
        overlayView.showLockRay = cfg.showLockRay
        overlayView.rangeRadius = cfg.range.coerceIn(50, 800)
        // 修复：初始化enabledClassIds，确保各部位显示功能默认生效
        overlayView.enabledClassIds = cfg.showDetectionClassIds.ifEmpty { null }
        overlayParams = makeParams(MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        wm.addView(overlayView, overlayParams); overlayAdded = true
    }

    private fun setupVolumeKeyController() {
        if (volumeKeyView != null) return
        volumeKeyView = VolumeKeyController(this)
        val p = android.view.WindowManager.LayoutParams(
            1, 1,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        p.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        p.x = 0; p.y = 0
        try {
            wm.addView(volumeKeyView, p)
            volumeKeyView!!.isFocusable = true
            volumeKeyView!!.requestFocus()
            volumeKeyView!!.onToggleInference = { start ->
                mainHandler.post {
                    modelRunning = start
                    if (start && !inferRunning.get()) startInferLoop()
                    else if (!start) {
                        if (!recordEnabled) { inferRunning.set(false); broadcastState(1) }
                    }
                    broadcastState(if (start && inferRunning.get()) 2 else 1,
                        ProjectionHolder.currentModelName)
                    if (overlayAdded) overlayView.inferRunning = start
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Volume key controller setup failed", e)
        }
    }

    private fun initTouchInjector() {
        executor.execute {
            val commonCallback = object : InjectorCallback {
                override fun onConnected() {
                    touchClient?.setResolution(screenWidth, screenHeight, deviceAbsMaxX, deviceAbsMaxY)
                    applyTouchOrientationConfig()
                    touchClient?.setInputMethod(ProjectionHolder.selectedTouchMethod)
                    updateTriggerZone(); updateAdsZone(); updateFireZone(); updateJoystickZone()
                    Log.d(TAG, "TouchInjector connected, resolution=${deviceAbsMaxX}x${deviceAbsMaxY}, calling init...")
                    try {
                        val initOk = touchClient?.initRemote() ?: false
                        Log.d(TAG, "RemoteInjector init: $initOk")
                        touchClient?.startGeteventListener()
                    } catch (e: Exception) { Log.e(TAG, "initRemote error: ${e.message}") }
                }
                override fun onDisconnected() { touchClient = null; Log.w(TAG, "TouchInjector disconnected") }
                override fun onError(msg: String) { Log.e(TAG, "TouchInjector error: $msg") }
            }
            // Try root first
            try {
                val rootClient = RootInjectorClient(this@FloatService)
                rootClient.connect(object : InjectorCallback {
                    override fun onConnected() { touchClient = rootClient; commonCallback.onConnected() }
                    override fun onDisconnected() { commonCallback.onDisconnected() }
                    override fun onError(msg: String) {
                        Log.d(TAG, "Root not available ($msg), trying Shizuku...")
                        // Fallback to Shizuku
                        try {
                            val shizukuClient = ShizukuInjectorClient(this@FloatService)
                            shizukuClient.connect(object : InjectorCallback {
                                override fun onConnected() { touchClient = shizukuClient; commonCallback.onConnected() }
                                override fun onDisconnected() { commonCallback.onDisconnected() }
                                override fun onError(msg2: String) { Log.e(TAG, "Shizuku also failed: $msg2"); commonCallback.onError("Both root and Shizuku failed") }
                            })
                        } catch (e: Exception) { Log.e(TAG, "Shizuku init failed: ${e.message}") }
                    }
                })
            } catch (e: Exception) { Log.e(TAG, "Root init failed: ${e.message}") }
        }
    }

    private fun toggleRecording(enabled: Boolean) {
        if (enabled) {
            if (mediaRecorder != null) return
            try {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", java.util.Locale.US).format(java.util.Date())
                val random = (1000..9999).random()
                val dir = java.io.File("/storage/emulated/0/Pictures/Screenshots")
                if (!dir.exists()) dir.mkdirs()
                val outputFile = java.io.File(dir, "XunleiAI_${timestamp}_$random.mp4")
                val mr = android.media.MediaRecorder()
                mr.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                mr.setOutputFile(outputFile.absolutePath)
                mr.setVideoEncodingBitRate(32_000_000)
                mr.setVideoFrameRate(60)
                mr.setVideoSize(captureW, captureH)
                mr.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.HEVC)
                mr.prepare()
                recordSurface = mr.surface
                mr.start()
                mediaRecorder = mr
                recordEnabled = true
                // 开录屏时自动启动推理循环
                if (!inferRunning.get()) startInferLoop()
                Log.d(TAG, "Recording started: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null; recordSurface = null
            }
        } else {
            recordEnabled = false
            try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) { Log.e(TAG, "Stop failed", e) }
            mediaRecorder = null; recordSurface = null
            // 如果模型没在运行，停止推理循环
            if (!modelRunning) { inferRunning.set(false); broadcastState(1) }
            Log.d(TAG, "Recording stopped")
        }
    }

    private fun saveDatasetFrame(hwBuf: android.hardware.HardwareBuffer, result: FloatArray, count: Int) {
        try {
            if (datasetCounter < 0) {
                datasetCounter = datasetDir.listFiles { f -> f.name.endsWith(".jpg") }
                    ?.mapNotNull { f -> f.nameWithoutExtension.toIntOrNull() }?.maxOrNull()?.let { it + 1 } ?: 0
            }
            val bmp = Bitmap.wrapHardwareBuffer(hwBuf, null) ?: return
            val idx = datasetCounter++; val name = "%06d".format(idx)
            val imgFile = java.io.File(datasetDir, "$name.jpg")
            java.io.FileOutputStream(imgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bmp.recycle()
            val txtFile = java.io.File(datasetDir, "$name.txt")
            java.io.BufferedWriter(java.io.FileWriter(txtFile)).use { w ->
                for (i in 0 until count) {
                    val classId = result[i * 6].toInt()
                    val x1 = result[i * 6 + 2]; val y1 = result[i * 6 + 3]
                    val x2 = result[i * 6 + 4]; val y2 = result[i * 6 + 5]
                    val cx = (x1 + x2) / 2f; val cy = (y1 + y2) / 2f
                    val bw = x2 - x1; val bh = y2 - y1
                    w.write("$classId $cx $cy $bw $bh\n")
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Dataset save failed", e) }
    }

    private fun setupTriggerOverlay() {
        if (triggerOverlayAdded) return
        triggerOverlay = TriggerOverlayView(this)
        ProjectionHolder.triggerOverlayView = triggerOverlay
        val size = dp(triggerTouchRange.coerceAtLeast(30))
        val p = makeParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = screenWidth / 2 - size / 2; p.y = screenHeight / 2 - size / 2
        triggerAreaX = p.x; triggerAreaY = p.y
        triggerOverlay!!.areaSize = size
        triggerOverlay!!.onPositionChanged = { l, t -> triggerAreaX = l; triggerAreaY = t }
        wm.addView(triggerOverlay!!, p); triggerOverlayAdded = true
        triggerOverlay!!.alpha = 0f
        Log.d(TAG, "trigger overlay at ($triggerAreaX,$triggerAreaY) size=$size")
    }

    private fun updateTriggerOverlayVisibility() {
        val ov = triggerOverlay ?: return
        val p = ov.layoutParams as? WindowManager.LayoutParams ?: return
        if (triggerShowArea) { ov.alpha = 1f } else { ov.alpha = 0f }
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(ov, p) } catch (_: Exception) {}
    }

    private fun updateTriggerOverlaySize() {
        val ov = triggerOverlay ?: return
        val p = ov.layoutParams as? WindowManager.LayoutParams ?: return
        val size = dp(triggerTouchRange.coerceAtLeast(30)); p.width = size; p.height = size
        ov.areaSize = size; try { wm.updateViewLayout(ov, p) } catch (_: Exception) {}
    }

    private fun setupTouchDisplayView() {
        if (touchDisplayAdded) return
        val size = dp(ConfigManager.getConfig().aimTouchSize) * 2
        touchDisplayView = TouchDisplayView(this)
        ProjectionHolder.touchDisplayView = touchDisplayView
        val p = makeParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        p.gravity = Gravity.TOP or Gravity.START; p.x = screenWidth / 2 - size / 2; p.y = screenHeight / 2 - size / 2
        touchDisplayView!!.dotRadius = dp(ConfigManager.getConfig().aimTouchSize).toFloat()
        wm.addView(touchDisplayView, p); touchDisplayAdded = true
        touchDisplayView!!.alpha = 0f
    }

    private fun loadModel(filename: String) {
        val wasRunning = inferRunning.getAndSet(false)
        try { executor.submit { }.get() } catch (_: Exception) {}
        JniCallBack.release()
        val entry = ProjectionHolder.modelList.find { it.filename == filename }
        val modelFile = entry?.sourcePath?.let { java.io.File(it) } ?: java.io.File(applicationContext.filesDir, filename)
        try {
            val qnnCache = java.io.File(applicationContext.cacheDir, "qnn")
            if (qnnCache.exists()) qnnCache.deleteRecursively()
            qnnCache.mkdirs()
            if (entry?.sourcePath == null) {
                if (!modelFile.exists()) {
                    modelFile.parentFile?.mkdirs()
                    assets.open(filename).use { i -> java.io.FileOutputStream(modelFile).use { o -> i.copyTo(o) } }
                }
                if (filename.endsWith(".param")) {
                    val binFilename = filename.replace(".param", ".bin")
                    val binFile = java.io.File(applicationContext.filesDir, binFilename)
                    if (!binFile.exists()) {
                        assets.open(binFilename).use { i -> java.io.FileOutputStream(binFile).use { o -> i.copyTo(o) } }
                    }
                }
            }
            val cfg = ConfigManager.getConfig()
            val useCpu = cfg.useCpuInference
            Log.d(TAG, "loadModel: useCpuInference=$useCpu, threads=${cfg.cpuThreadCount}")
            JniCallBack.setForceCpu(useCpu)
            JniCallBack.setCpuThreads(cfg.cpuThreadCount)
            if (entry != null) {
                JniCallBack.setInputSize(entry.inputSize, entry.inputSize)
            }
            if (JniCallBack.init(modelFile.absolutePath)) {
                val backend = JniCallBack.getBackend()
                Log.d(TAG, "模型切换成功: $filename, 后端=$backend")
                ProjectionHolder.currentModelName = backend
                currentClasses = entry?.classes ?: emptyMap()
                if (currentClasses.isNotEmpty()) {
                    val validIds = currentClasses.keys
                    aimClasses = aimClasses.filter { it in validIds }.toMutableSet()
                    if (aimClasses.isEmpty()) aimClasses = validIds.toMutableSet()
                    triggerClasses = triggerClasses.filter { it in validIds }.toMutableSet()
                    if (triggerClasses.isEmpty()) triggerClasses = validIds.toMutableSet()
                }
                Log.d(TAG, "模型类别: $currentClasses, aimClasses=$aimClasses, triggerClasses=$triggerClasses")
                showDetectionClassIds = currentClasses.keys.toMutableSet()
                broadcastState(ProjectionHolder.currentState)
            } else { Log.e(TAG, "模型切换失败: $filename") }
        } catch (e: Exception) { Log.e(TAG, "模型切换异常: ${e.message}") }
        if (wasRunning) startInferLoop()
    }

    fun performTestCircle() {
        Thread {
            val cx = screenWidth / 2
            val cy = screenHeight / 2
            val radius = 200
            val steps = 72
            val aspect = screenWidth.toFloat() / screenHeight.toFloat()
            touchClient?.swipe(cx, cy, cx, cy, 0)
            Thread.sleep(50)
            for (i in 1 until steps) {
                val angle = (i * 360.0 / steps) * Math.PI / 180.0
                val x = (cx + radius * aspect * Math.cos(angle)).toInt()
                val y = (cy + radius * Math.sin(angle)).toInt()
                touchClient?.moveTo(x, y)
                Thread.sleep(20)
            }
            touchClient?.lift()
        }.start()
    }

    private fun setupAreaSettingsView() {
        areaSettingsView = AreaSettingsView(this)
        ProjectionHolder.areaSettingsView = areaSettingsView
        val params = makeParams(MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        areaSettingsView?.apply {
            onConfirm = { areas ->
                savedAreas.clear(); savedAreas.addAll(areas)
                ConfigManager.updateConfig { this.areas = areas.toList() }
                updateTriggerZone(); updateAdsZone(); updateFireZone(); updateJoystickZone()
                removeAreaSettingsView()
            }
            onCancel = { removeAreaSettingsView() }
        }
        wm.addView(areaSettingsView!!, params); areaSettingsAdded = true
        areaSettingsView!!.visibility = View.GONE
    }

    private fun showAreaSettings() {
        if (areaSettingsView == null) setupAreaSettingsView()
        if (areaSettingsAdded) {
            // Capture screenshot from ImageReader for background
            try {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * image.width
                        val bitmap = Bitmap.createBitmap(
                            image.width + rowPadding / pixelStride,
                            image.height, Bitmap.Config.ARGB_8888
                        )
                        buffer.position(0)
                        bitmap.copyPixelsFromBuffer(buffer)
                        val cropped = Bitmap.createBitmap(bitmap, 0, 0,
                            image.width, image.height)
                        areaSettingsView?.setBackgroundBitmap(cropped)
                        if (bitmap !== cropped) bitmap.recycle()
                    }
                    image.close()
                }
            } catch (_: Exception) {}
            areaSettingsView?.apply {
                setAreas(this@FloatService.savedAreas)
                open()
            }
        }
    }

    private fun showAreaSettingsWithImage(imagePath: String) {
        try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = 1
            }
            val bitmap = BitmapFactory.decodeFile(imagePath, opts)
            if (areaSettingsView == null) setupAreaSettingsView()
            if (areaSettingsAdded && bitmap != null) {
                areaSettingsView?.setBackgroundBitmap(bitmap)
                areaSettingsView?.apply {
                    setAreas(this@FloatService.savedAreas)
                    open()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load area image: ${e.message}")
        }
    }

    private fun removeAreaSettingsView() {
        if (areaSettingsAdded) {
            areaSettingsView?.animate()?.alpha(0f)?.setDuration(150)
                ?.withEndAction {
                    areaSettingsView?.visibility = View.GONE
                }?.start()
        }
    }

    fun updateTriggerZone() {
        val area = savedAreas.getOrNull(AREA_INDEX_TRIGGER) ?: return
        touchClient?.setTriggerZone(area.x, area.y, area.x + area.width, area.y + area.height)
    }

    fun updateAdsZone() {
        val area = savedAreas.getOrNull(AREA_INDEX_ADS) ?: return
        touchClient?.setAdsZone(area.x, area.y, area.x + area.width, area.y + area.height)
    }

    fun updateFireZone() {
        val area = savedAreas.getOrNull(AREA_INDEX_FIRE) ?: return
        touchClient?.setFireZone(area.x, area.y, area.x + area.width, area.y + area.height)
    }

    fun updateJoystickZone() {}

    private fun clearAimSession(reason: String, clearVisualTargets: Boolean = true, clearLockRay: Boolean = true) {
        aimController.lift()
        aimController.reset()
        if (clearLockRay) { mainHandler.post { overlayView.updateLockRay(null, null) } }
        if (clearVisualTargets) {
            hasDetects.set(false); lastDetections = emptyList()
            mainHandler.post { overlayView.updateDetections(emptyList()) }
        }
        Log.d(TAG, "clearAimSession: reason=$reason")
    }

    private fun setupImageReader() {
        val mW = screenWidth; val mH = screenHeight
        captureW = mW; captureH = mH
        Log.d(TAG, "setupImageReader: w=${captureW} h=${captureH}")
        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        mediaProjection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { Log.d(TAG, "MediaProjection 停止"); inferRunning.set(false); imageReader?.close() }
        }, Handler(Looper.getMainLooper()))
        captureVirtualDisplay = mediaProjection?.createVirtualDisplay("AimbotCapture", captureW, captureH, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)
    }

    private fun startInferLoop() {
        if (inferRunning.getAndSet(true)) { Log.d(TAG, "infer loop already running"); return }
        broadcastState(2) // INFERENCING
        centerX = captureW / 2f; centerY = captureH / 2f
        Log.d(TAG, "infer loop started, center=($centerX,$centerY)")
        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var aliveCtr = 0
            fpsLastTime = SystemClock.elapsedRealtime()
            while (inferRunning.get()) {
                if (++aliveCtr % 30 == 0) { Log.d(TAG, "alive trigger=$triggerEnabled shizuku=${touchClient?.isConnected()} detects=${hasDetects.get()}") }
                val currentRange = cachedRangePx
                if (currentRange != cachedRangePx) { cachedRangePx = currentRange; cachedRange = currentRange.toFloat() }
                val image = imageReader?.acquireLatestImage()
                if (image == null) { Thread.yield(); continue }
                val hwBuf = image.hardwareBuffer
                try {
                    if (recordEnabled && recordSurface != null && hwBuf != null) {
                        try {
                            val canvas = recordSurface!!.lockHardwareCanvas()
                            try {
                                val bmp = Bitmap.wrapHardwareBuffer(hwBuf, null)
                                if (bmp != null) { canvas.drawBitmap(bmp, 0f, 0f, null); bmp.recycle() }
                            } finally { recordSurface!!.unlockCanvasAndPost(canvas) }
                        } catch (_: Exception) {}
                    }
                    hasDetects.set(false)
                    val plane = image.planes[0]; val buffer = plane.buffer
                    val regionW = cachedRangePx * 2; val regionH = cachedRangePx * 2
                    val offsetX = (captureW - regionW) / 2; val offsetY = (captureH - regionH) / 2

                    val result = JniCallBack.detect(buffer, offsetX, offsetY, regionW, regionH, captureW, captureH, plane.rowStride, plane.pixelStride)

                    val rawDetections = mutableListOf<DetectionInfo>()
                    if (result != null) {
                        val count = result.size / 6
                        if (count > 0) {
                            val cid = result[0].toInt(); val sc = result[1]
                            val className = currentClasses[cid] ?: "unknown"
                            Log.d(TAG, "detect: count=$count, classId=$cid ($className) score=${"%.3f".format(sc)}")
                        }
                        var i = 0
                        while (i < count && rawDetections.size < detectionBuffer.size) {
                            val cid = result[i * 6].toInt()
                            val score = result[i * 6 + 1]
                            val rect = RectF(
                                result[i * 6 + 2] * captureW,
                                result[i * 6 + 3] * captureH,
                                result[i * 6 + 4] * captureW,
                                result[i * 6 + 5] * captureH
                            )
                            rawDetections += DetectionInfo(rect, cid, currentClasses[cid] ?: "cls$cid", score = score)
                            i++
                        }
                        if (autoSaveDataset && rawDetections.isNotEmpty() && hwBuf != null) {
                            saveDatasetFrame(hwBuf, result, count)
                        }
                    }

                    lastDetections = if (kalmanPredictEnabled) {
                        kalmanTracker.update(rawDetections, SystemClock.elapsedRealtime())
                    } else {
                        kalmanTracker.reset()
                        rawDetections
                    }
                    hasDetects.set(lastDetections.isNotEmpty())
                    mainHandler.post {
                        overlayView.enabledClassIds = showDetectionClassIds.ifEmpty { null }
                        overlayView.updateDetections(lastDetections)
                    }

                    // 按住激发: 物理手指按在触发区或开镜区时都能触发自瞄
                    val holdToAimActive = if (aimHoldEnabled) {
                        val adsAimTrigger = triggerEnabled && autoTriggerAdsEnabled
                        if (adsAimTrigger) { true }
                        else {
                            val fingerOnTrigger = touchClient?.isFingerInTriggerZone() ?: false
                            val fingerOnAds = touchClient?.isFingerInAdsZone() ?: false
                            fingerOnTrigger || fingerOnAds
                        }
                    } else true

                    // Filter detections by aimClasses
                    val aimDets = if (aimClasses.isEmpty()) lastDetections
                        else lastDetections.filter { it.classId in aimClasses }

                    val target = if (aimDets.isNotEmpty()) {
                        aimController.selectTarget(aimDets, centerX, centerY)
                    } else null
                    val rayPoint = if (showLockRay) aimController.currentLockRayPoint() else null
                    mainHandler.post {
                        overlayView.showLockRay = showLockRay
                        overlayView.updateLockRay(rayPoint?.first, rayPoint?.second)
                    }

                    if (aimbotOn.get() && target != null && holdToAimActive) {
                        aimController.executeAiming(target.aimX, target.aimY, centerX, centerY)
                    } else {
                        val reason = when {
                            !aimbotOn.get() -> "aimbot_off"
                            !holdToAimActive -> "hold_inactive"
                            aimDets.isEmpty() -> "no_targets"
                            else -> "no_target_selected"
                        }
                        val keepVisualTargets = reason == "hold_inactive" || reason == "aimbot_off"
                        clearAimSession(
                            reason = reason,
                            clearVisualTargets = !keepVisualTargets,
                            clearLockRay = target == null
                        )
                    }

                    // detection-based trigger: center in any detection box (filtered by aimClasses)
                    triggerController.processTrigger(lastDetections, centerX, centerY, hasDetects.get())

                    // FPS 计算
                    fpsFrameCount++
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - fpsLastTime
                    if (elapsed >= 1000) {
                        currentFps = fpsFrameCount * 1000f / elapsed
                        fpsFrameCount = 0
                        fpsLastTime = now
                        // 每 2 秒更新一次温度
                        if (aliveCtr % 60 == 0) {
                            currentTemperature = readCpuTemperature()
                        }
                        mainHandler.post {
                            overlayView.fps = currentFps.toInt().toString()
                            overlayView.temperature = currentTemperature
                            overlayView.inferRunning = inferRunning.get()
                            overlayView.postInvalidateOnAnimation()
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "推理帧异常: ${e.message}") }
                finally { hwBuf?.close(); image.close() }
            }
            inferRunning.set(false)
        }
    }

    private fun makeParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val ch = NotificationChannel(CH_ID, "迅雷AI", NotificationManager.IMPORTANCE_LOW); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch) } }
    private fun buildNotification() = NotificationCompat.Builder(this, CH_ID).setContentTitle("迅雷AI").setContentText("运行中").setSmallIcon(R.drawable.ic_notification).build()

    private fun actualDisplayRotation(): Int = try { wm.defaultDisplay.rotation } catch (_: Exception) { Surface.ROTATION_0 }

    fun currentDisplayRotation(): Int {
        val rawRotation = actualDisplayRotation()
        return when (touchOrientationMode) {
            TOUCH_ORIENTATION_RIGHT -> Surface.ROTATION_90
            TOUCH_ORIENTATION_LEFT -> Surface.ROTATION_270
            else -> rawRotation
        }
    }

    private fun applyTouchOrientationConfig() {
        val rawRotation = actualDisplayRotation()
        val effectiveRotation = currentDisplayRotation()
        Log.d(TAG, "applyTouchOrientationConfig: mode=$touchOrientationMode raw=$rawRotation effective=$effectiveRotation")
        touchClient?.setOrientationConfig(effectiveRotation)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rawRotation = actualDisplayRotation()
        val rotation = currentDisplayRotation()
        Log.d(TAG, "orientation changed: raw=$rawRotation effective=$rotation mode=$touchOrientationMode display=${screenWidth}x${screenHeight} capture=${captureW}x${captureH}")
        touchClient?.setResolution(screenWidth, screenHeight, deviceAbsMaxX, deviceAbsMaxY)
        touchClient?.setOrientationConfig(rotation)
        centerX = captureW / 2f; centerY = captureH / 2f
        val ov = triggerOverlay
        if (triggerOverlayAdded && ov != null) {
            val size = dp(triggerTouchRange.coerceAtLeast(30))
            (ov.layoutParams as? WindowManager.LayoutParams)?.let { p ->
                p.width = size; p.height = size
                p.x = screenWidth / 2 - size / 2; p.y = screenHeight / 2 - size / 2
                triggerAreaX = p.x; triggerAreaY = p.y
                wm.updateViewLayout(ov, p)
            }
        }
        if (overlayAdded) {
            (overlayView.layoutParams as? WindowManager.LayoutParams)?.let { p ->
                p.width = screenWidth; p.height = screenHeight
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                wm.updateViewLayout(overlayView, p)
            }
        }
        // GUI panel removed - settings are in main page
        restartCapture()
    }

    private fun restartCapture() {
        val curW = screenWidth; val curH = screenHeight
        if (captureW == curW && captureH == curH) return
        val wasRunning = inferRunning.getAndSet(false)
        Log.d(TAG, "restartCapture: wasRunning=$wasRunning newSize=${curW}x${curH}")
        executor.execute {
            try { Thread.sleep(200) } catch (_: Exception) {}
            val oldReader = imageReader; imageReader = null
            try { oldReader?.close() } catch (_: Exception) {}
            imageReader = ImageReader.newInstance(curW, curH, PixelFormat.RGBA_8888, 2)
            try {
                captureVirtualDisplay?.resize(curW, curH, screenDensity)
                captureVirtualDisplay?.setSurface(imageReader!!.surface)
                Log.d(TAG, "restartCapture: resized to ${curW}x${curH}")
            } catch (e: Exception) { Log.w(TAG, "VirtualDisplay resize failed: ${e.message}") }
            captureW = curW; captureH = curH
            touchClient?.setResolution(curW, curH, deviceAbsMaxX, deviceAbsMaxY)
            applyTouchOrientationConfig()
            centerX = captureW / 2f; centerY = captureH / 2f
            if (wasRunning) startInferLoop()
        }
    }

    private inner class SafeTouchClient : TouchInjectorInterface {
        private val delegate: TouchInjectorInterface? get() = touchClient
        private fun blocked(x: Int, y: Int) = false
        override fun connect(callback: InjectorCallback) { delegate?.connect(callback) }
        override fun isConnected(): Boolean = delegate?.isConnected() == true
        override fun disconnect() { delegate?.disconnect() }
        override fun tap(x: Int, y: Int) { if (!blocked(x, y)) delegate?.tap(x, y) }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) { if (!blocked(x1, y1) && !blocked(x2, y2)) delegate?.swipe(x1, y1, x2, y2, durationMs) }
        override fun moveTo(x: Int, y: Int) { if (!blocked(x, y)) delegate?.moveTo(x, y) else delegate?.lift() }
        override fun lift() { delegate?.lift() }
        override fun keepAlive() { delegate?.keepAlive() }
        override fun triggerDown(x: Int, y: Int) { if (!blocked(x, y)) delegate?.triggerDown(x, y) }
        override fun triggerUp() { delegate?.triggerUp() }
        override fun triggerTap(x: Int, y: Int, durationMs: Int) { if (!blocked(x, y)) delegate?.triggerTap(x, y, durationMs) }
        override fun setTriggerZone(left: Int, top: Int, right: Int, bottom: Int) { delegate?.setTriggerZone(left, top, right, bottom) }
        override fun isFingerInTriggerZone(): Boolean = delegate?.isFingerInTriggerZone() == true
        override fun setAdsZone(left: Int, top: Int, right: Int, bottom: Int) { delegate?.setAdsZone(left, top, right, bottom) }
        override fun isFingerInAdsZone(): Boolean = delegate?.isFingerInAdsZone() == true
        override fun setFireZone(left: Int, top: Int, right: Int, bottom: Int) { delegate?.setFireZone(left, top, right, bottom) }
        override fun isFingerInFireZone(): Boolean = delegate?.isFingerInFireZone() == true
        override fun setJoystickZone(left: Int, top: Int, right: Int, bottom: Int) { delegate?.setJoystickZone(left, top, right, bottom) }
        override fun isFingerInJoystickZone(): Boolean = delegate?.isFingerInJoystickZone() == true
        override fun liftJoystickFinger(): Boolean = delegate?.liftJoystickFinger() ?: false
        override fun blockPhysicalTouch() { delegate?.blockPhysicalTouch() }
        override fun unblockPhysicalTouch() { delegate?.unblockPhysicalTouch() }
        override fun destroyRemote() { delegate?.destroyRemote() }
        override fun setResolution(w: Int, h: Int, maxX: Int, maxY: Int) { delegate?.setResolution(w, h, maxX, maxY) }
        override fun setOrientationConfig(rotation: Int) { delegate?.setOrientationConfig(rotation) }
        override fun setInputMethod(method: Int) { delegate?.setInputMethod(method) }
        override fun startGeteventListener() { delegate?.startGeteventListener() }
        override fun stopGeteventListener() { delegate?.stopGeteventListener() }
        override fun initRemote(): Boolean = delegate?.initRemote() ?: false
        override fun queryDeviceAbs(devicePath: String, axis: Int): IntArray = delegate?.queryDeviceAbs(devicePath, axis) ?: intArrayOf()
        override fun findTouchDevice(): String? = delegate?.findTouchDevice()
    }
}
