package com.genwow.camera.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val GenWowSans = FontFamily.SansSerif

val BrandChineseTextStyle = TextStyle(
    fontFamily = GenWowSans,
    fontWeight = FontWeight.Bold,
    fontSize = 36.sp,
    lineHeight = 44.sp,
    color = BrandOnSurface,
)

val BrandEnglishTextStyle = TextStyle(
    fontFamily = GenWowSans,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 19.sp,
    letterSpacing = 6.sp,
    color = BrandOnSurface,
)

val GenWowTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = GenWowSans,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = GenWowSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GenWowSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GenWowSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = GenWowSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = GenWowSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
