package com.xunlei.ai.model

/**
 * Smoothstep timer for slow-fast-slow easing.
 *
 * Usage:
 *   start(now, now + durationMs)
 *   each frame: val ratio = tickRatio(now)  // returns 0..1
 *   moveX = errorX * ratio
 *
 * Movement phases:
 *   t=0.0~0.25  -> slow  (easing in)
 *   t=0.25~0.75 -> fast  (cruising)
 *   t=0.75~1.0  -> slow  (easing out)
 */
class BezierMover {
    private var active = false
    private var startTime = 0L
    private var endTime = 0L
    private var prevRawT = 0f

    fun start(startMs: Long, endMs: Long) {
        startTime = startMs
        endTime = endMs
        prevRawT = 0f
        active = true
    }

    /**
     * Returns the smoothstep ratio (0..1) for this frame.
     * The returned value is the delta since last call (always >= 0).
     * Returns 0f if not active or already finished.
     */
    fun tickRatio(now: Long): Float {
        if (!active) return 0f
        val duration = (endTime - startTime).toFloat().coerceAtLeast(1f)
        val rawT = ((now - startTime).toFloat() / duration).coerceIn(0f, 1f)
        val curSmooth = smoothstep(rawT)
        val delta = curSmooth - prevRawT
        prevRawT = curSmooth
        if (rawT >= 1f) active = false
        return delta.coerceAtLeast(0f)
    }

    fun isActive() = active
    fun cancel() { active = false }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)
}
