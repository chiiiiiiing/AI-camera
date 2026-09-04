package com.example.aicamera.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.aicamera.R
import com.example.aicamera.ui.auth.LightSystemBars
import com.example.aicamera.ui.theme.BrandBackgroundGradient
import com.example.aicamera.ui.theme.BrandChineseTextStyle
import com.example.aicamera.ui.theme.BrandEnglishTextStyle
import com.example.aicamera.ui.theme.GenWowMotion
import kotlinx.coroutines.delay

/**
 * 启动欢迎页 — "帧好" + "GenWow"
 *
 * 视觉移植自队友 frontend/ ui/splash/SplashScreen.kt：
 * 品牌渐变背景 + ic_genwow_logo + 品牌文字错峰淡入。
 * 导航契约保持原样：停留 2s 后回调（登录态判断由调用方完成）。
 */
@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    LightSystemBars()

    // ★ 保持原有导航时机：2s 后跳转
    LaunchedEffect(Unit) {
        delay(800L)
        onSplashFinished()
    }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val logoAlpha by animateFloatAsState(
        if (entered) 1f else 0f,
        tween(240),
        label = "splash logo alpha",
    )
    val logoScale by animateFloatAsState(
        if (entered) 1f else 0.94f,
        tween(240, easing = GenWowMotion.StandardEasing),
        label = "splash logo scale",
    )
    val chineseAlpha by animateFloatAsState(
        if (entered) 1f else 0f,
        tween(220, delayMillis = 120),
        label = "splash chinese alpha",
    )
    val englishAlpha by animateFloatAsState(
        if (entered) 1f else 0f,
        tween(240, delayMillis = 220),
        label = "splash english alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandBackgroundGradient)
            .offset(y = (-12).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_genwow_logo),
            contentDescription = null,
            modifier = Modifier
                .size(90.dp)
                .scale(logoScale)
                .alpha(logoAlpha)
                .semantics { contentDescription = "帧好 GenWow 标志" },
        )
        Spacer(Modifier.height(38.dp))
        Text("帧好", style = BrandChineseTextStyle, modifier = Modifier.alpha(chineseAlpha))
        Spacer(Modifier.height(10.dp))
        Text("GenWow", style = BrandEnglishTextStyle, modifier = Modifier.alpha(englishAlpha))
    }
}
