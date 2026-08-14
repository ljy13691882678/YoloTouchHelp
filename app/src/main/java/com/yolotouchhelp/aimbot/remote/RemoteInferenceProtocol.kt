package com.yolotouchhelp.aimbot.remote

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * 远程推理协议 - 平板与手机间的二进制消息格式
 *
 * 所有消息使用统一帧头：
 *   [4B 总长度(不含此4B)][1B type][payload]
 *
 * 消息类型：
 *   0x01 HANDSHAKE_REQ   客户端 → 服务端
 *   0x02 HANDSHAKE_ACK   服务端 → 客户端
 *   0x03 FRAME           客户端 → 服务端  (ROI 帧 + 元数据)
 *   0x04 DETECTIONS      服务端 → 客户端  (检测结果)
 *   0x05 PING            双向
 *   0x06 PONG            双向
 *   0x07 ERROR           服务端 → 客户端  (UTF-8 错误信息)
 *   0x08 CONFIG          客户端 → 服务端  (运行时配置：conf/forceCpu/threads)
 *
 * FRAME payload (固定 25B 头 + 图像):
 *   [4B frame_id]
 *   [2B offset_x][2B offset_y]
 *   [2B region_w][2B region_h]
 *   [2B capture_w][2B capture_h]
 *   [2B row_stride][2B pixel_stride]
 *   [1B jpeg_quality] (0 = 原始 RGBA, 1-100 = JPEG)
 *   [4B image_data_length]
 *   [N B image_data]
 *
 * DETECTIONS payload:
 *   [4B frame_id]
 *   [2B num_detections]
 *   For each: [1B class_id][4B score][4B x1][4B y1][4B x2][4B y2] = 22B
 *
 * HANDSHAKE_REQ payload (UTF-8 JSON):
 *   {"v":1,"model":"yolov8n_int8_256.tflite","inputSize":256,
 *    "confidence":0.5,"useCpu":false,"cpuThreads":4,
 *    "classes":{"0":"head","1":"body"}}
 *
 * HANDSHAKE_ACK payload (UTF-8 JSON):
 *   {"v":1,"backend":"QNN HTP","ok":true,"msg":"..."}
 */
object RemoteInferenceProtocol {
    const val PROTOCOL_VERSION = 1

    const val TYPE_HANDSHAKE_REQ: Byte = 0x01
    const val TYPE_HANDSHAKE_ACK: Byte = 0x02
    const val TYPE_FRAME: Byte = 0x03
    const val TYPE_DETECTIONS: Byte = 0x04
    const val TYPE_PING: Byte = 0x05
    const val TYPE_PONG: Byte = 0x06
    const val TYPE_ERROR: Byte = 0x07
    const val TYPE_CONFIG: Byte = 0x08

    const val MAX_MESSAGE_SIZE = 4 * 1024 * 1024  // 4MB 上限
    const val MAX_FRAME_SIZE = 3 * 1024 * 1024    // 单帧图片上限 3MB
    const val HEADER_SIZE = 4 + 1                  // 长度 + 类型

