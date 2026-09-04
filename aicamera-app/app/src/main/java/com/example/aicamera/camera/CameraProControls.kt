package com.example.aicamera.camera

import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import com.example.aicamera.camera.CameraConfig.FlashMode

/**
 * 专业相机手动控制 — EV 补偿 / 对焦模式 / 白平衡 / 变焦 / 手电筒。
 *
 * ## 能力来源
 * | 控制项 | CameraX 直接支持 | Camera2 Interop |
 * |---|---|---|
 * | 曝光补偿 (EV) | ✅ setExposureCompensationIndex | — |
 * | 变焦 | ✅ setZoomRatio / setLinearZoom | — |
 * | 对焦模式 | ✅ FocusMeteringAction (tap-to-focus) | ✅ CONTROL_AF_MODE |
 * | 闪光灯/手电 | ✅ ImageCapture.FLASH_MODE / cameraControl.enableTorch | — |
 * | 白平衡 | ❌ | ✅ CONTROL_AWB_MODE |
 * | ISO / 快门速度 | ❌ | ✅ SENSOR_SENSITIVITY / SENSOR_EXPOSURE_TIME |
 *
 * 注：大多数手机镜头是固定光圈，无法手动调节。
 */
class CameraProControls(
    private val camera: Camera
) {
    private val cameraControl: CameraControl = camera.cameraControl
    private val cameraInfo = camera.cameraInfo

    // ── 曝光补偿 (EV) ────────────────────────────────────────

    /** EV 补偿范围 */
    val exposureRange: Range<Int>
        get() = cameraInfo.exposureState.exposureCompensationRange

    /** 当前 EV 补偿值 */
    val currentExposure: Int
        get() = cameraInfo.exposureState.exposureCompensationIndex

    /**
     * 设置曝光补偿 (EV)。
     * @param index EV 补偿索引，范围由 [exposureRange] 提供
     * @return 是否操作成功（CameraControl 无返回值，视为最佳努力）
     */
    fun setExposureCompensation(index: Int): Boolean {
        val clamped = exposureRange.clamp(index)
        return try {
            cameraControl.setExposureCompensationIndex(clamped)
            Log.d(TAG, "EV 补偿设为 $clamped")
            true
        } catch (e: Exception) {
            Log.e(TAG, "设置 EV 补偿失败", e)
            false
        }
    }

    /** EV 补偿步长（通常 1/3 EV） */
    val exposureStep: Float
        get() = cameraInfo.exposureState.exposureCompensationStep.toFloat()

    // ── 变焦 ──────────────────────────────────────────────────

    /** 最小变焦比 */
    val minZoom: Float get() = cameraInfo.zoomState.value?.minZoomRatio ?: 1f

    /** 最大变焦比 */
    val maxZoom: Float get() = cameraInfo.zoomState.value?.maxZoomRatio ?: CameraConfig.MAX_ZOOM_RATIO

    /** 当前变焦比 */
    val currentZoom: Float get() = cameraInfo.zoomState.value?.zoomRatio ?: 1f

    /**
     * 设置变焦比（平滑过渡）。
     */
    fun setZoom(ratio: Float): Boolean {
        val clamped = ratio.coerceIn(minZoom, maxZoom)
        return try {
            cameraControl.setZoomRatio(clamped)
            true
        } catch (e: Exception) {
            Log.e(TAG, "设置变焦失败", e)
            false
        }
    }

    /**
     * 设置线性变焦 (0.0–1.0)，更均匀的视觉变焦。
     */
    fun setLinearZoom(fraction: Float): Boolean {
        val clamped = fraction.coerceIn(0f, 1f)
        return try {
            cameraControl.setLinearZoom(clamped)
            true
        } catch (e: Exception) {
            Log.e(TAG, "设置线性变焦失败", e)
            false
        }
    }

    // ── 对焦 ──────────────────────────────────────────────────

    /**
     * 点击对焦 + 测光。
     *
     * @param x 点击位置 X（View 坐标）
     * @param y 点击位置 Y（View 坐标）
     * @param width 预览 View 宽度
     * @param height 预览 View 高度
     */
    /**
     * 使用 MeteringPointFactory 执行对焦 + 测光。
     *
     * @param pointFactory 从 PreviewView 获取
     * @param context Android Context（用于主线程 Executor）
     */
    fun focusAt(
        pointFactory: MeteringPointFactory,
        x: Float, y: Float,
        context: android.content.Context? = null
    ): Boolean {
        return try {
            val point = pointFactory.createPoint(x, y, 0.1f) // 对焦区域大小
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            cameraControl.startFocusAndMetering(action)
            true
        } catch (e: Exception) {
            Log.e(TAG, "对焦失败", e)
            false
        }
    }

    // ── 手电筒 / 闪光灯 ──────────────────────────────────────

    /** 手电筒是否可用 */
    val isTorchAvailable: Boolean
        get() = cameraInfo.hasFlashUnit()

    /** 当前手电筒状态 */
    val isTorchOn: Boolean
        get() = cameraInfo.torchState.value == androidx.camera.core.TorchState.ON

    fun enableTorch(on: Boolean): Boolean {
        return try {
            cameraControl.enableTorch(on)
            true
        } catch (e: Exception) {
            Log.e(TAG, "手电筒切换失败", e)
            false
        }
    }

    // ── 闪光灯（拍照时） ──────────────────────────────────────

    fun setFlashMode(mode: FlashMode, imageCapture: ImageCapture) {
        imageCapture.flashMode = CameraConfig.flashModeToImageCapture(mode)
        if (mode == FlashMode.TORCH) enableTorch(true)
        else if (isTorchOn && mode != FlashMode.TORCH) enableTorch(false)
    }

    // ── Camera2 扩展说明 ─────────────────────────────────────
    // ISO / 白平衡 / 快门 / 对焦距离经 CameraManager.applyProOptions
    // 在 UseCase 构建阶段写入（Camera2Interop，重绑后生效），
    // 本类只负责相机绑定后可直接调用的实时控制（EV/变焦/对焦/闪光）。

    // ── 静态信息查询 ──────────────────────────────────────────

    companion object {
        private const val TAG = "ProControls"

        /** 获取设备支持的 EV 范围（需要在相机绑定后调用） */
        fun getSupportedEvRange(camera: Camera): Range<Int> {
            return camera.cameraInfo.exposureState.exposureCompensationRange
        }

        /** 获取设备支持的变焦范围 */
        fun getSupportedZoomRange(camera: Camera): Pair<Float, Float> {
            val state = camera.cameraInfo.zoomState.value
            return if (state != null) {
                (state.minZoomRatio ?: 1f) to (state.maxZoomRatio ?: CameraConfig.MAX_ZOOM_RATIO)
            } else {
                1f to CameraConfig.MAX_ZOOM_RATIO
            }
        }

        /** 白平衡模式标签 */
        val AWB_MODE_LABELS = mapOf(
            CaptureRequest.CONTROL_AWB_MODE_AUTO to "自动",
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "白炽灯",
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "荧光灯",
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "日光",
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "阴天",
            CaptureRequest.CONTROL_AWB_MODE_TWILIGHT to "黄昏",
            CaptureRequest.CONTROL_AWB_MODE_SHADE to "阴影"
        )

        /** 常用 ISO 预设值 */
        val ISO_PRESETS = intArrayOf(100, 200, 400, 800, 1600, 3200)
    }
}
