package com.yolotouchhelp.aimbot.remote

import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.yolotouchhelp.aimbot.inference.JniCallBack
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 手机端远程推理服务端。
 *
 * 工作流程：
 *   1. start() 启动 ServerSocket 监听端口（单客户端模式）
 *   2. 接受连接 → 后台线程：
 *      - 收 HANDSHAKE_REQ → JniCallBack.init(modelPath) → 发 HANDSHAKE_ACK
 *      - 循环：收 H.264 FRAME → MediaCodec 解码 → YUV→RGBA → JniCallBack.detect()
 *             → 序列化 detections → 发回
 *   3. stop() 关闭所有
 */
object RemoteInferenceServer {
    private const val TAG = "RemoteInfServer"
    const val STATE_IDLE = 0
    const val STATE_LISTENING = 1
    const val STATE_CONNECTED = 2
    const val STATE_ERROR = 3

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var workerThread: Thread? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var state = STATE_IDLE
    @Volatile private var lastError = ""
    @Volatile private var clientAddr = ""
    @Volatile private var frameCount = 0
    @Volatile private var avgInferMs = 0.0
    private val lastFrameMs = AtomicLong(0L)
    private val port = AtomicInteger(0)

    @Volatile private var listener: ((state: Int, msg: String) -> Unit)? = null
    private val stopping = AtomicBoolean(false)

    fun setOnStateChangeListener(l: (Int, String) -> Unit) { listener = l }

    fun state(): Int = state
    fun clientAddress(): String = clientAddr
    fun frameCount(): Int = frameCount
    fun avgInferMs(): Double = avgInferMs
    fun currentPort(): Int = port.get()
    fun lastError(): String = lastError

