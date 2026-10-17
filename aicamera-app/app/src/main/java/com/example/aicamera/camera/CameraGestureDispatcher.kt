package com.example.aicamera.camera

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * 统一手势分发器 — 基于 Android 原生 GestureDetectorCompat + ScaleGestureDetector。
 *
 * 相比手写状态机的方案，利用 Android 框架做手势识别更稳健：
 * - GestureDetectorCompat：单击、长按由系统判定
 * - ScaleGestureDetector：双指缩放手势
 * - 两者可以在同一个 onTouchEvent 中协作，不会互相覆盖
 *
 * 绑定到 PreviewView：
 * - 单击 → 对焦（触发 AF/AE）
 * - 双指捏合 → 变焦
 * - 长按 → AE/AF 锁定
 */
class CameraGestureDispatcher(
    private val onFocusCircle: (cx: Float, cy: Float) -> Unit,
    private val onZoomChanged: ((Float) -> Unit)? = null,
    private val onFocusLocked: ((Boolean) -> Unit)? = null
) {
    var currentZoomRatio: Float = 1.0f
        private set

    private val focusLockTimeoutMs = 3000L

    /** ★ GestureDetector 引用，用于判断手势类型 */
    private var gestureDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null

    /** ★ 标记当前是否在双指缩放中（防止缩放时触发单击对焦） */
    private var isScaling = false

    /**
     * 绑定到 PreviewView。
     */
    fun attach(
        previewView: PreviewView,
        camera: Camera
    ) {
        val context = previewView.context

        // 手势识别器：单击 → 对焦，长按 → 锁定
        val handler = Handler(Looper.getMainLooper())
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isScaling) {
                    performTapToFocus(previewView, camera, e.x, e.y)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isScaling) {
                    performLongPressLock(camera)
                }
            }
        }, handler)

        // 双指缩放手势
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                // 直接使用硬件可用范围，不再人为压上限（旧版本写死 3×）。
                val zoomState = camera.cameraInfo.zoomState.value
                val maxHardware = zoomState?.maxZoomRatio ?: CameraConfig.MAX_ZOOM_RATIO
                val minHardware = zoomState?.minZoomRatio ?: CameraConfig.MIN_ZOOM_RATIO
                val newRatio = (currentZoomRatio * scaleFactor).coerceIn(minHardware, maxHardware)
                if (kotlin.math.abs(newRatio - currentZoomRatio) > 0.01f) {
                    currentZoomRatio = newRatio
                    camera.cameraControl.setZoomRatio(newRatio)
                    onZoomChanged?.invoke(newRatio)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

        previewView.setOnTouchListener { _, event ->
            // ★ 先让两个 detector 消费事件，再用 isScaling 标记做互斥
            val scaleHandled = scaleDetector?.onTouchEvent(event) ?: false
            val gestureHandled = gestureDetector?.onTouchEvent(event) ?: false

            // 双指手势结束后重置标记
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                isScaling = false
            }

            // 消费事件防止穿透
            true
        }
    }

    /**
     * 解绑手势监听（相机重绑/销毁时调用，防止旧 Camera 实例泄漏）。
     */
    fun detach(previewView: PreviewView) {
        previewView.setOnTouchListener(null)
        gestureDetector = null
        scaleDetector = null
        isScaling = false
    }

    // ── 点击对焦 ─────────────────────────────────────────────

    private fun performTapToFocus(
        previewView: PreviewView,
        camera: Camera,
        x: Float,
        y: Float
    ) {
        val executor = ContextCompat.getMainExecutor(previewView.context)

        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)

            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).apply {
                setAutoCancelDuration(focusLockTimeoutMs, TimeUnit.MILLISECONDS)
            }.build()

            onFocusCircle(x, y)
            camera.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            android.util.Log.w(CameraConfig.TAG, "[Gesture] 对焦失败: ${e.message}")
        }
    }

    // ── 长按锁定 ─────────────────────────────────────────────

    private fun performLongPressLock(camera: Camera) {
        try {
            onFocusLocked?.invoke(true)
            android.util.Log.d(CameraConfig.TAG, "[Gesture] AE/AF 锁定")

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                onFocusLocked?.invoke(false)
            }, focusLockTimeoutMs)
        } catch (e: Exception) {
            android.util.Log.w(CameraConfig.TAG, "[Gesture] 锁定失败: ${e.message}")
        }
    }
}
