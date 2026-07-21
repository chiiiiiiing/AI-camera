package com.example.aicamera.overlay

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

/**
 * 动画控制器 — 管理 pulse、flow、transition 动画状态。
 *
 * ★ 每个 animator update 后调用 onFrame 触发 View 重绘。
 */
class AnimationController(
    private val onFrame: () -> Unit
) {
    var pulseAlpha: Float = 1.0f
        private set

    var pulseScale: Float = 1.0f
        private set

    private val pulseAnimator: ValueAnimator = ValueAnimator.ofFloat(0.3f, 1.0f).apply {
        duration = 1200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseAlpha = 0.3f + 0.7f * (it.animatedValue as Float)
            pulseScale = 0.85f + 0.3f * (it.animatedValue as Float)
            onFrame() // ★ 驱动重绘
        }
    }

    var flowOffset: Float = 0.0f
        private set

    private val flowAnimator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            flowOffset = it.animatedValue as Float
            onFrame() // ★ 驱动重绘
        }
    }

    var transitionAlpha: Float = 1.0f
        private set

    private var transitionAnimator: ValueAnimator? = null

    /**
     * ★ 修复 midpoint 逻辑：用 animatedFraction 而非 onAnimationRepeat。
     */
    fun startTransition(onMidpoint: () -> Unit) {
        transitionAnimator?.cancel()

        var midCalled = false
        transitionAnimator = ValueAnimator.ofFloat(1f, 0f, 1f).apply {
            duration = 400L
            addUpdateListener { animation ->
                transitionAlpha = animation.animatedValue as Float

                if (!midCalled && animation.animatedFraction >= 0.5f) {
                    midCalled = true
                    onMidpoint()
                }

                onFrame() // ★ 驱动重绘
            }
            start()
        }
    }

    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        pulseAnimator.start()
        flowAnimator.start()
        isRunning = true
    }

    fun pause() {
        pulseAnimator.pause()
        flowAnimator.pause()
        isRunning = false
    }

    fun resume() {
        if (isRunning) return
        pulseAnimator.resume()
        flowAnimator.resume()
        isRunning = true
    }

    fun stop() {
        pulseAnimator.cancel()
        flowAnimator.cancel()
        transitionAnimator?.cancel()
        isRunning = false
    }
}
