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

    // ── On-demand background finger ──
    // Injected only when aiming starts, removed when aiming stops.
    // A permanent ACTION_DOWN (never lifting) triggers the system's
    // ghost-touch detection, causing the entire touchscreen to become
    // unresponsive ("屏幕断触"). On-demand avoids this entirely.
    private val rng = Random
    private val bgId = 3 + rng.nextInt(6)        // 3~8
    private var bgX = 3f + rng.nextFloat() * 6f  // 3~9px
    private var bgY = 3f + rng.nextFloat() * 6f
    private var bgDownTime = 0L
    private var bgActive = false                  // true when bg finger is down
    private val touchId = 5 + rng.nextInt(8)     // 5~12, aim finger
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

    private fun bgCoord(): MotionEvent.PointerCoords {
        bgX += (rng.nextFloat() - 0.5f) * 0.6f
        bgY += (rng.nextFloat() - 0.5f) * 0.6f
        bgX = bgX.coerceIn(2f, 10f)
        bgY = bgY.coerceIn(2f, 10f)
        return coord(bgX, bgY)
    }

    private fun randTapDelay(): Int = 5 + rng.nextInt(14)

    /** Inject the background finger (ACTION_DOWN). Called at aim start. */
    private fun injectBgDown() {
        if (bgActive) return
        bgDownTime = SystemClock.uptimeMillis()
        val bgDown = MotionEvent.obtain(bgDownTime, bgDownTime, MotionEvent.ACTION_DOWN, 1,
            arrayOf(ptr(bgId)), arrayOf(bgCoord()),
            0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        injectMethod?.invoke(inputManager, bgDown, INJECT_MODE_ASYNC)
        bgDown.recycle()
        bgActive = true
    }

    /** Remove the background finger (ACTION_UP). Called at aim stop. */
    private fun injectBgUp() {
        if (!bgActive) return
        val now = SystemClock.uptimeMillis()
        val bgUp = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_UP, 1,
            arrayOf(ptr(bgId)), arrayOf(bgCoord()),
            0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        injectMethod?.invoke(inputManager, bgUp, INJECT_MODE_ASYNC)
        bgUp.recycle()
        bgActive = false
    }

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

            // NO permanent background finger here.
            // It is injected on-demand in swipe() and removed in lift().
            // A permanent ACTION_DOWN triggers system ghost-touch detection
            // which makes the entire touchscreen unresponsive.

            available = true
            Log.d(TAG, "ready, bgId=$bgId touchId=$touchId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init: ${e.message}"); destroy(); return false
        }
    }

    fun keepAlive() {
        // No-op: background finger is on-demand, not permanent.
    }

    fun tap(x: Int, y: Int) {
        if (!available) return
        val now = SystemClock.uptimeMillis()
        val delay = randTapDelay()
        val shift = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        try {
            val wasBgActive = bgActive
            if (!wasBgActive) injectBgDown()
            val bgC = bgCoord()
            val targetC = coord(x.toFloat(), y.toFloat())
            val down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN or shift, 2,
                arrayOf(ptr(bgId), ptr(touchId)), arrayOf(bgC, targetC),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            val up = MotionEvent.obtain(bgDownTime, now + delay, MotionEvent.ACTION_POINTER_UP or shift, 2,
                arrayOf(ptr(bgId), ptr(touchId)), arrayOf(bgC, targetC),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, down, INJECT_MODE_ASYNC)
            injectMethod?.invoke(inputManager, up, INJECT_MODE_ASYNC)
            down.recycle(); up.recycle()
            // Only remove bg if we created it (not if aim is already active)
            if (!wasBgActive) injectBgUp()
        } catch (e: Exception) { Log.e(TAG, "tap fail: ${e.message}"); available = false }
    }

    fun moveTo(x: Int, y: Int) {
        if (!available || drawingPointerId < 0) return
        val now = SystemClock.uptimeMillis()
        try {
            val move = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_MOVE, 2,
                arrayOf(ptr(bgId), ptr(drawingPointerId)),
                arrayOf(bgCoord(), coord(x.toFloat(), y.toFloat())),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, move, INJECT_MODE_ASYNC); move.recycle()
        } catch (e: Exception) { Log.e(TAG, "moveTo fail: ${e.message}") }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 1) {
        if (!available) return
        val now = SystemClock.uptimeMillis()
        val shift = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        try {
            if (drawingPointerId < 0) {
                // First touch: inject background finger, then aim finger on top
                injectBgDown()
                drawingPointerId = touchId
                downTime = now
                val bgC = bgCoord()
                val down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN or shift, 2,
                    arrayOf(ptr(bgId), ptr(drawingPointerId)),
                    arrayOf(bgC, coord(x1.toFloat(), y1.toFloat())),
                    0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                injectMethod?.invoke(inputManager, down, INJECT_MODE_ASYNC); down.recycle()
            }
            // ACTION_MOVE to target position
            val move = MotionEvent.obtain(bgDownTime, now + durationMs, MotionEvent.ACTION_MOVE, 2,
                arrayOf(ptr(bgId), ptr(drawingPointerId)),
                arrayOf(bgCoord(), coord(x2.toFloat(), y2.toFloat())),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, move, INJECT_MODE_ASYNC); move.recycle()
        } catch (e: Exception) { Log.e(TAG, "swipe fail: ${e.message}"); available = false }
    }

    fun lift() {
        if (!available || drawingPointerId < 0) return
        val now = SystemClock.uptimeMillis()
        val upDelay = 3 + rng.nextInt(6)
        val shift = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        try {
            val bgC = bgCoord()
            val up = MotionEvent.obtain(bgDownTime, now + upDelay, MotionEvent.ACTION_POINTER_UP or shift, 2,
                arrayOf(ptr(bgId), ptr(drawingPointerId)),
                arrayOf(bgC, bgC),
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, up, INJECT_MODE_ASYNC); up.recycle()
            drawingPointerId = -1
            // Remove background finger after aim finger is lifted
            injectBgUp()
        } catch (e: Exception) { Log.e(TAG, "lift fail: ${e.message}") }
    }

    fun destroy() {
        // Clean up: lift bg if still active
        if (bgActive) {
            try { injectBgUp() } catch (_: Exception) {}
        }
        available = false; drawingPointerId = -1
        inputManager = null; injectMethod = null
    }
}