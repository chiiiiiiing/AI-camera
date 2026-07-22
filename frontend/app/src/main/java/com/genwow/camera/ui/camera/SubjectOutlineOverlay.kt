package com.genwow.camera.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.genwow.camera.ui.theme.GuideAligned

/**
 * Draws the latest contours supplied by the AI layer.
 *
 * No human silhouette is embedded here: every path is rebuilt from normalized per-frame points.
 * Debug previews may provide generated samples, while production is expected to pass model output.
 */
@Composable
fun SubjectOutlineOverlay(
    outline: SubjectOutline,
    anchors: GuidanceAnchors?,
    guideColor: Color,
    aligned: Boolean,
    motionPhase: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier.semantics {
            contentDescription = if (aligned) "AI人物轮廓：已对齐" else "AI人物轮廓：目标"
        },
    ) {
        val contours = outline.contours.mapNotNull { contour ->
            scaledSubjectContour(contour, size.width, size.height)?.let { contour to it }
        }
        if (contours.isEmpty()) return@Canvas

        val foreground = if (aligned) GuideAligned else guideColor
        val dash = PathEffect.dashPathEffect(
            intervals = floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
            phase = if (aligned) 0f else motionPhase * 14.dp.toPx(),
        )
        val underStroke = Stroke(
            width = 2.8.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = dash,
        )
        val foregroundStroke = Stroke(
            width = if (aligned) 1.5.dp.toPx() else 1.2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = dash,
        )

        contours.forEach { (contour, points) ->
            val path = points.toPath(contour.closed)
            drawPath(path, Color.Black.copy(alpha = .34f), style = underStroke)
            drawPath(
                path,
                foreground.copy(alpha = if (aligned) .92f else .78f),
                style = foregroundStroke,
            )
            if (contour.points.size >= 4) {
                points
                    .filterIndexed { index, _ -> index % maxOf(1, contour.points.size / 5) == 0 }
                    .forEach { point ->
                        drawCircle(
                            color = foreground.copy(alpha = if (aligned) .88f else .62f),
                            radius = if (aligned) 2.7.dp.toPx() else 2.2.dp.toPx(),
                            center = point,
                        )
                    }
            }
        }

        val currentAnchor = scaledPoint(anchors?.current, size.width, size.height)
        val targetAnchor = scaledPoint(anchors?.target, size.width, size.height)
        if (currentAnchor != null && targetAnchor != null) {
            drawCircle(
                color = Color.Black.copy(alpha = .18f),
                radius = 20.dp.toPx(),
                center = targetAnchor,
            )
            drawCircle(
                color = foreground.copy(alpha = if (aligned) .32f else .18f),
                radius = 16.dp.toPx(),
                center = targetAnchor,
                style = Stroke(1.2.dp.toPx()),
            )
            drawCircle(
                color = foreground.copy(alpha = .92f),
                radius = 4.5.dp.toPx(),
                center = targetAnchor,
            )
            if (!aligned) {
                drawLine(
                    color = foreground.copy(alpha = .5f),
                    start = currentAnchor,
                    end = targetAnchor,
                    strokeWidth = 1.1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = foreground.copy(alpha = .9f),
                    radius = 4.dp.toPx(),
                    center = currentAnchor,
                )
            }
        }

        if (aligned) {
            val anchor = contours.flatMap { it.second }.minByOrNull { it.y } ?: return@Canvas
            drawCircle(Color.Black.copy(alpha = .28f), 11.dp.toPx(), anchor)
            drawCircle(GuideAligned.copy(alpha = .95f), 8.dp.toPx(), anchor)
            drawLine(
                Color(0xFF10251F),
                anchor + Offset(-3.dp.toPx(), 0f),
                anchor + Offset(-.5.dp.toPx(), 2.5.dp.toPx()),
                1.5.dp.toPx(),
                StrokeCap.Round,
            )
            drawLine(
                Color(0xFF10251F),
                anchor + Offset(-.5.dp.toPx(), 2.5.dp.toPx()),
                anchor + Offset(4.dp.toPx(), -3.dp.toPx()),
                1.5.dp.toPx(),
                StrokeCap.Round,
            )
        }
    }
}

private fun List<Offset>.toPath(closed: Boolean): Path = Path().apply {
    moveTo(first().x, first().y)
    drop(1).forEach { lineTo(it.x, it.y) }
    if (closed) close()
}
