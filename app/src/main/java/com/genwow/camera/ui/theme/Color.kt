package com.genwow.camera.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colors derived from the Figma product screens. The camera cyan remains a
// semantic guidance color and is no longer used as the GenWow brand primary.
val BrandPurple = Color(0xFF4B3A62)
val BrandPink = Color(0xFFF4B6C2)
val BrandLavender = Color(0xFFD8CBFF)
val BrandBlueTint = Color(0xFFDCE6FF)
val BrandSurface = Color(0xFFFFF9FC)
val BrandOnSurface = BrandPurple
val BrandOnSurfaceMuted = Color(0xFF8A8093)

// Exact gradient stops from the official logo at Figma node 1:28.
val LogoGradientStart = Color(0xFFFFCCF2)
val LogoGradientEnd = Color(0xFF977DFF)

val CameraChrome = Color(0xFF050505)
val CameraOnChrome = Color(0xFFF8F7FA)
val CameraControlMuted = Color(0xFF9A969F)

val GuidePrimary = Color(0xFF35C6D4)
val GuideAlignedState = Color(0xFF8CE0C3)
val GuideWarningState = Color(0xFFFF9D55)
val GuideNeutral = Color(0xB3F8F7FA)

// Compatibility aliases used by the existing camera MVP. They keep the current
// camera code stable while the page components migrate to semantic tokens.
val GenWowBackground = CameraChrome
val GenWowOnBackground = CameraOnChrome
val GenWowAccent = BrandPink
val GenWowAligned = GuideAlignedState
val GenWowWarning = GuideWarningState

val GuideAccent = GuidePrimary
val GuideAligned = GuideAlignedState
val GuideWarning = GuideWarningState
