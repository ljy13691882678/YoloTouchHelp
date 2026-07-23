package com.xunlei.ai.view

import android.content.Context
import android.view.KeyEvent
import android.view.View

/**
 * Transparent view that intercepts volume key events for inference control.
 * Double-press volume up = start inference, double-press volume down = stop.
 */
class VolumeKeyController(context: Context) : View(context) {

    var onToggleInference: ((Boolean) -> Unit)? = null // true=start, false=stop

    private val DOUBLE_PRESS_MS = 600L
    private var lastVolumeUpMs = 0L
    private var lastVolumeDownMs = 0L
    private var lastVolumeUpCount = 0
    private var lastVolumeDownCount = 0

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val now = System.currentTimeMillis()
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (now - lastVolumeUpMs < DOUBLE_PRESS_MS && lastVolumeUpCount >= 1) {
                    // Double press volume up → start inference
                    onToggleInference?.invoke(true)
                    lastVolumeUpCount = 0
                    lastVolumeUpMs = 0L
                    return true
                }
                lastVolumeUpMs = now
                lastVolumeUpCount++
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (now - lastVolumeDownMs < DOUBLE_PRESS_MS && lastVolumeDownCount >= 1) {
                    // Double press volume down → stop inference
                    onToggleInference?.invoke(false)
                    lastVolumeDownCount = 0
                    lastVolumeDownMs = 0L
                    return true
                }
                lastVolumeDownMs = now
                lastVolumeDownCount++
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
    }
}
