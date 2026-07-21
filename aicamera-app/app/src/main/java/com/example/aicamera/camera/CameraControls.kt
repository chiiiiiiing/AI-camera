package com.example.aicamera.camera

import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import com.example.aicamera.camera.CameraConfig.CameraFace
import com.example.aicamera.camera.CameraConfig.FlashMode

/**
 * 相机控制：变焦、闪光灯、前后摄像头切换。
 *
 * 不负责对焦（见 [CameraFocus]）、不负责拍照（见 [CameraCapture]）。
 *
 * 注意：手势监听（双指缩放/点击对焦）会互相覆盖 setOnTouchListener，
 * 后续建议统一为 CameraGestureDispatcher 合并处理。
 */
class CameraControls(
    private val onCameraFaceChanged: ((CameraFace) -> Unit)? = null,
    private val onZoomChanged: ((zoomRatio: Float) -> Unit)? = null
) {
    /** 当前闪光灯模式 */
    var currentFlashMode: FlashMode = CameraConfig.DEFAULT_FLASH_MODE
        private set

    /** 当前变焦倍数 (1.0–4.0) */
    var currentZoomRatio: Float = CameraConfig.MIN_ZOOM_RATIO
        private set

    // ── 双指变焦 ──────────────────────────────────────────────

    /**
     * 为 View 启用手势缩放。
     *
     * @param touchView 接收缩放手势的 View（通常是 PreviewView）
     * @param camera 当前 Camera，用于执行 setZoomRatio
     */
    fun enablePinchToZoom(touchView: View, camera: Camera) {
        val detector = ScaleGestureDetector(
            touchView.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scaleFactor = detector.scaleFactor
                    val maxHardware = camera.cameraInfo.zoomState.value?.maxZoomRatio
                        ?: CameraConfig.MAX_ZOOM_RATIO
                    val newRatio = (currentZoomRatio * scaleFactor).coerceIn(
                        CameraConfig.MIN_ZOOM_RATIO,
                        minOf(CameraConfig.MAX_ZOOM_RATIO, maxHardware)
                    )

                    if (kotlin.math.abs(newRatio - currentZoomRatio) > 0.01f) {
                        applyZoomRatio(newRatio, camera)
                    }
                    return true
                }
            }
        )

        touchView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    /**
     * 通过滑动条设置变焦。
     */
    fun setZoomRatio(ratio: Float, camera: Camera): Boolean {
        val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio
            ?: CameraConfig.MAX_ZOOM_RATIO
        val clamped = ratio.coerceIn(CameraConfig.MIN_ZOOM_RATIO, maxZoom)

        if (kotlin.math.abs(clamped - currentZoomRatio) < 0.005f) return true

        return applyZoomRatio(clamped, camera)
    }

    /**
     * 应用变焦倍数。
     * CameraX setZoomRatio 在调用线程同步返回；本地状态乐观更新。
     */
    private fun applyZoomRatio(clamped: Float, camera: Camera): Boolean {
        return try {
            camera.cameraControl.setZoomRatio(clamped)
            currentZoomRatio = clamped
            onZoomChanged?.invoke(clamped)
            logDebug("变焦已设置: ${"%.1f".format(clamped)}x")
            true
        } catch (e: Exception) {
            logError("设置变焦失败", e)
            false
        }
    }

    /** 重置变焦为 1.0x */
    fun resetZoom(camera: Camera): Boolean = setZoomRatio(CameraConfig.MIN_ZOOM_RATIO, camera)

    // ── 前后摄像头切换 ────────────────────────────────────────

    /**
     * ★ currentFace 改由 CameraManager 作为唯一状态源，
     * CameraControls 仅负责构造 CameraSelector 和通知回调。
     */
    fun cameraSelectorFor(face: CameraFace): CameraSelector {
        onCameraFaceChanged?.invoke(face)
        return if (face == CameraFace.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    // ── 闪光灯 ────────────────────────────────────────────────

    /**
     * ★ 设置闪光灯模式，同时写入 ImageCapture 和 cameraControl。
     *
     * @param mode 闪光灯模式
     * @param camera 当前 Camera（用于 enableTorch）
     * @param imageCapture 当前 ImageCapture（用于设置拍照闪光模式）
     */
    fun setFlashMode(
        mode: FlashMode,
        camera: Camera,
        imageCapture: ImageCapture
    ): Boolean {
        // ★ 检查设备是否支持闪光灯
        if ((mode == FlashMode.ON || mode == FlashMode.TORCH) &&
            !camera.cameraInfo.hasFlashUnit()
        ) {
            currentFlashMode = FlashMode.OFF
            imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
            logWarning("设备无闪光灯，降级为 OFF")
            return false
        }

        return try {
            when (mode) {
                FlashMode.AUTO -> {
                    camera.cameraControl.enableTorch(false)
                    imageCapture.flashMode = ImageCapture.FLASH_MODE_AUTO
                }
                FlashMode.ON -> {
                    camera.cameraControl.enableTorch(false)
                    imageCapture.flashMode = ImageCapture.FLASH_MODE_ON
                }
                FlashMode.OFF -> {
                    camera.cameraControl.enableTorch(false)
                    imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
                }
                FlashMode.TORCH -> {
                    camera.cameraControl.enableTorch(true)
                    imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
                }
            }
            currentFlashMode = mode
            logDebug("闪光灯已设置: $mode")
            true
        } catch (e: Exception) {
            logError("设置闪光灯失败", e)
            false
        }
    }

    /**
     * 获取拍照时对应的 ImageCapture 闪光模式。
     */
    fun getImageCaptureFlashMode(): Int = CameraConfig.flashModeToImageCapture(currentFlashMode)

    /**
     * 循环切换闪光灯模式：AUTO → ON → OFF → TORCH → AUTO
     */
    fun cycleFlashMode(camera: Camera, imageCapture: ImageCapture): FlashMode {
        val next = when (currentFlashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.AUTO
        }
        setFlashMode(next, camera, imageCapture)
        return next
    }

    // ── 查询 ──────────────────────────────────────────────────

    /** 是否处于手电筒模式 */
    fun isTorchOn(): Boolean = currentFlashMode == FlashMode.TORCH
}

// ── 内部日志 ──────────────────────────────────────────────────

private fun logDebug(msg: String) = Log.d(CameraConfig.TAG, "[Controls] $msg")
private fun logWarning(msg: String) = Log.w(CameraConfig.TAG, "[Controls] $msg")
private fun logError(msg: String, e: Exception? = null) = Log.e(CameraConfig.TAG, "[Controls] $msg", e)
