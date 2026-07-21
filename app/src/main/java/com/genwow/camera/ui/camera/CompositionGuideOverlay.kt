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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genwow.camera.ui.theme.GenWowMotion
import com.genwow.camera.ui.theme.GuideAccent
import com.genwow.camera.ui.theme.GuideAligned
import com.genwow.camera.ui.theme.GuideWarning

@Composable
fun CompositionGuideOverlay(state: CameraUiState, modifier: Modifier = Modifier) {
    val guidance = state.guidance
    val visualFamily = guidance.cue.visualFamily()
    val subjectMode = visualFamily == GuideVisualFamily.SUBJECT
    var lockedSubjectOutline by remember { mutableStateOf<SubjectOutline?>(null) }
    var lockedSubjectAnchors by remember { mutableStateOf<GuidanceAnchors?>(null) }
    val looping = guidance.phase == GuidancePhase.ADJUST || guidance.phase == GuidancePhase.NEAR
    val motion = remember { Animatable(0f) }
    val guideAlpha by animateFloatAsState(
        targetValue = when {
            state.analysisStatus == AnalysisStatus.FAILED -> .28f
            guidance.phase == GuidancePhase.NEUTRAL -> .32f
            guidance.phase == GuidancePhase.ALIGNED -> .94f
            else -> 1f
        },
        animationSpec = tween(GenWowMotion.ModeChangeMillis),
        label = "guide alpha",
    )
    val guideColor by animateColorAsState(
        targetValue = when {
            state.analysisStatus == AnalysisStatus.FAILED -> GuideWarning
            guidance.phase == GuidancePhase.WRONG_DIRECTION -> GuideWarning
            guidance.phase == GuidancePhase.ALIGNED -> GuideAligned
            else -> GuideAccent
        },
        animationSpec = tween(GenWowMotion.ModeChangeMillis),
        label = "guide color",
    )

    if (!subjectMode || state.analysisStatus != AnalysisStatus.TRACKING) {
        lockedSubjectOutline = null
        lockedSubjectAnchors = null
    } else if (lockedSubjectOutline == null && guidance.subjectOutline != null) {
        lockedSubjectOutline = guidance.subjectOutline
        lockedSubjectAnchors = guidance.anchors
    }

    val portraitTranslationGuidance = if (
        subjectMode &&
        lockedSubjectOutline == null &&
        guidance.anchors?.current != null &&
        guidance.anchors?.target != null
    ) {
        guidance.toPortraitAlignmentCue()
    } else {
        null
    }

    LaunchedEffect(looping, guidance.cue, guidance.phase) {
        when {
            guidance.phase == GuidancePhase.WRONG_DIRECTION -> {
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
            drawThirdsGrid(alpha = if (subjectMode) .05f * guideAlpha else .12f * guideAlpha)
            if (!subjectMode) {
                drawTargetFrame(
                    color = guideColor,
                    alpha = if (guidance.phase == GuidancePhase.ALIGNED) .88f else .24f * guideAlpha,
                    aligned = guidance.phase == GuidancePhase.ALIGNED,
                )
            }

            val wrongDirection = guidance.phase == GuidancePhase.WRONG_DIRECTION
            val displayMotion = if (wrongDirection) .5f + motion.value * .18f else motion.value
            when (visualFamily) {
                GuideVisualFamily.TRANSLATION -> drawTranslationGuide(
                    guidance = guidance,
                    color = guideColor,
                    motionPhase = displayMotion,
                    alpha = guideAlpha,
                    wrongDirection = wrongDirection,
                )

                GuideVisualFamily.AXIS_ROTATION -> drawAxisRotationGuide(
                    cue = guidance.cue,
                    phase = guidance.phase,
                    color = guideColor,
                    motionPhase = displayMotion,
                    alpha = guideAlpha,
                    wrongDirection = wrongDirection,
                )

                GuideVisualFamily.PLANAR_ROTATION -> drawPlanarRotationGuide(
                    cue = guidance.cue,
                    phase = guidance.phase,
                    color = guideColor,
                    motionPhase = displayMotion,
                    alpha = guideAlpha,
                    wrongDirection = wrongDirection,
                )

                GuideVisualFamily.NONE -> if (guidance.phase == GuidancePhase.ALIGNED) {
                    drawAlignedMark(guideColor)
                }

                GuideVisualFamily.SUBJECT -> {
                    portraitTranslationGuidance?.let { translationGuidance ->
                        drawTranslationGuide(
                            guidance = translationGuidance,
                            color = guideColor,
                            motionPhase = displayMotion,
                            alpha = guideAlpha,
                            wrongDirection = wrongDirection,
                        )
                    }
                }
            }
        }

        lockedSubjectOutline?.takeIf { subjectMode }?.let { outline ->
            SubjectOutlineOverlay(
                outline = outline,
                anchors = lockedSubjectAnchors,
                guideColor = guideColor,
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
        drawLine(
            Color.White.copy(alpha = alpha),
            Offset(x, size.height * .13f),
            Offset(x, size.height * .74f),
            stroke,
        )
    }
    listOf(size.height * .34f, size.height * .58f).forEach { y ->
        drawLine(
            Color.White.copy(alpha = alpha),
            Offset(size.width * .08f, y),
            Offset(size.width * .92f, y),
            stroke,
        )
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

private fun DrawScope.drawCorner(
    origin: Offset,
    horizontal: Offset,
    vertical: Offset,
    color: Color,
    stroke: Float,
) {
    drawLine(color, origin, origin + horizontal, stroke, StrokeCap.Round)
    drawLine(color, origin, origin + vertical, stroke, StrokeCap.Round)
}

private fun DrawScope.drawTranslationGuide(
    guidance: GuidanceState,
    color: Color,
    motionPhase: Float,
    alpha: Float,
    wrongDirection: Boolean,
) {
    val derivedAnchors = deriveMoveAnchors(guidance.cue, guidance.progress)
    val targetAnchor = scaledPoint(
        point = guidance.anchors?.target ?: derivedAnchors?.target,
        width = size.width,
        height = size.height,
    ) ?: Offset(size.width / 2f, size.height * .44f)
    val currentAnchor = scaledPoint(
        point = guidance.anchors?.current ?: derivedAnchors?.current,
        width = size.width,
        height = size.height,
    ) ?: targetAnchor
    val isHorizontal = guidance.cue == GuidanceCue.MOVE_LEFT || guidance.cue == GuidanceCue.MOVE_RIGHT
    val sign = when (guidance.cue) {
        GuidanceCue.MOVE_RIGHT, GuidanceCue.MOVE_DOWN -> 1f
        else -> -1f
    }
    val swing = 18.dp.toPx() * guideMotionScale(guidance.phase) * (motionPhase - .5f) * sign
    var animatedCurrent = if (isHorizontal) {
        currentAnchor.copy(x = currentAnchor.x + swing)
    } else {
        currentAnchor.copy(y = currentAnchor.y + swing)
    }
    val minGap = 18.dp.toPx()
    val offset = animatedCurrent - targetAnchor
    val axisGap = if (isHorizontal) kotlin.math.abs(offset.x) else kotlin.math.abs(offset.y)
    if (axisGap < minGap && guidance.phase != GuidancePhase.ALIGNED) {
        animatedCurrent = if (isHorizontal) {
            targetAnchor.copy(x = targetAnchor.x + minGap * sign)
        } else {
            targetAnchor.copy(y = targetAnchor.y + minGap * sign)
        }
    }
    drawTranslationTrack(animatedCurrent, targetAnchor, color, alpha)
    drawTargetSun(center = targetAnchor, color = color, alpha = alpha)
    drawCrosshair(center = animatedCurrent, color = color, alpha = alpha)

    val direction = when (guidance.cue) {
        GuidanceCue.MOVE_LEFT -> Offset(-1f, 0f)
        GuidanceCue.MOVE_RIGHT -> Offset(1f, 0f)
        GuidanceCue.MOVE_UP -> Offset(0f, -1f)
        GuidanceCue.MOVE_DOWN -> Offset(0f, 1f)
        else -> Offset.Zero
    }
    if (direction != Offset.Zero) {
        val tip = animatedCurrent + direction * 20.dp.toPx()
        drawOpenArrow(
            tip = tip,
            direction = direction,
            color = color.copy(alpha = .94f * alpha),
            stroke = 1.65.dp.toPx(),
        )
    }

    if (wrongDirection) {
        val reverseTip = animatedCurrent - direction * 16.dp.toPx()
        drawOpenArrow(
            tip = reverseTip,
            direction = -direction,
            color = color.copy(alpha = .36f * alpha),
            stroke = 1.35.dp.toPx(),
        )
    }
}

private fun DrawScope.drawTranslationTrack(
    current: Offset,
    target: Offset,
    color: Color,
    alpha: Float,
) {
    val direction = target - current
    val distance = direction.getDistance()
    val unit = if (distance > 0.001f) direction / distance else Offset.Zero
    val accentStart = current + unit * (distance * 0.38f)
    val accentEnd = current + unit * (distance * 0.76f)
    drawLine(
        Color.Black.copy(alpha = .18f * alpha),
        current,
        target,
        3.dp.toPx(),
        StrokeCap.Round,
    )
    drawLine(
        color.copy(alpha = .38f * alpha),
        current,
        target,
        1.1.dp.toPx(),
        StrokeCap.Round,
    )
    if (distance > 0.001f) {
        drawLine(
            color.copy(alpha = .82f * alpha),
            accentStart,
            accentEnd,
            1.55.dp.toPx(),
            StrokeCap.Round,
        )
    }
    val mid = Offset((current.x + target.x) / 2f, (current.y + target.y) / 2f)
    drawCircle(
        color = color.copy(alpha = .2f * alpha),
        radius = 2.6.dp.toPx(),
        center = mid,
    )
}

private fun DrawScope.drawTargetSun(center: Offset, color: Color, alpha: Float) {
    val radius = 15.dp.toPx()
    val coreRadius = 4.2.dp.toPx()
    drawCircle(Color.Black.copy(alpha = .18f * alpha), radius = radius + 4.dp.toPx(), center = center)
    drawCircle(color.copy(alpha = .08f * alpha), radius = radius + 2.dp.toPx(), center = center)
    drawCircle(color.copy(alpha = .48f * alpha), radius = radius - 4.2.dp.toPx(), center = center, style = Stroke(.95.dp.toPx()))
    drawCircle(color.copy(alpha = .94f * alpha), radius = radius, center = center, style = Stroke(1.25.dp.toPx()))
    drawCircle(color.copy(alpha = .96f * alpha), radius = coreRadius, center = center)
    repeat(8) { index ->
        val angle = Math.toRadians((index * 45).toDouble()).toFloat()
        val unit = Offset(kotlin.math.cos(angle), kotlin.math.sin(angle))
        drawLine(
            color.copy(alpha = .84f * alpha),
            center + unit * 8.2.dp.toPx(),
            center + unit * 12.2.dp.toPx(),
            1.05.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawCrosshair(center: Offset, color: Color, alpha: Float) {
    val radius = 9.5.dp.toPx()
    val gap = 3.2.dp.toPx()
    val arm = 8.4.dp.toPx()
    val stroke = 1.42.dp.toPx()
    drawCircle(Color.Black.copy(alpha = .2f * alpha), radius = radius + 2.dp.toPx(), center = center)
    drawCircle(color = color.copy(alpha = .92f * alpha), radius = radius, center = center, style = Stroke(1.18.dp.toPx()))
    drawCircle(color = color.copy(alpha = .52f * alpha), radius = 2.1.dp.toPx(), center = center)
    drawLine(color, Offset(center.x - radius - gap, center.y), Offset(center.x - gap - arm * .28f, center.y), stroke, StrokeCap.Round)
    drawLine(color, Offset(center.x + gap + arm * .28f, center.y), Offset(center.x + radius + gap, center.y), stroke, StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y - radius - gap), Offset(center.x, center.y - gap - arm * .28f), stroke, StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y + gap + arm * .28f), Offset(center.x, center.y + radius + gap), stroke, StrokeCap.Round)
}

private fun DrawScope.drawAxisRotationGuide(
    cue: GuidanceCue,
    phase: GuidancePhase,
    color: Color,
    motionPhase: Float,
    alpha: Float,
    wrongDirection: Boolean,
) {
    val isPitch = cue == GuidanceCue.PITCH_UP || cue == GuidanceCue.PITCH_DOWN
    val isYaw = cue == GuidanceCue.YAW_LEFT || cue == GuidanceCue.YAW_RIGHT
    val clockwise = cue == GuidanceCue.PITCH_DOWN || cue == GuidanceCue.YAW_RIGHT
    val center = Offset(size.width / 2f, size.height * .44f)
    val axisHalf = 80.dp.toPx()
    val axisHorizontal = isPitch
    drawFadingAxis(
        center = center,
        horizontal = axisHorizontal,
        half = axisHalf,
        color = color,
        guideAlpha = alpha,
    )

    val bounds = when {
        isPitch -> Rect(
            left = center.x - 72.dp.toPx(),
            top = center.y - 30.dp.toPx(),
            right = center.x + 72.dp.toPx(),
            bottom = center.y + 30.dp.toPx(),
        )
        isYaw -> Rect(
            left = center.x - 34.dp.toPx(),
            top = center.y - 72.dp.toPx(),
            right = center.x + 34.dp.toPx(),
            bottom = center.y + 72.dp.toPx(),
        )
        else -> return
    }
    drawPerspectiveArc(
        bounds = bounds,
        clockwise = clockwise,
        phase = phase,
        motionPhase = motionPhase,
        color = color,
        alpha = alpha,
        wrongDirection = wrongDirection,
    )
}

private fun DrawScope.drawPerspectiveArc(
    bounds: Rect,
    clockwise: Boolean,
    phase: GuidancePhase,
    motionPhase: Float,
    color: Color,
    alpha: Float,
    wrongDirection: Boolean,
) {
    val start = if (clockwise) -150f else -30f
    val sweep = if (clockwise) 118f else -118f
    val movingAngle = start + sweep * .8f + 16f * guideMotionScale(phase) * (motionPhase - .5f)

    drawArc(
        color = Color.Black.copy(alpha = .24f * alpha),
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round),
    )
    drawArc(
        color = color.copy(alpha = .22f * alpha),
        startAngle = start,
        sweepAngle = sweep * .34f,
        useCenter = false,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(.9.dp.toPx(), cap = StrokeCap.Round),
    )
    drawArc(
        color = color.copy(alpha = .92f * alpha),
        startAngle = start + sweep * .34f,
        sweepAngle = sweep * .66f,
        useCenter = false,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(1.35.dp.toPx(), cap = StrokeCap.Round),
    )
    val shimmerStart = start + sweep * (.18f + .34f * motionPhase.coerceIn(0f, 1f))
    drawArc(
        color = color.copy(alpha = .98f * alpha),
        startAngle = shimmerStart,
        sweepAngle = sweep * .14f,
        useCenter = false,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round),
    )

    val tip = ellipsePoint(
        centerX = bounds.center.x,
        centerY = bounds.center.y,
        radiusX = bounds.width / 2f,
        radiusY = bounds.height / 2f,
        angleDegrees = movingAngle,
    )
    val tangent = ellipseTangent(
        radiusX = bounds.width / 2f,
        radiusY = bounds.height / 2f,
        angleDegrees = movingAngle,
        clockwise = clockwise,
    )
    drawOpenArrow(
        tip = tip,
        direction = tangent,
        color = color.copy(alpha = .98f * alpha),
        stroke = 1.75.dp.toPx(),
    )

    if (wrongDirection) {
        val reverseAngle = start + sweep * .2f
        val reverseTip = ellipsePoint(
            centerX = bounds.center.x,
            centerY = bounds.center.y,
            radiusX = bounds.width / 2f,
            radiusY = bounds.height / 2f,
            angleDegrees = reverseAngle,
        )
        val reverseTangent = ellipseTangent(
            radiusX = bounds.width / 2f,
            radiusY = bounds.height / 2f,
            angleDegrees = reverseAngle,
            clockwise = !clockwise,
        )
        drawOpenArrow(
            tip = reverseTip,
            direction = reverseTangent,
            color = color.copy(alpha = .34f * alpha),
            stroke = 1.25.dp.toPx(),
        )
    }
}

private fun DrawScope.drawPlanarRotationGuide(
    cue: GuidanceCue,
    phase: GuidancePhase,
    color: Color,
    motionPhase: Float,
    alpha: Float,
    wrongDirection: Boolean,
) {
    val clockwise = cue == GuidanceCue.ROLL_CLOCKWISE
    val center = Offset(size.width / 2f, size.height * .44f)
    val phoneRect = Rect(
        left = center.x - 36.dp.toPx(),
        top = center.y - 56.dp.toPx(),
        right = center.x + 36.dp.toPx(),
        bottom = center.y + 56.dp.toPx(),
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = .18f * alpha),
        topLeft = phoneRect.topLeft,
        size = phoneRect.size,
        cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
    )
    drawRoundRect(
        color = color.copy(alpha = .88f * alpha),
        topLeft = phoneRect.topLeft,
        size = phoneRect.size,
        cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
        style = Stroke(1.2.dp.toPx()),
    )
    drawRoundRect(
        color = color.copy(alpha = .18f * alpha),
        topLeft = Offset(phoneRect.left + 8.dp.toPx(), phoneRect.top + 10.dp.toPx()),
        size = Size(phoneRect.width - 16.dp.toPx(), phoneRect.height - 20.dp.toPx()),
        cornerRadius = CornerRadius(13.dp.toPx(), 13.dp.toPx()),
        style = Stroke(.95.dp.toPx()),
    )

    val orbit = Rect(
        left = center.x - 76.dp.toPx(),
        top = center.y - 76.dp.toPx(),
        right = center.x + 76.dp.toPx(),
        bottom = center.y + 76.dp.toPx(),
    )
    val start = if (clockwise) -118f else 62f
    val sweep = if (clockwise) 136f else -136f
    val arrowAngle = start + sweep * .84f + 18f * guideMotionScale(phase) * (motionPhase - .5f)

    drawArc(
        color = Color.Black.copy(alpha = .2f * alpha),
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = orbit.topLeft,
        size = orbit.size,
        style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round),
    )
    drawArc(
        color = color.copy(alpha = .88f * alpha),
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = orbit.topLeft,
        size = orbit.size,
        style = Stroke(1.55.dp.toPx(), cap = StrokeCap.Round),
    )
    val orbitShimmer = start + sweep * (.2f + .36f * motionPhase.coerceIn(0f, 1f))
    drawArc(
        color = color.copy(alpha = .98f * alpha),
        startAngle = orbitShimmer,
        sweepAngle = sweep * .12f,
        useCenter = false,
        topLeft = orbit.topLeft,
        size = orbit.size,
        style = Stroke(1.85.dp.toPx(), cap = StrokeCap.Round),
    )

    val tip = ellipsePoint(
        centerX = orbit.center.x,
        centerY = orbit.center.y,
        radiusX = orbit.width / 2f,
        radiusY = orbit.height / 2f,
        angleDegrees = arrowAngle,
    )
    val tangent = ellipseTangent(
        radiusX = orbit.width / 2f,
        radiusY = orbit.height / 2f,
        angleDegrees = arrowAngle,
        clockwise = clockwise,
    )
    drawOpenArrow(
        tip = tip,
        direction = tangent,
        color = color.copy(alpha = .96f * alpha),
        stroke = 1.8.dp.toPx(),
    )

    if (wrongDirection) {
        drawOpenArrow(
            tip = center + Offset(if (clockwise) -54.dp.toPx() else 54.dp.toPx(), -30.dp.toPx()),
            direction = if (clockwise) Offset(-1f, -0.15f) else Offset(1f, -0.15f),
            color = color.copy(alpha = .34f * alpha),
            stroke = 1.25.dp.toPx(),
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
        val origin = if (horizontal) {
            Offset(center.x + index * gap, center.y)
        } else {
            Offset(center.x, center.y + index * gap)
        }
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

private fun DrawScope.drawOpenArrow(
    tip: Offset,
    direction: Offset,
    color: Color,
    stroke: Float,
) {
    val safeDirection = if (direction.getDistance() > 0.001f) {
        direction / direction.getDistance()
    } else {
        Offset(1f, 0f)
    }
    val length = 9.dp.toPx()
    val side = 5.dp.toPx()
    val normal = Offset(-safeDirection.y, safeDirection.x)
    val base = tip - safeDirection * length
    drawLine(color, tip, base + normal * side, stroke, StrokeCap.Round)
    drawLine(color, tip, base - normal * side, stroke, StrokeCap.Round)
    drawLine(
        color.copy(alpha = .62f),
        base + normal * side * .24f,
        base - normal * side * .24f,
        stroke * .72f,
        StrokeCap.Round,
    )
}

private fun DrawScope.drawAlignedMark(color: Color) {
    val center = Offset(size.width / 2f, size.height * .43f)
    drawCircle(Color.Black.copy(alpha = .24f), 25.dp.toPx(), center)
    drawCircle(color.copy(alpha = .12f), 24.dp.toPx(), center)
    drawCircle(color.copy(alpha = .36f), 18.dp.toPx(), center)
    drawCircle(color.copy(alpha = .88f), 22.dp.toPx(), center, style = Stroke(1.35.dp.toPx()))
    val path = Path().apply {
        moveTo(center.x - 9.dp.toPx(), center.y)
        lineTo(center.x - 2.dp.toPx(), center.y + 7.dp.toPx())
        lineTo(center.x + 11.dp.toPx(), center.y - 8.dp.toPx())
    }
    drawPath(
        path,
        color,
        style = Stroke(2.1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

@Composable
fun GuidanceMessage(state: CameraUiState) {
    val text = when {
        state.capturePhase == CapturePhase.FAILURE -> state.errorMessage ?: "拍摄失败，请重试"
        state.analysisStatus == AnalysisStatus.FAILED -> state.errorMessage ?: "分析暂时不可用"
        state.analysisStatus == AnalysisStatus.ANALYZING -> "正在分析画面"
        state.guidance.phase == GuidancePhase.WRONG_DIRECTION -> "方向相反，稍微回一点"
        state.guidance.cue == GuidanceCue.SUBJECT_TARGET && state.guidance.subjectOutline == null -> "左右移动画面，让人物进入目标位置"
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
            .background(Color.Black.copy(alpha = .24f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
        )
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

private fun GuidanceState.toPortraitAlignmentCue(): GuidanceState {
    val safeAnchors = anchors ?: return this
    val current = safeAnchors.current ?: return this
    val target = safeAnchors.target ?: return this
    val cue = if (current.x >= target.x) GuidanceCue.MOVE_LEFT else GuidanceCue.MOVE_RIGHT
    return copy(
        cue = cue,
        subjectOutline = null,
    )
}
