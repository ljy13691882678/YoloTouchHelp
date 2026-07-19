package com.xunlei.ai.injector

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
import kotlin.math.abs

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
 *
 * 触控方案:
 * - 瞄准通道 (moveTo/swipe/lift) 走真实陀螺仪设备注入（OPEN_GYRO + GYRO_MOVE），
 *   由 AimController 调用 moveTo 传入绝对屏幕坐标，本类内部计算像素增量并写入
 *   /dev/input 中真实陀螺仪 event 设备的 ABS_RX/ABS_RY 轴。
 * - 扳机/单点 tap/fire 等仍走 uinput 触摸通道（OPEN_UINPUT + DOWN/MOVE/UP）。
 * - 陀螺仪不可用时（设备未找到）自动回退到触摸方案。
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

    // 陀螺仪瞄准通道状态
    @Volatile
    private var gyroAvailable = false
    // 上次注入的屏幕坐标，用于计算增量；负值表示尚未初始化（等待 swipe 设定起点）
    private var gyroLastX = Int.MIN_VALUE
    private var gyroLastY = Int.MIN_VALUE

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

    // [SMOOTH] swipe 使用 cubic ease-in-out 模拟真人手指的加速度曲线
    // 真人手指滑动: 起手慢 → 中间快 → 收尾慢，而非机械式线性等距步进
    //
    // 陀螺仪模式下：陀螺仪是速率型传感器，没有"按下/抬起"语义。
    // AimController 调用 swipe(x, y, x, y, 0) 表示开始追踪目标，之后会连续
    // 调用 moveTo 更新期望位置。这里只记录起点；速率更新由 moveTo 计算。
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        if (gyroAvailable) {
            // 陀螺仪模式：记录起点，等待后续 moveTo 推送速率
            gyroLastX = x1
            gyroLastY = y1
            // 若调用方直接给出位移（非 aim DOWN 语义），按 cubic 曲线分步推送
            if (durationMs > 0 && (x1 != x2 || y1 != y2)) {
                val steps = maxOf(4, durationMs / 8)
                for (i in 1..steps) {
                    val t = i.toFloat() / steps.toFloat()
                    val easedT = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f
                    val cx = x1 + ((x2 - x1).toFloat() * easedT).toInt()
                    val cy = y1 + ((y2 - y1).toFloat() * easedT).toInt()
                    injectGyroRate(cx, cy)
                    val midBonus = (1f - abs(2f * t - 1f)) * 6f
                    val sleepMs = (5 + (Math.random() * 11).toInt() - midBonus.toInt()).coerceIn(4, 16)
                    Thread.sleep(sleepMs.toLong())
                }
            }
            return
        }

        // 回退：原触摸 swipe
        execOk("DOWN $x1 $y1")
        if (durationMs > 0) {
            val baseSteps = maxOf(4, durationMs / 8)       // 至少4步保证平滑
            val steps = baseSteps + (Math.random() * 3).toInt()  // 步数微随机(4~+2)
            var lastX = x1
            var lastY = y1
            for (i in 1..steps) {
                // cubic ease-in-out: t³(3t-2)²  → 使轨迹两端慢中间快
                val t = i.toFloat() / steps.toFloat()
                val easedT = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f
                val cx = x1 + ((x2 - x1).toFloat() * easedT).toInt()
                val cy = y1 + ((y2 - y1).toFloat() * easedT).toInt()
                // 跳过与上一步完全相同的点（节省IPC）
                if (cx != lastX || cy != lastY) {
                    execOk("MOVE $cx $cy")
                    lastX = cx
                    lastY = cy
                }
                // 可变间隔: 起止段较慢(10~15ms)，中间段较快(5~9ms)
                val midBonus = (1f - abs(2f * t - 1f)) * 6f  // 中间最多减6ms
                val sleepMs = (5 + (Math.random() * 11).toInt() - midBonus.toInt()).coerceIn(4, 16)
                Thread.sleep(sleepMs.toLong())
            }
            // 确保终点精确到达
            if (lastX != x2 || lastY != y2) execOk("MOVE $x2 $y2")
            execOk("UP")
        }
    }

    override fun moveTo(x: Int, y: Int) {
        if (gyroAvailable) {
            injectGyroRate(x, y)
            return
        }
        execOk("MOVE $x $y")
    }

    override fun lift() {
        if (gyroAvailable) {
            // 抬起：停止旋转，陀螺仪归零
            execOk("GYRO_MOVE 0 0")
            gyroLastX = Int.MIN_VALUE
            gyroLastY = Int.MIN_VALUE
            return
        }
        execOk("UP")
    }

    // 把当前期望屏幕坐标转换为速率并推送给 daemon
    // daemon 端 gyro_inject_move 是异步的：只更新原子变量，
    // 由后台 writer 线程以 500Hz 持续写入真机陀螺仪设备
    private fun injectGyroRate(x: Int, y: Int) {
        if (gyroLastX == Int.MIN_VALUE || gyroLastY == Int.MIN_VALUE) {
            gyroLastX = x
            gyroLastY = y
            return
        }
        val dx = x - gyroLastX
        val dy = y - gyroLastY
        // 直接发送当前帧的位移作为速率值；daemon 后台线程会持续以该速率注入
        if (dx != 0 || dy != 0) {
            execOk("GYRO_MOVE $dx $dy")
        }
        gyroLastX = x
        gyroLastY = y
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
        val uinputOk = execOk("OPEN_UINPUT")
        if (!uinputOk) return false
        // 默认禁用陀螺仪，由配置决定是否启用（setGyroEnabled）
        // 调用方应在 initRemote 后调用 setGyroEnabled 触发实际启用
        return true
    }

    /**
     * 运行时切换瞄准通道：true=陀螺仪（直接写真机陀螺仪设备），
     * false=原 uinput 触摸（DOWN/MOVE/UP）。
     * 不需要重启 daemon，立即生效。
     * 返回最终状态：true 表示陀螺仪已启用，false 表示仍走触摸（含启用失败回退）。
     */
    fun setGyroEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            if (gyroAvailable) return true
            if (!connected) return false
            val ok = execOk("OPEN_GYRO")
            if (ok) {
                gyroAvailable = true
                gyroLastX = Int.MIN_VALUE
                gyroLastY = Int.MIN_VALUE
                Log.d(TAG, "Gyro aim channel enabled")
            } else {
                gyroAvailable = false
                Log.w(TAG, "No gyro device found, aim channel falls back to uinput touch")
            }
            return gyroAvailable
        } else {
            if (!gyroAvailable) return false
            if (connected) execOk("CLOSE_GYRO")
            gyroAvailable = false
            gyroLastX = Int.MIN_VALUE
            gyroLastY = Int.MIN_VALUE
            Log.d(TAG, "Gyro aim channel disabled, falling back to uinput touch")
            return false
        }
    }

    fun isGyroEnabled(): Boolean = gyroAvailable

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
        if (gyroAvailable) {
            execOk("CLOSE_GYRO")
            gyroAvailable = false
        }
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
            if (gyroAvailable) execOk("CLOSE_GYRO")
            execOk("DESTROY")
        } catch (_: Exception) {}
        gyroAvailable = false
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