    data class FrameMeta(
        val frameId: Int,
        val offsetX: Int, val offsetY: Int,
        val regionW: Int, val regionH: Int,
        val captureW: Int, val captureH: Int,
        val rowStride: Int, val pixelStride: Int,
        val jpegQuality: Int,
        val imageData: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameMeta) return false
            return frameId == other.frameId &&
                    offsetX == other.offsetX && offsetY == other.offsetY &&
                    regionW == other.regionW && regionH == other.regionH &&
                    captureW == other.captureW && captureH == other.captureH &&
                    rowStride == other.rowStride && pixelStride == other.pixelStride &&
                    jpegQuality == other.jpegQuality &&
                    imageData.contentEquals(other.imageData)
        }
        override fun hashCode(): Int = frameId
    }

    data class Detection(
        val classId: Int,
        val score: Float,
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float
    )

    data class DetectionsMessage(
        val frameId: Int,
        val detections: List<Detection>
    )

    data class HandshakeInfo(
        val protocolVersion: Int,
        val modelName: String,
        val inputSize: Int,
        val classesJson: String,
        val confidence: Float,
        val useCpu: Boolean,
        val cpuThreads: Int
    )

    data class HandshakeAck(
        val protocolVersion: Int,
        val backend: String,
        val ok: Boolean,
        val msg: String
    )

    // ---------- 序列化 ----------

    fun encodeFramePayload(meta: FrameMeta): ByteArray {
        val baos = ByteArrayOutputStream(64 + meta.imageData.size)
        val dos = DataOutputStream(baos)
        dos.writeInt(meta.frameId)
        dos.writeShort(meta.offsetX)
        dos.writeShort(meta.offsetY)
        dos.writeShort(meta.regionW)
        dos.writeShort(meta.regionH)
        dos.writeShort(meta.captureW)
        dos.writeShort(meta.captureH)
        dos.writeShort(meta.rowStride)
        dos.writeShort(meta.pixelStride)
        dos.writeByte(meta.jpegQuality)
        dos.writeInt(meta.imageData.size)
        dos.write(meta.imageData)
        return baos.toByteArray()
    }

    fun decodeFramePayload(payload: ByteArray): FrameMeta {
        val dis = DataInputStream(ByteArrayInputStream(payload))
        val frameId = dis.readInt()
        val offsetX = dis.readShort().toInt()
        val offsetY = dis.readShort().toInt()
        val regionW = dis.readShort().toInt()
        val regionH = dis.readShort().toInt()
        val captureW = dis.readShort().toInt()
        val captureH = dis.readShort().toInt()
        val rowStride = dis.readShort().toInt()
        val pixelStride = dis.readShort().toInt()
        val jpegQuality = dis.readByte().toInt()
        val imageLen = dis.readInt()
        if (imageLen < 0 || imageLen > MAX_FRAME_SIZE) {
            throw IOException("invalid image length: $imageLen")
        }
        val image = ByteArray(imageLen)
        dis.readFully(image)
        return FrameMeta(
            frameId, offsetX, offsetY, regionW, regionH,
            captureW, captureH, rowStride, pixelStride,
            jpegQuality, image
        )
    }

    fun encodeDetectionsPayload(msg: DetectionsMessage): ByteArray {
        val baos = ByteArrayOutputStream(64 + msg.detections.size * 22)
        val dos = DataOutputStream(baos)
        dos.writeInt(msg.frameId)
        dos.writeShort(msg.detections.size.coerceIn(0, 65535))
        for (d in msg.detections) {
            dos.writeByte(d.classId)
            dos.writeFloat(d.score)
            dos.writeFloat(d.x1)
            dos.writeFloat(d.y1)
            dos.writeFloat(d.x2)
            dos.writeFloat(d.y2)
        }
        return baos.toByteArray()
    }

    fun decodeDetectionsPayload(payload: ByteArray): DetectionsMessage {
        val dis = DataInputStream(ByteArrayInputStream(payload))
        val frameId = dis.readInt()
        val n = dis.readShort().toInt() and 0xFFFF
        val list = ArrayList<Detection>(n)
        repeat(n) {
            val cid = dis.readByte().toInt() and 0xFF
            val sc = dis.readFloat()
            val x1 = dis.readFloat()
            val y1 = dis.readFloat()
            val x2 = dis.readFloat()
            val y2 = dis.readFloat()
            list.add(Detection(cid, sc, x1, y1, x2, y2))
        }
        return DetectionsMessage(frameId, list)
    }

    fun encodeHandshakeReq(info: HandshakeInfo): String {
        return JSONObject().apply {
            put("v", info.protocolVersion)
            put("model", info.modelName)
            put("inputSize", info.inputSize)
            put("classes", info.classesJson)
            put("confidence", info.confidence.toDouble())
            put("useCpu", info.useCpu)
            put("cpuThreads", info.cpuThreads)
        }.toString()
    }

    fun decodeHandshakeReq(json: String): HandshakeInfo {
        val o = JSONObject(json)
        return HandshakeInfo(
            protocolVersion = o.optInt("v", 0),
            modelName = o.optString("model", ""),
            inputSize = o.optInt("inputSize", 0),
            classesJson = o.optJSONObject("classes")?.toString() ?: "{}",
            confidence = o.optDouble("confidence", 0.5).toFloat(),
            useCpu = o.optBoolean("useCpu", false),
            cpuThreads = o.optInt("cpuThreads", 4)
        )
    }

    fun encodeHandshakeAck(ack: HandshakeAck): String {
        return JSONObject().apply {
            put("v", ack.protocolVersion)
            put("backend", ack.backend)
            put("ok", ack.ok)
            put("msg", ack.msg)
        }.toString()
    }

    fun decodeHandshakeAck(json: String): HandshakeAck {
        val o = JSONObject(json)
        return HandshakeAck(
            protocolVersion = o.optInt("v", 0),
            backend = o.optString("backend", ""),
            ok = o.optBoolean("ok", false),
            msg = o.optString("msg", "")
        )
    }
}