    /**
     * 启动服务端。
     * @param port 监听端口
     * @param modelPath TFLite 模型绝对路径（手机端 filesDir 内）
     * @param inputSize 模型输入尺寸（用于协议握手）
     * @param classesJson 类别 JSON（"0":"head",...）
     */
    fun start(
        port: Int,
        modelPath: String,
        inputSize: Int,
        classesJson: String,
        confidence: Float = 0.5f,
        useCpu: Boolean = false,
        cpuThreads: Int = 4
    ): Boolean {
        if (state == STATE_LISTENING || state == STATE_CONNECTED) {
            stop()
        }
        if (!File(modelPath).exists()) {
            setError("model not found: $modelPath")
            return false
        }
        stopping.set(false)
        this.port.set(port)

        val ss = try {
            ServerSocket(port, 1, InetAddress.getByName("0.0.0.0"))
        } catch (e: IOException) {
            setError("bind port $port failed: ${e.message}")
            return false
        }
        serverSocket = ss
        setState(STATE_LISTENING, "listening on :$port")

        workerThread = Thread({
            acceptLoop(modelPath, inputSize, classesJson, confidence, useCpu, cpuThreads)
        }, "RemoteInfServer-Worker").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        stopping.set(true)
        try { serverSocket?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        clientSocket = null
        workerThread?.interrupt()
        workerThread = null
        setState(STATE_IDLE, "stopped")
    }

    private fun acceptLoop(
        modelPath: String, inputSize: Int, classesJson: String,
        confidence: Float, useCpu: Boolean, cpuThreads: Int
    ) {
        val ss = serverSocket ?: return
        try {
            val client = ss.accept()
            if (stopping.get()) {
                try { client.close() } catch (_: Exception) {}
                return
            }
            clientSocket = client
            client.tcpNoDelay = true
            client.soTimeout = 30000
            clientAddr = (client.remoteSocketAddress?.toString() ?: "?")
            setState(STATE_CONNECTED, "client $clientAddr connected")

            handleClient(client, modelPath, inputSize, classesJson, confidence, useCpu, cpuThreads)

            try { client.close() } catch (_: Exception) {}
            clientSocket = null
            if (!stopping.get()) {
                setState(STATE_LISTENING, "client disconnected, listening again")
                workerThread = Thread({
                    acceptLoop(modelPath, inputSize, classesJson, confidence, useCpu, cpuThreads)
                }, "RemoteInfServer-Worker").apply { isDaemon = true; start() }
            }
        } catch (e: IOException) {
            if (!stopping.get()) setError("accept failed: ${e.message}")
        }
    }

    private fun handleClient(
        client: Socket,
        modelPath: String, inputSize: Int, classesJson: String,
        confidence: Float, useCpu: Boolean, cpuThreads: Int
    ) {
        val ins = DataInputStream(client.getInputStream())
        val outs = DataOutputStream(client.getOutputStream())

        // 1) 等待 HANDSHAKE_REQ
        val info = try {
            val json = ins.readTextMessage(RemoteInferenceProtocol.TYPE_HANDSHAKE_REQ)
            RemoteInferenceProtocol.decodeHandshakeReq(json)
        } catch (e: IOException) {
            setError("handshake read failed: ${e.message}")
            return
        }
        if (info.protocolVersion != RemoteInferenceProtocol.PROTOCOL_VERSION) {
            val err = "version mismatch: client=${info.protocolVersion} server=${RemoteInferenceProtocol.PROTOCOL_VERSION}"
            sendError(outs, err)
            setError(err)
            return
        }

        // 2) 加载模型
        if (!JniCallBack.init(modelPath)) {
            val err = "model init failed: ${info.modelName}"
            sendError(outs, err)
            setError(err)
            return
        }
        if (inputSize > 0) JniCallBack.setInputSize(inputSize, inputSize)
        if (useCpu) JniCallBack.setForceCpu(useCpu)
        JniCallBack.setCpuThreads(cpuThreads)
        JniCallBack.setConfidence(confidence)
        val backend = try { JniCallBack.getBackend() } catch (_: Exception) { "?" }

        // 3) 发 HANDSHAKE_ACK
        val ack = RemoteInferenceProtocol.HandshakeAck(
            protocolVersion = RemoteInferenceProtocol.PROTOCOL_VERSION,
            backend = backend,
            ok = true,
            msg = "ready"
        )
        try {
            outs.writeTextMessage(
                RemoteInferenceProtocol.TYPE_HANDSHAKE_ACK,
                RemoteInferenceProtocol.encodeHandshakeAck(ack)
            )
            outs.flush()
        } catch (e: IOException) {
            setError("ack send failed: ${e.message}")
            return
        }
        setState(STATE_CONNECTED, "ready, backend=$backend")

        // 4) 循环：FRAME → 解码 → 推理 → 回包
        val decoder = H264Decoder()
        try {
            if (!decoder.start(inputSize, inputSize)) {
                setError("decoder start failed")
                return
            }
            while (!stopping.get()) {
                val meta = try { ins.readFrameMessage() } catch (e: IOException) { break }
                if (meta.codec != RemoteInferenceProtocol.CODEC_H264) {
                    sendError(outs, "unsupported codec ${meta.codec}")
                    continue
                }
                if (!decoder.feed(meta.imageData,
                                  isKeyframe = meta.flags.toInt() and RemoteInferenceProtocol.FLAG_KEYFRAME.toInt() != 0)) {
                    sendError(outs, "decoder feed failed")
                    continue
                }
                val img = decoder.dequeueImage(timeoutUs = 50_000L)
                if (img == null) {
                    Log.w(TAG, "dequeueImage timeout")
                    continue
                }
                // YUV → RGBA
                val rgba = yuvImageToRgba(img, meta.regionW, meta.regionH)
                img.close()

                // JniCallBack.detect
                val t0 = System.currentTimeMillis()
                val det = JniCallBack.detect(
                    rgba, 0, 0,
                    meta.regionW, meta.regionH,
                    meta.regionW, meta.regionH,
                    meta.regionW * 4, 4
                )
                val inferMs = System.currentTimeMillis() - t0
                avgInferMs = if (avgInferMs == 0.0) inferMs.toDouble()
                             else avgInferMs * 0.9 + inferMs * 0.1

                // 构造 DETECTIONS
                val list: MutableList<RemoteInferenceProtocol.Detection> =
                    if (det == null) mutableListOf()
                    else ArrayList<RemoteInferenceProtocol.Detection>(det.size / 6)
                if (det != null) {
                    val n = det.size / 6
                    for (i in 0 until n) {
                        list.add(RemoteInferenceProtocol.Detection(
                            classId = det[i * 6].toInt(),
                            score = det[i * 6 + 1],
                            x1 = det[i * 6 + 2], y1 = det[i * 6 + 3],
                            x2 = det[i * 6 + 4], y2 = det[i * 6 + 5]
                        ))
                    }
                }
                try {
                    outs.writeDetectionsMessage(
                        RemoteInferenceProtocol.DetectionsMessage(meta.frameId, list)
                    )
                    outs.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "send detections failed", e)
                    break
                }
                frameCount++
                lastFrameMs.set(System.currentTimeMillis())
            }
        } finally {
            decoder.release()
        }
    }

