package com.xunlei.ai.injector

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import kotlin.random.Random

class TouchInjector {

    companion object {
        private const val TAG = "TouchInjector"
        private const val INJECT_MODE_ASYNC = 0
    }

    private var injectMethod: Method? = null
    private var inputManager: Any? = null

    // ── Pure single-touch aim injection ──
    // The aim finger is injected as a standalone ACTION_DOWN → MOVE → UP
    // sequence. Physical touch and injected touch coexist because they
    // come from different input sources with non-overlapping pointer IDs.
    private val rng = Random
    private val touchId = 5 + rng.nextInt(8)     // 5~12, avoids physical touch IDs 0~4
    private var drawingPointerId = -1
    private var downTime = 0L

    @Volatile
    var available: Boolean = false
        private set

    private fun ptr(id: Int) = MotionEvent.PointerProperties().also {
        it.id = id; it.toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    private fun coord(x: Float, y: Float): MotionEvent.PointerCoords {
        val c = MotionEvent.PointerCoords()
        c.x = x; c.y = y
        c.pressure = 0.55f + rng.nextFloat() * 0.35f
        c.size = 0.08f + rng.nextFloat() * 0.04f
        c.touchMajor = 20f + rng.nextFloat() * 15f
        c.touchMinor = 18f + rng.nextFloat() * 12f
        return c
    }

    private fun randTapDelay(): Int = 5 + rng.nextInt(14)

    fun init(): Boolean {
        if (available) return true
        if (!Shizuku.pingBinder()) { Log.w(TAG, "Shizuku not running"); return false }

        try {
            val inputBinder = try {
                SystemServiceHelper.getSystemService("input")
            } catch (e: Exception) {
                val sm = Class.forName("android.os.ServiceManager")
                sm.getDeclaredMethod("getService", String::class.java).invoke(null, "input") as? IBinder
            }
            if (inputBinder == null) { Log.w(TAG, "input binder null"); return false }
            val proxied = ShizukuBinderWrapper(inputBinder)

            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asI = stub.getDeclaredMethod("asInterface", IBinder::class.java)
            val raw = asI.invoke(null, proxied)
            if (raw == null) { Log.w(TAG, "IInputManager null"); return false }
            inputManager = raw
            injectMethod = raw.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)

            available = true
            Log.d(TAG, "ready, touchId=$touchId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init: ${e.message}"); destroy(); return false
        }
    }

    fun keepAlive() {
        // No-op: no background finger to keep alive.
    }

    fun tap(x: Int, y: Int) {
        if (!available) return
        val now = SystemClock.uptimeMillis()
        val delay = randTapDelay()
        try {
            val c = coord(x.toFloat(), y.toFloat())
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1,
                arrayOf(ptr(touchId)), arrayOf(c),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            val up = MotionEvent.obtain(now, now + delay, MotionEvent.ACTION_UP, 1,
                arrayOf(ptr(touchId)), arrayOf(c),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, down, INJECT_MODE_ASYNC)
            injectMethod?.invoke(inputManager, up, INJECT_MODE_ASYNC)
            down.recycle(); up.recycle()
        } catch (e: Exception) { Log.e(TAG, "tap fail: ${e.message}"); available = false }
    }

    fun moveTo(x: Int, y: Int) {
        if (!available || drawingPointerId < 0) return
        val now = SystemClock.uptimeMillis()
        try {
            val move = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, 1,
                arrayOf(ptr(drawingPointerId)),
                arrayOf(coord(x.toFloat(), y.toFloat())),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, move, INJECT_MODE_ASYNC); move.recycle()
        } catch (e: Exception) { Log.e(TAG, "moveTo fail: ${e.message}") }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 1) {
        if (!available) return
        val now = SystemClock.uptimeMillis()
        try {
            if (drawingPointerId < 0) {
                drawingPointerId = touchId
                downTime = now
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1,
                    arrayOf(ptr(drawingPointerId)),
                    arrayOf(coord(x1.toFloat(), y1.toFloat())),
                    0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                injectMethod?.invoke(inputManager, down, INJECT_MODE_ASYNC); down.recycle()
            }
            val move = MotionEvent.obtain(downTime, now + durationMs, MotionEvent.ACTION_MOVE, 1,
                arrayOf(ptr(drawingPointerId)),
                arrayOf(coord(x2.toFloat(), y2.toFloat())),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, move, INJECT_MODE_ASYNC); move.recycle()
        } catch (e: Exception) { Log.e(TAG, "swipe fail: ${e.message}"); available = false }
    }

    fun lift() {
        if (!available || drawingPointerId < 0) return
        val now = SystemClock.uptimeMillis()
        val upDelay = 3 + rng.nextInt(6)
        try {
            val up = MotionEvent.obtain(downTime, now + upDelay, MotionEvent.ACTION_UP, 1,
                arrayOf(ptr(drawingPointerId)),
                arrayOf(coord(0f, 0f)),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, up, INJECT_MODE_ASYNC); up.recycle()
            drawingPointerId = -1
        } catch (e: Exception) { Log.e(TAG, "lift fail: ${e.message}") }
    }

    fun destroy() {
        available = false; drawingPointerId = -1
        inputManager = null; injectMethod = null
    }
}