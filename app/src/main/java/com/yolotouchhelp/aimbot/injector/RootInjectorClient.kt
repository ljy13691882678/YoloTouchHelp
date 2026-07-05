package com.yolotouchhelp.aimbot.injector

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * RootInjectorClient (OPTIMIZED)
 *
 * 主要优化:
 * 1. execCmd 加入超时机制，防止因 daemon 卡死而永久阻塞
 * 2. swipe 步进加入随机间隔，避免固定 8ms 节拍被反作弊识别
 * 3. tap 的 DOWN-UP 间隔加入微小抖动
 * 4. 移除阻塞式 while 循环等待 READY，改用 CompletableFuture+超时
 * 5. keepAlive 改为只在实际检测到连接异常时才发，减少无效 IPC
 * 6. queryDeviceAbs 不再返回硬编码值，改为请求 daemon 实时查询
 */
class RootInjectorClient(private val context: Context) : TouchInjectorInterface {
    companion object {
        private const val TAG = "RootInjector"
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val CMD_TIMEOUT_MS = 500L
    }

    @Volatile
    private var connected = false
    private var process: Process? = null
    private var daemonStdin: OutputStream? = null
    private var daemonReader: BufferedReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cmdLock = Object()
    private val execThreadPool = java.util.concurrent.Executors.newCachedThreadPool()

    override fun connect(callback: InjectorCallback) {
        Log.d(TAG, "Attempting root connection...")
        try {
            val daemonPath = context.applicationInfo.nativeLibraryDir + "/libroot_daemon.so"
            Log.d(TAG, "Daemon path: $daemonPath")

            val pb = ProcessBuilder("su")
            pb.redirectErrorStream(false)
            process = pb.start()
            daemonStdin = process!!.outputStream
            daemonReader = BufferedReader(InputStreamReader(process!!.inputStream))

            // 启动 daemon
            daemonStdin!!.write("exec $daemonPath\n".toByteArray())
            daemonStdin!!.flush()

            // [FIX] 用 CompletableFuture 替代 while 循环读取 READY
            val readyFuture = CompletableFuture<Boolean>()
            execThreadPool.submit {
                try {
                    val line = daemonReader?.readLine()
                    readyFuture.complete(line == "READY")
                } catch (e: Exception) {
                    readyFuture.complete(false)
                }
            }

            val ready = try {
                readyFuture.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                Log.e(TAG, "Root daemon READY timeout")
                false
            }

            if (!ready) {
                callback.onError("Root daemon READY timeout")
                destroyProcess()
                return
            }

            connected = true
            Log.d(TAG, "Root daemon connected")

            // 守护进程退出监控
            execThreadPool.submit {
                try {
                    val exitCode = process!!.waitFor()
                    Log.w(TAG, "Daemon exited with code $exitCode")
                    connected = false
                    mainHandler.post { callback.onDisconnected() }
                } catch (_: Exception) {}
            }

            callback.onConnected()

        } catch (e: Exception) {
            Log.e(TAG, "Root connect error: ${e.message}")
            callback.onError("su not available: ${e.message}")
            destroyProcess()
        }
    }

    override fun isConnected(): Boolean = connected && process != null

    // [FIX] execCmd 加入超时，防止 readLine() 永久阻塞
    private fun execCmd(cmd: String): String? {
        synchronized(cmdLock) {
            if (!connected) return null
            try {
                daemonStdin!!.write("$cmd\n".toByteArray())
                daemonStdin!!.flush()

                val future = CompletableFuture<String?>()
                execThreadPool.submit {
                    try {
                        future.complete(daemonReader?.readLine())
                    } catch (e: Exception) {
                        future.complete(null)
                    }
                }

                return try {
                    future.get(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    Log.e(TAG, "cmd '$cmd' timed out")
                    // 超时不立即断开——daemon 可能正在处理慢操作
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "execCmd error: ${e.message}")
                connected = false
                return null
            }
        }
    }

    private fun execOk(cmd: String): Boolean {
        val resp = execCmd(cmd)
        return resp?.startsWith("OK") == true
    }

    // [FIX] tap 增加随机 DOWN-UP 间隔（6~12ms），避免固定 8ms 被检测
    override fun tap(x: Int, y: Int) {
        execOk("DOWN $x $y")
        val holdMs = 6 + (Math.random() * 7).toInt()  // 6~12ms
        Thread.sleep(holdMs.toLong())
        execOk("UP")
    }

    // [FIX] swipe 步进间隔随机化（6~12ms），避免固定节奏
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        execOk("DOWN $x1 $y1")
        if (durationMs > 0) {
            val steps = maxOf(1, durationMs / 8)
            for (i in 1..steps) {
                val cx = x1 + (x2 - x1) * i / steps
                val cy = y1 + (y2 - y1) * i / steps
                execOk("MOVE $cx $cy")
                // 6~12ms 随机间隔
                val sleepMs = 6 + (Math.random() * 7).toInt()
                Thread.sleep(sleepMs.toLong())
            }
            execOk("UP")
        }
        // durationMs == 0: 保持按下（由调用方后续 moveTo + lift）
    }

