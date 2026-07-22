package com.genwow.camera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GenWowColorScheme = darkColorScheme(
    primary = BrandPink,
    onPrimary = CameraChrome,
    secondary = BrandPurple,
    onSecondary = Color.White,
    background = CameraChrome,
    onBackground = CameraOnChrome,
    surface = CameraChrome,
    onSurface = CameraOnChrome,
    error = GuideWarningState,
    onError = Color.Black,
)

@Composable
fun GenWowTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GenWowColorScheme,
        typography = GenWowTypography,
        shapes = GenWowShapes,
        content = content,
    )
}
