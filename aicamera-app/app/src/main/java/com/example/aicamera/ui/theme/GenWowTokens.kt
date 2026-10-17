// AUTO-GENERATED — 由 tools/design-tokens/convert.mjs 从 designs/design-tokens.json 生成。
// 请勿手改此文件；改令牌请改 JSON 后重新生成。
// 生成时间: 2026-10-17T09:48:22.228Z

package com.example.aicamera.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object GenWowTokens {
    object Colors {
        val BrandPurple = Color(0xFF4B3A62)
        val BrandPink = Color(0xFFF4B6C2)
        val BrandPinkLight = Color(0xFFFFCCF2)
        val BrandIconTab = Color(0xFFEFB8C8)
        val BrandLike = Color(0xFFFF5151)
        val BrandLavender = Color(0xFFD8CBFF)
        val BrandBlueTint = Color(0xFFDCE6FF)
        val SurfaceBrand = Color(0xFFFFF9FC)
        val SurfaceSheet = Color(0xFFFFF1F5)
        val SurfaceTabBar = Color(0xFFFDEEF2)
        val SurfaceCamera = Color(0xFF050505)
        val SurfaceCameraBar = Color(0xFF09090B)
        val SurfaceStatusBar = Color(0xFF202124)
        val TextOnBrand = Color(0xFF4B3A62)
        val TextMuted = Color(0xFF828282)
        val TextOnCamera = Color(0xFFF8F7FA)
        val TextOnCameraMuted = Color(0xFF9A969F)
        val TextBody = Color(0xFF3D3D3D)
        val TextOnSurface = Color(0xFF1D1B20)
        val TextGray = Color(0xFF8E8E93)
        val GuidePrimary = Color(0xFF35C6D4)
        val GuideAligned = Color(0xFF8CE0C3)
        val GuideWarning = Color(0xFFFF9D55)
        val GuideNeutral = Color(0xFFF8F7FA).copy(alpha = 0.7f)
        val StateError = Color(0xFFC76464)
        val DividerLine = Color(0xFFE6E6E6)
    }

    /** 渐变停靠色列表；用 Brush.linearGradient(value) 构造画刷。 */
    object Gradients {
        val BrandBackground = listOf(Color(0xFFFFF9FC), Color(0xFFF8E1F0), Color(0xFFDCCEFF), Color(0xFFDCE7FF))
        val LogoStroke = listOf(Color(0xFFFFCCF2), Color(0xFF977DFF))
    }

    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }

    object Radius {
        val xs = 8.dp
        val sm = 12.dp
        val md = 16.dp
        val lg = 28.dp
        val xl = 32.dp
    }

    object Font {
        object Family {
            val sans = "Noto Sans CJK SC"
        }

        object Size {
            val display = 36.sp
            val headline = 26.sp
            val title = 22.sp
            val medium = 16.sp
            val body = 14.sp
            val caption = 12.sp
        }

        object Weight {
            val regular = FontWeight(400)
            val medium = FontWeight(500)
            val semibold = FontWeight(600)
            val bold = FontWeight(700)
        }
    }

    object Typography {
        val Heading = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight(600),
            fontSize = 26.sp,
            lineHeight = 39.sp,
            letterSpacing = (-0.26).sp,
        )
        val Button = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight(400),
            fontSize = 17.sp,
            lineHeight = 23.8.sp,
            letterSpacing = 0.sp,
        )
        val Helper13 = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight(700),
            fontSize = 13.sp,
            lineHeight = 19.5.sp,
            letterSpacing = 0.sp,
        )
        val Body14 = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight(400),
            fontSize = 14.sp,
            lineHeight = 19.6.sp,
            letterSpacing = 0.sp,
        )
        val Helper14 = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight(700),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.sp,
        )
        val LegalRegular = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight(400),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
        )
        val LegalBold = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight(700),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
        )
    }

    object Motion {
        const val Press = 90
        const val PressRelease = 150
        const val Focus = 180
        const val ModeChange = 210
        const val PageEnter = 240
        const val WrongDirection = 120
        const val WrongDirectionRecovery = 180
        const val GuidanceLoop = 1400
        const val NearGuidanceLoop = 1900
        const val AlignmentSettle = 300
        const val CaptureFlashIn = 70
        const val CaptureFlashOut = 190
    }
}
