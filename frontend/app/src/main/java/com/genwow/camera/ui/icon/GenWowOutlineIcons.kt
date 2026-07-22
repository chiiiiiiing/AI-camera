package com.genwow.camera.ui.icon

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WeChatOutlineIcon(
    modifier: Modifier,
    tint: Color,
) {
    Canvas(modifier) {
        val bubbleOne = Path().apply {
            addOval(
                Rect(
                    left = size.width * 0.04f,
                    top = size.height * 0.08f,
                    right = size.width * 0.63f,
                    bottom = size.height * 0.60f,
                ),
            )
            moveTo(size.width * 0.18f, size.height * 0.52f)
            cubicTo(
                size.width * 0.16f,
                size.height * 0.63f,
                size.width * 0.13f,
                size.height * 0.72f,
                size.width * 0.11f,
                size.height * 0.80f,
            )
            cubicTo(
                size.width * 0.18f,
                size.height * 0.73f,
                size.width * 0.27f,
                size.height * 0.66f,
                size.width * 0.33f,
                size.height * 0.61f,
            )
            close()
        }
        val bubbleTwo = Path().apply {
            addOval(
                Rect(
                    left = size.width * 0.36f,
                    top = size.height * 0.25f,
                    right = size.width * 0.94f,
                    bottom = size.height * 0.73f,
                ),
            )
            moveTo(size.width * 0.71f, size.height * 0.65f)
            cubicTo(
                size.width * 0.74f,
                size.height * 0.75f,
                size.width * 0.77f,
                size.height * 0.82f,
                size.width * 0.80f,
                size.height * 0.89f,
            )
            cubicTo(
                size.width * 0.73f,
                size.height * 0.83f,
                size.width * 0.64f,
                size.height * 0.76f,
                size.width * 0.58f,
                size.height * 0.71f,
            )
            close()
        }
        drawPath(bubbleOne, color = tint, style = Fill)
        drawPath(bubbleTwo, color = tint, style = Fill)
        val cutout = Color.White.copy(alpha = 0.95f)
        drawCircle(
            color = cutout,
            radius = size.minDimension * 0.045f,
            center = Offset(size.width * 0.28f, size.height * 0.28f),
        )
        drawCircle(
            color = cutout,
            radius = size.minDimension * 0.045f,
            center = Offset(size.width * 0.47f, size.height * 0.28f),
        )
        drawCircle(
            color = cutout,
            radius = size.minDimension * 0.038f,
            center = Offset(size.width * 0.58f, size.height * 0.45f),
        )
        drawCircle(
            color = cutout,
            radius = size.minDimension * 0.038f,
            center = Offset(size.width * 0.75f, size.height * 0.45f),
        )
    }
}

