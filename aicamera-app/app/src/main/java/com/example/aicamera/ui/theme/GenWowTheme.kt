package com.example.aicamera.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════════
// GenWow 设计系统 — 移植自队友 frontend/ui/theme/ (2026-07-28 定稿)
// 全 App 唯一主题入口。原 AiCameraTheme.kt / LoginTheme 已移除。
// ═══════════════════════════════════════════════════════════════

// ── 品牌色（Figma 实测） ──

/** 深紫色 — 按钮、强调文字 (Figma #4B3A62) */
val BrandPurple = Color(0xFF4B3A62)
val BrandPink = Color(0xFFF4B6C2)
val BrandLavender = Color(0xFFD8CBFF)
val BrandBlueTint = Color(0xFFDCE6FF)
val BrandSurface = Color(0xFFFFF9FC)
val BrandOnSurface = Color(0xFF4B3A62)
val BrandOnSurfaceMuted = Color(0xFF8A8093)

/** Logo 渐变起止色 (Figma Node 1:28) */
val LogoGradientStart = Color(0xFFFFCCF2)
val LogoGradientEnd = Color(0xFF977DFF)

// ── 兼容别名（旧代码可能引用） ──
val DeepPurple = BrandPurple
val DeepPurpleDark = Color(0xFF3A2B4E)
val DeepPurpleLight = Color(0xFF8B5FBF)
val GradientPink = LogoGradientStart
val GradientPurple = Color(0xFFE8D5F5)
val TextDark = Color(0xFF2D1048)
val TextGray = Color(0xFF999999)
val TextLightGray = Color(0xFFBBBBBB)

// ── 相机 chrome（队友规范 CameraChrome #050505） ──
val CameraChrome = Color(0xFF050505)
val CameraOnChrome = Color(0xFFF8F7FA)
val CameraControlMuted = Color(0xFF9A969F)

// ── 引导色（队友设计规范） ──
val GuidePrimary = Color(0xFF35C6D4)
val GuideAccent = GuidePrimary
val GuideAligned = Color(0xFF8CE0C3)
val GuideWarning = Color(0xFFFF9D55)
val GuideNeutral = Color(0xB3F8F7FA)

// ── 品牌背景渐变 ──
val BrandBackgroundGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFFFFF9FC),
        Color(0xFFF8E1F0),
        Color(0xFFDCCEFF),
        Color(0xFFDCE7FF),
    ),
)

/** Logo 描边渐变 (Figma Node 1:28) */
val LogoStrokeGradient = Brush.linearGradient(
    colors = listOf(LogoGradientStart, LogoGradientEnd),
)

// ── 字体 — 队友 Figma 指定 Noto Sans CJK SC，未随素材交付，用系统 SansSerif ──
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
        fontFamily = GenWowSans, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = GenWowSans, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GenWowSans, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GenWowSans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = GenWowSans, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = GenWowSans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
)

val GenWowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// ── 动效时长（design-audit §7 动效规范） ──
object GenWowMotion {
    const val PressMillis = 90
    const val PressReleaseMillis = 150
    const val FocusMillis = 180
    const val ModeChangeMillis = 210
    const val PageEnterMillis = 240
    const val WrongDirectionMillis = 120
    const val WrongDirectionRecoveryMillis = 180
    const val GuidanceLoopMillis = 1_400
    const val NearGuidanceLoopMillis = 1_900
    const val AlignmentSettleMillis = 300
    const val CaptureFlashInMillis = 70
    const val CaptureFlashOutMillis = 190

    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

// ── 主题入口（队友 GenWowTheme: darkColorScheme + BrandPink 主色） ──

private val GenWowColorScheme = darkColorScheme(
    primary = BrandPink,
    onPrimary = CameraChrome,
    secondary = BrandPurple,
    onSecondary = Color.White,
    background = CameraChrome,
    onBackground = CameraOnChrome,
    surface = CameraChrome,
    onSurface = CameraOnChrome,
    error = GuideWarning,
    onError = Color.Black,
)

/**
 * GenWow 全局主题 — 全 App 唯一入口。
 * 原 [AiCameraTheme] / [LoginTheme] 已废弃删除，统一使用此主题。
 */
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
