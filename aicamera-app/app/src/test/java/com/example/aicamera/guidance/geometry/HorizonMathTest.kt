package com.example.aicamera.guidance.geometry

import org.junit.Assert.*
import org.junit.Test

/**
 * HorizonMath 单元测试 — 覆盖 180° 倒置 Bug 修复验证。
 */
class HorizonMathTest {

    // ═══════════════════════════════════════════════════════════
    // compute() — roll 角计算
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正立竖屏 — roll 应该接近 0°`() {
        val obs = HorizonMath.compute(gx = 0f, gy = 9.8f, gz = 0f)
        assertEquals(0f, obs.rollDegrees, 0.5f)
        assertTrue("正立时 isUpright 应为 true", obs.isUpright)
    }

    @Test
    fun `微倾 3° — 方向应正确`() {
        // 顺时针倾斜 3°，gx 约 0.51
        val obs = HorizonMath.compute(gx = 0.51f, gy = 9.78f, gz = 0f)
        assertTrue("顺时针倾斜应 > 0", obs.rollDegrees > 0f)
        assertTrue("3° 偏差应在 ±1°", kotlin.math.abs(obs.rollDegrees - 3f) < 1.5f)
        assertTrue(obs.isUpright)
    }

    @Test
    fun `逆时针倾斜 -3° — 方向应正确`() {
        val obs = HorizonMath.compute(gx = -0.51f, gy = 9.78f, gz = 0f)
        assertTrue("逆时针倾斜应 < 0", obs.rollDegrees < 0f)
        assertTrue("-3° 偏差应在 ±1°", kotlin.math.abs(obs.rollDegrees + 3f) < 1.5f)
        assertTrue(obs.isUpright)
    }

    @Test
    fun `顺时针 10°`() {
        val obs = HorizonMath.compute(gx = 1.71f, gy = 9.63f, gz = 0f)
        assertTrue(obs.rollDegrees > 5f)
        assertTrue(obs.isUpright)
    }

    @Test
    fun `逆时针 -10°`() {
        val obs = HorizonMath.compute(gx = -1.71f, gy = 9.63f, gz = 0f)
        assertTrue(obs.rollDegrees < -5f)
        assertTrue(obs.isUpright)
    }

    @Test
    fun `90° 横屏`() {
        val obs = HorizonMath.compute(gx = 9.8f, gy = 0f, gz = 0f)
        assertTrue(kotlin.math.abs(obs.rollDegrees) > 45f)
        // gy≈0 → 既非正立也非倒置
    }

    @Test
    fun `-90° 横屏`() {
        val obs = HorizonMath.compute(gx = -9.8f, gy = 0f, gz = 0f)
        assertTrue(kotlin.math.abs(obs.rollDegrees) > 45f)
    }

    @Test
    fun `175° 接近倒置 — roll 应接近 ±180°`() {
        // gy≈-9.65, gx≈0.85
        val obs = HorizonMath.compute(gx = 0.85f, gy = -9.65f, gz = 0f)
        assertTrue("应接近 ±180°", kotlin.math.abs(obs.rollDegrees) > 170f)
        assertFalse("gy < 0 应判倒置", obs.isUpright)
    }

    @Test
    fun `-175° 接近倒置 — roll 应接近 ±180°`() {
        val obs = HorizonMath.compute(gx = -0.85f, gy = -9.65f, gz = 0f)
        assertTrue("应接近 ±180°", kotlin.math.abs(obs.rollDegrees) > 170f)
        assertFalse("gy < 0 应判倒置", obs.isUpright)
    }

    @Test
    fun `180° 完全倒置 — 绝不能判正立`() {
        val obs = HorizonMath.compute(gx = 0f, gy = -9.8f, gz = 0f)
        assertTrue("完全倒置 roll 应接近 ±180°", kotlin.math.abs(obs.rollDegrees) > 170f)
        assertFalse("倒置时 isUpright 必须为 false", obs.isUpright)
    }

    @Test
    fun `倒置但 roll 接近 0° — isUpright 必须 false`() {
        // 旧 Bug 场景：atan2(-gx,-gy) 倒置时给 ~0° + 无 isUpright → 错误通过
        // 修正后：atan2(gx,gy) 倒置时给 ~±180°，isUpright=false
        val obs = HorizonMath.compute(gx = 0f, gy = -9.8f, gz = 0f)
        // roll 应接近 ±180° 而非 0°
        assertTrue("倒置时 roll 不应接近 0°", kotlin.math.abs(obs.rollDegrees) > 90f)
        assertFalse(obs.isUpright)
    }

    // ═══════════════════════════════════════════════════════════
    // 置信度
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `标准重力置信度应接近 1`() {
        val obs = HorizonMath.compute(gx = 0f, gy = 9.8f, gz = 0f)
        assertTrue("置信度应 > 0.95", obs.confidence > 0.95f)
    }

    @Test
    fun `大幅加速度干扰时置信度应降低`() {
        // |g| = 20 → 远离 9.8
        val obs = HorizonMath.compute(gx = 10f, gy = 17.32f, gz = 0f)
        assertTrue("置信度应 < 0.5", obs.confidence < 0.5f)
    }
}
