package com.genwow.camera.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Static brushes avoid realtime blur while preserving the Figma pink-purple-blue mood. */
val BrandBackgroundGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFFFFF9FC),
        Color(0xFFF8E1F0),
        Color(0xFFDCCEFF),
        Color(0xFFDCE7FF),
    ),
)

val LogoStrokeGradient = Brush.linearGradient(
    colors = listOf(LogoGradientStart, LogoGradientEnd),
)
