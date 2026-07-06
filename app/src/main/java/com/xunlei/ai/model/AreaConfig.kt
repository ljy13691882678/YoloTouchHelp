package com.xunlei.ai.model

import android.graphics.Color
import android.graphics.RectF

data class AreaConfig(
    var x: Int = 0,
    var y: Int = 0,
    var width: Int = 150,
    var height: Int = 150,
    val name: String,
    val color: Int = Color.RED
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    fun toRectF() = RectF(
        x.toFloat(), y.toFloat(), right.toFloat(), bottom.toFloat()
    )
}
