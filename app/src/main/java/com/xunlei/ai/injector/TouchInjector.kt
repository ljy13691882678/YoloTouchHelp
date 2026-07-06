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

class TouchInjector {

    companion object {
        private const val TAG = "TouchInjector"
        private const val INJECT_MODE_ASYNC = 0
        private const val BG_ID = 10
    }

    private var injectMethod: Method? = null
    private var inputManager: Any? = null
    private var bgDownTime = 0L
    private var lastTapId = 6
    private var drawingPointerId = -1

    @Volatile
    var available: Boolean = false
        private set

    private fun ptr(id: Int) = MotionEvent.PointerProperties().also { it.id = id; it.toolType = MotionEvent.TOOL_TYPE_FINGER }
    private fun coord(x: Float, y: Float): MotionEvent.PointerCoords {
        val c = MotionEvent.PointerCoords()
        c.x = x; c.y = y
        return c
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

            bgDownTime = SystemClock.uptimeMillis()
            val bgDown = MotionEvent.obtain(bgDownTime, bgDownTime, MotionEvent.ACTION_DOWN, 1,
                arrayOf(ptr(BG_ID)), arrayOf(coord(5f, 5f)),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, bgDown, INJECT_MODE_ASYNC)
            bgDown.recycle()

            available = true
            Log.d(TAG, "ready, bg=ID$BG_ID at (5,5)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init: ${e.message}"); destroy(); return false
        }
    }

    fun keepAlive() {
        if (!available) return
        try {
            val m = MotionEvent.obtain(bgDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1,
                arrayOf(ptr(BG_ID)), arrayOf(coord(5f, 5f)),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, m, INJECT_MODE_ASYNC); m.recycle()
        } catch (_: Exception) {}
    }

    fun tap(x: Int, y: Int) {
        if (!available) return
        val now = SystemClock.uptimeMillis()
        val tapId = { var n: Int; do { n = 7 + (Math.random() * 3).toInt() } while (n == lastTapId); lastTapId = n; n }()
        val shift = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT

        val down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN or shift, 2,
            arrayOf(ptr(BG_ID), ptr(tapId)), arrayOf(coord(5f, 5f), coord(x.toFloat(), y.toFloat())),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        val up = MotionEvent.obtain(bgDownTime, now + 8, MotionEvent.ACTION_POINTER_UP or shift, 2,
            arrayOf(ptr(BG_ID), ptr(tapId)), arrayOf(coord(5f, 5f), coord(x.toFloat(), y.toFloat())),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)

        try { injectMethod?.invoke(inputManager, down, INJECT_MODE_ASYNC); injectMethod?.invoke(inputManager, up, INJECT_MODE_ASYNC) }
        catch (e: Exception) { Log.e(TAG, "tap fail: ${e.message}"); available = false }
        down.recycle(); up.recycle()
    }

    fun moveTo(x: Int, y: Int) {
        if (!available || drawingPointerId < 0) return
        val now = SystemClock.uptimeMillis()
        try {
            val move = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_MOVE, 2,
                arrayOf(ptr(BG_ID), ptr(drawingPointerId)), arrayOf(coord(5f, 5f), coord(x.toFloat(), y.toFloat())),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, move, INJECT_MODE_ASYNC); move.recycle()
        } catch (e: Exception) { Log.e(TAG, "moveTo fail: ${e.message}") }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 1) {
        if (!available) return
        val now = SystemClock.uptimeMillis()
        val shift = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT

        try {
            if (drawingPointerId < 0) {
                // Start of drawing: DOWN at first point
                val newId = { var n: Int; do { n = 7 + (Math.random() * 3).toInt() } while (n == lastTapId); lastTapId = n; n }()
                drawingPointerId = newId
                val down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN or shift, 2,
                    arrayOf(ptr(BG_ID), ptr(drawingPointerId)), arrayOf(coord(5f, 5f), coord(x1.toFloat(), y1.toFloat())),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                injectMethod?.invoke(inputManager, down, INJECT_MODE_ASYNC); down.recycle()
            }
            // MOVE to end point
            val move = MotionEvent.obtain(bgDownTime, now + durationMs, MotionEvent.ACTION_MOVE, 2,
                arrayOf(ptr(BG_ID), ptr(drawingPointerId)), arrayOf(coord(5f, 5f), coord(x2.toFloat(), y2.toFloat())),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, move, INJECT_MODE_ASYNC); move.recycle()
        } catch (e: Exception) { Log.e(TAG, "swipe fail: ${e.message}"); available = false }
    }

    fun lift() {
        if (!available || drawingPointerId < 0) return
        val now = SystemClock.uptimeMillis()
        val shift = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        try {
            val up = MotionEvent.obtain(bgDownTime, now + 4, MotionEvent.ACTION_POINTER_UP or shift, 2,
                arrayOf(ptr(BG_ID), ptr(drawingPointerId)), arrayOf(coord(5f, 5f), coord(5f, 5f)),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, up, INJECT_MODE_ASYNC); up.recycle()
            drawingPointerId = -1
        } catch (e: Exception) { Log.e(TAG, "lift fail: ${e.message}") }
    }

    fun destroy() {
        available = false; drawingPointerId = -1
        try {
            val up = MotionEvent.obtain(bgDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1,
                arrayOf(ptr(BG_ID)), arrayOf(coord(5f, 5f)),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectMethod?.invoke(inputManager, up, INJECT_MODE_ASYNC); up.recycle()
        } catch (_: Exception) {}
        inputManager = null; injectMethod = null
    }
}