    private fun sendError(outs: DataOutputStream, msg: String) {
        try {
            outs.writeTextMessage(RemoteInferenceProtocol.TYPE_ERROR, msg)
            outs.flush()
        } catch (_: Exception) {}
    }

    private fun setState(s: Int, msg: String) {
        state = s
        lastError = msg
        Log.i(TAG, "state=$s $msg")
        listener?.invoke(s, msg)
    }

    private fun setError(msg: String) {
        setState(STATE_ERROR, msg)
    }

    /** 工具：返回局域网 IPv4 地址（用于 UI 显示）。 */
    fun lanIpv4(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "?"
            for (iface in Collections.list(ifaces)) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses ?: continue
                for (addr in Collections.list(addrs)) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "?"
                    }
                }
            }
        } catch (_: Exception) {}
        return "?"
    }

    // ============== H.264 解码器 ==============

    private class H264Decoder {
        private var codec: MediaCodec? = null
        private var width = 0
        private var height = 0
        private val bufferInfo = MediaCodec.BufferInfo()

        fun start(w: Int, h: Int): Boolean {
            width = w; height = h
            return try {
                val format = MediaFormat.createVideoFormat("video/avc", w, h)
                val c = MediaCodec.createDecoderByType("video/avc")
                c.configure(format, null, null, 0)
                c.start()
                codec = c
                true
            } catch (e: Exception) {
                Log.e("H264Decoder", "start failed", e)
                false
            }
        }

        fun feed(data: ByteArray, isKeyframe: Boolean): Boolean {
            val c = codec ?: return false
            return try {
                val idx = c.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    val buf = c.getInputBuffer(idx) ?: return false
                    buf.clear()
                    buf.put(data)
                    c.queueInputBuffer(idx, 0, data.size, 0, 0)
                    true
                } else false
            } catch (e: Exception) {
                Log.e("H264Decoder", "feed failed", e)
                false
            }
        }

        fun dequeueImage(timeoutUs: Long): Image? {
            val c = codec ?: return null
            return try {
                val idx = c.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> null
                    idx >= 0 -> c.getOutputImage(idx)
                    else -> null
                }
            } catch (e: Exception) {
                Log.e("H264Decoder", "dequeueImage failed", e)
                null
            }
        }

        fun release() {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
        }
    }

    /**
     * 把 YUV_420_888 Image 转为 RGBA8888 ByteBuffer。
     * 支持 NV12/I420/YV12（通过 Plane.pixelStride 自动判断）。
     * 公式：BT.601 limited range。
     */
    private fun yuvImageToRgba(img: Image, w: Int, h: Int): ByteBuffer {
        val planes = img.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val out = ByteBuffer.allocateDirect(w * h * 4)
        val outArr = ByteArray(w * h * 4)

        for (y in 0 until h) {
            val yRowStart = y * yRowStride
            val uvRowStart = (y / 2) * uRowStride
            for (x in 0 until w) {
                val yVal = (yBuf.get(yRowStart + x * yPixelStride).toInt() and 0xFF)
                val uvX = x / 2
                val uVal: Int
                val vVal: Int
                if (uPixelStride == 1) {
                    uVal = (uBuf.get(uvRowStart + uvX).toInt() and 0xFF)
                    vVal = (vBuf.get((y / 2) * vRowStride + uvX).toInt() and 0xFF)
                } else {
                    // NV12: U 和 V 交错在 uPlane，pixelStride=2
                    val uvIdx = uvRowStart + uvX * uPixelStride
                    uVal = (uBuf.get(uvIdx).toInt() and 0xFF)
                    vVal = (uBuf.get(uvIdx + 1).toInt() and 0xFF)
                }
                val c = yVal - 16
                val d = uVal - 128
                val e = vVal - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                val o = (y * w + x) * 4
                outArr[o] = r.toByte()
                outArr[o + 1] = g.toByte()
                outArr[o + 2] = b.toByte()
                outArr[o + 3] = 0xFF.toByte()
            }
        }
        out.put(outArr)
        out.position(0)
        return out
    }
}