@Composable
fun QqOutlineIcon(
    modifier: Modifier,
    tint: Color,
) {
    Canvas(modifier) {
        val body = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.06f)
            cubicTo(
                size.width * 0.35f,
                size.height * 0.06f,
                size.width * 0.24f,
                size.height * 0.16f,
                size.width * 0.24f,
                size.height * 0.30f,
            )
            cubicTo(
                size.width * 0.14f,
                size.height * 0.38f,
                size.width * 0.14f,
                size.height * 0.55f,
                size.width * 0.23f,
                size.height * 0.63f,
            )
            cubicTo(
                size.width * 0.25f,
                size.height * 0.73f,
                size.width * 0.33f,
                size.height * 0.81f,
                size.width * 0.43f,
                size.height * 0.84f,
            )
            cubicTo(
                size.width * 0.46f,
                size.height * 0.84f,
                size.width * 0.48f,
                size.height * 0.82f,
                size.width * 0.50f,
                size.height * 0.80f,
            )
            cubicTo(
                size.width * 0.52f,
                size.height * 0.82f,
                size.width * 0.54f,
                size.height * 0.84f,
                size.width * 0.57f,
                size.height * 0.84f,
            )
            cubicTo(
                size.width * 0.67f,
                size.height * 0.81f,
                size.width * 0.75f,
                size.height * 0.73f,
                size.width * 0.77f,
                size.height * 0.68f,
            )
            cubicTo(
                size.width * 0.86f,
                size.height * 0.55f,
                size.width * 0.86f,
                size.height * 0.38f,
                size.width * 0.76f,
                size.height * 0.30f,
            )
            cubicTo(
                size.width * 0.76f,
                size.height * 0.16f,
                size.width * 0.65f,
                size.height * 0.06f,
                size.width * 0.50f,
                size.height * 0.06f,
            )
            close()
        }
        drawPath(body, color = tint, style = Fill)
        val footLeft = Path().apply {
            moveTo(size.width * 0.36f, size.height * 0.84f)
            cubicTo(
                size.width * 0.31f,
                size.height * 0.90f,
                size.width * 0.30f,
                size.height * 0.97f,
                size.width * 0.35f,
                size.height * 0.98f,
            )
            lineTo(size.width * 0.46f, size.height * 0.98f)
            cubicTo(
                size.width * 0.44f,
                size.height * 0.93f,
                size.width * 0.41f,
                size.height * 0.88f,
                size.width * 0.36f,
                size.height * 0.84f,
            )
            close()
        }
        val footRight = Path().apply {
            moveTo(size.width * 0.64f, size.height * 0.84f)
            cubicTo(
                size.width * 0.69f,
                size.height * 0.90f,
                size.width * 0.70f,
                size.height * 0.97f,
                size.width * 0.65f,
                size.height * 0.98f,
            )
            lineTo(size.width * 0.54f, size.height * 0.98f)
            cubicTo(
                size.width * 0.56f,
                size.height * 0.93f,
                size.width * 0.59f,
                size.height * 0.88f,
                size.width * 0.64f,
                size.height * 0.84f,
            )
            close()
        }
        drawPath(footLeft, color = tint, style = Fill)
        drawPath(footRight, color = tint, style = Fill)
    }
}

@Composable
fun HeartOutlineIcon(
    modifier: Modifier,
    tint: Color,
    filled: Boolean,
    fillColor: Color,
) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * .5f, size.height * .86f)
            cubicTo(size.width * .42f, size.height * .76f, size.width * .1f, size.height * .55f, size.width * .1f, size.height * .3f)
            cubicTo(size.width * .1f, size.height * .08f, size.width * .38f, size.height * .02f, size.width * .5f, size.height * .22f)
            cubicTo(size.width * .62f, size.height * .02f, size.width * .9f, size.height * .08f, size.width * .9f, size.height * .3f)
            cubicTo(size.width * .9f, size.height * .55f, size.width * .58f, size.height * .76f, size.width * .5f, size.height * .86f)
            close()
        }
        if (filled) drawPath(path, fillColor)
        drawPath(path, tint, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun CommentOutlineIcon(
    modifier: Modifier,
    tint: Color,
) {
    Canvas(modifier) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            tint,
            topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
            size = Size(16.dp.toPx(), 13.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = stroke,
        )
        val tail = Path().apply {
            moveTo(6.dp.toPx(), 15.dp.toPx())
            lineTo(5.dp.toPx(), 19.dp.toPx())
            lineTo(10.dp.toPx(), 15.dp.toPx())
        }
        drawPath(tail, tint, style = stroke)
    }
}

@Composable
fun SettingsOutlineIcon(
    modifier: Modifier,
    tint: Color,
) {
    Canvas(modifier) {
        val stroke = Stroke(1.55.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(tint, 5.dp.toPx(), center, style = stroke)
        drawCircle(tint, 9.dp.toPx(), center, style = stroke)
        repeat(8) { index ->
            val angle = Math.toRadians(index * 45.0)
            val inner = Offset(
                x = center.x + cos(angle).toFloat() * 8.dp.toPx(),
                y = center.y + sin(angle).toFloat() * 8.dp.toPx(),
            )
            val outer = Offset(
                x = center.x + cos(angle).toFloat() * 11.dp.toPx(),
                y = center.y + sin(angle).toFloat() * 11.dp.toPx(),
            )
            drawLine(tint, inner, outer, stroke.width, StrokeCap.Round)
        }
    }
}
