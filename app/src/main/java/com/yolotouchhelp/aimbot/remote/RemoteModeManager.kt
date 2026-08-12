package com.yolotouchhelp.aimbot.remote

import android.util.Log
import com.yolotouchhelp.aimbot.model.DetectionInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

object RemoteModeManager {
    enum class Mode { LOCAL, HOST, CLIENT }
    var mode = Mode.LOCAL

    // Host (server) settings
    var hostPort = 8765
    private var serverSocket: ServerSocket? = null
    private var hostSocket: Socket? = null
    private var hostExecutor = Executors.newSingleThreadExecutor()
    private var hostReader: BufferedReader? = null
    private var hostWriter: BufferedWriter? = null

    // Client settings
    var clientIp = "192.168.1.100"
    var clientPort = 8765
    private var clientSocket: Socket? = null
    private var clientExecutor = Executors.newSingleThreadExecutor()
    private var clientReader: BufferedReader? = null
    private var clientWriter: BufferedWriter? = null

    // Connection state
    var isConnected = false
        private set
    var connectionState = "disconnected"
        private set
    var lastFrameId = 0L
    var receivedFrameCount = 0L
    var sentFrameCount = 0L
    var lastRttMs = 0L

    // Callbacks
    var onDetectionsReceived: ((List<RemoteDetection>) -> Unit)? = null
    var onConnectionStateChanged: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val TAG = "RemoteModeManager"

    // ---- Host (Server) ----
    fun startHost(): Boolean {
        if (mode != Mode.HOST) return false
        stopHost()
        try {
            serverSocket = ServerSocket()
            serverSocket?.bind(InetSocketAddress("0.0.0.0", hostPort))
            serverSocket?.soTimeout = 5000
            hostExecutor = Executors.newSingleThreadExecutor()
            hostExecutor.execute {
                Log.d(TAG, "Host listening on port $hostPort")
                updateState("listening")
                while (!serverSocket?.isClosed!!) {
                    try {
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            socket.tcpNoDelay = true
                            hostSocket?.close()
                            hostSocket = socket
                            hostReader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            hostWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                            Log.d(TAG, "Host client connected from ${socket.inetAddress}")
                            updateState("connected")
                            readHostLoop()
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // timeout is expected, just continue
                    } catch (e: Exception) {
                        if (!serverSocket?.isClosed!!) {
                            Log.e(TAG, "Host accept error: ${e.message}")
                        }
                        break
                    }
                }
                Log.d(TAG, "Host server stopped")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startHost failed: ${e.message}")
            return false
        }
    }

    private fun readHostLoop() {
        try {
            var line: String?
            while (hostReader?.readLine().also { line = it } != null) {
                try {
                    val detections = parseDetectionsJson(line!!)
                    receivedFrameCount++
                    onDetectionsReceived?.invoke(detections)
                } catch (e: Exception) {
                    Log.e(TAG, "Host parse message failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Host read loop error: ${e.message}")
        } finally {
            try { hostReader?.close() } catch (_: Exception) {}
            hostReader = null
            try { hostWriter?.close() } catch (_: Exception) {}
            hostWriter = null
            try { hostSocket?.close() } catch (_: Exception) {}
            hostSocket = null
            updateState("listening")
        }
    }

    fun stopHost() {
        try { hostSocket?.close() } catch (_: Exception) {}
        hostSocket = null
        try { hostReader?.close() } catch (_: Exception) {}
        hostReader = null
        try { hostWriter?.close() } catch (_: Exception) {}
        hostWriter = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { hostExecutor.shutdownNow() } catch (_: Exception) {}
        updateState("disconnected")
    }

    // ---- Client (Sender) ----
    fun startClient(): Boolean {
        if (mode != Mode.CLIENT) return false
        stopClient()
        try {
            clientExecutor = Executors.newSingleThreadExecutor()
            clientExecutor.execute {
                try {
                    val socket = Socket()
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(clientIp, clientPort), 5000)
                    clientSocket = socket
                    clientReader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    clientWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                    Log.d(TAG, "Client connected to $clientIp:$clientPort")
                    updateState("connected")
                    // Keep-alive: just read (ignore responses)
                    try {
                        while (clientReader?.readLine() != null) { /* ignore */ }
                    } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.e(TAG, "Client connect failed: ${e.message}")
                    clientSocket = null
                    updateState("disconnected")
                }
            }
            updateState("connecting")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startClient failed: ${e.message}")
            return false
        }
    }

    fun stopClient() {
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        try { clientReader?.close() } catch (_: Exception) {}
        clientReader = null
        try { clientWriter?.close() } catch (_: Exception) {}
        clientWriter = null
        try { clientExecutor.shutdownNow() } catch (_: Exception) {}
        updateState("disconnected")
    }

    // ---- Send detections (Client side) ----
    fun sendDetections(detections: List<DetectionInfo>, screenW: Int, screenH: Int): Boolean {
        val writer = when (mode) {
            Mode.CLIENT -> clientWriter
            Mode.HOST -> hostWriter
            else -> null
        } ?: return false

        try {
            val json = buildDetectionsJson(detections, screenW, screenH) + "\n"
            writer.write(json)
            writer.flush()
            sentFrameCount++
            return true
        } catch (e: Exception) {
            Log.e(TAG, "sendDetections failed: ${e.message}")
            return false
        }
    }

    // ---- JSON Serialization ----
    private fun buildDetectionsJson(detections: List<DetectionInfo>, screenW: Int, screenH: Int): String {
        val obj = JSONObject()
        val arr = JSONArray()
        for (det in detections) {
            val d = JSONObject()
            d.put("cid", det.classId)
            d.put("cls", det.className)
            d.put("sc", det.score)
            d.put("x1", det.rect.left.toDouble())
            d.put("y1", det.rect.top.toDouble())
            d.put("x2", det.rect.right.toDouble())
            d.put("y2", det.rect.bottom.toDouble())
            arr.put(d)
        }
        obj.put("f", ++lastFrameId)
        obj.put("w", screenW)
        obj.put("h", screenH)
        obj.put("d", arr)
        return obj.toString()
    }

    private fun parseDetectionsJson(json: String): List<RemoteDetection> {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("d") ?: return emptyList()
        val list = mutableListOf<RemoteDetection>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            list.add(
                RemoteDetection(
                    classId = d.optInt("cid", -1),
                    className = d.optString("cls", "unknown"),
                    score = d.optDouble("sc", 0.0).toFloat(),
                    x1 = d.optDouble("x1", 0.0).toFloat(),
                    y1 = d.optDouble("y1", 0.0).toFloat(),
                    x2 = d.optDouble("x2", 0.0).toFloat(),
                    y2 = d.optDouble("y2", 0.0).toFloat(),
                    clientScreenW = d.optInt("w", 0),
                    clientScreenH = d.optInt("h", 0)
                )
            )
        }
        return list
    }

    // ---- State ----
    private fun updateState(state: String) {
        connectionState = state
        isConnected = state == "connected"
        mainHandler.post {
            onConnectionStateChanged?.invoke(state)
        }
    }

    fun cleanup() {
        stopHost()
        stopClient()
    }
}

data class RemoteDetection(
    val classId: Int,
    val className: String,
    val score: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val clientScreenW: Int = 0,
    val clientScreenH: Int = 0
)
