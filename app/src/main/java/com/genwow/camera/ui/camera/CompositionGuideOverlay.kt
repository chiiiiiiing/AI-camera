package com.genwow.camera.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.ui.theme.GuideAccent
import com.genwow.camera.ui.theme.GuideAligned
import com.genwow.camera.ui.theme.GuideWarning
import com.genwow.camera.ui.theme.GenWowMotion

@Composable
fun CompositionGuideOverlay(state: CameraUiState, modifier: Modifier = Modifier) {
    val guidance = state.guidance
    val looping = guidance.phase == GuidancePhase.ADJUST || guidance.phase == GuidancePhase.NEAR
    val subjectMode = guidance.cue == GuidanceCue.SUBJECT_TARGET ||
        guidance.cue == GuidanceCue.SUBJECT_ALIGNED
    val motion = remember { Animatable(0f) }
    val guideAlpha by animateFloatAsState(
        targetValue = when {
            state.analysisStatus == AnalysisStatus.FAILED -> .28f
            guidance.phase == GuidancePhase.NEUTRAL -> .32f
            guidance.phase == GuidancePhase.ALIGNED -> .9f
            else -> 1f
        },
        animationSpec = tween(GenWowMotion.ModeChangeMillis),
        label = "guide visibility",
    )
    val guideColor by animateColorAsState(
        targetValue = when {
            state.analysisStatus == AnalysisStatus.FAILED -> GuideWarning
            guidance.phase == GuidancePhase.WRONG_DIRECTION -> GuideWarning
            guidance.phase == GuidancePhase.ALIGNED -> GuideAligned
            guidance.phase == GuidancePhase.NEUTRAL -> Color.White
            else -> GuideAccent
        },
        animationSpec = tween(GenWowMotion.ModeChangeMillis),
        label = "guide state color",
    )

    // One cancellable clock expresses “keep moving”. Changing cue/phase cancels the
    // previous loop immediately, so recomposition cannot accumulate animation jobs.
    LaunchedEffect(looping, guidance.cue, guidance.phase) {
        when {
            guidance.phase == GuidancePhase.WRONG_DIRECTION -> {
                // A single short reverse rebound says “that was the wrong way” without shaking
                // the preview or starting another repeating job.
                motion.snapTo(-1f)
                motion.animateTo(
                    0f,
                    animationSpec = tween(
                        durationMillis = GenWowMotion.WrongDirectionMillis,
                        easing = GenWowMotion.EmphasizedEasing,
                    ),
                )
            }

            looping -> while (true) {
                motion.snapTo(0f)
                motion.animateTo(
                    1f,
                    animationSpec = tween(
                        durationMillis = if (guidance.phase == GuidancePhase.NEAR) {
                            GenWowMotion.NearGuidanceLoopMillis
                        } else {
                            GenWowMotion.GuidanceLoopMillis
                        },
                        easing = LinearEasing,
                    ),
                )
            }

            else -> motion.snapTo(0f)
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawThirdsGrid(alpha = (if (subjectMode) .05f else .14f) * guideAlpha)
            if (!subjectMode) {
                drawTargetFrame(
                    color = guideColor,
                    alpha = if (guidance.phase == GuidancePhase.ALIGNED) .92f else .34f * guideAlpha,
                    aligned = guidance.phase == GuidancePhase.ALIGNED,
                )
            }
            val wrongDirection = guidance.phase == GuidancePhase.WRONG_DIRECTION
            val displayMotion = if (wrongDirection) {
                .5f + motion.value * .18f
            } else {
                motion.value
            }
            when (guidance.cue) {
                GuidanceCue.MOVE_LEFT,
                GuidanceCue.MOVE_RIGHT,
                GuidanceCue.MOVE_UP,
                GuidanceCue.MOVE_DOWN,
                -> drawMoveIndicator(
                    guidance,
                    guideColor,
                    displayMotion,
                    guideAlpha,
                    wrongDirection,
                )

                GuidanceCue.PITCH_UP,
                GuidanceCue.PITCH_DOWN,
                GuidanceCue.YAW_LEFT,
                GuidanceCue.YAW_RIGHT,
                GuidanceCue.ROLL_CLOCKWISE,
                GuidanceCue.ROLL_COUNTERCLOCKWISE,
                -> drawRotationIndicator(
                    guidance,
                    guideColor,
                    displayMotion,
                    guideAlpha,
                    wrongDirection,
                )

                GuidanceCue.NONE -> if (guidance.phase == GuidancePhase.ALIGNED) {
                    drawAlignedMark(guideColor)
                }

                GuidanceCue.SUBJECT_TARGET,
                GuidanceCue.SUBJECT_ALIGNED,
                -> Unit
            }
        }
        guidance.subjectOutline?.takeIf { subjectMode }?.let { outline ->
            SubjectOutlineOverlay(
                outline = outline,
                aligned = guidance.phase == GuidancePhase.ALIGNED ||
                    guidance.cue == GuidanceCue.SUBJECT_ALIGNED,
                motionPhase = motion.value.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

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
    val frame = Rect(
        left = size.width * .18f,
        top = size.height * .24f,
        right = size.width * .82f,
        bottom = size.height * .61f,
    )
    val corner = if (aligned) 26.dp.toPx() else 18.dp.toPx()
    val stroke = if (aligned) 2.dp.toPx() else 1.25.dp.toPx()
    val c = color.copy(alpha = alpha)
    drawCorner(frame.topLeft, Offset(corner, 0f), Offset(0f, corner), c, stroke)
    drawCorner(frame.topRight, Offset(-corner, 0f), Offset(0f, corner), c, stroke)
    drawCorner(frame.bottomLeft, Offset(corner, 0f), Offset(0f, -corner), c, stroke)
    drawCorner(frame.bottomRight, Offset(-corner, 0f), Offset(0f, -corner), c, stroke)
}

private fun DrawScope.drawCorner(origin: Offset, horizontal: Offset, vertical: Offset, color: Color, stroke: Float) {
    drawLine(color, origin, origin + horizontal, stroke, StrokeCap.Round)
    drawLine(color, origin, origin + vertical, stroke, StrokeCap.Round)
}

private fun DrawScope.drawMoveIndicator(
    guidance: GuidanceState,
    color: Color,
    phase: Float,
    alpha: Float,
    wrongDirection: Boolean,
) {
    val horizontal = guidance.cue == GuidanceCue.MOVE_LEFT || guidance.cue == GuidanceCue.MOVE_RIGHT
    val positive = guidance.cue == GuidanceCue.MOVE_RIGHT || guidance.cue == GuidanceCue.MOVE_DOWN
    val amplitude = 16.dp.toPx() * guideMotionScale(guidance.phase)
    val shift = (phase - .5f) * amplitude * if (positive) 1f else -1f
    val center = Offset(size.width / 2f, size.height * .44f) +
        if (horizontal) Offset(shift, 0f) else Offset(0f, shift)
    val halfLength = if (guidance.phase == GuidancePhase.NEAR) 34.dp.toPx() else 48.dp.toPx()
    val start = if (horizontal) Offset(center.x - halfLength, center.y) else Offset(center.x, center.y - halfLength)
    val end = if (horizontal) Offset(center.x + halfLength, center.y) else Offset(center.x, center.y + halfLength)
    drawLine(Color.Black.copy(alpha = .25f * alpha), start, end, 3.4.dp.toPx(), StrokeCap.Round)
    drawLine(color.copy(alpha = .78f * alpha), start, end, 1.7.dp.toPx(), StrokeCap.Round)
    val tip = if (positive) end else start
    val direction = if (horizontal) Offset(if (positive) 1f else -1f, 0f) else Offset(0f, if (positive) 1f else -1f)
    drawOpenArrow(tip, direction, color.copy(alpha = .94f * alpha), 1.8.dp.toPx())
    if (wrongDirection) {
        val reverseTip = if (positive) start else end
        drawOpenArrow(
            reverseTip,
            direction * -1f,
            color.copy(alpha = .34f * alpha),
            1.35.dp.toPx(),
        )
        val normal = Offset(-direction.y, direction.x) * 5.dp.toPx()
        drawLine(
            color.copy(alpha = .88f * alpha),
            reverseTip - normal,
            reverseTip + normal,
            1.7.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawRotationIndicator(
    guidance: GuidanceState,
    color: Color,
    phase: Float,
    alpha: Float,
    wrongDirection: Boolean,
) {
    val cue = guidance.cue
    val isPitch = cue == GuidanceCue.PITCH_UP || cue == GuidanceCue.PITCH_DOWN
    val isYaw = cue == GuidanceCue.YAW_LEFT || cue == GuidanceCue.YAW_RIGHT
    val clockwise = cue == GuidanceCue.ROLL_CLOCKWISE || cue == GuidanceCue.PITCH_DOWN || cue == GuidanceCue.YAW_RIGHT
    val center = Offset(size.width / 2f, size.height * .43f)
    val axisHalf = 82.dp.toPx()

    if (isPitch) drawFadingAxis(center, horizontal = true, axisHalf, color, alpha)
    if (isYaw) drawFadingAxis(center, horizontal = false, axisHalf, color, alpha)

    val arcWidth = when {
        isYaw -> 74.dp.toPx()
        isPitch -> 132.dp.toPx()
        else -> 146.dp.toPx()
    }
    val arcHeight = when {
        isYaw -> 132.dp.toPx()
        isPitch -> 64.dp.toPx()
        else -> 126.dp.toPx()
    }
    val bounds = Rect(
        center.x - arcWidth / 2f,
        center.y - arcHeight / 2f,
        center.x + arcWidth / 2f,
        center.y + arcHeight / 2f,
    )
    val start = if (clockwise) -145f else -35f
    val sweep = if (clockwise) 110f else -110f
    val motionSweep = 18f * guideMotionScale(guidance.phase) * (phase - .5f)
    drawArc(
        Color.Black.copy(alpha = .28f * alpha),
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round),
    )
    drawArc(
        color.copy(alpha = .26f * alpha),
        startAngle = start,
        sweepAngle = sweep * .42f,
        useCenter = false,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(1.35.dp.toPx(), cap = StrokeCap.Round),
    )
    drawArc(
        color.copy(alpha = .86f * alpha),
        startAngle = start + sweep * .42f,
        sweepAngle = sweep * .58f,
        useCenter = false,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
    )

    val arrowAngle = start + sweep * .83f + motionSweep
    val point = ellipsePoint(
        centerX = center.x,
        centerY = center.y,
        radiusX = arcWidth / 2f,
        radiusY = arcHeight / 2f,
        angleDegrees = arrowAngle,
    )
    val tangent = ellipseTangent(
        radiusX = arcWidth / 2f,
        radiusY = arcHeight / 2f,
        angleDegrees = arrowAngle,
        clockwise = clockwise,
    )
    drawOpenArrow(point, tangent, color.copy(alpha = .98f * alpha), 1.8.dp.toPx())
    if (wrongDirection) {
        val reverseAngle = start + sweep * .18f
        val reversePoint = ellipsePoint(
            center.x,
            center.y,
            arcWidth / 2f,
            arcHeight / 2f,
            reverseAngle,
        )
        val reverseTangent = ellipseTangent(
            arcWidth / 2f,
            arcHeight / 2f,
            reverseAngle,
            clockwise = !clockwise,
        )
        drawOpenArrow(
            reversePoint,
            reverseTangent,
            color.copy(alpha = .38f * alpha),
            1.3.dp.toPx(),
        )
        val normal = Offset(-reverseTangent.y, reverseTangent.x) * 5.dp.toPx()
        drawLine(
            color.copy(alpha = .9f * alpha),
            reversePoint - normal,
            reversePoint + normal,
            1.6.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawFadingAxis(
    center: Offset,
    horizontal: Boolean,
    half: Float,
    color: Color,
    guideAlpha: Float,
) {
    val segments = 13
    for (index in -segments..segments) {
        if (index == 0) continue
        val normalizedPosition = index.toFloat() / segments
        val alpha = fadingAxisAlpha(normalizedPosition)
        val length = 4.dp.toPx() + 5.dp.toPx() * alpha
        val gap = half / segments
        val origin = if (horizontal) Offset(center.x + index * gap, center.y) else Offset(center.x, center.y + index * gap)
        val start = if (horizontal) origin - Offset(length / 2f, 0f) else origin - Offset(0f, length / 2f)
        val end = if (horizontal) origin + Offset(length / 2f, 0f) else origin + Offset(0f, length / 2f)
        drawLine(
            Color.Black.copy(alpha = .2f * alpha * guideAlpha),
            start,
            end,
            2.2.dp.toPx(),
            StrokeCap.Round,
        )
        drawLine(
            color.copy(alpha = .58f * alpha * guideAlpha),
            start,
            end,
            .85.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawOpenArrow(tip: Offset, direction: Offset, color: Color, stroke: Float) {
    val length = 9.dp.toPx()
    val side = 5.dp.toPx()
    val normal = Offset(-direction.y, direction.x)
    val base = tip - direction * length
    drawLine(color, tip, base + normal * side, stroke, StrokeCap.Round)
    drawLine(color, tip, base - normal * side, stroke, StrokeCap.Round)
}

private fun DrawScope.drawAlignedMark(color: Color) {
    val center = Offset(size.width / 2f, size.height * .43f)
    drawCircle(Color.Black.copy(alpha = .24f), 25.dp.toPx(), center)
    drawCircle(color.copy(alpha = .18f), 24.dp.toPx(), center)
    drawCircle(color.copy(alpha = .88f), 22.dp.toPx(), center, style = Stroke(1.4.dp.toPx()))
    val path = Path().apply {
        moveTo(center.x - 9.dp.toPx(), center.y)
        lineTo(center.x - 2.dp.toPx(), center.y + 7.dp.toPx())
        lineTo(center.x + 11.dp.toPx(), center.y - 8.dp.toPx())
    }
    drawPath(path, color, style = Stroke(2.1.dp.toPx(), cap = StrokeCap.Round))
}

@Composable
fun GuidanceMessage(state: CameraUiState) {
    val text = when {
        state.capturePhase == CapturePhase.FAILURE -> state.errorMessage ?: "拍摄失败，请重试"
        state.analysisStatus == AnalysisStatus.FAILED -> state.errorMessage ?: "分析暂时不可用"
        state.analysisStatus == AnalysisStatus.ANALYZING -> "正在分析画面"
        state.guidance.phase == GuidancePhase.WRONG_DIRECTION -> "方向相反，稍微回一点"
        state.guidance.cue == GuidanceCue.SUBJECT_TARGET -> "将人物移入轮廓"
        state.guidance.cue == GuidanceCue.SUBJECT_ALIGNED -> "保持，人物构图已对齐"
        state.guidance.phase == GuidancePhase.ALIGNED -> "保持，构图已对齐"
        state.guidance.cue == GuidanceCue.MOVE_LEFT -> "向左移动"
        state.guidance.cue == GuidanceCue.MOVE_RIGHT -> "向右移动"
        state.guidance.cue == GuidanceCue.MOVE_UP -> "手机稍向上"
        state.guidance.cue == GuidanceCue.MOVE_DOWN -> "手机稍向下"
        state.guidance.cue == GuidanceCue.PITCH_UP -> "轻轻上仰"
        state.guidance.cue == GuidanceCue.PITCH_DOWN -> "轻轻下俯"
        state.guidance.cue == GuidanceCue.YAW_LEFT -> "向左转动手机"
        state.guidance.cue == GuidanceCue.YAW_RIGHT -> "向右转动手机"
        state.guidance.cue == GuidanceCue.ROLL_CLOCKWISE -> "顺时针旋转"
        state.guidance.cue == GuidanceCue.ROLL_COUNTERCLOCKWISE -> "逆时针旋转"
        else -> "寻找主体"
    }
    val color = when (state.guidance.phase) {
        GuidancePhase.ALIGNED -> GuideAligned
        GuidancePhase.WRONG_DIRECTION -> GuideWarning
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .background(Color.Black.copy(alpha = .31f), RoundedCornerShape(17.dp))
            .padding(horizontal = 15.dp, vertical = 9.dp),
    ) {
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CaptureFeedbackOverlay(modifier: Modifier = Modifier) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        flash.animateTo(.16f, tween(70, easing = FastOutSlowInEasing))
        flash.animateTo(0f, tween(190, easing = FastOutSlowInEasing))
    }
    Box(modifier.background(Color.White.copy(alpha = flash.value)))
}
