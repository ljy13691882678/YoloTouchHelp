package com.xunlei.ai.inference

import android.graphics.RectF
import com.xunlei.ai.model.DetectionInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class KalmanObjectTracker {
    companion object {
        private const val VELOCITY_FRAME_DT = 1.0f / 60.0f
    }

    var maxMissed = 5
    var processNoise = 70.0f
    var measureNoise = 110.0f
    var boxSmooth = 0.60f
    var matchIouThreshold = 0.20f

    private data class TrackedObject(
        var id: Int,
        var label: Int,
        var className: String,
        var score: Float,
        var lastUpdateMs: Long,
        var box: RectF,
        var width: Float,
        var height: Float,
        var missed: Int = 0,
        val state: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        val covariance: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
    )

    private val trackedObjects = mutableListOf<TrackedObject>()
    private var nextTrackId = 1

    fun reset() {
        trackedObjects.clear()
        nextTrackId = 1
    }

    fun update(detections: List<DetectionInfo>, nowMs: Long): List<DetectionInfo> {
        val maxMissed = maxMissed.coerceIn(0, 30)
        val processNoise = processNoise.coerceIn(0.0f, 500.0f)
        val measureNoise = measureNoise.coerceIn(1.0f, 500.0f)
        val boxSmooth = boxSmooth.coerceIn(0.0f, 1.0f)
        val matchIouThreshold = matchIouThreshold.coerceIn(0.0f, 1.0f)

        for (track in trackedObjects) {
            val dt = ((nowMs - track.lastUpdateMs) / 1000.0f).coerceIn(0.0f, 0.2f)
            track.state[0] += track.state[2] * dt
            track.state[1] += track.state[3] * dt
            track.covariance[0] += track.covariance[2] * dt * dt + processNoise
            track.covariance[1] += track.covariance[3] * dt * dt + processNoise
            track.covariance[2] += processNoise
            track.covariance[3] += processNoise
            track.box = RectF(
                track.state[0] - track.width * 0.5f,
                track.state[1] - track.height * 0.5f,
                track.state[0] + track.width * 0.5f,
                track.state[1] + track.height * 0.5f
            )
        }

        val trackUsed = BooleanArray(trackedObjects.size)
        val detectionToTrack = IntArray(detections.size) { -1 }

        detections.forEachIndexed { detIndex, detection ->
            val detCx = centerX(detection.rect)
            val detCy = centerY(detection.rect)
            var bestIndex = -1
            var bestScore = 0.0f

            trackedObjects.forEachIndexed { trackIndex, track ->
                if (trackUsed[trackIndex]) return@forEachIndexed
                if (track.label != detection.classId) return@forEachIndexed

                val overlap = iou(track.box, detection.rect)
                if (overlap < matchIouThreshold) return@forEachIndexed

                val dx = detCx - track.state[0]
                val dy = detCy - track.state[1]
                val distance = sqrt(dx * dx + dy * dy)
                val maxDistance = max(rectWidth(detection.rect), rectHeight(detection.rect)) * 2.0f + 1.0f
                val distanceScore = max(0.0f, 1.0f - distance / maxDistance)
                val matchScore = overlap * 0.75f + distanceScore * 0.25f
                if (matchScore > bestScore) {
                    bestScore = matchScore
                    bestIndex = trackIndex
                }
            }

            if (bestIndex >= 0) {
                trackUsed[bestIndex] = true
                detectionToTrack[detIndex] = bestIndex
            }
        }

        detections.forEachIndexed { detIndex, detection ->
            val trackIndex = detectionToTrack[detIndex]
            if (trackIndex < 0) return@forEachIndexed

            val track = trackedObjects[trackIndex]
            val mx = centerX(detection.rect)
            val my = centerY(detection.rect)
            val oldX = track.state[0]
            val oldY = track.state[1]
            val gainX = track.covariance[0] / (track.covariance[0] + measureNoise)
            val gainY = track.covariance[1] / (track.covariance[1] + measureNoise)
            track.state[0] += gainX * (mx - track.state[0])
            track.state[1] += gainY * (my - track.state[1])
            val dt = ((nowMs - track.lastUpdateMs) / 1000.0f).coerceIn(0.001f, 0.2f)
            track.state[2] = track.state[2] * 0.55f + ((track.state[0] - oldX) / dt) * 0.45f
            track.state[3] = track.state[3] * 0.55f + ((track.state[1] - oldY) / dt) * 0.45f
            track.covariance[0] *= (1.0f - gainX)
            track.covariance[1] *= (1.0f - gainY)
            track.width = track.width * (1.0f - boxSmooth) + rectWidth(detection.rect) * boxSmooth
            track.height = track.height * (1.0f - boxSmooth) + rectHeight(detection.rect) * boxSmooth
            track.box = RectF(detection.rect)
            track.className = detection.className
            track.score = detection.score
            track.missed = 0
            track.lastUpdateMs = nowMs
        }

        trackedObjects.forEachIndexed { trackIndex, track ->
            if (trackUsed[trackIndex]) return@forEachIndexed
            track.missed += 1
            track.box = RectF(
                track.state[0] - track.width * 0.5f,
                track.state[1] - track.height * 0.5f,
                track.state[0] + track.width * 0.5f,
                track.state[1] + track.height * 0.5f
            )
            track.score *= 0.85f
            track.lastUpdateMs = nowMs
        }

        detections.forEachIndexed { detIndex, detection ->
            if (detectionToTrack[detIndex] >= 0) return@forEachIndexed
            val width = rectWidth(detection.rect)
            val height = rectHeight(detection.rect)
            val newTrack = TrackedObject(
                id = nextTrackId++,
                label = detection.classId,
                className = detection.className,
                score = detection.score,
                lastUpdateMs = nowMs,
                box = RectF(detection.rect),
                width = width,
                height = height
            ).apply {
                state[0] = centerX(detection.rect)
                state[1] = centerY(detection.rect)
            }
            trackedObjects += TrackedObject(
                id = nextTrackId++,
                label = detection.classId,
                className = detection.className,
                score = detection.score,
                lastUpdateMs = nowMs,
                box = RectF(detection.rect),
                width = width,
                height = height
            ).apply {
                state[0] = centerX(detection.rect)
                state[1] = centerY(detection.rect)
            }
        }

        trackedObjects.removeAll { it.missed > maxMissed || it.width <= 1.0f || it.height <= 1.0f }

        return trackedObjects.map { track ->
            val frameVelocityX = track.state[2] * VELOCITY_FRAME_DT
            val frameVelocityY = track.state[3] * VELOCITY_FRAME_DT
            DetectionInfo(
                rect = RectF(track.box),
                classId = track.label,
                className = track.className,
                score = track.score,
                trackId = track.id,
                missedFrames = track.missed,
                velocityX = frameVelocityX,
                velocityY = frameVelocityY
            )
        }
    }

    private fun centerX(rect: RectF): Float = (rect.left + rect.right) * 0.5f
    private fun centerY(rect: RectF): Float = (rect.top + rect.bottom) * 0.5f
    private fun rectWidth(rect: RectF): Float = max(1.0f, rect.width())
    private fun rectHeight(rect: RectF): Float = max(1.0f, rect.height())

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val interW = max(0.0f, right - left)
        val interH = max(0.0f, bottom - top)
        val inter = interW * interH
        val areaA = max(1.0f, a.width() * a.height())
        val areaB = max(1.0f, b.width() * b.height())
        return inter / max(1.0f, areaA + areaB - inter)
    }
}
