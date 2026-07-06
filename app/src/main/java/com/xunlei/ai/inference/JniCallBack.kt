package com.xunlei.ai.inference

import java.nio.ByteBuffer

object JniCallBack {
    // 初始化模型
    init {
        System.loadLibrary("aimbot")
    }

    external fun init(modelPath: String): Boolean

    // 传入图像数据，返回坐标数组 [id, score, x1, y1, x2, y2, ...]
    // regionWidth/regionHeight: 要检测的区域大小（像素）
    // screenWidth/screenHeight: 原始屏幕尺寸，用于坐标转换
    external fun detect(
        buffer: ByteBuffer,
        offsetX: Int, offsetY: Int,
        regionWidth: Int, regionHeight: Int,
        screenWidth: Int, screenHeight: Int,
        rowStride: Int, pixelStride: Int
    ): FloatArray?

    external fun getBackend(): String

    external fun setConfidence(threshold: Float)

    external fun setForceCpu(useCpu: Boolean)

    external fun setCpuThreads(threads: Int)

    external fun setInputSize(width: Int, height: Int)

    external fun release()
}
