package com.genwow.camera.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.genwow.camera.ui.theme.BrandOnSurface
import com.genwow.camera.ui.theme.BrandPink
import com.genwow.camera.ui.theme.GenWowMotion

@Composable
fun MainBottomBar(
    currentDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF1F5))
            .navigationBarsPadding()
            .height(62.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppDestination.mainDestinations.forEach { destination ->
            val selected = destination == currentDestination
            val color by animateColorAsState(
                targetValue = if (selected) BrandPink else BrandOnSurface,
                animationSpec = tween(GenWowMotion.ModeChangeMillis),
                label = "main destination color",
            )
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.08f else 1f,
                animationSpec = tween(GenWowMotion.ModeChangeMillis, easing = GenWowMotion.StandardEasing),
                label = "main destination scale",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
                    .semantics { contentDescription = destination.accessibilityDescription() }
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onDestinationSelected(destination) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                MainDestinationGlyph(
                    destination = destination,
                    color = color,
                    modifier = Modifier.size(26.dp).scale(scale),
                )
            }
        }
    }
}

@Composable
private fun MainDestinationGlyph(
    destination: AppDestination,
    color: Color,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val stroke = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round)
        when (destination) {
            AppDestination.Camera -> {
                drawRoundRect(
                    color,
                    Offset(3.dp.toPx(), 7.dp.toPx()),
                    Size(20.dp.toPx(), 15.dp.toPx()),
                    CornerRadius(2.dp.toPx()),
                    style = stroke,
                )
                drawCircle(color, 4.dp.toPx(), Offset(13.dp.toPx(), 14.5.dp.toPx()), style = stroke)
                drawLine(color, Offset(8.dp.toPx(), 7.dp.toPx()), Offset(10.dp.toPx(), 4.dp.toPx()), stroke.width)
                drawLine(color, Offset(10.dp.toPx(), 4.dp.toPx()), Offset(16.dp.toPx(), 4.dp.toPx()), stroke.width)
            }

            AppDestination.Feed -> {
                drawCircle(color, 2.5.dp.toPx(), Offset(5.dp.toPx(), 13.dp.toPx()), style = stroke)
                drawCircle(color, 2.5.dp.toPx(), Offset(20.dp.toPx(), 6.dp.toPx()), style = stroke)
                drawCircle(color, 2.5.dp.toPx(), Offset(20.dp.toPx(), 20.dp.toPx()), style = stroke)
                drawLine(color, Offset(7.2.dp.toPx(), 12.dp.toPx()), Offset(17.5.dp.toPx(), 7.dp.toPx()), stroke.width)
                drawLine(color, Offset(7.2.dp.toPx(), 14.dp.toPx()), Offset(17.5.dp.toPx(), 19.dp.toPx()), stroke.width)
            }

            AppDestination.Profile -> {
                drawCircle(color, 4.dp.toPx(), Offset(13.dp.toPx(), 7.dp.toPx()), style = stroke)
                drawArc(
                    color,
                    195f,
                    150f,
                    false,
                    Offset(4.dp.toPx(), 12.dp.toPx()),
                    Size(18.dp.toPx(), 12.dp.toPx()),
                    style = stroke,
                )
            }

            else -> Unit
        }
    }
}

private fun AppDestination.accessibilityDescription(): String = when (this) {
    AppDestination.Camera -> "打开相机"
    AppDestination.Feed -> "打开推荐"
    AppDestination.Profile -> "打开个人主页"
    else -> label
}
