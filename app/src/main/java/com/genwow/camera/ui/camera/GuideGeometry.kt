package com.genwow.camera.ui.camera

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun ellipsePoint(
    centerX: Float,
    centerY: Float,
    radiusX: Float,
    radiusY: Float,
    angleDegrees: Float,
): Offset {
    val angle = angleDegrees.takeIf(Float::isFinite) ?: 0f
    val radians = angle * PI.toFloat() / 180f
    val safeRadiusX = radiusX.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val safeRadiusY = radiusY.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    return Offset(
        x = centerX + cos(radians) * safeRadiusX,
        y = centerY + sin(radians) * safeRadiusY,
    )
}

internal fun ellipseTangent(
    radiusX: Float,
    radiusY: Float,
    angleDegrees: Float,
    clockwise: Boolean,
): Offset {
    val angle = angleDegrees.takeIf(Float::isFinite) ?: 0f
    val radians = angle * PI.toFloat() / 180f
    val safeRadiusX = radiusX.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val safeRadiusY = radiusY.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val direction = if (clockwise) 1f else -1f
    val raw = Offset(
        x = -safeRadiusX * sin(radians) * direction,
        y = safeRadiusY * cos(radians) * direction,
    )
    val length = sqrt(raw.x * raw.x + raw.y * raw.y)
    return if (length > 0.0001f) raw / length else Offset(direction, 0f)
}

internal fun fadingAxisAlpha(normalizedPosition: Float): Float {
    if (!normalizedPosition.isFinite()) return 0f
    val distance = abs(normalizedPosition).coerceIn(0f, 1f)
    return (1f - distance) * (1f - distance)
}

internal fun guideMotionScale(phase: GuidancePhase): Float = when (phase) {
    GuidancePhase.NEAR -> 0.45f
    GuidancePhase.ALIGNED,
    GuidancePhase.NEUTRAL,
    -> 0f
    else -> 1f
}

internal fun scaledSubjectContours(
    outline: SubjectOutline,
    width: Float,
    height: Float,
): List<List<Offset>> {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return emptyList()
    return outline.contours.mapNotNull { scaledSubjectContour(it, width, height) }
}

internal fun scaledSubjectContour(
    contour: SubjectContour,
    width: Float,
    height: Float,
): List<Offset>? {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
    return contour.points.mapNotNull { point ->
        if (!point.x.isFinite() || !point.y.isFinite()) {
            null
        } else {
            Offset(
                x = point.x.coerceIn(0f, 1f) * width,
                y = point.y.coerceIn(0f, 1f) * height,
            )
        }
    }.takeIf { it.size >= 2 }
}
