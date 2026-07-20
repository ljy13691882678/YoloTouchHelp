package com.xunlei.ai.model

import android.graphics.RectF

data class AimingState(
    var pointerDown: Boolean = false,
    var centerX: Float = 0f,
    var centerY: Float = 0f,
    var startX: Float = 0f,
    var startY: Float = 0f,
    var prevErrorX: Float = 0f,
    var prevErrorY: Float = 0f,
    var integralX: Float = 0f,
    var integralY: Float = 0f,
    var lockedTarget: RectF? = null,
    var lockedTrackId: Int = -1,
    var lockedMissedFrames: Int = 0,
    var maxDragDist: Float = 400f,
    var swayTimer: Int = 0,
    var swayPulse: Int = 0,
    var swayDuration: Int = 10,
    var swayDir: Float = 0f,
    var deadzoneFrames: Int = 0,
    var lastMoveX: Float = 0f,
    var lastMoveY: Float = 0f,
    var lastTargetX: Float = Float.NaN,
    var lastTargetY: Float = Float.NaN,
    var prevTargetX: Float = Float.NaN,
    var prevTargetY: Float = Float.NaN,
    var smoothVelX: Float = 0f,
    var smoothVelY: Float = 0f,
    // Commitment: 锁定目标直到消失再切换下一个
    var committedTrackId: Int = -1,
    var committedClassId: Int = -1,
    var committedBox: RectF? = null,          // 承诺目标的框(用于trackId不可用时的回退匹配)
    var committedMissingFrames: Int = 0,
    var commitKillConfirmFrames: Int = 12,   // 目标消失N帧后认为已击杀
    var commitMinHoldFrames: Int = 3,        // 最少锁定帧数后才算正式commit
    var commitFrameCount: Int = 0,
    // Bionic (拟人) aim state
    var bionicReactionEndMs: Long = 0,
    var bionicAimStartMs: Long = 0,
    var bionicOvershootPeaked: Boolean = false,
    var bionicJitterPhase: Float = 0f,
    var bionicImperfectX: Float = 0f,
    var bionicImperfectY: Float = 0f,
    var bionicSpeedFactor: Float = 1f,
    var bionicLastTargetId: Int = -1,
) {
    fun updateVelocity(cx: Float, cy: Float) {
        if (!prevTargetX.isNaN()) {
            val rawVx = cx - prevTargetX
            val rawVy = cy - prevTargetY
            smoothVelX = smoothVelX * 0.7f + rawVx * 0.3f
            smoothVelY = smoothVelY * 0.7f + rawVy * 0.3f
        }
        prevTargetX = cx; prevTargetY = cy
    }

    fun reset() {
        pointerDown = false
        lockedTarget = null
        lockedTrackId = -1
        lockedMissedFrames = 0
        deadzoneFrames = 0
        lastMoveX = 0f
        lastMoveY = 0f
        lastTargetX = Float.NaN
        lastTargetY = Float.NaN
        prevErrorX = 0f; prevErrorY = 0f
        integralX = 0f; integralY = 0f
        prevTargetX = Float.NaN; prevTargetY = Float.NaN
        smoothVelX = 0f; smoothVelY = 0f
        swayTimer = (30..90).random(); swayPulse = 0; swayDir = 0f
        // Reset commitment
        committedTrackId = -1
        committedClassId = -1
        committedBox = null
        committedMissingFrames = 0
        commitFrameCount = 0
        // Reset bionic
        bionicReactionEndMs = 0
        bionicAimStartMs = 0
        bionicOvershootPeaked = false
        bionicJitterPhase = 0f
        bionicImperfectX = 0f
        bionicImperfectY = 0f
        bionicSpeedFactor = 1f
        bionicLastTargetId = -1
    }
}
