package com.xunlei.ai.controller
import com.xunlei.ai.model.DetectionInfo

import android.graphics.RectF
import android.util.Log
import com.xunlei.ai.service.FloatService
import com.xunlei.ai.injector.TouchInjectorInterface
import com.xunlei.ai.model.AreaConfig
import com.xunlei.ai.model.AimingState
import com.xunlei.ai.model.BezierMover

class AimController(
    private val service: FloatService,
    private val touchClient: () -> TouchInjectorInterface?,
    private val savedAreas: () -> List<AreaConfig>
) {
    data class AimSolution(
        val detection: DetectionInfo?,
        val aimX: Float,
        val aimY: Float,
        val usingFallback: Boolean
    )

    companion object {
        private const val TAG = "AimController"
        private const val AREA_INDEX_AIM = 2
    }

    // PID parameters
    var kp = 0.015f
    var ki = 0f
    var kd = 0.008f
    var pidSamplePeriodMs = 8

    // Aim settings
    var aimMode = 0 // 0=PID, 1=Bezier
    var bezierDuration = 30
    var bezierControlOffset = 0.3f
    var bezierRandomSpread = 0.1f
    var convergeThresh = 10f
    var aimOffsetYRatio = 0f
    var aimSwayAmplitude = 0
    var aimPredictionMultiplier = 0.7f
    var aimHoldEnabled = false
    var recoilEnabled = false
    var recoilStrength = 0f
    var targetLostTolerance = 2
    var showLockRay = false
    var lockBoxThreshold = 0.35f
    var lockCenterWeight = 0.3f
    var moveSmooth = 0.35f
    var deadzoneHoldFrames = 3
    var edgeReturnStrength = 0.25f

    // Class filtering
    var aimClasses: MutableSet<Int> = mutableSetOf()
    var priorityClass: Int = -1
    var classAimOffsets: Map<Int, Float> = emptyMap()
    var boxAimRatio = 0.5f
    var classBoxAimRatios: Map<Int, Float> = emptyMap()

    // State
    val aimingState = AimingState()
    private val bezierMover = BezierMover()
    private var predictionTrackId = -1
    private var predictionLabel = -1
    private var predictionTargetX = Float.NaN
    private var predictionTargetY = Float.NaN
    private var predictedVelocityX = 0f
    private var predictedVelocityY = 0f

    private fun isRecoilActive(): Boolean {
        val client = touchClient() ?: return false
        return if (aimHoldEnabled) client.isFingerInTriggerZone() else false
    }

    private fun currentRecoilOffset(): Float {
        if (!recoilEnabled) return 0f
        if (!isRecoilActive()) return 0f
        return recoilStrength.coerceIn(0f, 80f)
    }

    fun selectTarget(dets: List<DetectionInfo>, cx: Float, cy: Float): AimSolution? {
        val candidates = if (priorityClass >= 0) {
            val prioritized = dets.filter { it.classId == priorityClass }
            if (prioritized.isNotEmpty()) prioritized else dets
        } else dets

        // ========== COMMITMENT PHASE ==========
        // 锁定目标后，直到目标消失才切换下一个
        val committedId = aimingState.committedTrackId
        val committedBox = aimingState.committedBox
        if (committedId >= 0 || committedBox != null) {
            // 优先用 trackId 匹配，回退到框匹配
            var committedTarget: DetectionInfo? = null
            if (committedId >= 0) {
                committedTarget = candidates.firstOrNull { it.trackId == committedId }
            }
            if (committedTarget == null && committedBox != null) {
                // trackId 匹配失败，用框匹配回退
                committedTarget = matchCommittedBox(candidates, committedBox)
            }

            if (committedTarget != null && committedTarget.missedFrames <= 0) {
                // 目标仍在画面中 — 保持锁定，无论距离多远
                aimingState.committedMissingFrames = 0
                aimingState.commitFrameCount++
                var (aimX, aimY) = computeAimPoint(committedTarget)
                updatePredictionState(committedTarget, aimX, aimY)
                applyPrediction(committedTarget, aimX, aimY).also {
                    aimX = it.first
                    aimY = it.second
                }
                aimingState.lastTargetX = aimX
                aimingState.lastTargetY = aimY
                aimingState.lockedMissedFrames = 0
                updateLockedTarget(committedTarget)
                // 更新 committedBox 以跟踪目标移动
                aimingState.committedBox = RectF(committedTarget.rect)
                if (committedTarget.trackId >= 0) {
                    aimingState.committedTrackId = committedTarget.trackId
                }
                return AimSolution(committedTarget, aimX, aimY, false)
            } else {
                // 目标消失 — 计数确认
                aimingState.committedMissingFrames++
                val killFrames = aimingState.commitKillConfirmFrames.coerceIn(3, 60)
                if (aimingState.committedMissingFrames > killFrames) {
                    Log.d(TAG, "commit: target missing ${aimingState.committedMissingFrames} frames, confirmed killed, switching to next")
                    clearCommitment()
                    // 确认击杀后允许选择新目标，继续往下走到正常选择阶段
                } else {
                    // 宽限期 — 使用最后已知位置，绝不切换到其他目标
                    if (!aimingState.lastTargetX.isNaN() && !aimingState.lastTargetY.isNaN() &&
                        aimingState.lockedMissedFrames < targetLostTolerance.coerceIn(0, 10)) {
                        aimingState.lockedMissedFrames++
                        return AimSolution(
                            detection = null,
                            aimX = aimingState.lastTargetX,
                            aimY = aimingState.lastTargetY,
                            usingFallback = true
                        )
                    }
                    // 宽限期内没有可用位置 — 停止瞄准，等待目标回来，不切换
                    return null
                }
            }
        }

        // ========== NORMAL SELECTION PHASE ==========
        // 优先选择距离准星最近的目标
        val target = selectRawTarget(candidates, cx, cy)
        if (target != null && target.missedFrames <= 0) {
            // 锁定到该目标
            commitToTarget(target)
            var (aimX, aimY) = computeAimPoint(target)
            updatePredictionState(target, aimX, aimY)
            applyPrediction(target, aimX, aimY).also {
                aimX = it.first
                aimY = it.second
            }
            aimingState.lastTargetX = aimX
            aimingState.lastTargetY = aimY
            aimingState.lockedMissedFrames = 0
            updateLockedTarget(target)
            return AimSolution(target, aimX, aimY, false)
        }

        val lostTolerance = targetLostTolerance.coerceIn(0, 10)
        val hasLock = aimingState.lockedTrackId >= 0 || aimingState.lockedTarget != null
        if (hasLock && !aimingState.lastTargetX.isNaN() && !aimingState.lastTargetY.isNaN() &&
            aimingState.lockedMissedFrames < lostTolerance) {
            aimingState.lockedMissedFrames++
            return AimSolution(
                detection = target,
                aimX = aimingState.lastTargetX,
                aimY = aimingState.lastTargetY,
                usingFallback = true
            )
        }

        clearLockedTarget()
        return null
    }

    private fun commitToTarget(target: DetectionInfo) {
        // 如果已经有承诺目标且相同，只需更新 committedBox
        if (aimingState.committedTrackId == target.trackId && target.trackId >= 0) {
            aimingState.committedBox = RectF(target.rect)
            return
        }
        // 即使用 trackId 不可用，也用框来承诺
        aimingState.committedTrackId = if (target.trackId >= 0) target.trackId else -1
        aimingState.committedClassId = target.classId
        aimingState.committedBox = RectF(target.rect)
        aimingState.committedMissingFrames = 0
        aimingState.commitFrameCount = 0
        Log.d(TAG, "commit: NEW target trackId=${target.trackId} classId=${target.classId} box=${target.rect}")
    }

    private fun clearCommitment() {
        aimingState.committedTrackId = -1
        aimingState.committedClassId = -1
        aimingState.committedBox = null
        aimingState.committedMissingFrames = 0
        aimingState.commitFrameCount = 0
    }

    private fun hasActiveCommitment(): Boolean {
        if (aimingState.committedTrackId < 0) return false
        val minFrames = aimingState.commitMinHoldFrames.coerceIn(0, 30)
        if (aimingState.commitFrameCount < minFrames) return false
        val killFrames = aimingState.commitKillConfirmFrames.coerceIn(3, 60)
        return aimingState.committedMissingFrames <= killFrames
    }

    fun currentLockRayPoint(): Pair<Float, Float>? {
        if (!showLockRay) return null
        if (aimingState.lastTargetX.isNaN() || aimingState.lastTargetY.isNaN()) return null
        return aimingState.lastTargetX to aimingState.lastTargetY
    }

    private fun selectRawTarget(candidates: List<DetectionInfo>, cx: Float, cy: Float): DetectionInfo? {
        val lockedTrackId = aimingState.lockedTrackId
        if (lockedTrackId >= 0) {
            candidates.firstOrNull { it.trackId == lockedTrackId }?.let { tracked ->
                return tracked
            }
            aimingState.lockedTrackId = -1
        }

        val lock = aimingState.lockedTarget
        if (lock != null) {
            val matched = matchLockedBox(candidates, lock)
            if (matched != null) {
                return matched
            }
            clearLockedTarget()
        }

        var bestDistSq = Float.MAX_VALUE
        var bestDet: DetectionInfo? = null
        for (det in candidates) {
            val r = det.rect
            val bcx = (r.left + r.right) * 0.5f
            val bcy = (r.top + r.bottom) * 0.5f
            val d = (bcx - cx) * (bcx - cx) + (bcy - cy) * (bcy - cy)
            if (d < bestDistSq) {
                bestDistSq = d
                bestDet = det
            }
        }
        return bestDet
    }

    private fun matchLockedBox(candidates: List<DetectionInfo>, lockedRect: RectF): DetectionInfo? {
        var bestDet: DetectionInfo? = null
        var bestScore = 0f
        for (det in candidates) {
            val score = lockedBoxScore(lockedRect, det.rect)
            if (score > bestScore) {
                bestScore = score
                bestDet = det
            }
        }
        return if (bestScore >= lockBoxThreshold.coerceIn(0f, 1f)) bestDet else null
    }

    // 回退框匹配：当 trackId 不可用时，用 IoU 匹配承诺目标
    private fun matchCommittedBox(candidates: List<DetectionInfo>, committedRect: RectF): DetectionInfo? {
        var bestDet: DetectionInfo? = null
        var bestIou = 0f
        for (det in candidates) {
            if (det.classId != aimingState.committedClassId) continue
            val iou = computeIoU(committedRect, det.rect)
            if (iou > bestIou) {
                bestIou = iou
                bestDet = det
            }
        }
        // 承诺框匹配阈值稍低，因为目标可能快速移动
        return if (bestIou >= 0.15f && bestDet != null) bestDet else null
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interW = maxOf(0f, interRight - interLeft)
        val interH = maxOf(0f, interBottom - interTop)
        val interArea = interW * interH
        val areaA = maxOf(1f, a.width() * a.height())
        val areaB = maxOf(1f, b.width() * b.height())
        return interArea / maxOf(1f, areaA + areaB - interArea)
    }

    private fun lockedBoxScore(lockedRect: RectF, rect: RectF): Float {
        val interLeft = maxOf(lockedRect.left, rect.left)
        val interTop = maxOf(lockedRect.top, rect.top)
        val interRight = minOf(lockedRect.right, rect.right)
        val interBottom = minOf(lockedRect.bottom, rect.bottom)
        val interW = maxOf(0f, interRight - interLeft)
        val interH = maxOf(0f, interBottom - interTop)
        val interArea = interW * interH
        val lockedArea = maxOf(1f, lockedRect.width() * lockedRect.height())
        val currentArea = maxOf(1f, rect.width() * rect.height())
        val iou = interArea / maxOf(1f, lockedArea + currentArea - interArea)

        val lockedCenterX = lockedRect.centerX()
        val lockedCenterY = lockedRect.centerY()
        val currentCenterX = rect.centerX()
        val currentCenterY = rect.centerY()
        val lockedDiag = kotlin.math.sqrt(lockedArea)
        val dx = currentCenterX - lockedCenterX
        val dy = currentCenterY - lockedCenterY
        val centerScore = 1f - minOf(1f, kotlin.math.sqrt(dx * dx + dy * dy) / maxOf(1f, lockedDiag))

        val centerWeight = lockCenterWeight.coerceIn(0f, 1f)
        return iou * (1f - centerWeight) + centerScore * centerWeight
    }

    private fun updateLockedTarget(target: DetectionInfo) {
        aimingState.lockedTarget = RectF(target.rect)
        aimingState.lockedTrackId = target.trackId
    }

    private fun updatePredictionState(target: DetectionInfo, aimX: Float, aimY: Float) {
        val identityChanged = when {
            target.trackId >= 0 -> target.trackId != predictionTrackId
            predictionTrackId >= 0 -> true
            else -> target.classId != predictionLabel || predictionTargetX.isNaN() || predictionTargetY.isNaN()
        }
        if (identityChanged) {
            predictionTrackId = target.trackId
            predictionLabel = target.classId
            predictionTargetX = aimX
            predictionTargetY = aimY
            predictedVelocityX = 0f
            predictedVelocityY = 0f
            return
        }

        val kalmanVelocityValid = target.trackId >= 0 &&
            (kotlin.math.abs(target.velocityX) > 0.001f || kotlin.math.abs(target.velocityY) > 0.001f)
        if (kalmanVelocityValid) {
            predictedVelocityX = target.velocityX
            predictedVelocityY = target.velocityY
            predictionTargetX = aimX
            predictionTargetY = aimY
            return
        }

        predictedVelocityX = predictedVelocityX * 0.65f + (aimX - predictionTargetX) * 0.35f
        predictedVelocityY = predictedVelocityY * 0.65f + (aimY - predictionTargetY) * 0.35f
        predictionTargetX = aimX
        predictionTargetY = aimY
    }

    private fun applyPrediction(target: DetectionInfo, aimX: Float, aimY: Float): Pair<Float, Float> {
        val factor = aimPredictionMultiplier.coerceIn(0f, 2f)
        if (factor <= 0f) return aimX to aimY
        val predictedX = aimX + predictedVelocityX * factor
        val predictedY = aimY + predictedVelocityY * factor
        val rect = target.rect
        val padX = rect.width()
        val padY = rect.height()
        return predictedX.coerceIn(rect.left - padX, rect.right + padX) to
            predictedY.coerceIn(rect.top - padY, rect.bottom + padY)
    }

    private fun clearPredictionState() {
        predictionTrackId = -1
        predictionLabel = -1
        predictionTargetX = Float.NaN
        predictionTargetY = Float.NaN
        predictedVelocityX = 0f
        predictedVelocityY = 0f
    }

    private fun clearLockedTarget() {
        aimingState.lockedTarget = null
        aimingState.lockedTrackId = -1
        aimingState.lockedMissedFrames = 0
        aimingState.lastTargetX = Float.NaN
        aimingState.lastTargetY = Float.NaN
        clearPredictionState()
    }

    private fun computeAimPoint(target: DetectionInfo): Pair<Float, Float> {
        val boxH = target.rect.height()
        val tcx = target.rect.centerX()
        val tcy = target.rect.centerY()
        val classOffset = classAimOffsets[target.classId] ?: aimOffsetYRatio
        val classBoxRatio = classBoxAimRatios[target.classId] ?: boxAimRatio
        val aimX = tcx
        val aimY = (tcy - boxH * 0.5f) + boxH * (1f - classBoxRatio) - boxH * classOffset
        return aimX to aimY
    }

    fun executeAiming(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        if (aimMode == 1) {
            executeAimingBezier(targetX, targetY, cx, cy)
        } else {
            executeAimingPid(targetX, targetY, cx, cy)
        }
    }

    private fun executeAimingBezier(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        val errorX = targetX - cx
        val errorY = targetY - cy

        if (!aimingState.pointerDown) {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) return

            val aimArea = savedAreas().getOrNull(AREA_INDEX_AIM)
            if (aimArea != null) {
                aimingState.centerX = aimArea.x + (Math.random() * aimArea.width).toFloat()
                aimingState.centerY = aimArea.y + (Math.random() * aimArea.height).toFloat()
            } else {
                aimingState.centerX = cx
                aimingState.centerY = cy
            }
            aimingState.startX = aimingState.centerX
            aimingState.startY = aimingState.centerY

            touchClient()?.swipe(aimingState.centerX.toInt(), aimingState.centerY.toInt(), aimingState.centerX.toInt(), aimingState.centerY.toInt(), 0)
            aimingState.pointerDown = true
            val now = System.currentTimeMillis()
            val dist = Math.sqrt((errorX * errorX + errorY * errorY).toDouble()).toFloat()
            val duration = (bezierDuration * 5 + dist * 0.3f).toInt().coerceIn(200, 800)
            bezierMover.start(now, now + duration)
        } else {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) {
                aimingState.lastMoveX = 0f
                aimingState.lastMoveY = 0f
                // FIX: 收敛时保持触控不动，不释放指针
                //      始终同触点平滑拉枪，避免 lift+swipe 的间断
                if (aimingState.pointerDown) aimingState.deadzoneFrames = 0
                return
            }
            aimingState.deadzoneFrames = 0

            if (!bezierMover.isActive()) {
                val now = System.currentTimeMillis()
                val dist = Math.sqrt((errorX * errorX + errorY * errorY).toDouble()).toFloat()
                val duration = (bezierDuration * 5 + dist * 0.3f).toInt().coerceIn(200, 800)
                bezierMover.start(now, now + duration)
            }
            val t = bezierMover.tickRatio(System.currentTimeMillis())
            var moveX = errorX * t
            var moveY = errorY * t
            moveY += currentRecoilOffset()
            if (aimSwayAmplitude > 0) moveY += computeSway()
            val smooth = moveSmooth.coerceIn(0f, 0.95f)
            moveX = aimingState.lastMoveX * smooth + moveX * (1f - smooth)
            moveY = aimingState.lastMoveY * smooth + moveY * (1f - smooth)
            aimingState.lastMoveX = moveX
            aimingState.lastMoveY = moveY
            aimingState.centerX += moveX
            aimingState.centerY += moveY
            val clamped = clampToAimArea()
            if (!clamped && applyDragSafety()) return
            touchClient()?.moveTo(aimingState.centerX.toInt(), aimingState.centerY.toInt())
        }
    }

    private fun executeAimingPid(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        val errorX = targetX - cx
        val errorY = targetY - cy
        if (!aimingState.pointerDown) {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) return
            val aimArea = savedAreas().getOrNull(AREA_INDEX_AIM)
            if (aimArea != null) {
                aimingState.centerX = aimArea.x + (Math.random() * aimArea.width).toFloat()
                aimingState.centerY = aimArea.y + (Math.random() * aimArea.height).toFloat()
            } else {
                aimingState.centerX = cx
                aimingState.centerY = cy
            }
            aimingState.startX = aimingState.centerX
            aimingState.startY = aimingState.centerY
            aimingState.prevErrorX = 0f
            aimingState.prevErrorY = 0f
            aimingState.integralX = 0f
            aimingState.integralY = 0f
            touchClient()?.swipe(aimingState.centerX.toInt(), aimingState.centerY.toInt(), aimingState.centerX.toInt(), aimingState.centerY.toInt(), 0)
            aimingState.pointerDown = true
            Log.d(TAG, "aim DOWN at (${aimingState.centerX}, ${aimingState.centerY}) target=($targetX, $targetY)")
        } else {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) {
                aimingState.lastMoveX = 0f
                aimingState.lastMoveY = 0f
                // FIX: 收敛时保持触控不动，不释放指针
                //      始终同触点平滑拉枪，避免 lift+swipe 的间断
                if (aimingState.pointerDown) aimingState.deadzoneFrames = 0
                return
            }
            aimingState.deadzoneFrames = 0
            if (errorX * aimingState.prevErrorX <= 0) aimingState.integralX = 0f
            if (errorY * aimingState.prevErrorY <= 0) aimingState.integralY = 0f
            val samplePeriod = (pidSamplePeriodMs.coerceIn(1, 1000) / 1000.0f).coerceAtLeast(0.001f)
            aimingState.integralX += errorX * samplePeriod
            aimingState.integralY += errorY * samplePeriod
            val integralLimit = 100f
            aimingState.integralX = aimingState.integralX.coerceIn(-integralLimit, integralLimit)
            aimingState.integralY = aimingState.integralY.coerceIn(-integralLimit, integralLimit)
            val derivX = (errorX - aimingState.prevErrorX) / samplePeriod
            val derivY = (errorY - aimingState.prevErrorY) / samplePeriod
            var moveX = errorX * kp + aimingState.integralX * ki + derivX * kd
            var moveY = errorY * kp + aimingState.integralY * ki + derivY * kd
            moveY += currentRecoilOffset()
            if (aimSwayAmplitude > 0) moveY += computeSway()
            val smooth = moveSmooth.coerceIn(0f, 0.95f)
            moveX = aimingState.lastMoveX * smooth + moveX * (1f - smooth)
            moveY = aimingState.lastMoveY * smooth + moveY * (1f - smooth)
            aimingState.lastMoveX = moveX
            aimingState.lastMoveY = moveY
            aimingState.prevErrorX = errorX
            aimingState.prevErrorY = errorY
            val maxPerFrame = 1200f
            val moveDist = Math.sqrt((moveX * moveX + moveY * moveY).toDouble()).toFloat()
            if (moveDist > maxPerFrame) {
                moveX = moveX / moveDist * maxPerFrame
                moveY = moveY / moveDist * maxPerFrame
            }
            aimingState.centerX += moveX
            aimingState.centerY += moveY
            val clamped = clampToAimArea()
            if (!clamped && applyDragSafety()) return
            touchClient()?.moveTo(aimingState.centerX.toInt(), aimingState.centerY.toInt())
        }
    }

    private fun computeSway(): Float {
        if (aimSwayAmplitude <= 0) return 0f
        if (aimingState.swayPulse > 0) {
            aimingState.swayPulse--
            val half = aimingState.swayDuration / 2
            val t = if (aimingState.swayPulse > half) (aimingState.swayDuration - aimingState.swayPulse) / half.toFloat() else aimingState.swayPulse / half.toFloat()
            val sway = aimingState.swayDir * aimSwayAmplitude * t
            if (aimingState.swayPulse == 0) aimingState.swayTimer = (30..90).random()
            return sway
        } else {
            aimingState.swayTimer--
            if (aimingState.swayTimer <= 0) {
                aimingState.swayDuration = (6..16).random()
                aimingState.swayPulse = aimingState.swayDuration
                aimingState.swayDir = if (Math.random() > 0.5f) 1f else -1f
            }
            return 0f
        }
    }

    private fun applyDragSafety(): Boolean {
        val dx = aimingState.centerX - aimingState.startX
        val dy = aimingState.centerY - aimingState.startY
        val dragDist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dragDist > aimingState.maxDragDist) {
            touchClient()?.lift()
            aimingState.pointerDown = false
            aimingState.lockedTarget = null
            aimingState.lockedTrackId = -1
            bezierMover.cancel()
            Log.d(TAG, "aim edge lift at (${aimingState.centerX}, ${aimingState.centerY}) drag=$dragDist")
            return true
        }
        return false
    }

    private fun clampToAimArea(): Boolean {
        val aimArea = savedAreas().getOrNull(AREA_INDEX_AIM) ?: return false
        val minX = aimArea.x.toFloat()
        val maxX = (aimArea.x + aimArea.width).toFloat()
        val minY = aimArea.y.toFloat()
        val maxY = (aimArea.y + aimArea.height).toFloat()
        val oldX = aimingState.centerX
        val oldY = aimingState.centerY
        aimingState.centerX = aimingState.centerX.coerceIn(minX, maxX)
        aimingState.centerY = aimingState.centerY.coerceIn(minY, maxY)
        val clamped = aimingState.centerX != oldX || aimingState.centerY != oldY
        if (clamped && edgeReturnStrength > 0f) {
            val areaCx = minX + (maxX - minX) * 0.5f
            val areaCy = minY + (maxY - minY) * 0.5f
            aimingState.centerX += (areaCx - aimingState.centerX) * edgeReturnStrength.coerceIn(0f, 1f)
            aimingState.centerY += (areaCy - aimingState.centerY) * edgeReturnStrength.coerceIn(0f, 1f)
            aimingState.centerX = aimingState.centerX.coerceIn(minX, maxX)
            aimingState.centerY = aimingState.centerY.coerceIn(minY, maxY)
        }
        return clamped
    }

    private fun releaseAimPointer() {
        touchClient()?.lift()
        aimingState.pointerDown = false
        aimingState.deadzoneFrames = 0
        aimingState.lastMoveX = 0f
        aimingState.lastMoveY = 0f
    }

    fun lift() {
        releaseAimPointer()
        clearLockedTarget()
    }

    fun reset() {
        aimingState.reset()
        bezierMover.cancel()
        clearPredictionState()
    }
}
