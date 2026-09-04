package com.example.aicamera.guidance

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.ui.theme.GuideAccent
import com.example.aicamera.ui.theme.GuideAligned
import com.example.aicamera.ui.theme.GuideWarning
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════
// Compose Canvas 引导叠加层 — 从队友 CompositionGuideOverlay 完整移植
//
// 5 种视觉家族全部实现：
//   NONE           → 对齐标记
//   TRANSLATION    → 平移箭头 + 目标太阳 + 十字准心
//   AXIS_ROTATION  → 透视椭圆弧 + 渐隐轴线 (Pitch/Yaw)
//   PLANAR_ROTATION → 面内旋转弧 + 手机外框 (Roll)
//   SUBJECT        → 逐帧轮廓渲染
// ═══════════════════════════════════════════════════════════════

@Composable
fun ComposeGuidanceOverlay(
    state: GuidanceState,
    modifier: Modifier = Modifier,
    showGrid: Boolean = true,
    showTargetFrame: Boolean = true,
) {
    val guidance = state
    val visualFamily = guidance.cue.visualFamily()
    val subjectMode = visualFamily == GuideVisualFamily.SUBJECT
    val looping = guidance.phase == GuidancePhase.ADJUST || guidance.phase == GuidancePhase.NEAR
    val motion = remember { Animatable(0f) }

    val guideAlpha by animateFloatAsState(
        targetValue = when {
            guidance.phase == GuidancePhase.NEUTRAL -> .70f   // ★ 提高：原来 .32f 太淡
            guidance.phase == GuidancePhase.ALIGNED -> .94f
            else -> 1f
        },
        animationSpec = tween(210),
        label = "guide alpha",
    )

    val guideColor by animateColorAsState(
        targetValue = when {
            guidance.phase == GuidancePhase.WRONG_DIRECTION -> GuideWarning
            guidance.phase == GuidancePhase.ALIGNED -> GuideAligned
            else -> GuideAccent
        },
        animationSpec = tween(210),
        label = "guide color",
    )

    LaunchedEffect(looping, guidance.cue, guidance.phase) {
        when {
            guidance.phase == GuidancePhase.WRONG_DIRECTION -> {
                motion.snapTo(-1f)
                motion.animateTo(0f, animationSpec = tween(120))
            }
            looping -> while (true) {
                motion.snapTo(0f)
                motion.animateTo(1f, animationSpec = tween(
                    if (guidance.phase == GuidancePhase.NEAR) 1900 else 1400,
                    easing = LinearEasing,
                ))
            }
            else -> motion.snapTo(0f)
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // 基础网格（★ 提高基础可见性，最低 20%）
            if (showGrid) {
                val gridAlpha = if (subjectMode) 0.08f else 0.28f.coerceAtLeast(guideAlpha * 0.35f)
                drawThirdsGrid(alpha = gridAlpha)
            }
            // 目标参考框
            if (showTargetFrame && !subjectMode) {
                drawTargetFrame(
                    color = guideColor,
                    alpha = if (guidance.phase == GuidancePhase.ALIGNED) .92f else 0.35f.coerceAtLeast(guideAlpha * 0.40f),
                    aligned = guidance.phase == GuidancePhase.ALIGNED,
                )
            }

            val wrongDir = guidance.phase == GuidancePhase.WRONG_DIRECTION
            val displayMotion = if (wrongDir) .5f + motion.value * .18f else motion.value

            when (visualFamily) {
                GuideVisualFamily.TRANSLATION -> drawTranslationGuide(
                    guidance = guidance, color = guideColor,
                    motionPhase = displayMotion, alpha = guideAlpha, wrongDirection = wrongDir,
                )
                GuideVisualFamily.AXIS_ROTATION -> drawAxisRotationGuide(
                    cue = guidance.cue, phase = guidance.phase,
                    color = guideColor, motionPhase = displayMotion, alpha = guideAlpha, wrongDirection = wrongDir,
                )
                GuideVisualFamily.PLANAR_ROTATION -> drawPlanarRotationGuide(
                    cue = guidance.cue, phase = guidance.phase,
                    color = guideColor, motionPhase = displayMotion, alpha = guideAlpha, wrongDirection = wrongDir,
                )
                GuideVisualFamily.NONE -> if (guidance.phase == GuidancePhase.ALIGNED) {
                    drawAlignedMark(guideColor)
                }
                GuideVisualFamily.SUBJECT -> {}
            }
        }

        // 主体轮廓
        if (subjectMode && guidance.subjectOutline != null) {
            SubjectOutlineCanvas(
                outline = guidance.subjectOutline!!,
                anchors = guidance.anchors,
                guideColor = guideColor,
                aligned = guidance.phase == GuidancePhase.ALIGNED ||
                    guidance.cue == GuidanceCue.SUBJECT_ALIGNED,
                motionPhase = motion.value.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// NONE — 对齐标记
// ═══════════════════════════════════════════════════════════════

private fun DrawScope.drawAlignedMark(color: Color) {
    val center = Offset(size.width / 2f, size.height * .43f)
    drawCircle(Color.Black.copy(alpha = .24f), 25.dp.toPx(), center)
    drawCircle(color.copy(alpha = .18f), 24.dp.toPx(), center)
    drawCircle(color.copy(alpha = .88f), 22.dp.toPx(), center, style = Stroke(1.4.dp.toPx()))
    val p = Path().apply {
        moveTo(center.x - 9.dp.toPx(), center.y)
        lineTo(center.x - 2.dp.toPx(), center.y + 7.dp.toPx())
        lineTo(center.x + 11.dp.toPx(), center.y - 8.dp.toPx())
    }
    drawPath(p, color, style = Stroke(2.1.dp.toPx(), cap = StrokeCap.Round))
}

// ═══════════════════════════════════════════════════════════════
// 基础 — 三分线 + 目标框
// ═══════════════════════════════════════════════════════════════

private fun DrawScope.drawThirdsGrid(alpha: Float) {
    val stroke = 0.8.dp.toPx()
    listOf(size.width / 3f, size.width * 2f / 3f).forEach { x ->
        drawLine(Color.White.copy(alpha = alpha), Offset(x, size.height * .13f), Offset(x, size.height * .74f), stroke)
    }
    listOf(size.height * .34f, size.height * .58f).forEach { y ->
        drawLine(Color.White.copy(alpha = alpha), Offset(size.width * .08f, y), Offset(size.width * .92f, y), stroke)
    }
}

private fun DrawScope.drawTargetFrame(color: Color, alpha: Float, aligned: Boolean) {
    val frame = Rect(size.width * .18f, size.height * .24f, size.width * .82f, size.height * .61f)
    val corner = if (aligned) 26.dp.toPx() else 18.dp.toPx()
    val s = if (aligned) 2.dp.toPx() else 1.25.dp.toPx()
    val c = color.copy(alpha = alpha)
    drawCorner(frame.topLeft, Offset(corner, 0f), Offset(0f, corner), c, s)
    drawCorner(frame.topRight, Offset(-corner, 0f), Offset(0f, corner), c, s)
    drawCorner(frame.bottomLeft, Offset(corner, 0f), Offset(0f, -corner), c, s)
    drawCorner(frame.bottomRight, Offset(-corner, 0f), Offset(0f, -corner), c, s)
}

private fun DrawScope.drawCorner(origin: Offset, h: Offset, v: Offset, color: Color, stroke: Float) {
    drawLine(color, origin, origin + h, stroke, StrokeCap.Round)
    drawLine(color, origin, origin + v, stroke, StrokeCap.Round)
}

// ═══════════════════════════════════════════════════════════════
// TRANSLATION — 平移引导
// ═══════════════════════════════════════════════════════════════

private fun DrawScope.drawTranslationGuide(
    guidance: GuidanceState, color: Color, motionPhase: Float, alpha: Float, wrongDirection: Boolean,
) {
    val targetAnchor = Offset(size.width / 2f, size.height * .44f)
    val isHorizontal = guidance.cue == GuidanceCue.MOVE_LEFT || guidance.cue == GuidanceCue.MOVE_RIGHT
    val sign = when (guidance.cue) { GuidanceCue.MOVE_RIGHT, GuidanceCue.MOVE_DOWN -> 1f; else -> -1f }
    val scale = guideMotionScale(guidance.phase)
    val swing = 18.dp.toPx() * scale * (motionPhase - .5f) * sign
    val offset = 48.dp.toPx() * sign
    val currentAnchor = if (isHorizontal) Offset(targetAnchor.x + swing + offset, targetAnchor.y)
    else Offset(targetAnchor.x, targetAnchor.y + swing + offset)

    drawTranslationTrack(currentAnchor, targetAnchor, color, alpha)
    drawTargetSun(targetAnchor, color, alpha)
    drawCrosshair(currentAnchor, color, alpha)

    val direction = when (guidance.cue) {
        GuidanceCue.MOVE_LEFT -> Offset(-1f, 0f); GuidanceCue.MOVE_RIGHT -> Offset(1f, 0f)
        GuidanceCue.MOVE_UP -> Offset(0f, -1f); GuidanceCue.MOVE_DOWN -> Offset(0f, 1f)
        else -> Offset.Zero
    }
    if (direction != Offset.Zero) {
        drawOpenArrow(currentAnchor + direction * 20.dp.toPx(), direction, color.copy(alpha = .94f * alpha), 1.65.dp.toPx())
        if (wrongDirection) {
            drawOpenArrow(currentAnchor - direction * 16.dp.toPx(), -direction, color.copy(alpha = .36f * alpha), 1.35.dp.toPx())
        }
    }
}

private fun DrawScope.drawTranslationTrack(current: Offset, target: Offset, color: Color, alpha: Float) {
    drawLine(Color.Black.copy(alpha = .18f * alpha), current, target, 3.dp.toPx(), StrokeCap.Round)
    drawLine(color.copy(alpha = .38f * alpha), current, target, 1.1.dp.toPx(), StrokeCap.Round)
    val mid = Offset((current.x + target.x) / 2f, (current.y + target.y) / 2f)
    drawCircle(color.copy(alpha = .2f * alpha), 2.6.dp.toPx(), mid)
}

private fun DrawScope.drawTargetSun(center: Offset, color: Color, alpha: Float) {
    val radius = 16.dp.toPx()
    drawCircle(Color.Black.copy(alpha = .18f * alpha), radius + 4.dp.toPx(), center)
    drawCircle(color.copy(alpha = .1f * alpha), radius + 1.5.dp.toPx(), center)
    drawCircle(color.copy(alpha = .54f * alpha), radius - 4.dp.toPx(), center, style = Stroke(1.dp.toPx()))
    drawCircle(color.copy(alpha = .92f * alpha), radius, center, style = Stroke(1.35.dp.toPx()))
    drawCircle(color.copy(alpha = .9f * alpha), 4.5.dp.toPx(), center)
    repeat(8) { i ->
        val a = Math.toRadians((i * 45).toDouble()).toFloat()
        val u = Offset(cos(a), sin(a))
        drawLine(color.copy(alpha = .84f * alpha), center + u * 8.dp.toPx(), center + u * 13.dp.toPx(), 1.15.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.drawCrosshair(center: Offset, color: Color, alpha: Float) {
    val r = 10.dp.toPx(); val gap = 4.dp.toPx(); val s = 1.5.dp.toPx()
    drawCircle(Color.Black.copy(alpha = .2f * alpha), r + 2.dp.toPx(), center)
    drawCircle(color.copy(alpha = .96f * alpha), r, center, style = Stroke(s))
    drawLine(color, Offset(center.x - r - gap, center.y), Offset(center.x - gap, center.y), s, StrokeCap.Round)
    drawLine(color, Offset(center.x + gap, center.y), Offset(center.x + r + gap, center.y), s, StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y - r - gap), Offset(center.x, center.y - gap), s, StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y + gap), Offset(center.x, center.y + r + gap), s, StrokeCap.Round)
}

// ═══════════════════════════════════════════════════════════════
// AXIS_ROTATION — 轴旋转引导 (Pitch/Yaw)
// ═══════════════════════════════════════════════════════════════

private fun DrawScope.drawAxisRotationGuide(
    cue: GuidanceCue, phase: GuidancePhase, color: Color,
    motionPhase: Float, alpha: Float, wrongDirection: Boolean,
) {
    val isPitch = cue == GuidanceCue.PITCH_UP || cue == GuidanceCue.PITCH_DOWN
    val isYaw = cue == GuidanceCue.YAW_LEFT || cue == GuidanceCue.YAW_RIGHT
    val clockwise = cue == GuidanceCue.PITCH_DOWN || cue == GuidanceCue.YAW_RIGHT
    val center = Offset(size.width / 2f, size.height * .44f)
    val axisHalf = 80.dp.toPx()
    drawFadingAxis(center, horizontal = isPitch, half = axisHalf, color = color, guideAlpha = alpha)

    val bounds = when {
        isPitch -> Rect(center.x - 72.dp.toPx(), center.y - 30.dp.toPx(), center.x + 72.dp.toPx(), center.y + 30.dp.toPx())
        isYaw -> Rect(center.x - 34.dp.toPx(), center.y - 72.dp.toPx(), center.x + 34.dp.toPx(), center.y + 72.dp.toPx())
        else -> return
    }
    drawPerspectiveArc(bounds, clockwise, phase, motionPhase, color, alpha, wrongDirection)
}

private fun DrawScope.drawFadingAxis(center: Offset, horizontal: Boolean, half: Float, color: Color, guideAlpha: Float) {
    val segments = 13
    for (i in -segments..segments) {
        if (i == 0) continue
        val norm = i.toFloat() / segments
        val a = fadingAxisAlpha(norm)
        val len = 4.dp.toPx() + 5.dp.toPx() * a
        val gap = half / segments
        val origin = if (horizontal) Offset(center.x + i * gap, center.y) else Offset(center.x, center.y + i * gap)
        val start = if (horizontal) origin - Offset(len / 2f, 0f) else origin - Offset(0f, len / 2f)
        val end = if (horizontal) origin + Offset(len / 2f, 0f) else origin + Offset(0f, len / 2f)
        drawLine(Color.Black.copy(alpha = .2f * a * guideAlpha), start, end, 2.2.dp.toPx(), StrokeCap.Round)
        drawLine(color.copy(alpha = .58f * a * guideAlpha), start, end, .85.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.drawPerspectiveArc(
    bounds: Rect, clockwise: Boolean, phase: GuidancePhase,
    motionPhase: Float, color: Color, alpha: Float, wrongDirection: Boolean,
) {
    val start = if (clockwise) -150f else -30f
    val sweep = if (clockwise) 118f else -118f
    val movingAngle = start + sweep * .8f + 16f * guideMotionScale(phase) * (motionPhase - .5f)

    // 背景弧
    drawArc(Color.Black.copy(alpha = .24f * alpha), start, sweep, false, bounds.topLeft, bounds.size, style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))
    // 远侧淡弧
    drawArc(color.copy(alpha = .22f * alpha), start, sweep * .34f, false, bounds.topLeft, bounds.size, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
    // 近侧清晰弧
    drawArc(color.copy(alpha = .92f * alpha), start + sweep * .34f, sweep * .66f, false, bounds.topLeft, bounds.size, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))

    // 箭头
    val tip = ellipsePoint(bounds.center.x, bounds.center.y, bounds.width / 2f, bounds.height / 2f, movingAngle)
    val tangent = ellipseTangent(bounds.width / 2f, bounds.height / 2f, movingAngle, clockwise)
    drawOpenArrow(tip, tangent, color.copy(alpha = .98f * alpha), 1.75.dp.toPx())

    if (wrongDirection) {
        val revAngle = start + sweep * .2f
        val revTip = ellipsePoint(bounds.center.x, bounds.center.y, bounds.width / 2f, bounds.height / 2f, revAngle)
        val revTangent = ellipseTangent(bounds.width / 2f, bounds.height / 2f, revAngle, !clockwise)
        drawOpenArrow(revTip, revTangent, color.copy(alpha = .34f * alpha), 1.25.dp.toPx())
    }
}

// ═══════════════════════════════════════════════════════════════
// PLANAR_ROTATION — 面内旋转引导 (Roll)
// ═══════════════════════════════════════════════════════════════

private fun DrawScope.drawPlanarRotationGuide(
    cue: GuidanceCue, phase: GuidancePhase, color: Color,
    motionPhase: Float, alpha: Float, wrongDirection: Boolean,
) {
    val clockwise = cue == GuidanceCue.ROLL_CLOCKWISE
    val center = Offset(size.width / 2f, size.height * .44f)

    // 手机外框
    val phoneRect = Rect(center.x - 36.dp.toPx(), center.y - 56.dp.toPx(), center.x + 36.dp.toPx(), center.y + 56.dp.toPx())
    drawRoundRect(Color.Black.copy(alpha = .18f * alpha), phoneRect.topLeft, phoneRect.size, CornerRadius(18.dp.toPx()))
    drawRoundRect(color.copy(alpha = .88f * alpha), phoneRect.topLeft, phoneRect.size, CornerRadius(18.dp.toPx()), style = Stroke(1.2.dp.toPx()))
    drawRoundRect(color.copy(alpha = .18f * alpha), Offset(phoneRect.left + 8.dp.toPx(), phoneRect.top + 10.dp.toPx()),
        Size(phoneRect.width - 16.dp.toPx(), phoneRect.height - 20.dp.toPx()), CornerRadius(13.dp.toPx()), style = Stroke(.95.dp.toPx()))

    // 旋转轨道
    val orbit = Rect(center.x - 76.dp.toPx(), center.y - 76.dp.toPx(), center.x + 76.dp.toPx(), center.y + 76.dp.toPx())
    val start = if (clockwise) -118f else 62f
    val sweep = if (clockwise) 136f else -136f
    val arrowAngle = start + sweep * .84f + 18f * guideMotionScale(phase) * (motionPhase - .5f)

    drawArc(Color.Black.copy(alpha = .2f * alpha), start, sweep, false, orbit.topLeft, orbit.size, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
    drawArc(color.copy(alpha = .88f * alpha), start, sweep, false, orbit.topLeft, orbit.size, style = Stroke(1.55.dp.toPx(), cap = StrokeCap.Round))

    val tip = ellipsePoint(orbit.center.x, orbit.center.y, orbit.width / 2f, orbit.height / 2f, arrowAngle)
    val tangent = ellipseTangent(orbit.width / 2f, orbit.height / 2f, arrowAngle, clockwise)
    drawOpenArrow(tip, tangent, color.copy(alpha = .96f * alpha), 1.8.dp.toPx())

    if (wrongDirection) {
        drawOpenArrow(center + Offset(if (clockwise) -54.dp.toPx() else 54.dp.toPx(), -30.dp.toPx()),
            if (clockwise) Offset(-1f, -0.15f) else Offset(1f, -0.15f), color.copy(alpha = .34f * alpha), 1.25.dp.toPx())
    }
}

// ═══════════════════════════════════════════════════════════════
// 通用绘制工具
// ═══════════════════════════════════════════════════════════════

private fun DrawScope.drawOpenArrow(tip: Offset, direction: Offset, color: Color, stroke: Float) {
    val safe = if (direction.getDistance() > 0.001f) direction / direction.getDistance() else Offset(1f, 0f)
    val len = 9.dp.toPx(); val side = 5.dp.toPx()
    val base = tip - safe * len; val normal = Offset(-safe.y, safe.x)
    drawLine(color, tip, base + normal * side, stroke, StrokeCap.Round)
    drawLine(color, tip, base - normal * side, stroke, StrokeCap.Round)
}

// ═══════════════════════════════════════════════════════════════
// 椭圆几何工具
// ═══════════════════════════════════════════════════════════════

private fun ellipsePoint(cx: Float, cy: Float, rx: Float, ry: Float, angleDeg: Float): Offset {
    val rad = (angleDeg.takeIf(Float::isFinite) ?: 0f) * PI.toFloat() / 180f
    return Offset(cx + cos(rad) * rx.coerceAtLeast(0f), cy + sin(rad) * ry.coerceAtLeast(0f))
}

private fun ellipseTangent(rx: Float, ry: Float, angleDeg: Float, clockwise: Boolean): Offset {
    val rad = (angleDeg.takeIf(Float::isFinite) ?: 0f) * PI.toFloat() / 180f
    val d = if (clockwise) 1f else -1f
    val raw = Offset(-rx.coerceAtLeast(0f) * sin(rad) * d, ry.coerceAtLeast(0f) * cos(rad) * d)
    val len = sqrt(raw.x * raw.x + raw.y * raw.y)
    return if (len > 0.0001f) raw / len else Offset(d, 0f)
}

private fun fadingAxisAlpha(normalizedPosition: Float): Float {
    if (!normalizedPosition.isFinite()) return 0f
    val d = kotlin.math.abs(normalizedPosition).coerceIn(0f, 1f)
    return (1f - d) * (1f - d)
}

// ═══════════════════════════════════════════════════════════════
// 主体轮廓 Canvas
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SubjectOutlineCanvas(
    outline: SubjectOutline, anchors: GuidanceAnchors?, guideColor: Color,
    aligned: Boolean, motionPhase: Float, modifier: Modifier,
) {
    Canvas(modifier) {
        val contours = outline.contours.mapNotNull { c ->
            c.toViewPoints(size.width, size.height)?.let { c to it }
        }
        if (contours.isEmpty()) return@Canvas

        val foreground = if (aligned) GuideAligned else guideColor
        val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), if (aligned) 0f else motionPhase * 14.dp.toPx())
        val underStroke = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash)
        val fgStroke = Stroke(if (aligned) 1.5.dp.toPx() else 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash)

        contours.forEach { (contour, points) ->
            val path = points.toPath(contour.closed)
            drawPath(path, Color.Black.copy(alpha = .34f), style = underStroke)
            drawPath(path, foreground.copy(alpha = if (aligned) .92f else .78f), style = fgStroke)
            if (contour.points.size >= 4) {
                points.filterIndexed { i, _ -> i % maxOf(1, contour.points.size / 5) == 0 }.forEach { pt ->
                    drawCircle(foreground.copy(alpha = if (aligned) .88f else .62f), if (aligned) 2.7.dp.toPx() else 2.2.dp.toPx(), pt)
                }
            }
        }
    }
}

private fun SubjectContour.toViewPoints(w: Float, h: Float): List<Offset>? {
    return points.mapNotNull { p ->
        if (!p.x.isFinite() || !p.y.isFinite()) null
        else Offset(p.x.coerceIn(0f, 1f) * w, p.y.coerceIn(0f, 1f) * h)
    }.takeIf { it.size >= 2 }
}

private fun List<Offset>.toPath(closed: Boolean): Path = Path().apply {
    moveTo(first().x, first().y)
    drop(1).forEach { lineTo(it.x, it.y) }
    if (closed) close()
}

// ═══════════════════════════════════════════════════════════════
// Cue → 视觉家族映射 + 辅助函数
// ═══════════════════════════════════════════════════════════════

internal fun GuidanceCue.visualFamily(): GuideVisualFamily = when (this) {
    GuidanceCue.NONE -> GuideVisualFamily.NONE
    GuidanceCue.MOVE_LEFT, GuidanceCue.MOVE_RIGHT, GuidanceCue.MOVE_UP, GuidanceCue.MOVE_DOWN -> GuideVisualFamily.TRANSLATION
    GuidanceCue.PITCH_UP, GuidanceCue.PITCH_DOWN, GuidanceCue.YAW_LEFT, GuidanceCue.YAW_RIGHT -> GuideVisualFamily.AXIS_ROTATION
    GuidanceCue.ROLL_CLOCKWISE, GuidanceCue.ROLL_COUNTERCLOCKWISE -> GuideVisualFamily.PLANAR_ROTATION
    GuidanceCue.SUBJECT_TARGET, GuidanceCue.SUBJECT_ALIGNED -> GuideVisualFamily.SUBJECT
    GuidanceCue.DEVICE_UPSIDE_DOWN -> GuideVisualFamily.NONE
}

enum class GuideVisualFamily { NONE, TRANSLATION, AXIS_ROTATION, PLANAR_ROTATION, SUBJECT }

internal fun guideMotionScale(phase: GuidancePhase): Float = when (phase) {
    GuidancePhase.NEAR -> 0.45f
    GuidancePhase.ALIGNED, GuidancePhase.NEUTRAL -> 0f
    else -> 1f
}

// ═══════════════════════════════════════════════════════════════
// 达标绿闪反馈
// ═══════════════════════════════════════════════════════════════

/**
 * 构图达标瞬间的全屏绿边闪烁。
 *
 * 触发时机：GuidancePhase 跃迁进入 ALIGNED（连续 3 帧稳定达标），
 * 与震动反馈同步。动画：绿色边框从透明 → 0.55 → 0，约 550ms。
 * 只闪一次，不循环（持续达标状态由 drawAlignedMark 和绿色文案表达）。
 */
@Composable
fun AlignmentFlashOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.snapTo(0f)
        alpha.animateTo(0.55f, animationSpec = tween(160, easing = LinearEasing))
        alpha.animateTo(0f, animationSpec = tween(390, easing = LinearEasing))
        onFinished()
    }

    if (alpha.value > 0.001f) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2f
            drawRoundRect(
                color = GuideAligned.copy(alpha = alpha.value),
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(14.dp.toPx()),
                style = Stroke(stroke)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 提示文案
// ═══════════════════════════════════════════════════════════════

@Composable
fun GuidanceMessage(state: GuidanceState, isDemo: Boolean = false, stepHint: String? = null) {
    val text = when {
        state.phase == GuidancePhase.WRONG_DIRECTION -> "方向相反，稍微回一点"
        state.cue == GuidanceCue.SUBJECT_TARGET -> "将人物移入轮廓"
        state.cue == GuidanceCue.SUBJECT_ALIGNED -> "保持，人物构图已对齐"
        state.phase == GuidancePhase.ALIGNED -> "保持，构图已对齐"
        state.cue == GuidanceCue.MOVE_LEFT -> "← 向左移动"
        state.cue == GuidanceCue.MOVE_RIGHT -> "→ 向右移动"
        state.cue == GuidanceCue.MOVE_UP -> "↑ 手机稍向上"
        state.cue == GuidanceCue.MOVE_DOWN -> "↓ 手机稍向下"
        state.cue == GuidanceCue.PITCH_UP -> "轻轻上仰"
        state.cue == GuidanceCue.PITCH_DOWN -> "轻轻下俯"
        state.cue == GuidanceCue.YAW_LEFT -> "向左转动手机"
        state.cue == GuidanceCue.YAW_RIGHT -> "向右转动手机"
        state.cue == GuidanceCue.ROLL_CLOCKWISE -> "顺时针旋转"
        state.cue == GuidanceCue.ROLL_COUNTERCLOCKWISE -> "逆时针旋转"
        // ★ 分步引导：当前步骤无具体指令时显示步骤提示（如"端平手机"）
        !stepHint.isNullOrBlank() -> stepHint
        state.cue == GuidanceCue.NONE && state.phase == GuidancePhase.NEUTRAL -> "寻找拍摄主体..."
        else -> "调整构图"
    }
    val demoLabel = if (isDemo) " [Demo]" else ""
    Box(
        modifier = Modifier
            .background(Color.Black.copy(alpha = .31f), RoundedCornerShape(17.dp))
            .padding(horizontal = 15.dp, vertical = 9.dp),
    ) {
        Text(
            text + demoLabel, color = when (state.phase) {
                GuidancePhase.ALIGNED -> GuideAligned
                GuidancePhase.WRONG_DIRECTION -> GuideWarning
                else -> Color.White
            }, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
}
