package com.yolotouchhelp.aimbot.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 平板端远程推理客户端。
 *
 * 接口与 JniCallBack 一致（init / detect / release / isConnected / backend）。
 *
 * 帧传输：RGBA ROI → H.264 硬件编码（MediaCodec + Surface input）→ TCP。
 * 反馈：DETECTIONS 消息解码为 FloatArray（每 6 个 = [classId, score, x1, y1, x2, y2]）。
 *
 * 线程模型：detect() 是阻塞调用，由调用方在 inference 线程里同步调用。
 * 网络读超时：8s（防服务端僵死）。
 */
object RemoteInferenceClient {
    private const val TAG = "RemoteInfClient"
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val SOCKET_READ_TIMEOUT_MS = 8000
    // 120-144Hz 高帧率下，固定 1.5Mbps 会出现明显块状瑕疵。
    // 按目标帧率与 ROI 分辨率动态计算：fps * 像素数 * bpp
    // 这里取 bpp≈0.18，144fps@320×320 ≈ 2.7Mbps
    private const val H264_BPP = 0.18f
    private const val H264_BITRATE_MIN = 2_000_000
    private const val H264_BITRATE_MAX = 12_000_000
    private const val I_FRAME_INTERVAL = 1

    @Volatile private var connected = false
    @Volatile private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val frameIdGen = AtomicInteger(0)
    private val lastRttMs = AtomicLong(-1L)
    @Volatile private var lastServerBackend = "?"
    @Volatile private var lastServerMsg = ""

    private var codec: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var srcW = 0
    private var srcH = 0
    private var encW = 0
    private var encH = 0
    private var encFps = 60
    private var frameCount = 0
    private val bufferInfo = MediaCodec.BufferInfo()

    // ROI 拷贝临时缓冲（每帧重分配会很慢，所以用预分配）
    private var roiBuffer: ByteBuffer? = null
    private var bmp: Bitmap? = null
    private var canvas: Canvas? = null
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val srcRect = Rect()
    private val dstRect = Rect()
    // 预分配的整行拷贝目标 buffer，size 取决于 ROI 单行字节数
    private var rowCopyBuf: ByteArray? = null

    // ---------- 公共 API（仿 JniCallBack） ----------

    fun init(
        ip: String,
        port: Int,
        modelName: String,
        inputSize: Int,
        classesJson: String,
        confidence: Float = 0.5f,
        useCpu: Boolean = false,
        cpuThreads: Int = 4,
        frameRate: Int = 60,
        targetWidth: Int = 320,
        targetHeight: Int = 320
    ): Boolean {
        if (connected) release()

        // 1) 建连
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.soTimeout = SOCKET_READ_TIMEOUT_MS
            s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            Log.e(TAG, "connect $ip:$port failed", e)
            try { s.close() } catch (_: Exception) {}
            return false
        }
        socket = s
        val ins = DataInputStream(s.getInputStream())
        val outs = DataOutputStream(s.getOutputStream())
        input = ins
        output = outs

        // 2) 握手
        try {
            val info = RemoteInferenceProtocol.HandshakeInfo(
                protocolVersion = RemoteInferenceProtocol.PROTOCOL_VERSION,
                modelName = modelName,
                inputSize = inputSize,
                classesJson = classesJson,
                confidence = confidence,
                useCpu = useCpu,
                cpuThreads = cpuThreads
            )
            outs.writeTextMessage(
                RemoteInferenceProtocol.TYPE_HANDSHAKE_REQ,
                RemoteInferenceProtocol.encodeHandshakeReq(info)
            )
            outs.flush()
            val (t, payload) = ins.readFullMessage()
            if (t != RemoteInferenceProtocol.TYPE_HANDSHAKE_ACK) {
                Log.e(TAG, "expected HANDSHAKE_ACK, got 0x%02x".format(t.toInt() and 0xFF))
                release()
                return false
            }
            val ack = RemoteInferenceProtocol.decodeHandshakeAck(String(payload, Charsets.UTF_8))
            lastServerBackend = ack.backend
            lastServerMsg = ack.msg
            if (!ack.ok) {
                Log.e(TAG, "server refused: ${ack.msg}")
                release()
                return false
            }
        } catch (e: IOException) {
            Log.e(TAG, "handshake failed", e)
            release()
            return false
        }