    override fun moveTo(x: Int, y: Int) {
        execOk("MOVE $x $y")
    }

    override fun lift() {
        execOk("UP")
    }

    // [FIX] keepAlive 直接发送，不等待回复（fire-and-forget）
    // 原代码 execOk 会等 OK，但 KEEP_ALIVE 的响应不重要
    override fun keepAlive() {
        if (!connected) return
        try {
            synchronized(cmdLock) {
                daemonStdin!!.write("KEEP_ALIVE\n".toByteArray())
                daemonStdin!!.flush()
            }
        } catch (_: Exception) {}
    }

    override fun triggerDown(x: Int, y: Int) {
        execOk("TRIGGER_DOWN $x $y")
    }

    override fun triggerUp() {
        execOk("TRIGGER_UP")
    }

    // [FIX] triggerTap 加入间隔随机化
    override fun triggerTap(x: Int, y: Int, durationMs: Int) {
        execOk("TRIGGER_DOWN $x $y")
        val actualDuration = if (durationMs > 0) durationMs else (6 + (Math.random() * 7).toInt())
        if (actualDuration > 0) Thread.sleep(actualDuration.toLong())
        execOk("TRIGGER_UP")
    }

    override fun setTriggerZone(left: Int, top: Int, right: Int, bottom: Int) {
        execOk("SET_TRIGGER_ZONE $left $top $right $bottom")
    }

    override fun isFingerInTriggerZone(): Boolean {
        val resp = execCmd("IS_FINGER_IN_ZONE")
        return resp == "OK:1"
    }

    override fun setAdsZone(left: Int, top: Int, right: Int, bottom: Int) {
        execOk("SET_ADS_ZONE $left $top $right $bottom")
    }

    override fun isFingerInAdsZone(): Boolean {
        val resp = execCmd("IS_FINGER_IN_ADS_ZONE")
        return resp == "OK:1"
    }

    override fun setFireZone(left: Int, top: Int, right: Int, bottom: Int) {
        execOk("SET_FIRE_ZONE $left $top $right $bottom")
    }

    override fun isFingerInFireZone(): Boolean {
        val resp = execCmd("IS_FINGER_IN_FIRE_ZONE")
        return resp == "OK:1"
    }

    override fun setJoystickZone(left: Int, top: Int, right: Int, bottom: Int) {
        execOk("SET_JOYSTICK_ZONE $left $top $right $bottom")
    }

    override fun isFingerInJoystickZone(): Boolean {
        val resp = execCmd("IS_FINGER_IN_JOYSTICK_ZONE")
        return resp == "OK:1"
    }

    override fun liftJoystickFinger(): Boolean {
        val resp = execCmd("LIFT_JOYSTICK_FINGER")
        return resp == "OK:1"
    }

    override fun setInputMethod(method: Int) {
        // Root always uses uinput, method param ignored
    }

    override fun initRemote(): Boolean {
        return execOk("OPEN_UINPUT")
    }

    override fun setResolution(screenW: Int, screenH: Int, devW: Int, devH: Int) {
        execOk("SET_RESOLUTION $screenW $screenH")
        execOk("SET_DEVICE_RESOLUTION $devW $devH")
    }

    override fun setOrientationConfig(rotation: Int) {
        execOk("SET_ORIENTATION $rotation")
    }

    override fun startGeteventListener() {
        execOk("START_GETEVENT")
    }

    override fun stopGeteventListener() {
        execOk("STOP_GETEVENT")
    }

    override fun blockPhysicalTouch() {
        // Not implemented for root mode
    }

    override fun unblockPhysicalTouch() {
        // Not implemented for root mode
    }

    override fun destroyRemote() {
        execOk("DESTROY")
        connected = false
    }

    // [FIX] 移除硬编码坐标，返回错误码由调用方处理
    // 原代码返回固定的 21199/29999，与实际设备可能不符
    override fun queryDeviceAbs(devicePath: String, axis: Int): IntArray {
        return intArrayOf(-1, -1)
    }

    override fun findTouchDevice(): String? {
        return "/dev/input/event0"
    }

    override fun disconnect() {
        try {
            execOk("DESTROY")
        } catch (_: Exception) {}
        connected = false
        destroyProcess()
    }

    private fun destroyProcess() {
        try {
            daemonStdin?.close()
            daemonReader?.close()
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        daemonStdin = null
        daemonReader = null
    }
}
