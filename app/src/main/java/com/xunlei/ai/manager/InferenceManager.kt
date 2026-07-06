package com.xunlei.ai.manager
import com.xunlei.ai.model.DetectionInfo

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.xunlei.ai.service.FloatService
import com.xunlei.ai.controller.AimController
import com.xunlei.ai.controller.TriggerController
import com.xunlei.ai.view.OverlayCanvasView
import com.xunlei.ai.inference.JniCallBack
import com.xunlei.ai.util.ProjectionHolder

class InferenceManager(
    private val service: FloatService,
    private val aimController: AimController,
    private val triggerController: TriggerController,
    private val overlayCanvasView: () -> OverlayCanvasView?
) {
    companion object {
        private const val TAG = "InferenceManager"
    }

    // Inference state
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    val inferRunning = AtomicBoolean(false)
    val hasDetects = AtomicBoolean(false)
    var modelRunning = false
    var lastModelIndex = 0
    var currentClasses: Map<Int, String> = emptyMap()

    // Detection buffer
    private val detectionBuffer = Array(20) { DetectionInfo(RectF(), -1, "") }
    var lastDetections: List<DetectionInfo> = emptyList()
    var centerX = 0f
    var centerY = 0f
    var cachedRange = 0f
    var cachedRangePx = 0

    // Dataset saving
    var autoSaveDataset = false
    var datasetCounter = -1
    val datasetDir: File by lazy {
        File(service.getExternalFilesDir(null), "dataset").apply { mkdirs() }
    }

    // Screen dimensions
    var captureW = 0
    var captureH = 0
    val mainHandler = Handler(Looper.getMainLooper())

    private fun clearAimSession(reason: String, clearVisualTargets: Boolean = true, clearLockRay: Boolean = true) {
        aimController.lift()
        aimController.reset()
        if (clearLockRay) {
            mainHandler.post { overlayCanvasView()?.updateLockRay(null, null) }
        }
        if (clearVisualTargets) {
            hasDetects.set(false)
            lastDetections = emptyList()
            mainHandler.post { overlayCanvasView()?.updateDetections(emptyList()) }
        }
        Log.d(TAG, "clearAimSession: reason=$reason")
    }

    // MediaProjection and ImageReader
    var imageReader: ImageReader? = null
    var captureVirtualDisplay: android.hardware.display.VirtualDisplay? = null

    // Recording
    var mediaRecorder: android.media.MediaRecorder? = null
    var recordSurface: android.view.Surface? = null
    var recordEnabled = false

    // Touch display
    var touchDisplayEnabled = false

    fun loadModel(filename: String) {
        val wasRunning = inferRunning.getAndSet(false)
        try { executor.submit { }.get() } catch (_: Exception) {}
        JniCallBack.release()
        val entry = ProjectionHolder.modelList.find { it.filename == filename }
        val modelFile = entry?.sourcePath?.let { File(it) } ?: File(service.applicationContext.filesDir, filename)
        try {
            val qnnCache = File(service.applicationContext.cacheDir, "qnn")
            if (qnnCache.exists()) qnnCache.deleteRecursively()
            qnnCache.mkdirs()
            if (entry?.sourcePath == null) {
                if (!modelFile.exists()) {
                    // Create parent directories if needed (for ncnn/ subdirectory)
                    modelFile.parentFile?.mkdirs()
                    service.assets.open(filename).use { i ->
                        FileOutputStream(modelFile).use { o -> i.copyTo(o) }
                    }
                }
                // For NCNN models, also copy the .bin file
                if (filename.endsWith(".param")) {
                    val binFilename = filename.replace(".param", ".bin")
                    val binFile = File(service.applicationContext.filesDir, binFilename)
                    if (!binFile.exists()) {
                        service.assets.open(binFilename).use { i ->
                            FileOutputStream(binFile).use { o -> i.copyTo(o) }
                        }
                    }
                }
            }
            val cfg = ConfigManager.getConfig()
            val useCpu = cfg.useCpuInference
            Log.d(TAG, "loadModel: useCpuInference=$useCpu, threads=${cfg.cpuThreadCount}")
            JniCallBack.setForceCpu(useCpu)
            JniCallBack.setCpuThreads(cfg.cpuThreadCount)
            if (JniCallBack.init(modelFile.absolutePath)) {
                val backend = JniCallBack.getBackend()
                Log.d(TAG, "模型切换成功: $filename, 后端=$backend")
                ProjectionHolder.currentModelName = backend
                // Load classes map from ProjectionHolder
                currentClasses = entry?.classes ?: emptyMap()
                // 模型切换时更新类别选择：保留仍存在的类别，新增的自动选中
                if (currentClasses.isNotEmpty()) {
                    val validIds = currentClasses.keys
                    aimController.aimClasses = aimController.aimClasses.filter { it in validIds }.toMutableSet()
                    if (aimController.aimClasses.isEmpty()) aimController.aimClasses = validIds.toMutableSet()
                    triggerController.triggerClasses = triggerController.triggerClasses.filter { it in validIds }.toMutableSet()
                    if (triggerController.triggerClasses.isEmpty()) triggerController.triggerClasses = validIds.toMutableSet()
                }
                Log.d(TAG, "模型类别: $currentClasses, aimClasses=${aimController.aimClasses}, triggerClasses=${triggerController.triggerClasses}")
                broadcastState(ProjectionHolder.currentState)
            } else {
                Log.e(TAG, "模型切换失败: $filename")
            }
        } catch (e: Exception) {
            Log.e(TAG, "模型切换异常: ${e.message}")
        }
        if (wasRunning) startInferLoop()
    }

    fun startInferLoop() {
        if (inferRunning.getAndSet(true)) {
            Log.d(TAG, "infer loop already running")
            return
        }
        broadcastState(2) // INFERENCING
        centerX = captureW / 2f
        centerY = captureH / 2f
        Log.d(TAG, "infer loop started, center=($centerX,$centerY)")
        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var aliveCtr = 0
            while (inferRunning.get()) {
                if (++aliveCtr % 30 == 0) {
                    Log.d(TAG, "alive trigger=${triggerController.triggerEnabled} shizuku=${service.touchClient?.isConnected()} detects=${hasDetects.get()}")
                }
                val currentRange = cachedRangePx
                if (currentRange != cachedRangePx) {
                    cachedRangePx = currentRange
                    cachedRange = currentRange.toFloat()
                }
                val image = imageReader?.acquireLatestImage()
                if (image == null) {
                    Thread.yield()
                    continue
                }
                val hwBuf = image.hardwareBuffer
                try {
                    // 录屏: 把当前帧转发给 MediaRecorder
                    if (recordEnabled && recordSurface != null && hwBuf != null) {
                        try {
                            val canvas = recordSurface!!.lockHardwareCanvas()
                            try {
                                val bmp = Bitmap.wrapHardwareBuffer(hwBuf, null)
                                if (bmp != null) {
                                    canvas.drawBitmap(bmp, 0f, 0f, null)
                                    bmp.recycle()
                                }
                            } finally {
                                recordSurface!!.unlockCanvasAndPost(canvas)
                            }
                        } catch (_: Exception) {}
                    }
                    hasDetects.set(false)
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val regionW = cachedRangePx * 2
                    val regionH = cachedRangePx * 2
                    val offsetX = (captureW - regionW) / 2
                    val offsetY = (captureH - regionH) / 2

                    val result = JniCallBack.detect(buffer, offsetX, offsetY, regionW, regionH, captureW, captureH, plane.rowStride, plane.pixelStride)

                    if (result != null) {
                        val count = result.size / 6
                        if (count > 0) {
                            val cid = result[0].toInt()
                            val sc = result[1]
                            val className = currentClasses[cid] ?: "unknown"
                            Log.d(TAG, "detect: count=$count, classId=$cid ($className) score=${"%.3f".format(sc)}")
                        }
                        var detCount = 0
                        var i = 0
                        while (i < count && detCount < detectionBuffer.size) {
                            val cid = result[i * 6].toInt()
                            val rect = RectF(
                                result[i * 6 + 2] * captureW,
                                result[i * 6 + 3] * captureH,
                                result[i * 6 + 4] * captureW,
                                result[i * 6 + 5] * captureH
                            )
                            detectionBuffer[detCount] = DetectionInfo(rect, cid, currentClasses[cid] ?: "cls$cid")
                            detCount++
                            i++
                        }
                        lastDetections = detectionBuffer.take(detCount)
                        hasDetects.set(detCount > 0)
                        mainHandler.post { overlayCanvasView()?.updateDetections(lastDetections) }

                        if (autoSaveDataset && detCount > 0 && hwBuf != null) {
                            saveDatasetFrame(hwBuf, result, count)
                        }

                        // 按住激发: 物理手指按在触发区或开镜区时都能触发自瞄
                        val holdToAimActive = if (aimController.aimHoldEnabled) {
                            val adsAimTrigger = triggerController.triggerEnabled && triggerController.autoTriggerAdsEnabled
                            if (adsAimTrigger) {
                                true
                            } else {
                                val fingerOnTrigger = service.touchClient?.isFingerInTriggerZone() ?: false
                                val fingerOnAds = service.touchClient?.isFingerInAdsZone() ?: false
                                fingerOnTrigger || fingerOnAds
                            }
                        } else true

                        // Filter detections by aimClasses
                        val aimDets = if (aimController.aimClasses.isEmpty()) lastDetections
                            else lastDetections.filter { it.classId in aimController.aimClasses }

                        val target = if (aimDets.isNotEmpty()) {
                            aimController.selectTarget(aimDets, centerX, centerY)
                        } else null
                        val rayPoint = if (aimController.showLockRay) aimController.currentLockRayPoint() else null
                        mainHandler.post { overlayCanvasView()?.updateLockRay(rayPoint?.first, rayPoint?.second) }

                        if (service.aimbotOn.get() && target != null && holdToAimActive) {
                            aimController.executeAiming(target.aimX, target.aimY, centerX, centerY)
                        } else {
                            val reason = when {
                                !service.aimbotOn.get() -> "aimbot_off"
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
                    } else {
                        clearAimSession("detect_null")
                    }

                    // detection-based trigger: center in any detection box (filtered by aimClasses)
                    triggerController.processTrigger(lastDetections, centerX, centerY, hasDetects.get())

                    if (result == null) {
                        hasDetects.set(false)
                        lastDetections = emptyList()
                        mainHandler.post { overlayCanvasView()?.updateDetections(lastDetections) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "推理帧异常: ${e.message}")
                } finally {
                    hwBuf?.close()
                    image.close()
                }
            }
            inferRunning.set(false)
        }
    }

    private fun saveDatasetFrame(hwBuf: android.hardware.HardwareBuffer, result: FloatArray, count: Int) {
        try {
            // 首次保存时扫描目录，找到最大编号避免覆盖
            if (datasetCounter < 0) {
                datasetCounter = datasetDir.listFiles { f -> f.name.endsWith(".jpg") }
                    ?.mapNotNull { f -> f.nameWithoutExtension.toIntOrNull() }
                    ?.maxOrNull()?.let { it + 1 } ?: 0
            }
            val bmp = Bitmap.wrapHardwareBuffer(hwBuf, null) ?: return
            val idx = datasetCounter++
            val name = "%06d".format(idx)
            val imgFile = File(datasetDir, "$name.jpg")
            FileOutputStream(imgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
            // 生成 YOLO 标注文件
            val txtFile = File(datasetDir, "$name.txt")
            BufferedWriter(FileWriter(txtFile)).use { w ->
                for (i in 0 until count) {
                    val classId = result[i * 6].toInt()
                    val x1 = result[i * 6 + 2]
                    val y1 = result[i * 6 + 3]
                    val x2 = result[i * 6 + 4]
                    val y2 = result[i * 6 + 5]
                    val cx = (x1 + x2) / 2f
                    val cy = (y1 + y2) / 2f
                    val bw = x2 - x1
                    val bh = y2 - y1
                    w.write("$classId $cx $cy $bw $bh\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dataset save failed", e)
        }
    }

    fun broadcastState(state: Int, modelName: String? = null) {
        ProjectionHolder.updateState(state, modelName ?: ProjectionHolder.currentModelName)
    }

    fun cleanup() {
        if (mediaRecorder != null) toggleRecording(false)
        inferRunning.set(false)
        executor.shutdown()
    }

    fun toggleRecording(enabled: Boolean) {
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
                mediaRecorder = null
                recordSurface = null
            }
        } else {
            recordEnabled = false
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Stop failed", e)
            }
            mediaRecorder = null
            recordSurface = null
            // 如果模型没在运行，停止推理循环
            if (!modelRunning) {
                inferRunning.set(false)
                broadcastState(1)
            }
            Log.d(TAG, "Recording stopped")
        }
    }

    fun setupImageReader(screenWidth: Int, screenHeight: Int, screenDensity: Int) {
        captureW = screenWidth
        captureH = screenHeight
        Log.d(TAG, "setupImageReader: w=${captureW} h=${captureH}")

        imageReader = ImageReader.newInstance(captureW, captureH, android.graphics.PixelFormat.RGBA_8888, 2)
        service.mediaProjection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection 停止")
                inferRunning.set(false)
                imageReader?.close()
            }
        }, Handler(Looper.getMainLooper()))
        captureVirtualDisplay = service.mediaProjection?.createVirtualDisplay(
            "AimbotCapture",
            captureW,
            captureH,
            screenDensity,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            null
        )
    }

    fun restartCapture(screenWidth: Int, screenHeight: Int, screenDensity: Int) {
        val curW = screenWidth
        val curH = screenHeight
        if (captureW == curW && captureH == curH) return

        val wasRunning = inferRunning.getAndSet(false)
        Log.d(TAG, "restartCapture: wasRunning=$wasRunning newSize=${curW}x${curH}")
        executor.execute {
            try { Thread.sleep(200) } catch (_: Exception) {}
            // Close old reader
            val oldReader = imageReader
            imageReader = null
            try { oldReader?.close() } catch (_: Exception) {}
            // Create new reader at new size
            imageReader = ImageReader.newInstance(curW, curH, android.graphics.PixelFormat.RGBA_8888, 2)
            // Resize VirtualDisplay and attach new surface
            try {
                captureVirtualDisplay?.resize(curW, curH, screenDensity)
                captureVirtualDisplay?.setSurface(imageReader!!.surface)
                Log.d(TAG, "restartCapture: resized to ${curW}x${curH}")
            } catch (e: Exception) {
                Log.w(TAG, "VirtualDisplay resize failed: ${e.message}")
            }
            captureW = curW
            captureH = curH
            service.touchClient?.setResolution(captureW, captureH, service.deviceAbsMaxX, service.deviceAbsMaxY)
            service.touchClient?.setOrientationConfig(service.currentDisplayRotation())
            centerX = captureW / 2f
            centerY = captureH / 2f
            if (wasRunning) startInferLoop()
        }
    }
}

