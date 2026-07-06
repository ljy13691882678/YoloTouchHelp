package com.xunlei.ai.model

import android.graphics.RectF

data class DetectionInfo(
    val rect: RectF,
    val classId: Int,
    val className: String,
    val score: Float = 0f,
    val trackId: Int = -1,
    val missedFrames: Int = 0,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f
)

