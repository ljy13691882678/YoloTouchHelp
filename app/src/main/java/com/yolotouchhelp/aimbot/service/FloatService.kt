package com.yolotouchhelp.aimbot.service
import com.yolotouchhelp.aimbot.model.DetectionInfo

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
import com.yolotouchhelp.aimbot.controller.AimController
import com.yolotouchhelp.aimbot.controller.TriggerController
import com.yolotouchhelp.aimbot.manager.InferenceManager
import com.yolotouchhelp.aimbot.manager.OverlayManager
import com.yolotouchhelp.aimbot.manager.ConfigManager
import com.yolotouchhelp.aimbot.view.FloatBallView
import com.yolotouchhelp.aimbot.view.OverlayCanvasView
import com.yolotouchhelp.aimbot.view.GuiPanelView
import com.yolotouchhelp.aimbot.view.TriggerOverlayView
import com.yolotouchhelp.aimbot.view.TouchDisplayView
import com.yolotouchhelp.aimbot.view.AreaSettingsView
import com.yolotouchhelp.aimbot.model.AreaConfig
import com.yolotouchhelp.aimbot.injector.TouchInjectorInterface
import com.yolotouchhelp.aimbot.injector.RootInjectorClient
import com.yolotouchhelp.aimbot.injector.ShizukuInjectorClient
import com.yolotouchhelp.aimbot.injector.InjectorCallback
import com.yolotouchhelp.aimbot.inference.JniCallBack
import com.yolotouchhelp.aimbot.inference.KalmanObjectTracker
import com.yolotouchhelp.aimbot.util.ProjectionHolder

class FloatService : Service() {

    companion object {
        const val TAG = "FloatService"
        const val CH_ID = "aimbot_ch"
        const val TOUCH_ORIENTATION_AUTO = 0
        const val TOUCH_ORIENTATION_RIGHT = 1
        const val TOUCH_ORIENTATION_LEFT = 2
        private const val AIM_TOUCH_INTERVAL_MS = 8L
    }

    private lateinit var wm: WindowManager
    private lateinit var ballView: FloatBallView
    private lateinit var overlayView: OverlayCanvasView
    private lateinit var guiPanel: GuiPanelView

    private var ballParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var guiParams: WindowManager.LayoutParams? = null
    private var guiVisible = false; private var ballAdded = false
    private var overlayAdded = false; private var guiAdded = false

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
    private var kp = 0.30f; private var ki = 0.02f; private var kd = 0.08f
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
        // FIX: 连接后坐力脉冲回调 — 开火时通知 AimController
        triggerController.onFire = { aimController.bumpRecoil() }
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