        // 3) 初始化 H.264 编码器
        if (!initEncoder(targetWidth, targetHeight, frameRate)) {
            Log.e(TAG, "encoder init failed")
            release()
            return false
        }
        encW = targetWidth
        encH = targetHeight
        encFps = frameRate
        roiBuffer = ByteBuffer.allocateDirect(targetWidth * targetHeight * 4)
        bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bmp!!)
        // 预分配行拷贝缓冲，按最大可能行字节数（640*4）
        rowCopyBuf = ByteArray(targetWidth * 4)
        frameCount = 0
        connected = true
        Log.i(TAG, "connected to $ip:$port backend=$lastServerBackend")
        return true
    }

    fun detect(
        buffer: ByteBuffer,
        offsetX: Int, offsetY: Int,
        regionWidth: Int, regionHeight: Int,
        screenWidth: Int, screenHeight: Int,
        rowStride: Int, pixelStride: Int
    ): FloatArray? {
        if (!connected) return null
        val frameId = frameIdGen.incrementAndGet()
        val sentMs = System.currentTimeMillis()

        // 1) 编码 ROI 为 H.264
        val encoded = encodeH264(buffer, offsetX, offsetY, regionWidth, regionHeight,
                                  rowStride, pixelStride, frameId)
            ?: return null

        // 2) 发送
        val meta = RemoteInferenceProtocol.FrameMeta(
            frameId = frameId,
            offsetX = offsetX, offsetY = offsetY,
            regionW = regionWidth, regionH = regionHeight,
            captureW = screenWidth, captureH = screenHeight,
            rowStride = rowStride, pixelStride = pixelStride,
            codec = RemoteInferenceProtocol.CODEC_H264,
            flags = if (frameId % encFps == 0) RemoteInferenceProtocol.FLAG_KEYFRAME else 0,
            imageData = encoded
        )
        try {
            output!!.writeFrameMessage(meta)
            output!!.flush()
        } catch (e: IOException) {
            Log.e(TAG, "send frame failed", e)
            connected = false
            release()
            return null
        }

        // 3) 等待 DETECTIONS
        try {
            val (t, payload) = input!!.readFullMessage()
            if (t == RemoteInferenceProtocol.TYPE_ERROR) {
                Log.w(TAG, "server error: ${String(payload, Charsets.UTF_8)}")
                return null
            }
            if (t != RemoteInferenceProtocol.TYPE_DETECTIONS) {
                Log.w(TAG, "expected DETECTIONS, got 0x%02x".format(t.toInt() and 0xFF))
                return null
            }
            val detMsg = RemoteInferenceProtocol.decodeDetectionsPayload(payload)
            lastRttMs.set(System.currentTimeMillis() - sentMs)
            val out = FloatArray(detMsg.detections.size * 6)
            for (i in detMsg.detections.indices) {
                val d = detMsg.detections[i]
                out[i * 6 + 0] = d.classId.toFloat()
                out[i * 6 + 1] = d.score
                out[i * 6 + 2] = d.x1
                out[i * 6 + 3] = d.y1
                out[i * 6 + 4] = d.x2
                out[i * 6 + 5] = d.y2
            }
            return out
        } catch (e: IOException) {
            Log.e(TAG, "recv detections failed", e)
            connected = false
            release()
            return null
        }
    }

    fun release() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
        releaseEncoder()
        bmp?.recycle()
        bmp = null
        canvas = null
        roiBuffer = null
    }

    fun isConnected(): Boolean = connected
    fun backend(): String = lastServerBackend
    fun lastRtt(): Long = lastRttMs.get()
    fun lastSendFrameId(): Int = frameIdGen.get()

    // ---------- H.264 编码器 ----------

    private fun initEncoder(w: Int, h: Int, fps: Int): Boolean {
        // 动态码率：fps 越高、ROI 越大，码率按比例增长
        val bitrate = (w * h * fps * H264_BPP).toInt()
            .coerceIn(H264_BITRATE_MIN, H264_BITRATE_MAX)
        Log.i(TAG, "H264 encoder: ${w}x${h} @ ${fps}fps, bitrate=${bitrate / 1000}kbps")
        return try {
            val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            val enc = MediaCodec.createEncoderByType("video/avc")
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            codec = enc
            inputSurface = surface
            true
        } catch (e: Exception) {
            Log.e(TAG, "encoder init failed", e)
            false
        }
    }

    private fun releaseEncoder() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
    }

    /**
     * RGBA ROI → Bitmap → 编码器 input surface → 取 H.264 access unit。
     * inputSurface 用 Canvas 绘制，硬件加速。
     */
    private fun encodeH264(
        src: ByteBuffer,
        offsetX: Int, offsetY: Int,
        regionW: Int, regionH: Int,
        rowStride: Int, pixelStride: Int,
        frameId: Int
    ): ByteArray? {
        val c = codec ?: return null
        val surface = inputSurface ?: return null
        val b = bmp ?: return null
        val cv = canvas ?: return null

        // 1) 把 ROI 区域的 RGBA 数据拷贝到 bmp（直接内存拷贝）
        if (pixelStride == 4) {
            // 用预分配 ByteBuffer + 行拷贝快速 path
            val bmpBuf = ByteBuffer.allocateDirect(encW * encH * 4)
            val rowBytes = regionW * 4
            val row = rowCopyBuf ?: ByteArray(rowBytes).also { rowCopyBuf = it }
            for (r in 0 until regionH) {
                src.position((offsetY + r) * rowStride + offsetX * 4)
                val limit = src.position() + rowBytes
                if (limit > src.limit()) break
                src.limit(limit)
                src.get(row, 0, rowBytes)
                bmpBuf.put(row, 0, rowBytes)
                src.limit(src.capacity())
            }
            bmpBuf.position(0)
            bmp.copyPixelsFromBuffer(bmpBuf)
        } else {
            // 像素间隔不为 4，保守处理：逐像素拷贝
            val intArr = IntArray(regionW * regionH)
            for (row in 0 until regionH) {
                for (col in 0 until regionW) {
                    val idx = (offsetY + row) * rowStride + (offsetX + col) * pixelStride
                    if (idx + 3 >= src.limit()) break
                    val r = src.get(idx).toInt() and 0xFF
                    val g = src.get(idx + 1).toInt() and 0xFF
                    val bl = src.get(idx + 2).toInt() and 0xFF
                    val a = src.get(idx + 3).toInt() and 0xFF
                    intArr[row * regionW + col] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
            bmp.setPixels(intArr, 0, regionW, 0, 0, regionW, regionH)
        }

        // 2) 绘制到编码器输入 surface
        if (frameId % encFps == 0) {
            try {
                c.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1)
                })
            } catch (_: Exception) {}
        }
        srcRect.set(0, 0, regionW, regionH)
        dstRect.set(0, 0, encW, encH)
        try {
            val canvas2 = surface.lockCanvas(null)
            try {
                canvas2.drawColor(Color.BLACK)
                canvas2.drawBitmap(b, srcRect, dstRect, drawPaint)
            } finally {
                surface.unlockCanvasAndPost(canvas2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "lockCanvas failed", e)
            return null
        }

        // 3) 取编码后的 access unit
        val out = java.io.ByteArrayOutputStream(4096)
        var gotData = false
        // 120-144Hz 单帧预算 ~7ms，编码端超时 12ms 留余量
        val endMs = System.currentTimeMillis() + 12
        while (System.currentTimeMillis() < endMs) {
            val idx = c.dequeueOutputBuffer(bufferInfo, 2_000L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (gotData) break
                    continue
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx) ?: continue
                    if (bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        buf.position(bufferInfo.offset)
                        buf.get(data)
                        out.write(data)
                        gotData = true
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        return if (gotData) out.toByteArray() else null
    }
}