// ---------- 流扩展 ----------

fun DataOutputStream.writeRawMessage(type: Byte, payload: ByteArray) {
    writeInt(1 + payload.size)
    writeByte(type.toInt())
    write(payload)
}

fun DataOutputStream.writeFrameMessage(meta: RemoteInferenceProtocol.FrameMeta) {
    writeRawMessage(RemoteInferenceProtocol.TYPE_FRAME, RemoteInferenceProtocol.encodeFramePayload(meta))
}

fun DataOutputStream.writeDetectionsMessage(msg: RemoteInferenceProtocol.DetectionsMessage) {
    writeRawMessage(RemoteInferenceProtocol.TYPE_DETECTIONS, RemoteInferenceProtocol.encodeDetectionsPayload(msg))
}

fun DataOutputStream.writeTextMessage(type: Byte, text: String) {
    writeRawMessage(type, text.toByteArray(Charsets.UTF_8))
}

/** 读消息头，返回 (type, payloadLen)。 */
fun DataInputStream.readMessageHeader(): Pair<Byte, Int> {
    val len = readInt()
    if (len < 1 || len > RemoteInferenceProtocol.MAX_MESSAGE_SIZE) {
        throw IOException("invalid message length: $len")
    }
    val type = readByte()
    return type to (len - 1)
}

/** 读一条完整消息。 */
fun DataInputStream.readFullMessage(): Pair<Byte, ByteArray> {
    val (type, payloadLen) = readMessageHeader()
    val payload = ByteArray(payloadLen)
    readFully(payload)
    return type to payload
}

fun DataInputStream.readTextMessage(type: Byte): String {
    val (t, payloadLen) = readMessageHeader()
    if (t != type) throw IOException("expected 0x%02x, got 0x%02x".format(type.toInt() and 0xFF, t.toInt() and 0xFF))
    val bytes = ByteArray(payloadLen)
    readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}

fun DataInputStream.readFrameMessage(): RemoteInferenceProtocol.FrameMeta {
    val (type, payloadLen) = readMessageHeader()
    if (type != RemoteInferenceProtocol.TYPE_FRAME) {
        throw IOException("expected FRAME, got 0x%02x".format(type.toInt() and 0xFF))
    }
    val payload = ByteArray(payloadLen)
    readFully(payload)
    return RemoteInferenceProtocol.decodeFramePayload(payload)
}

fun DataInputStream.readDetectionsMessage(): RemoteInferenceProtocol.DetectionsMessage {
    val (type, payloadLen) = readMessageHeader()
    if (type != RemoteInferenceProtocol.TYPE_DETECTIONS) {
        throw IOException("expected DETECTIONS, got 0x%02x".format(type.toInt() and 0xFF))
    }
    val payload = ByteArray(payloadLen)
    readFully(payload)
    return RemoteInferenceProtocol.decodeDetectionsPayload(payload)
}
