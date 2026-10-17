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

// 所有颜色统一来自 designs/design-tokens.json 生成的门牌文件。
// 旧名字保留为别名，避免大规模重命名；新页面直接用 GenWowTokens.Colors.*。

/** 深紫色 — 按钮、强调文字 (Figma #4B3A62) */
val BrandPurple = GenWowTokens.Colors.BrandPurple
val BrandPink = GenWowTokens.Colors.BrandPink
val BrandPinkLight = GenWowTokens.Colors.BrandPinkLight
val BrandLavender = GenWowTokens.Colors.BrandLavender
val BrandBlueTint = GenWowTokens.Colors.BrandBlueTint
val BrandSurface = GenWowTokens.Colors.SurfaceBrand
val BrandOnSurface = GenWowTokens.Colors.TextOnBrand
val BrandOnSurfaceMuted = GenWowTokens.Colors.TextMuted

/** 表单/错误提示红 — 统一错误色，替代各处硬编码 0xFFC76464 */
val BrandError = GenWowTokens.Colors.StateError

/** 新设计补充色 */
val BrandLike = GenWowTokens.Colors.BrandLike
val BrandIconTab = GenWowTokens.Colors.BrandIconTab
val BrandTabBar = GenWowTokens.Colors.SurfaceTabBar

/** Logo 渐变起止色 (Figma Node 1:28) */
val LogoGradientStart = GenWowTokens.Gradients.LogoStroke[0]
val LogoGradientEnd = GenWowTokens.Gradients.LogoStroke[1]

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
val CameraChrome = GenWowTokens.Colors.SurfaceCamera
val CameraOnChrome = GenWowTokens.Colors.TextOnCamera
val CameraControlMuted = GenWowTokens.Colors.TextOnCameraMuted

// ── 引导色（队友设计规范） ──
val GuidePrimary = GenWowTokens.Colors.GuidePrimary
val GuideAccent = GuidePrimary
val GuideAligned = GenWowTokens.Colors.GuideAligned
val GuideWarning = GenWowTokens.Colors.GuideWarning
val GuideNeutral = GenWowTokens.Colors.GuideNeutral

// ── 品牌背景渐变 ──
val BrandBackgroundGradient = Brush.linearGradient(
    colors = GenWowTokens.Gradients.BrandBackground,
)

/** Logo 描边渐变 (Figma Node 1:28) */
val LogoStrokeGradient = Brush.linearGradient(
    colors = GenWowTokens.Gradients.LogoStroke,
)

// ── 字体 — 队友 Figma 指定 Noto Sans CJK SC，未随素材交付，用系统 SansSerif ──
private val GenWowSans = FontFamily.SansSerif

// Figma 启动页：品牌中文名是粉红渐变，不是深紫
private val SplashWordmarkBrush = Brush.linearGradient(
    listOf(Color(0xFFFF6B8A), Color(0xFFE85D7A)),
)

val BrandChineseTextStyle = TextStyle(
    fontFamily = GenWowSans,
    fontWeight = FontWeight.Bold,
    fontSize = 36.sp,
    lineHeight = 52.13.sp,
    letterSpacing = 4.sp,
    brush = SplashWordmarkBrush,
)

val BrandEnglishTextStyle = TextStyle(
    fontFamily = GenWowSans,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 18.75.sp,
    letterSpacing = 6.sp,
    color = BrandLike,
)

val GenWowTypography = Typography(
    headlineLarge = GenWowTokens.Typography.Heading,
    headlineMedium = TextStyle(
        fontFamily = GenWowSans, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GenWowSans, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodyMedium = GenWowTokens.Typography.Body14,
    bodySmall = TextStyle(
        fontFamily = GenWowSans, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp,
    ),
    labelMedium = GenWowTokens.Typography.Helper13,
)

val GenWowShapes = Shapes(
    extraSmall = RoundedCornerShape(GenWowTokens.Radius.xs),
    small = RoundedCornerShape(GenWowTokens.Radius.sm),
    medium = RoundedCornerShape(GenWowTokens.Radius.md),
    large = RoundedCornerShape(GenWowTokens.Radius.lg),
    extraLarge = RoundedCornerShape(GenWowTokens.Radius.xl),
)

// ── 动效时长（design-audit §7 动效规范） ──
object GenWowMotion {
    const val PressMillis = GenWowTokens.Motion.Press
    const val PressReleaseMillis = GenWowTokens.Motion.PressRelease
    const val FocusMillis = GenWowTokens.Motion.Focus
    const val ModeChangeMillis = GenWowTokens.Motion.ModeChange
    const val PageEnterMillis = GenWowTokens.Motion.PageEnter
    const val WrongDirectionMillis = GenWowTokens.Motion.WrongDirection
    const val WrongDirectionRecoveryMillis = GenWowTokens.Motion.WrongDirectionRecovery
    const val GuidanceLoopMillis = GenWowTokens.Motion.GuidanceLoop
    const val NearGuidanceLoopMillis = GenWowTokens.Motion.NearGuidanceLoop
    const val AlignmentSettleMillis = GenWowTokens.Motion.AlignmentSettle
    const val CaptureFlashInMillis = GenWowTokens.Motion.CaptureFlashIn
    const val CaptureFlashOutMillis = GenWowTokens.Motion.CaptureFlashOut

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
