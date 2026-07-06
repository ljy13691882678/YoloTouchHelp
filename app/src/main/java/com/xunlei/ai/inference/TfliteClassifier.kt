package com.xunlei.ai.inference

import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TfliteClassifier {
    private var interpreter: Interpreter? = null
    private var inputSize: Int = 192
    private var numOutputs: Int = 756

    data class Detection(
        val classId: Float,
        val score: Float,
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float
    )

    fun init(modelPath: String): Boolean {
        release()

        try {
            Log.d("TfliteClassifier", "Loading TFLite model: $modelPath")

            val options = Interpreter.Options()
                .setNumThreads(1)

            // Try NNAPI delegate (uses NPU on Qualcomm)
            try {
                val nnapiDelegate = NnApiDelegate()
                options.addDelegate(nnapiDelegate)
                Log.d("TfliteClassifier", "NNAPI delegate added")
            } catch (e: Exception) {
                Log.w("TfliteClassifier", "NNAPI delegate failed: ${e.message}")
            }

            interpreter = Interpreter(File(modelPath), options)
            Log.d("TfliteClassifier", "TFLite interpreter created successfully")

            return true
        } catch (e: Exception) {
            Log.e("TfliteClassifier", "Failed to create interpreter: ${e.message}", e)
            return false
        }
    }

    fun detect(
        inputBuffer: ByteBuffer,
        offsetX: Int, offsetY: Int,
        regionWidth: Int, regionHeight: Int,
        screenWidth: Int, screenHeight: Int,
        rowStride: Int, pixelStride: Int
    ): FloatArray? {
        val interp = interpreter ?: return null

        try {
            val H = inputSize
            val W = inputSize
            val C = 3

            // Prepare input tensor NHWC (1, H, W, 3)
            val inputBufferFloat = ByteBuffer.allocateDirect(1 * H * W * C * 4)
                .order(ByteOrder.nativeOrder())

            // Convert RGBA to NHWC float normalized to [0, 1]
            for (y in 0 until H) {
                for (x in 0 until W) {
                    val srcX = offsetX + x * regionWidth / W
                    val srcY = offsetY + y * regionHeight / H
                    val srcIdx = srcY * rowStride + srcX * pixelStride

                    // RGBA -> RGB
                    for (c in 0 until C) {
                        val pixel = inputBuffer.get(srcIdx + c).toInt() and 0xFF
                        val normalized = pixel / 255.0f
                        inputBufferFloat.putFloat(normalized)
                    }
                }
            }
            inputBufferFloat.rewind()

            // Prepare output buffer: [1, 5, 756]
            val outputBuffer = ByteBuffer.allocateDirect(1 * 5 * numOutputs * 4)
                .order(ByteOrder.nativeOrder())

            // Run inference
            interp.run(inputBufferFloat, outputBuffer)
            outputBuffer.rewind()

            // Parse output: [cx, cy, w, h, score] for each box
            val rawOutput = FloatArray(5 * numOutputs)
            outputBuffer.asFloatBuffer().get(rawOutput)

            // Post-process: confidence threshold and NMS
            val boxes = mutableListOf<Detection>()
            val confThreshold = 0.5f

            for (i in 0 until numOutputs) {
                val cx = rawOutput[i]
                val cy = rawOutput[numOutputs + i]
                val bw = rawOutput[2 * numOutputs + i]
                val bh = rawOutput[3 * numOutputs + i]
                val score = rawOutput[4 * numOutputs + i]

                if (score < confThreshold) continue
                if (cx < 0 || cx > 1 || cy < 0 || cy > 1) continue
                if (bw <= 0 || bh <= 0) continue

                // Convert xywh to x1y1x2y2
                val x1 = cx - bw * 0.5f
                val y1 = cy - bh * 0.5f
                val x2 = cx + bw * 0.5f
                val y2 = cy + bh * 0.5f

                // Convert to screen coordinates
                val screenX1 = offsetX + x1 * regionWidth
                val screenY1 = offsetY + y1 * regionHeight
                val screenX2 = offsetX + x2 * regionWidth
                val screenY2 = offsetY + y2 * regionHeight

                boxes.add(Detection(
                    classId = 0f,
                    score = score,
                    x1 = screenX1 / screenWidth,
                    y1 = screenY1 / screenHeight,
                    x2 = screenX2 / screenWidth,
                    y2 = screenY2 / screenHeight
                ))
            }

            Log.d("TfliteClassifier", "Raw detections: ${boxes.size}")

            val finalBoxes = nms(boxes, 0.3f)
            Log.d("TfliteClassifier", "After NMS: ${finalBoxes.size}")

            if (finalBoxes.isEmpty()) return null

            val result = FloatArray(finalBoxes.size * 6)
            for (i in finalBoxes.indices) {
                result[i * 6 + 0] = finalBoxes[i].classId
                result[i * 6 + 1] = finalBoxes[i].score
                result[i * 6 + 2] = finalBoxes[i].x1
                result[i * 6 + 3] = finalBoxes[i].y1
                result[i * 6 + 4] = finalBoxes[i].x2
                result[i * 6 + 5] = finalBoxes[i].y2
            }
            return result

        } catch (e: Exception) {
            Log.e("TfliteClassifier", "detect failed: ${e.message}", e)
            return null
        }
    }

    private fun nms(boxes: List<Detection>, iouThreshold: Float): List<Detection> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.score }
        val keep = mutableListOf<Detection>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            keep.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val iou = computeIou(sorted[i], sorted[j])
                if (iou > iouThreshold) suppressed[j] = true
            }
        }
        return keep
    }

    private fun computeIou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.x1, b.x1)
        val y1 = maxOf(a.y1, b.y1)
        val x2 = minOf(a.x2, b.x2)
        val y2 = minOf(a.y2, b.y2)
        val interW = maxOf(0f, x2 - x1)
        val interH = maxOf(0f, y2 - y1)
        val interArea = interW * interH
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = areaA + areaB - interArea
        return if (unionArea > 0) interArea / unionArea else 0f
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        Log.d("TfliteClassifier", "Released")
    }
}
