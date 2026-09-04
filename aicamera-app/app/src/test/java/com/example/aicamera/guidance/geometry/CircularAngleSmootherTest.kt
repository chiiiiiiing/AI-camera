package com.example.aicamera.guidance.geometry

import org.junit.Assert.*
import org.junit.Test

/**
 * CircularAngleSmoother 单元测试。
 *
 * 关键：179°→-179°、-179°→179°、359°→1°、1°→359°
 * 都不能产生巨大跳变（与普通 EMA 的本质区别）。
 */
class CircularAngleSmootherTest {

    @Test
    fun `初始值直接返回`() {
        val s = CircularAngleSmoother()
        assertEquals(45f, s.smooth(45f), 0.1f)
    }

    @Test
    fun `小幅变化平滑有效`() {
        val s = CircularAngleSmoother(alpha = 0.5f)
        s.smooth(0f)
        val r = s.smooth(10f)
        // alpha=0.5 时，第一次平滑后应在 5° 左右
        assertTrue("应平滑到 ~5°", kotlin.math.abs(r - 5f) < 2f)
    }

    @Test
    fun `179→-179 不能跳变`() {
        val s = CircularAngleSmoother(alpha = 0.5f)
        s.smooth(179f)
        val r = s.smooth(-179f)
        // 真实差仅 2°，平滑后的值应接近 ±180°（不能跳到 0° 附近）
        val diff = kotlin.math.abs(kotlin.math.abs(r) - 180f)
        assertTrue("不应跳到 0° 附近，当前值: $r", diff < 10f)
    }

    @Test
    fun `-179→179 不能跳变`() {
        val s = CircularAngleSmoother(alpha = 0.5f)
        s.smooth(-179f)
        val r = s.smooth(179f)
        // 真实差仅 2°，平滑后的值应接近 ±180°（不能跳到 0° 附近）
        val diff = kotlin.math.abs(kotlin.math.abs(r) - 180f)
        assertTrue("不应跳到 0° 附近，当前值: $r", diff < 10f)
    }

    @Test
    fun `连续小幅不应大幅变化`() {
        val s = CircularAngleSmoother(alpha = 0.35f)
        s.smooth(0f)
        for (i in 1..10) {
            s.smooth(0.5f)
        }
        val r = s.smooth(1f)
        assertTrue("平滑值应 < 2°", kotlin.math.abs(r) < 2f)
    }

    @Test
    fun `连续环绕不崩溃`() {
        val s = CircularAngleSmoother(alpha = 0.35f)
        val values = listOf(0f, 30f, 60f, 90f, 120f, 150f, -179f, -150f, -120f, -90f, -60f, -30f, 0f)
        for (v in values) {
            val r = s.smooth(v)
            assertTrue("结果应有限: $r", r.isFinite())
        }
    }

    @Test
    fun `reset 后像新的一样`() {
        val s = CircularAngleSmoother()
        s.smooth(90f)
        s.reset()
        assertEquals(0f, s.smooth(0f), 0.1f)
    }
}
