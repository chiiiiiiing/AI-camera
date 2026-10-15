package com.example.aicamera.guidance.geometry

import org.junit.Assert.*
import org.junit.Test

/**
 * HorizonMath 单元测试 — 覆盖 180° 倒置 Bug 修复验证。
 */
class HorizonMathTest {

    @Test
    fun `all four display orientations agree when image is level`() {
        val gravityByRotation = listOf(0f to 9.8f, 9.8f to 0f, 0f to -9.8f, -9.8f to 0f)
        for ((rotation, gravity) in gravityByRotation.withIndex()) {
            val raw = HorizonMath.compute(gravity.first, gravity.second, 0f)
            val displayed = HorizonMath.inDisplayCoordinates(raw, rotation)
            assertEquals(0f, displayed.rollDegrees, .001f)
            assertTrue(displayed.isUpright)
        }
    }

    @Test
    fun `display orientation keeps correction direction and upside down protection`() {
        for (rotation in 0..3) {
            val raw = HorizonObservation(-rotation * 90f + 10f, 0f, isUpright = false, confidence = 1f)
            val clockwise = HorizonMath.inDisplayCoordinates(raw, rotation)
            assertEquals(10f, clockwise.rollDegrees, .001f)
            assertTrue(clockwise.isUpright)
            val counterclockwise = HorizonMath.inDisplayCoordinates(raw.copy(rollDegrees = -rotation * 90f - 10f), rotation)
            assertEquals(-10f, counterclockwise.rollDegrees, .001f)
            val inverted = HorizonMath.inDisplayCoordinates(raw.copy(rollDegrees = -rotation * 90f + 180f), rotation)
            assertFalse(inverted.isUpright)
            assertEquals(180f, kotlin.math.abs(inverted.rollDegrees), .001f)
        }
    }

    @Test
    fun `display rotation never invents a horizon for overhead photography`() {
        val raw = HorizonMath.compute(0f, 0f, 9.8f)
        for (rotation in 0..3) {
            val displayed = HorizonMath.inDisplayCoordinates(raw, rotation)
            assertFalse(displayed.isUpright)
            assertFalse(HorizonMath.hasObservableRoll(displayed))
        }
    }

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
        // 面向屏幕顺时针倾斜 3°，设备 x 轴向下，gx 约 -0.51。
        val obs = HorizonMath.compute(gx = -0.51f, gy = 9.78f, gz = 0f)
        assertTrue("顺时针倾斜应 > 0", obs.rollDegrees > 0f)
        assertTrue("3° 偏差应在 ±1°", kotlin.math.abs(obs.rollDegrees - 3f) < 1.5f)
        assertTrue(obs.isUpright)
    }

    @Test
    fun `逆时针倾斜 -3° — 方向应正确`() {
        val obs = HorizonMath.compute(gx = 0.51f, gy = 9.78f, gz = 0f)
        assertTrue("逆时针倾斜应 < 0", obs.rollDegrees < 0f)
        assertTrue("-3° 偏差应在 ±1°", kotlin.math.abs(obs.rollDegrees + 3f) < 1.5f)
        assertTrue(obs.isUpright)
    }

    @Test
    fun `顺时针 10°`() {
        val obs = HorizonMath.compute(gx = -1.71f, gy = 9.63f, gz = 0f)
        assertTrue(obs.rollDegrees > 5f)
        assertTrue(obs.isUpright)
    }

    @Test
    fun `逆时针 -10°`() {
        val obs = HorizonMath.compute(gx = 1.71f, gy = 9.63f, gz = 0f)
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
        // 修正后：atan2(-gx,gy) 倒置时给 ~±180°，isUpright=false
        val obs = HorizonMath.compute(gx = 0f, gy = -9.8f, gz = 0f)
        // roll 应接近 ±180° 而非 0°
        assertTrue("倒置时 roll 不应接近 0°", kotlin.math.abs(obs.rollDegrees) > 90f)
        assertFalse(obs.isUpright)
    }

    @Test
    fun `following roll cue reduces physical tilt in every display orientation`() {
        for (rotation in 0..3) for (clockwiseTilt in listOf(-12f, 12f)) {
            fun observation(tilt: Float): HorizonObservation {
                val angle = Math.toRadians((tilt - rotation * 90f).toDouble())
                return HorizonMath.inDisplayCoordinates(HorizonMath.compute(
                    (-9.8 * kotlin.math.sin(angle)).toFloat(),
                    (9.8 * kotlin.math.cos(angle)).toFloat(), 0f), rotation)
            }
            val before = observation(clockwiseTilt)
            val cue = GuidanceGeometry.guidanceCue(null, null, false, horizonAngleDeg = before.rollDegrees)
            val correction = when (cue) {
                com.example.aicamera.guidance.GuidanceCue.ROLL_CLOCKWISE -> 4f
                com.example.aicamera.guidance.GuidanceCue.ROLL_COUNTERCLOCKWISE -> -4f
                else -> error("Expected a correction for 12 degree tilt")
            }
            assertTrue(kotlin.math.abs(observation(clockwiseTilt + correction).rollDegrees) <
                kotlin.math.abs(before.rollDegrees))
        }
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
