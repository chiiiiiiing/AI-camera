package com.example.aicamera.camera

import android.util.Size
import androidx.camera.core.ImageCapture

/**
 * 相机模块全局配置常量。
 *
 * 所有可调参数集中在此，方便后续根据设备分级或远程配置动态调整。
 */
object CameraConfig {

    // ── 帧分析（AI 推理用）───────────────────────────────────

    /**
     * ImageAnalysis 目标分辨率。
     * 640×480 是推理精度和性能的平衡点——太高推理慢，太低辅助线不准。
     * V1.1 可根据设备分级动态调整。
     */
    val ANALYSIS_RESOLUTION = Size(640, 480)

    // ── 拍照 ──────────────────────────────────────────────────

    /** 拍照模式：最大化画质 */
    const val CAPTURE_MODE = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY

    /** 闪关灯模式枚举 */
    enum class FlashMode {
        AUTO, ON, OFF, TORCH
    }

    /** 默认闪光灯模式 */
    val DEFAULT_FLASH_MODE = FlashMode.AUTO

    // ── 摄像头面 ──────────────────────────────────────────────

    enum class CameraFace {
        BACK, FRONT
    }

    // ── 变焦 ──────────────────────────────────────────────────

    /** 最大数码变焦倍数（产品上限，硬件上限由 CameraInfo 动态提供） */
    const val MAX_ZOOM_RATIO = 4.0f

    /** 最小变焦倍数 */
    const val MIN_ZOOM_RATIO = 1.0f

    // ── 对焦 ──────────────────────────────────────────────────

    /** 对焦圆圈半径（dp），用于点击对焦动画 */
    const val FOCUS_CIRCLE_RADIUS_DP = 32f

    /** 对焦动画时长（ms） */
    const val FOCUS_ANIM_DURATION_MS = 300L

    // ── 曝光补偿 ──────────────────────────────────────────────

    /** 曝光补偿默认值 */
    const val EXPOSURE_COMPENSATION_DEFAULT = 0

    // ── 权限 ──────────────────────────────────────────────────

    /** 权限请求 requestCode */
    const val REQUEST_CODE_CAMERA_PERMISSION = 1001

    // ── 日志标签 ──────────────────────────────────────────────

    const val TAG = "CameraLink"

    // ── 辅助映射函数 ──────────────────────────────────────────

    /**
     * 将 [FlashMode] 映射为 CameraX [ImageCapture.FlashMode]。
     * TORCH 映射为 FLASH_MODE_OFF（手电筒由 cameraControl.enableTorch 单独控制）。
     */
    fun flashModeToImageCapture(mode: FlashMode): Int = when (mode) {
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.OFF,
        FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF
    }
}
