package com.example.aicamera.guidance.geometry

import com.example.aicamera.guidance.GuidanceCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GuidanceGeometry 纯逻辑单元测试。
 *
 * 覆盖 docs/10 第 6 节的指令映射约定：
 * ROLL 优先 → SUBJECT_TARGET（期望主体但无检测）→ 容差内 ALIGNED → 四向 MOVE。
 */
class GuidanceGeometryTest {

    // ── ROLL 指令 ─────────────────────────────────────────────

    @Test
    fun `clockwise tilt beyond threshold gives ROLL_COUNTERCLOCKWISE`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = null, subjectCy = null,
            expectsSubject = false,
            horizonAngleDeg = 15f  // > ROLL_THRESHOLD_DEG
        )
        assertEquals(GuidanceCue.ROLL_COUNTERCLOCKWISE, cue)
    }

    @Test
    fun `counterclockwise tilt beyond threshold gives ROLL_CLOCKWISE`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = null, subjectCy = null,
            expectsSubject = false,
            horizonAngleDeg = -15f  // < -ROLL_THRESHOLD_DEG
        )
        assertEquals(GuidanceCue.ROLL_CLOCKWISE, cue)
    }

    @Test
    fun `tilt within threshold does not trigger roll cue`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = null, subjectCy = null,
            expectsSubject = false,
            horizonAngleDeg = 1f  // |1| < ROLL_THRESHOLD_DEG
        )
        assertEquals(GuidanceCue.NONE, cue)
    }

    @Test
    fun `roll cue has priority over move cue`() {
        // 主体明显偏离 + 水平倾斜 → 先修水平
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = 0.9f, subjectCy = 0.5f,
            expectsSubject = true,
            targetCx = 0.333f, targetCy = 0.333f, targetTolerance = 0.12f,
            horizonAngleDeg = 15f  // > ROLL_THRESHOLD_DEG
        )
        assertEquals(GuidanceCue.ROLL_COUNTERCLOCKWISE, cue)
    }

    // ── 主体指令 ──────────────────────────────────────────────

    @Test
    fun `portrait template without detected subject gives SUBJECT_TARGET`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = null, subjectCy = null,
            expectsSubject = true,
            horizonAngleDeg = 0.5f
        )
        assertEquals(GuidanceCue.SUBJECT_TARGET, cue)
    }

    @Test
    fun `landscape template without subject gives NONE`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = null, subjectCy = null,
            expectsSubject = false,
            horizonAngleDeg = 0f
        )
        assertEquals(GuidanceCue.NONE, cue)
    }

    @Test
    fun `subject inside tolerance gives SUBJECT_ALIGNED`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = 0.35f, subjectCy = 0.34f,
            expectsSubject = true,
            targetCx = 0.333f, targetCy = 0.333f, targetTolerance = 0.12f,
            horizonAngleDeg = 0f
        )
        assertEquals(GuidanceCue.SUBJECT_ALIGNED, cue)
    }

    @Test
    fun `subject too far right gives MOVE_LEFT`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = 0.7f, subjectCy = 0.333f,
            expectsSubject = true,
            targetCx = 0.333f, targetCy = 0.333f, targetTolerance = 0.12f,
            horizonAngleDeg = 0f
        )
        assertEquals(GuidanceCue.MOVE_LEFT, cue)
    }

    @Test
    fun `subject too far left gives MOVE_RIGHT`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = 0.1f, subjectCy = 0.333f,
            expectsSubject = true,
            targetCx = 0.333f, targetCy = 0.333f, targetTolerance = 0.12f,
            horizonAngleDeg = 0f
        )
        assertEquals(GuidanceCue.MOVE_RIGHT, cue)
    }

    @Test
    fun `subject too low gives MOVE_UP`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = 0.333f, subjectCy = 0.8f,
            expectsSubject = true,
            targetCx = 0.333f, targetCy = 0.333f, targetTolerance = 0.12f,
            horizonAngleDeg = 0f
        )
        assertEquals(GuidanceCue.MOVE_UP, cue)
    }

    @Test
    fun `subject too high gives MOVE_DOWN`() {
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = 0.333f, subjectCy = 0.05f,
            expectsSubject = true,
            targetCx = 0.333f, targetCy = 0.333f, targetTolerance = 0.12f,
            horizonAngleDeg = 0f
        )
        assertEquals(GuidanceCue.MOVE_DOWN, cue)
    }

    @Test
    fun `tiny target radius is floored to MIN_TOLERANCE`() {
        // radius=0.001 时应按 0.06 容差判断：偏差 0.03 视为对齐
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = 0.36f, subjectCy = 0.333f,
            expectsSubject = true,
            targetCx = 0.333f, targetCy = 0.333f, targetTolerance = 0.001f,
            horizonAngleDeg = 0f
        )
        assertEquals(GuidanceCue.SUBJECT_ALIGNED, cue)
    }

    // ── 构图进度 ──────────────────────────────────────────────

    @Test
    fun `perfect alignment and level gives progress near 1`() {
        val p = GuidanceGeometry.compositionProgress(
            subjectCx = 0.333f, subjectCy = 0.333f,
            targetCx = 0.333f, targetCy = 0.333f,
            horizonAngleDeg = 0f
        )!!
        assertTrue("进度应接近 1.0，实际 $p", p > 0.95f)
    }

    @Test
    fun `far subject and tilted camera gives low progress`() {
        val p = GuidanceGeometry.compositionProgress(
            subjectCx = 0.95f, subjectCy = 0.95f,
            targetCx = 0.333f, targetCy = 0.333f,
            horizonAngleDeg = 9f
        )!!
        assertTrue("进度应很低，实际 $p", p < 0.3f)
    }

    @Test
    fun `no signals at all returns null (caller falls back to template score)`() {
        val p = GuidanceGeometry.compositionProgress(
            subjectCx = null, subjectCy = null,
            horizonAngleDeg = null
        )
        assertNull(p)
    }

    @Test
    fun `horizon only progress is computed without subject`() {
        val p = GuidanceGeometry.compositionProgress(
            subjectCx = null, subjectCy = null,
            horizonAngleDeg = 1f
        )!!
        assertTrue(p > 0.85f)
    }

    // ── 对齐进度提升（达标反馈前置条件） ───────────────────────

    @Test
    fun `SUBJECT_ALIGNED boosts progress above aligned threshold`() {
        // 主体刚进容差圈时原始进度仅 ~0.73，提升后必须 ≥ 0.92（状态机阈值）
        val boosted = GuidanceGeometry.progressWithAlignedBoost(
            GuidanceCue.SUBJECT_ALIGNED, 0.73f
        )
        assertTrue(boosted >= 0.92f)
        assertEquals(GuidanceGeometry.ALIGNED_PROGRESS_FLOOR, boosted, 1e-4f)
    }

    @Test
    fun `SUBJECT_ALIGNED keeps already-high progress`() {
        val boosted = GuidanceGeometry.progressWithAlignedBoost(
            GuidanceCue.SUBJECT_ALIGNED, 0.99f
        )
        assertEquals(0.99f, boosted, 1e-4f)
    }

    @Test
    fun `other cues are not boosted`() {
        assertEquals(
            0.73f,
            GuidanceGeometry.progressWithAlignedBoost(GuidanceCue.MOVE_LEFT, 0.73f),
            1e-4f
        )
        assertEquals(
            0.5f,
            GuidanceGeometry.progressWithAlignedBoost(GuidanceCue.NONE, 0.5f),
            1e-4f
        )
        assertEquals(
            0.4f,
            GuidanceGeometry.progressWithAlignedBoost(GuidanceCue.ROLL_CLOCKWISE, 0.4f),
            1e-4f
        )
    }

    // ── EMA 平滑器 ────────────────────────────────────────────

    @Test
    fun `ema converges towards input series`() {
        val ema = EmaSmoother(0.3f)
        var v = ema.add(10f)
        assertEquals(10f, v, 1e-4f) // 首样本直通
        repeat(30) { v = ema.add(10f) }
        assertEquals(10f, v, 1e-3f) // 常数序列收敛到常数
    }

    @Test
    fun `ema smooths step jump`() {
        val ema = EmaSmoother(0.3f)
        ema.add(0f)
        val v = ema.add(10f)
        // 0 → 10 阶跃后应为 0 + 0.3*(10-0) = 3
        assertEquals(3f, v, 1e-4f)
    }

    @Test
    fun `ema reset clears state`() {
        val ema = EmaSmoother(0.3f)
        ema.add(5f)
        ema.reset()
        assertNull(ema.value())
    }

    // ── NormalizedBox ─────────────────────────────────────────

    @Test
    fun `box center computation`() {
        val box = NormalizedBox(0.2f, 0.3f, 0.6f, 0.7f)
        assertEquals(0.4f, box.centerX, 1e-4f)
        assertEquals(0.5f, box.centerY, 1e-4f)
        assertEquals(0.4f, box.width, 1e-4f)
        assertEquals(0.4f, box.height, 1e-4f)
    }
}
