package com.genwow.camera.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

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
