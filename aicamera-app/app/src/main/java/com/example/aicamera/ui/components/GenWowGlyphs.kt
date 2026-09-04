package com.example.aicamera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * GenWow Canvas 线性图标 — 移植自队友 frontend/ ui/icon/GenWowOutlineIcons.kt。
 * （微信/QQ 图标按产品决策不接入，未移植。）
 */

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
fun BookmarkOutlineIcon(
    modifier: Modifier,
    tint: Color,
    filled: Boolean,
    fillColor: Color,
) {
    Canvas(modifier) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(4.dp.toPx(), 3.dp.toPx())
            lineTo(size.width - 4.dp.toPx(), 3.dp.toPx())
            lineTo(size.width - 4.dp.toPx(), size.height - 3.dp.toPx())
            lineTo(size.width * 0.5f, size.height * 0.72f)
            lineTo(4.dp.toPx(), size.height - 3.dp.toPx())
            close()
        }
        if (filled) drawPath(path, fillColor)
        drawPath(path, tint, style = stroke)
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
