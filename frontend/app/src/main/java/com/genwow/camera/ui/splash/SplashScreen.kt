package com.genwow.camera.ui.splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.genwow.camera.R
import com.genwow.camera.ui.auth.LightSystemBars
import com.genwow.camera.ui.theme.BrandBackgroundGradient
import com.genwow.camera.ui.theme.BrandChineseTextStyle
import com.genwow.camera.ui.theme.BrandEnglishTextStyle
import com.genwow.camera.ui.theme.GenWowMotion
import com.genwow.camera.ui.theme.GenWowTheme

@Composable
fun SplashScreen(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    LightSystemBars()
    var entered by remember { mutableStateOf(!animate) }
    LaunchedEffect(animate) { entered = true }
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
        tween(220, delayMillis = if (animate) 120 else 0),
        label = "splash chinese alpha",
    )
    val englishAlpha by animateFloatAsState(
        if (entered) 1f else 0f,
        tween(240, delayMillis = if (animate) 220 else 0),
        label = "splash english alpha",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BrandBackgroundGradient)
            .offset(y = (-12).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
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

@Preview(widthDp = 412, heightDp = 917, showBackground = true)
@Composable
private fun SplashPreview() = GenWowTheme { SplashScreen(animate = false) }

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun SplashSmallPreview() = GenWowTheme { SplashScreen(animate = false) }
