package com.example.aicamera.camera

import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * 对焦与曝光控制。
 */
class CameraFocus(
    private val onFocusStateChanged: ((isFocused: Boolean) -> Unit)? = null
) {
    private val focusLockTimeoutMs = 3000L

    var exposureCompensation: Int = CameraConfig.EXPOSURE_COMPENSATION_DEFAULT
        private set

    @Volatile
    private var isLocked = false

    /** ★ 防竞态 token：避免旧 delayed 任务误清新对焦锁定状态 */
    private var focusToken = 0

    /**
     * ★ 使用 PreviewView.meteringPointFactory 替代手写 SurfaceOrientedMeteringPointFactory。
     * PreviewView 的 factory 自动处理 ScaleType 裁剪、前摄镜像等变换。
     */
    fun enableTapToFocus(
        previewView: PreviewView,
        camera: Camera,
        onFocusCircle: ((centerX: Float, centerY: Float) -> Unit)? = null
    ) {
        val executor = ContextCompat.getMainExecutor(previewView.context)

        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)

                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).apply {
                    setAutoCancelDuration(focusLockTimeoutMs, TimeUnit.MILLISECONDS)
                }.build()

                val future = camera.cameraControl.startFocusAndMetering(action)

                future.addListener({
                    try {
                        val result = future.get()
                        val focused = result.isFocusSuccessful
                        logDebug("对焦完成: focused=$focused")
                        onFocusStateChanged?.invoke(focused)
                    } catch (e: Exception) {
                        logWarning("对焦失败: ${e.message}")
                        onFocusStateChanged?.invoke(false)
                    }
                }, executor)

                markFocusLocked(previewView)
                onFocusCircle?.invoke(event.x, event.y)
            }
            false
        }
    }

    /** ★ 使用 token 机制防竞态：连续点击时旧 delayed 任务不会误清新状态 */
    private fun markFocusLocked(view: View) {
        val token = ++focusToken
        isLocked = true
        view.postDelayed({
            if (token == focusToken) {
                isLocked = false
            }
        }, focusLockTimeoutMs)
    }

    /**
     * 设置曝光补偿。乐观更新本地状态。
     */
    fun setExposureCompensation(value: Int, camera: Camera): Boolean {
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        val clamped = value.coerceIn(range.lower, range.upper)

        if (clamped != value) {
            logWarning("曝光补偿 $value 超出范围 $range，已截断为 $clamped")
        }

        if (clamped == exposureCompensation) return true

        return try {
            camera.cameraControl.setExposureCompensationIndex(clamped)
            exposureCompensation = clamped
            logDebug("曝光补偿已设置: $clamped")
            true
        } catch (e: Exception) {
            logError("设置曝光补偿失败", e)
            false
        }
    }

    fun resetExposureCompensation(camera: Camera): Boolean {
        return setExposureCompensation(CameraConfig.EXPOSURE_COMPENSATION_DEFAULT, camera)
    }

    fun isFocusLocked(): Boolean = isLocked

    fun getExposureRange(camera: Camera): android.util.Range<Int> {
        return camera.cameraInfo.exposureState.exposureCompensationRange
    }
}

private fun logDebug(msg: String) = Log.d(CameraConfig.TAG, "[Focus] $msg")
private fun logWarning(msg: String) = Log.w(CameraConfig.TAG, "[Focus] $msg")
private fun logError(msg: String, e: Exception? = null) = Log.e(CameraConfig.TAG, "[Focus] $msg", e)
