package com.example.aicamera.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicamera.ui.theme.GradientPink
import com.example.aicamera.ui.theme.GradientPurple
import com.example.aicamera.ui.theme.TextDark
import kotlinx.coroutines.delay

/**
 * 启动欢迎页 — "帧好" + "GenWow"
 *
 * 冷启动加载页，停留 2s 后自动跳转登录页。
 * 视觉：居中白色圆角方形容器内相机图标 + 品牌文字 + 英文名
 */
@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {

    LaunchedEffect(Unit) {
        delay(2000L)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(GradientPurple, GradientPink),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ① 白色圆角方形 + 相机图标
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                // 简约相机线性图标 (用 Compose Canvas 绘制)
                CameraIconSimple(modifier = Modifier.size(44.dp))
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ② 中文品牌名 「帧好」
            Text(
                text = "帧好",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            // ③ 英文品牌名 「GenWow」
            Text(
                text = "GenWow",
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = TextDark.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                letterSpacing = 2.sp
            )
        }
    }
}

/**
 * 简约相机线性图标（纯 Compose Canvas 绘制，无额外资源）
 */
@Composable
private fun CameraIconSimple(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.075f
        val bodyLeft = w * 0.12f
        val bodyTop = h * 0.25f
        val bodyRight = w * 0.88f
        val bodyBottom = h * 0.82f
        val lensRadius = w * 0.28f

        // 相机机身
        drawRoundRect(
            color = Color(0xFF6C2FB0),
            topLeft = androidx.compose.ui.geometry.Offset(bodyLeft, bodyTop),
            size = androidx.compose.ui.geometry.Size(
                bodyRight - bodyLeft,
                bodyBottom - bodyTop
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )

        // 镜头
        drawCircle(
            color = Color(0xFF6C2FB0),
            radius = lensRadius,
            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.52f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )

        // 镜头内圈
        drawCircle(
            color = Color(0xFF6C2FB0),
            radius = lensRadius * 0.45f,
            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.52f)
        )

        // 顶部凸起 (闪光灯区域)
        drawRoundRect(
            color = Color(0xFF6C2FB0),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.1f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.04f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )
    }
}
