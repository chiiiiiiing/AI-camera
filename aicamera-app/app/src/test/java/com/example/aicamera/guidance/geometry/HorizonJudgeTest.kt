package com.example.aicamera.guidance.geometry

import org.junit.Assert.*
import org.junit.Test

/**
 * HorizonJudge 单元测试 — 验证滞回、连续确认、倒置拒绝。
 */
class HorizonJudgeTest {

    private val policy = HorizonPolicy(
        enterLevelToleranceDeg = 4.0f,
        exitLevelToleranceDeg = 7.0f,
        stableFrameCount = 5,
        minGoodFrames = 4,
        exitFrames = 2
    )

    // ═══════════════════════════════════════════════════════════
    // 连续确认进入 ALIGNED
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `单帧达标不能进入 ALIGNED`() {
        val judge = HorizonJudge(policy)
        val obs = HorizonObservation(rollDegrees = 1f, isUpright = true, confidence = 1f)
        val state = judge.observe(obs, 0)
        assertNotEquals(HorizonJudge.State.ALIGNED, state)
    }

    @Test
    fun `连续达标帧进入 ALIGNED`() {
        val judge = HorizonJudge(policy)
        val obs = HorizonObservation(rollDegrees = 1f, isUpright = true, confidence = 1f)
        repeat(6) { judge.observe(obs, it * 100L) }
        assertEquals(HorizonJudge.State.ALIGNED, judge.state)
    }

    @Test
    fun `少数不良帧不影响进入 ALIGNED`() {
        val judge = HorizonJudge(policy)
        // 5 帧中 4 帧达标、1 帧微超 → 应进入 ALIGNED
        val good = HorizonObservation(rollDegrees = 1f, isUpright = true, confidence = 1f)
        val bad = HorizonObservation(rollDegrees = 5f, isUpright = true, confidence = 1f)
        // 5° > enter(4.0°) 但 < exit(7.0°)
        judge.observe(good, 0)
        judge.observe(good, 100)
        judge.observe(bad, 200)
        judge.observe(good, 300)
        judge.observe(good, 400)
        // 5 帧收集中 goodFrames=4 >= minGoodFrames(4) → ALIGNED
        // 但还需 check: bad 的 5° > enter 所以不计 good
        // 需要再一帧 good
        judge.observe(good, 500)
        assertEquals(HorizonJudge.State.ALIGNED, judge.state)
    }

    // ═══════════════════════════════════════════════════════════
    // 滞回：退出比进入宽松
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `单帧越界不能退出 ALIGNED`() {
        val judge = HorizonJudge(policy)
        val good = HorizonObservation(rollDegrees = 1f, isUpright = true, confidence = 1f)
        repeat(6) { judge.observe(good, it * 100L) }
        assertEquals(HorizonJudge.State.ALIGNED, judge.state)

        // 单帧超标（8° > exit 7.0°）
        judge.observe(HorizonObservation(rollDegrees = 8f, isUpright = true, confidence = 1f), 600)
        assertEquals("单帧超标不能退出", HorizonJudge.State.ALIGNED, judge.state)
    }

    @Test
    fun `连续越界退出 ALIGNED`() {
        val judge = HorizonJudge(policy)
        val good = HorizonObservation(rollDegrees = 1f, isUpright = true, confidence = 1f)
        repeat(6) { judge.observe(good, it * 100L) }
        assertEquals(HorizonJudge.State.ALIGNED, judge.state)

        // 连续超标（> exit 7.0°）
        val bad = HorizonObservation(rollDegrees = 8f, isUpright = true, confidence = 1f)
        repeat(3) { judge.observe(bad, 600 + it * 100L) }
        assertEquals("连续越界应退出 ALIGNED", HorizonJudge.State.NOT_LEVEL, judge.state)
    }

    @Test
    fun `在 exit 阈值内的偏差不会退出 ALIGNED`() {
        val judge = HorizonJudge(policy)
        val good = HorizonObservation(rollDegrees = 1f, isUpright = true, confidence = 1f)
        repeat(6) { judge.observe(good, it * 100L) }
        assertEquals(HorizonJudge.State.ALIGNED, judge.state)

        // 5° > enter(4.0°) 但 < exit(7.0°) → 不应退出
        val borderline = HorizonObservation(rollDegrees = 5f, isUpright = true, confidence = 1f)
        repeat(5) { judge.observe(borderline, 600 + it * 100L) }
        assertEquals("exit 阈值内不应退出", HorizonJudge.State.ALIGNED, judge.state)
    }

    // ═══════════════════════════════════════════════════════════
    // 倒置拒绝
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `倒置 0° roll — 绝不能进入 ALIGNED`() {
        // 这是审计揭示的核心 Bug：倒置时 roll≈0°（旧公式）→ 错误通过
        // 修正后：倒置时 isUpright=false → UPSIDE_DOWN
        val judge = HorizonJudge(policy)
        val upsideDown = HorizonObservation(rollDegrees = 0f, isUpright = false, confidence = 1f)
        repeat(10) { judge.observe(upsideDown, it * 100L) }
        assertEquals(HorizonJudge.State.UPSIDE_DOWN, judge.state)
    }

    @Test
    fun `正立微倾不应误判倒置`() {
        val judge = HorizonJudge(policy)
        val obs = HorizonObservation(rollDegrees = 3f, isUpright = true, confidence = 1f)
        repeat(6) { judge.observe(obs, it * 100L) }
        assertNotEquals(HorizonJudge.State.UPSIDE_DOWN, judge.state)
    }

    // ═══════════════════════════════════════════════════════════
    // 手持策略：只放宽 App 实时引导，不影响核心严格默认值
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `手持策略 5 度倾斜连续 3 帧即可进入 ALIGNED`() {
        val judge = HorizonJudge(HandheldHorizonPolicy)
        val handShake = HorizonObservation(rollDegrees = 5f, isUpright = true, confidence = 1f)
        repeat(3) { judge.observe(handShake, it * 100L) }
        assertEquals(HorizonJudge.State.ALIGNED, judge.state)
    }

    @Test
    fun `手持策略 8 度内不退出 超过 9 度连续两帧才退出`() {
        val judge = HorizonJudge(HandheldHorizonPolicy)
        val leveled = HorizonObservation(rollDegrees = 5f, isUpright = true, confidence = 1f)
        repeat(3) { judge.observe(leveled, it * 100L) }

        val withinExit = HorizonObservation(rollDegrees = 8f, isUpright = true, confidence = 1f)
        repeat(3) { judge.observe(withinExit, 400 + it * 100L) }
        assertEquals("8° 在手持退出容差内", HorizonJudge.State.ALIGNED, judge.state)

        val beyondExit = HorizonObservation(rollDegrees = 10f, isUpright = true, confidence = 1f)
        repeat(2) { judge.observe(beyondExit, 800 + it * 100L) }
        assertEquals("连续两帧超过 9° 才退出", HorizonJudge.State.NOT_LEVEL, judge.state)
    }
}