        // PID参数随机化（±5%范围），避免固定参数被行为分析检测
        // 仅在首次加载时随机化一次，保持本次运行参数稳定
        if (!::aimController.isInitialized) {
            val random = java.util.Random()
            val kpJitter = 1f + (random.nextFloat() - 0.5f) * 0.1f // ±5%
            val kiJitter = 1f + (random.nextFloat() - 0.5f) * 0.1f
            val kdJitter = 1f + (random.nextFloat() - 0.5f) * 0.1f
            kp = (kp * kpJitter).coerceIn(0.2f, 0.5f)
            ki = (ki * kiJitter).coerceIn(0.01f, 0.05f)
            kd = (kd * kdJitter).coerceIn(0.05f, 0.12f)
        }

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
                    if (guiAdded) { guiPanel.modelIndex = idx; guiPanel.buildUI() }
                    Log.d(TAG, "同步模型切换: index=$idx, ${entry.displayName}")
                }
            }
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
        setupBall(); setupOverlay(); initTouchInjector()

        lastModelIndex = ProjectionHolder.selectedModelIndex
        // Load classes map for current model
        val entry = ProjectionHolder.modelList.getOrNull(ProjectionHolder.selectedModelIndex)
        currentClasses = entry?.classes ?: emptyMap()
        if (aimClasses.isEmpty() && currentClasses.isNotEmpty()) aimClasses = currentClasses.keys.toMutableSet()
        if (triggerClasses.isEmpty() && currentClasses.isNotEmpty()) triggerClasses = currentClasses.keys.toMutableSet()
        Log.d(TAG, "启动模型类别: $currentClasses, aimClasses=$aimClasses, triggerClasses=$triggerClasses")

        ProjectionHolder.updateState(1, JniCallBack.getBackend())
        return START_NOT_STICKY
    }

    private fun broadcastState(state: Int, modelName: String? = null) {
        ProjectionHolder.updateState(state, modelName ?: ProjectionHolder.currentModelName)
    }

    private fun cleanupViews() {
        try { if (ballAdded) { wm.removeView(ballView); ballAdded = false } } catch (_: Exception) {}
        try { if (overlayAdded) { wm.removeView(overlayView); overlayAdded = false } } catch (_: Exception) {}
        try { if (guiAdded) { wm.removeView(guiPanel); guiAdded = false; guiVisible = false } } catch (_: Exception) {}
        try { if (triggerOverlayAdded) { wm.removeView(triggerOverlay); triggerOverlayAdded = false } } catch (_: Exception) {}
        try { if (touchDisplayAdded) { wm.removeView(touchDisplayView); touchDisplayAdded = false } } catch (_: Exception) {}
        try { if (areaSettingsAdded) { wm.removeView(areaSettingsView); areaSettingsAdded = false } } catch (_: Exception) {}
    }

    private fun setupBall() {
        val size = dp(35)
        ballView = FloatBallView(this)
        ProjectionHolder.floatBallView = ballView
        ballParams = makeParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply {
            gravity = Gravity.TOP or Gravity.START; x = 50; y = 200
        }
        ballView.onMoveCallback = { dx, dy -> ballParams?.let { it.x += dx; it.y += dy; wm.updateViewLayout(ballView, it) } }
        ballView.onClickCallback = { toggleGui() }; wm.addView(ballView, ballParams); ballAdded = true
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
                val outputFile = java.io.File(dir, "YoloTouchHelp_${timestamp}_$random.mp4")
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
        val size = dp(guiPanel.aimTouchSize) * 2
        touchDisplayView = TouchDisplayView(this)
        ProjectionHolder.touchDisplayView = touchDisplayView
        val p = makeParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        p.gravity = Gravity.TOP or Gravity.START; p.x = screenWidth / 2 - size / 2; p.y = screenHeight / 2 - size / 2
        touchDisplayView!!.dotRadius = dp(guiPanel.aimTouchSize).toFloat()
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
                if (guiAdded) {
                    guiPanel.classMap = currentClasses
                    guiPanel.aimClasses = aimClasses.toMutableSet()
                    guiPanel.triggerClasses = triggerClasses.toMutableSet()
                    guiPanel.showDetectionClassIds = currentClasses.keys
                    showDetectionClassIds = currentClasses.keys.toMutableSet()
                    guiPanel.buildUI()
                }
                broadcastState(ProjectionHolder.currentState)
            } else { Log.e(TAG, "模型切换失败: $filename") }
        } catch (e: Exception) { Log.e(TAG, "模型切换异常: ${e.message}") }
        if (wasRunning) startInferLoop()
    }

    private fun toggleGui() {
        if (guiVisible) hideGui() else showGui()
    }

    private fun showGui() {
        if (guiAdded) {
            guiPanel.aimbotEnabled = aimbotOn.get(); guiPanel.modelRunning = modelRunning
            guiPanel.recordEnabled = recordEnabled; guiPanel.autoSaveDataset = autoSaveDataset
            guiPanel.speed = kp; guiPanel.ki = ki; guiPanel.kd = kd
            guiPanel.modelNames = ProjectionHolder.modelList.map { it.displayName }
            guiPanel.modelIndex = ProjectionHolder.selectedModelIndex
            guiPanel.classMap = currentClasses
            guiPanel.aimClasses = aimClasses.toMutableSet()
            guiPanel.priorityClass = priorityClass
            guiPanel.classAimOffsets = classAimOffsets.toMutableMap()
            guiPanel.boxAimRatio = boxAimRatio
            guiPanel.classBoxAimRatios = classBoxAimRatios.toMutableMap()
            guiPanel.classTriggerOffsets = classTriggerOffsets.toMutableMap()
            guiPanel.triggerClasses = triggerClasses.toMutableSet()
            guiPanel.kalmanPredictEnabled = kalmanPredictEnabled
            guiPanel.aimPredictionMultiplier = aimPredictionMultiplier
            guiPanel.pidSamplePeriodMs = pidSamplePeriodMs
            guiPanel.aimMode = aimMode; guiPanel.bezierDuration = bezierDuration
            guiPanel.bezierControlOffset = bezierControlOffset; guiPanel.bezierRandomSpread = bezierRandomSpread
            guiPanel.convergeThresh = convergeThresh.toInt()
            guiPanel.targetLostTolerance = targetLostTolerance; guiPanel.showLockRay = showLockRay
            guiPanel.lockBoxThreshold = lockBoxThreshold; guiPanel.lockCenterWeight = lockCenterWeight
            guiPanel.moveSmooth = moveSmooth; guiPanel.deadzoneHoldFrames = deadzoneHoldFrames
            guiPanel.edgeReturnStrength = edgeReturnStrength; guiPanel.touchOrientationMode = touchOrientationMode
            guiPanel.showDetectionClassIds = showDetectionClassIds.toSet()
            guiPanel.buildUI()
            guiPanel.visibility = View.VISIBLE; guiPanel.alpha = 0f; guiPanel.scaleX = 0.85f; guiPanel.scaleY = 0.85f
            guiPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start(); guiVisible = true; return
        }

        guiPanel = GuiPanelView(this)
        ProjectionHolder.guiPanelView = guiPanel
        val cfg = ConfigManager.getConfig()
        guiPanel.aimbotEnabled = aimbotOn.get(); guiPanel.speed = cfg.speed
        guiPanel.range = overlayView.rangeRadius.coerceIn(50, 800); guiPanel.confidence = cfg.confidence
        guiPanel.triggerEnabled = cfg.triggerEnabled; guiPanel.triggerReactionSpeed = cfg.triggerReactionSpeed
        guiPanel.triggerCooldown = cfg.triggerCooldown; guiPanel.triggerUpFluctuation = cfg.triggerUpFluctuation
        guiPanel.triggerDownFluctuation = cfg.triggerDownFluctuation; guiPanel.triggerTouchDuration = cfg.triggerTouchDuration
        guiPanel.triggerTouchRange = cfg.triggerTouchRange; guiPanel.triggerShowArea = cfg.triggerShowArea
        guiPanel.autoStopEnabled = cfg.autoStopEnabled; guiPanel.aimHoldEnabled = cfg.aimHoldEnabled
        guiPanel.aimOffsetYRatio = cfg.aimOffsetYRatio; guiPanel.aimSwayAmplitude = cfg.aimSwayAmplitude
        guiPanel.aimPredictionMultiplier = cfg.aimPredictionMultiplier; guiPanel.pidSamplePeriodMs = cfg.pidSamplePeriodMs
        guiPanel.triggerOffsetYRatio = cfg.triggerOffsetYRatio; guiPanel.ki = cfg.ki; guiPanel.kd = cfg.kd
        guiPanel.aimMode = cfg.aimMode; guiPanel.bezierDuration = cfg.bezierDuration
        guiPanel.bezierControlOffset = cfg.bezierControlOffset; guiPanel.bezierRandomSpread = cfg.bezierRandomSpread
        guiPanel.convergeThresh = cfg.convergeThresh
        guiPanel.aimTouchDisplay = cfg.aimTouchDisplay; guiPanel.aimTouchSize = 20
        guiPanel.modelRunning = modelRunning; guiPanel.recordEnabled = recordEnabled; guiPanel.autoSaveDataset = autoSaveDataset
        guiPanel.showCaptureRange = cfg.showCaptureRange; guiPanel.showDetectionBox = cfg.showDetectionBox
        guiPanel.showCenterDot = cfg.showCenterDot; guiPanel.showLockRay = cfg.showLockRay
        guiPanel.recoilEnabled = cfg.recoilEnabled; guiPanel.recoilStrength = cfg.recoilStrength
        guiPanel.kalmanPredictEnabled = cfg.kalmanPredictEnabled; guiPanel.kalmanMaxMissed = cfg.kalmanMaxMissed
        guiPanel.kalmanProcessNoise = cfg.kalmanProcessNoise; guiPanel.kalmanMeasureNoise = cfg.kalmanMeasureNoise
        guiPanel.kalmanBoxSmooth = cfg.kalmanBoxSmooth; guiPanel.kalmanMatchIouThreshold = cfg.kalmanMatchIouThreshold
        guiPanel.targetLostTolerance = cfg.targetLostTolerance; guiPanel.showLockRay = cfg.showLockRay
        guiPanel.showDetectionClassIds = cfg.showDetectionClassIds
        guiPanel.lockBoxThreshold = cfg.lockBoxThreshold; guiPanel.lockCenterWeight = cfg.lockCenterWeight
        guiPanel.moveSmooth = cfg.moveSmooth; guiPanel.deadzoneHoldFrames = cfg.deadzoneHoldFrames
        guiPanel.edgeReturnStrength = cfg.edgeReturnStrength
        guiPanel.autoTriggerAdsEnabled = cfg.autoTriggerAdsEnabled; guiPanel.autoTriggerAdsRange = cfg.autoTriggerAdsRange
        guiPanel.activeTab = 0
        guiPanel.modelNames = ProjectionHolder.modelList.map { it.displayName }
        guiPanel.modelIndex = ProjectionHolder.selectedModelIndex
        guiPanel.onModelSelected = { idx ->
            val e = ProjectionHolder.modelList.getOrNull(idx)
            if (e != null) { ProjectionHolder.notifyModelIndexChanged(idx); lastModelIndex = idx; loadModel(e.filename) }
        }
        guiPanel.classMap = currentClasses
        guiPanel.aimClasses = aimClasses.toMutableSet(); guiPanel.priorityClass = priorityClass
        guiPanel.classAimOffsets = classAimOffsets.toMutableMap(); guiPanel.boxAimRatio = boxAimRatio
        guiPanel.classBoxAimRatios = classBoxAimRatios.toMutableMap(); guiPanel.classTriggerOffsets = classTriggerOffsets.toMutableMap()
        guiPanel.triggerClasses = triggerClasses.toMutableSet()
        guiPanel.recoilEnabled = recoilEnabled; guiPanel.recoilStrength = recoilStrength
        guiPanel.kalmanPredictEnabled = kalmanPredictEnabled; guiPanel.kalmanMaxMissed = kalmanMaxMissed
        guiPanel.kalmanProcessNoise = kalmanProcessNoise; guiPanel.kalmanMeasureNoise = kalmanMeasureNoise
        guiPanel.kalmanBoxSmooth = kalmanBoxSmooth; guiPanel.kalmanMatchIouThreshold = kalmanMatchIouThreshold
        guiPanel.aimPredictionMultiplier = aimPredictionMultiplier; guiPanel.pidSamplePeriodMs = pidSamplePeriodMs
        guiPanel.showLockRay = showLockRay
        guiPanel.targetLostTolerance = targetLostTolerance; guiPanel.lockBoxThreshold = lockBoxThreshold
        guiPanel.lockCenterWeight = lockCenterWeight; guiPanel.moveSmooth = moveSmooth
        guiPanel.deadzoneHoldFrames = deadzoneHoldFrames; guiPanel.edgeReturnStrength = edgeReturnStrength
        guiPanel.aimMode = aimMode; guiPanel.bezierDuration = bezierDuration
        guiPanel.bezierControlOffset = bezierControlOffset; guiPanel.bezierRandomSpread = bezierRandomSpread
        guiPanel.convergeThresh = convergeThresh.toInt()
        guiPanel.autoTriggerAdsEnabled = autoTriggerAdsEnabled; guiPanel.autoTriggerAdsRange = autoTriggerAdsRange
        guiPanel.touchOrientationMode = touchOrientationMode

        guiPanel.buildUI()
        val initialWidth = ((280 * resources.displayMetrics.density).toInt()).coerceAtMost((screenWidth * 0.92f).toInt().coerceAtLeast(dp(240)))
        val initialHeight = ((screenHeight * 0.68f).toInt()).coerceIn(dp(240).coerceAtMost((screenHeight * 0.88f).toInt().coerceAtLeast(dp(240))), (screenHeight * 0.88f).toInt().coerceAtLeast(dp(240)))
        guiParams = makeParams(initialWidth, initialHeight, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply {
            gravity = Gravity.TOP or Gravity.START; x = 60; y = 200
        }

        guiPanel.onPanelDrag = { dx, dy -> moveGuiPanel(dx, dy) }
        guiPanel.onPanelResize = { dx, dy -> resizeGuiPanel(dx, dy) }
        guiPanel.onClose = { hideGui() }
        guiPanel.onEnabledChanged = { on ->
            aimbotOn.set(on); overlayView.aimbotEnabled = on
            ConfigManager.updateConfig { aimbotEnabled = on }
            if (on) { aimController.aimingState.maxDragDist = (screenWidth.coerceAtMost(screenHeight) * 0.2f).coerceIn(100f, 600f) }
            else { clearAimSession("aimbot_disabled", clearVisualTargets = false) }
            Log.d("YoloTouchHelpInfer", "开关切换: $on")
        }
        guiPanel.onSpeedChanged = { kp = it; currentSpeed = it; aimController.kp = it; ConfigManager.updateConfig { speed = it } }
        guiPanel.onRangeChanged = { px -> overlayView.rangeRadius = px; overlayView.postInvalidate(); ConfigManager.updateConfig { range = px } }
        guiPanel.onConfidenceChanged = { currentConfidence = it; JniCallBack.setConfidence(it); ConfigManager.updateConfig { confidence = it } }
        guiPanel.onTriggerEnabled = { triggerEnabled = it; triggerController.triggerEnabled = it; ConfigManager.updateConfig { triggerEnabled = it } }
        guiPanel.onTriggerReactionSpeed = { triggerReactionSpeed = it; triggerController.triggerReactionSpeed = it; ConfigManager.updateConfig { triggerReactionSpeed = it } }
        guiPanel.onTriggerCooldown = { triggerCooldown = it; triggerController.triggerCooldown = it; ConfigManager.updateConfig { triggerCooldown = it } }
        guiPanel.onTriggerUpFluctuation = { triggerUpFluct = it; triggerController.triggerUpFluct = it; ConfigManager.updateConfig { triggerUpFluctuation = it } }
        guiPanel.onTriggerDownFluctuation = { triggerDownFluct = it; triggerController.triggerDownFluct = it; ConfigManager.updateConfig { triggerDownFluctuation = it } }
        guiPanel.onTriggerTouchDuration = { triggerTouchDuration = it; triggerController.triggerTouchDuration = it; ConfigManager.updateConfig { triggerTouchDuration = it } }
        guiPanel.onTriggerTouchRange = { px -> triggerTouchRange = px; triggerController.triggerTouchRange = px; updateTriggerOverlaySize(); triggerController.updateTriggerOverlaySize(); ConfigManager.updateConfig { triggerTouchRange = px } }
        guiPanel.onTriggerShowArea = { show -> triggerShowArea = show; triggerController.triggerShowArea = show; if (show) setupTriggerOverlay(); updateTriggerOverlayVisibility(); triggerController.updateTriggerOverlayVisibility(); ConfigManager.updateConfig { triggerShowArea = show } }
        guiPanel.onAutoStopEnabledChanged = { autoStopEnabled = it; triggerController.autoStopEnabled = it; ConfigManager.updateConfig { autoStopEnabled = it } }
        guiPanel.onAutoTriggerAdsEnabledChanged = { autoTriggerAdsEnabled = it; triggerController.autoTriggerAdsEnabled = it; ConfigManager.updateConfig { autoTriggerAdsEnabled = it } }
        guiPanel.onAutoTriggerAdsRangeChanged = { autoTriggerAdsRange = it; triggerController.autoTriggerAdsRange = it; ConfigManager.updateConfig { autoTriggerAdsRange = it } }
        guiPanel.onAimOffsetYRatioChanged = { aimOffsetYRatio = it; aimController.aimOffsetYRatio = it; ConfigManager.updateConfig { aimOffsetYRatio = it } }
        guiPanel.onAimSwayAmplitudeChanged = { aimSwayAmplitude = it; aimController.aimSwayAmplitude = it; ConfigManager.updateConfig { aimSwayAmplitude = it } }
        guiPanel.onAimPredictionMultiplierChanged = { aimPredictionMultiplier = it; aimController.aimPredictionMultiplier = it; ConfigManager.updateConfig { aimPredictionMultiplier = it } }
        guiPanel.onRecoilEnabledChanged = { recoilEnabled = it; aimController.recoilEnabled = it; ConfigManager.updateConfig { recoilEnabled = it } }
        guiPanel.onRecoilStrengthChanged = { recoilStrength = it; aimController.recoilStrength = it; ConfigManager.updateConfig { recoilStrength = it } }
        guiPanel.onShowLockRayChanged = { showLockRay = it; aimController.showLockRay = it; overlayView.showLockRay = it; if (!it) overlayView.updateLockRay(null, null); ConfigManager.updateConfig { showLockRay = it } }
        guiPanel.onShowDetectionClassIdsChanged = { ids ->
            showDetectionClassIds = ids.toMutableSet()
            overlayView.enabledClassIds = ids
            overlayView.postInvalidate()
            ConfigManager.updateConfig { showDetectionClassIds = ids }
        }
        guiPanel.onTargetLostToleranceChanged = { targetLostTolerance = it; aimController.targetLostTolerance = it; ConfigManager.updateConfig { targetLostTolerance = it } }
        guiPanel.onLockBoxThresholdChanged = { lockBoxThreshold = it; aimController.lockBoxThreshold = it; ConfigManager.updateConfig { lockBoxThreshold = it } }
        guiPanel.onLockCenterWeightChanged = { lockCenterWeight = it; aimController.lockCenterWeight = it; ConfigManager.updateConfig { lockCenterWeight = it } }
        guiPanel.onMoveSmoothChanged = { moveSmooth = it; aimController.moveSmooth = it; ConfigManager.updateConfig { moveSmooth = it } }
        guiPanel.onDeadzoneHoldFramesChanged = { deadzoneHoldFrames = it; aimController.deadzoneHoldFrames = it; ConfigManager.updateConfig { deadzoneHoldFrames = it } }
        guiPanel.onEdgeReturnStrengthChanged = { edgeReturnStrength = it; aimController.edgeReturnStrength = it; ConfigManager.updateConfig { edgeReturnStrength = it } }
        guiPanel.onKalmanPredictEnabledChanged = { enabled -> kalmanPredictEnabled = enabled; if (!enabled) kalmanTracker.reset(); ConfigManager.updateConfig { kalmanPredictEnabled = enabled } }
        guiPanel.onKalmanMaxMissedChanged = { value -> kalmanMaxMissed = value; applyKalmanConfig(); ConfigManager.updateConfig { kalmanMaxMissed = value } }
        guiPanel.onKalmanProcessNoiseChanged = { value -> kalmanProcessNoise = value; applyKalmanConfig(); ConfigManager.updateConfig { kalmanProcessNoise = value } }
        guiPanel.onKalmanMeasureNoiseChanged = { value -> kalmanMeasureNoise = value; applyKalmanConfig(); ConfigManager.updateConfig { kalmanMeasureNoise = value } }
        guiPanel.onKalmanBoxSmoothChanged = { value -> kalmanBoxSmooth = value; applyKalmanConfig(); ConfigManager.updateConfig { kalmanBoxSmooth = value } }
        guiPanel.onKalmanMatchIouThresholdChanged = { value -> kalmanMatchIouThreshold = value; applyKalmanConfig(); ConfigManager.updateConfig { kalmanMatchIouThreshold = value } }
        guiPanel.onTriggerOffsetYRatioChanged = { triggerOffsetYRatio = it; triggerController.triggerOffsetYRatio = it; ConfigManager.updateConfig { triggerOffsetYRatio = it } }
        guiPanel.onKiChanged = { ki = it; guiPanel.ki = it; aimController.ki = it; ConfigManager.updateConfig { ki = it } }
        guiPanel.onKdChanged = { kd = it; guiPanel.kd = it; aimController.kd = it; ConfigManager.updateConfig { kd = it } }
        guiPanel.onPidSamplePeriodMsChanged = { pidSamplePeriodMs = it; aimController.pidSamplePeriodMs = it; ConfigManager.updateConfig { pidSamplePeriodMs = it } }
        guiPanel.onAimModeChanged = { aimMode = it; aimController.aimMode = it; ConfigManager.updateConfig { aimMode = it } }
        guiPanel.onBezierDurationChanged = { bezierDuration = it; aimController.bezierDuration = it; ConfigManager.updateConfig { bezierDuration = it } }
        guiPanel.onBezierControlOffsetChanged = { bezierControlOffset = it; aimController.bezierControlOffset = it; ConfigManager.updateConfig { bezierControlOffset = it } }
        guiPanel.onBezierRandomSpreadChanged = { bezierRandomSpread = it; aimController.bezierRandomSpread = it; ConfigManager.updateConfig { bezierRandomSpread = it } }
        guiPanel.onConvergeThreshChanged = { convergeThresh = it.toFloat(); aimController.convergeThresh = it.toFloat(); ConfigManager.updateConfig { convergeThresh = it } }
        guiPanel.onAimHoldEnabled = { aimHoldEnabled = it; aimController.aimHoldEnabled = it; ConfigManager.updateConfig { aimHoldEnabled = it } }
        guiPanel.onAimTouchDisplay = { show ->
            touchDisplayEnabled = show; ConfigManager.updateConfig { aimTouchDisplay = show }
            if (touchDisplayAdded) {
                val lp = touchDisplayView?.layoutParams as? WindowManager.LayoutParams
                if (lp != null) {
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    touchDisplayView?.alpha = if (show) 1f else 0f
                    try { wm.updateViewLayout(touchDisplayView, lp) } catch (_: Exception) {}
                }
            }
        }
        guiPanel.onAimTouchSize = { px -> ConfigManager.updateConfig { aimTouchSize = px }; val p = dp(px); touchDisplayView?.dotRadius = p.toFloat(); if (touchDisplayAdded) { val lp = touchDisplayView?.layoutParams as? WindowManager.LayoutParams; if (lp != null) { lp.width = p * 2; lp.height = p * 2; wm.updateViewLayout(touchDisplayView, lp) } } }
        guiPanel.onShowCaptureRangeChanged = { on -> overlayView.showCaptureRange = on; overlayView.postInvalidate(); ConfigManager.updateConfig { showCaptureRange = on } }
        guiPanel.onShowDetectionBoxChanged = { on -> overlayView.showDetectionBox = on; overlayView.postInvalidate(); ConfigManager.updateConfig { showDetectionBox = on } }
        guiPanel.onShowCenterDotChanged = { on -> overlayView.showCenterDot = on; overlayView.postInvalidate(); ConfigManager.updateConfig { showCenterDot = on } }
        guiPanel.onRecordEnabledChanged = { on -> toggleRecording(on) }
        guiPanel.onAutoSaveDatasetChanged = { on -> autoSaveDataset = on }
        guiPanel.onTouchOrientationModeChanged = { mode -> touchOrientationMode = mode; applyTouchOrientationConfig(); ConfigManager.updateConfig { touchOrientationMode = mode } }
        guiPanel.onAimClassesChanged = { classes -> aimClasses = classes.toMutableSet(); aimController.aimClasses = classes.toMutableSet(); ConfigManager.updateConfig { aimClasses = classes } }
        guiPanel.onPriorityClassChanged = { cls -> priorityClass = cls; aimController.priorityClass = cls; ConfigManager.updateConfig { priorityClass = cls } }
        guiPanel.onClassAimOffsetChanged = { id, value -> classAimOffsets = classAimOffsets.toMutableMap().apply { put(id, value) }; aimController.classAimOffsets = classAimOffsets; ConfigManager.updateConfig { classAimOffsets = this@FloatService.classAimOffsets } }
        guiPanel.onBoxAimRatioChanged = { boxAimRatio = it; aimController.boxAimRatio = it; ConfigManager.updateConfig { boxAimRatio = it } }
        guiPanel.onClassBoxAimRatioChanged = { id, value -> classBoxAimRatios = classBoxAimRatios.toMutableMap().apply { put(id, value) }; aimController.classBoxAimRatios = classBoxAimRatios; ConfigManager.updateConfig { classBoxAimRatios = this@FloatService.classBoxAimRatios } }
        guiPanel.onClassTriggerOffsetChanged = { id, value -> classTriggerOffsets = classTriggerOffsets.toMutableMap().apply { put(id, value) }; triggerController.classTriggerOffsets = classTriggerOffsets; ConfigManager.updateConfig { classTriggerOffsets = this@FloatService.classTriggerOffsets } }
        guiPanel.onTriggerClassesChanged = { classes -> triggerClasses = classes.toMutableSet(); triggerController.triggerClasses = classes.toMutableSet(); ConfigManager.updateConfig { triggerClasses = classes } }
        guiPanel.onToggleModel = { running ->
            modelRunning = running
            if (running && !inferRunning.get()) startInferLoop()
            else if (!running) {
                if (!recordEnabled) { inferRunning.set(false); broadcastState(1) }
            }
        }
        guiPanel.onTestCircle = { mainHandler.post { Thread { val cx = screenWidth / 2; val cy = screenHeight / 2; val radius = 200; val steps = 72; val aspect = screenWidth.toFloat() / screenHeight.toFloat(); touchClient?.swipe(cx, cy, cx, cy, 0); Thread.sleep(50); for (i in 1 until steps) { val angle = (i * 360.0 / steps) * Math.PI / 180.0; val x = (cx + radius * aspect * Math.cos(angle)).toInt(); val y = (cy + radius * Math.sin(angle)).toInt(); touchClient?.moveTo(x, y); Thread.sleep(20) }; touchClient?.lift() }.start() } }
        guiPanel.onAreaSettingsToggle = { showAreaSettings() }

        overlayView.rangeRadius = guiPanel.range; JniCallBack.setConfidence(guiPanel.confidence)
        setupTriggerOverlay(); setupTouchDisplayView()
        wm.addView(guiPanel, guiParams); guiAdded = true; guiVisible = true
        guiPanel.alpha = 0f; guiPanel.scaleX = 0.85f; guiPanel.scaleY = 0.85f
        guiPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    private fun hideGui() {
        if (guiAdded) guiPanel.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(150).withEndAction { guiPanel.visibility = View.GONE }.start()
        guiVisible = false
    }

    private fun moveGuiPanel(dx: Int, dy: Int) {
        val params = guiParams ?: return
        params.x = (params.x + dx).coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
        params.y = (params.y + dy).coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
        try { wm.updateViewLayout(guiPanel, params) } catch (_: Exception) {}
    }

    private fun resizeGuiPanel(dx: Int, dy: Int) {
        val params = guiParams ?: return
        val minWidth = dp(240); val minHeight = dp(320)
        val maxWidth = (screenWidth * 0.92f).toInt().coerceAtLeast(dp(180))
        val maxHeight = (screenHeight * 0.88f).toInt().coerceAtLeast(dp(220))
        val safeMinWidth = minWidth.coerceAtMost(maxWidth); val safeMinHeight = minHeight.coerceAtMost(maxHeight)
        params.width = (params.width + dx).coerceIn(safeMinWidth, maxWidth)
        params.height = (params.height + dy).coerceIn(safeMinHeight, maxHeight)
        params.x = params.x.coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
        try { wm.updateViewLayout(guiPanel, params) } catch (_: Exception) {}
    }

    private fun isPointInGuiPanel(x: Int, y: Int): Boolean {
        val params = guiParams ?: return false
        if (!guiAdded || !guiVisible || guiPanel.visibility != View.VISIBLE) return false
        return x >= params.x && x <= params.x + params.width && y >= params.y && y <= params.y + params.height
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
                removeAreaSettingsView(); if (!guiVisible) showGui()
            }
            onCancel = { removeAreaSettingsView(); if (!guiVisible) showGui() }
        }
        wm.addView(areaSettingsView!!, params); areaSettingsAdded = true
        areaSettingsView!!.visibility = View.GONE
    }

    private fun showAreaSettings() {
        if (areaSettingsView == null) setupAreaSettingsView()
        if (areaSettingsAdded) {
            if (guiVisible) hideGui()
            areaSettingsView?.apply {
                visibility = View.VISIBLE; alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
                setAreas(this@FloatService.savedAreas)
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }

    private fun removeAreaSettingsView() {
        if (areaSettingsAdded) {
            areaSettingsView?.animate()?.alpha(0f)?.scaleX(0.85f)?.scaleY(0.85f)?.setDuration(150)
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
            while (inferRunning.get()) {
                if (++aliveCtr % 30 == 0) { Log.d(TAG, "alive trigger=$triggerEnabled shizuku=${touchClient?.isConnected()} detects=${hasDetects.get()}") }
                val currentRange = guiPanel.range
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
                } catch (e: Exception) { Log.e(TAG, "推理帧异常: ${e.message}") }
                finally { hwBuf?.close(); image.close() }
            }
            inferRunning.set(false)
        }
    }

    private fun makeParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val ch = NotificationChannel(CH_ID, "YoloTouchHelp", NotificationManager.IMPORTANCE_LOW); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch) } }
    private fun buildNotification() = NotificationCompat.Builder(this, CH_ID).setContentTitle("YoloTouchHelp").setContentText("运行中").setSmallIcon(android.R.drawable.ic_menu_view).build()
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
        if (guiAdded && guiVisible) { wm.removeView(guiPanel); guiAdded = false; showGui() }
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
        private fun blocked(x: Int, y: Int) = isPointInGuiPanel(x, y)
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
