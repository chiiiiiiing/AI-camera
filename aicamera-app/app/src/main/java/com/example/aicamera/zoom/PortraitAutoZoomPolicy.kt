package com.example.aicamera.zoom

import kotlin.math.max

/** Conservative, subject-driven portrait zoom. */
object PortraitAutoZoomPolicy {
    private const val TARGET_SUBJECT_EXTENT = 0.30f
    private const val MIN_VALID_EXTENT = 0.06f
    private const val MAX_VALID_EXTENT = 0.85f
    private const val MAX_STEP_SCALE = 1.25f
    private const val MIN_STEP_SCALE = 0.90f
    private const val MAX_AUTO_RATIO = 1.80f

    fun targetRatio(currentRatio: Float, subjectWidth: Float, subjectHeight: Float, deviceMinRatio: Float, deviceMaxRatio: Float): Float? {
        if (!currentRatio.isFinite() || !subjectWidth.isFinite() || !subjectHeight.isFinite()) return null
        val extent = max(subjectWidth, subjectHeight)
        if (extent !in MIN_VALID_EXTENT..MAX_VALID_EXTENT) return null
        val safeMin = max(1f, deviceMinRatio)
        val safeMax = minOf(deviceMaxRatio, MAX_AUTO_RATIO).coerceAtLeast(safeMin)
        val rawTarget = currentRatio * (TARGET_SUBJECT_EXTENT / extent)
        return rawTarget.coerceIn(currentRatio * MIN_STEP_SCALE, currentRatio * MAX_STEP_SCALE).coerceIn(safeMin, safeMax)
    }
}
