package com.example.aicamera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// 品牌色 — 来自队友 Figma 设计稿 (Node 1:28)
// ═══════════════════════════════════════════════════════════════

/** 深紫色 — 按钮、强调文字 (Figma 实测 #4B3A62) */
val DeepPurple = Color(0xFF4B3A62)
val DeepPurpleDark = Color(0xFF3A2B4E)
val DeepPurpleLight = Color(0xFF8B5FBF)

/** 品牌粉色 — 快门内圆等 (Figma 实测 #F4B6C2) */
val BrandPink = Color(0xFFF4B6C2)
val BrandLavender = Color(0xFFD8CBFF)
val BrandSurface = Color(0xFFFFF9FC)

/** Logo 渐变起止色 (Figma Node 1:28) */
val LogoGradientStart = Color(0xFFFFCCF2)
val LogoGradientEnd = Color(0xFF977DFF)

/** 文字色 */
val TextDark = Color(0xFF2D1048)
val TextGray = Color(0xFF999999)
val TextLightGray = Color(0xFFBBBBBB)

/** 渐变起止色（粉 → 紫，保持向后兼容） */
val GradientPink = LogoGradientStart
val GradientPurple = Color(0xFFE8D5F5)

// ═══════════════════════════════════════════════════════════════
// 引导色 — 来自队友设计规范
// 青色 = 正常调整, 橙色 = 警告/反向, 绿色 = 对齐
// ═══════════════════════════════════════════════════════════════

val GuideAccent = Color(0xFF35C6D4)
val GuideAligned = Color(0xFF8CE0C3)
val GuideWarning = Color(0xFFFF9D55)
val GuideNeutral = Color(0xB3F8F7FA)

/** 登录背景浅色主题 */
private val LoginColorScheme = lightColorScheme(
    primary = DeepPurple,
    secondary = DeepPurpleLight,
    background = Color.White,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextDark,
    onSurface = TextDark,
)

/** 相机页深色主题（保持原有） */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00C8FF),
    secondary = Color(0xFFFF6B35),
    background = Color(0xFF000000),
    surface = Color(0xFF1A1A2E),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

// ── 主题入口 ──

/** 登录/注册/启动页：浅色粉紫主题 */
@Composable
fun LoginTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LoginColorScheme,
        content = content
    )
}

/** 相机/社区/个人页：深色主题（保持原有） */
@Composable
fun AiCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
