package com.example.aicamera.guidance

import com.example.aicamera.guidance.GuidanceStepSequencer.GuidanceStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GuidanceStepSequencer 分步引导序列器单测。
 *
 * 覆盖：步骤推进顺序（ZOOM→LEVEL→SUBJECT→DONE）、变焦决策数学、
 * 指令过滤（每步只放行本步 cue）、用户取消、无信号降级、DONE 回退。
 */
class GuidanceStepSequencerTest {

    @Test
    fun `failed model never auto zooms or declares completion`() {
        val sequencer = GuidanceStepSequencer()
        repeat(100) { frame ->
            val result = sequencer.onFrame(
                GuidanceStepSequencer.Input(
                    horizonAngleDeg = 0f, subjectHeightFraction = 0.1f,
                    hasSubject = true, expectsSubject = false, currentZoomRatio = 1f,
                    minZoomRatio = 1f, maxZoomRatio = 4f, nowMs = frame * 500L,
                    recommendationReady = false
                ), GuidanceCue.SUBJECT_ALIGNED, 1f
            )
            assertEquals(GuidanceStep.ZOOM, result.step)
            assertEquals(0f, result.progress, 0f)
            assertNull(result.zoomTarget)
        }
    }

    private lateinit var seq: GuidanceStepSequencer
    private var now = 10_000L

    @Before
    fun setUp() {
        seq = GuidanceStepSequencer()
        seq.reset(now)
    }

    private fun input(
        angle: Float? = 0f,
        subjectFrac: Float? = null,
        hasSubject: Boolean = false,
        expectsSubject: Boolean = false,
        zoom: Float = 1.0f,
        minZoom: Float = 1.0f,
        maxZoom: Float = 4.0f,
        dtMs: Long = 100,
        // Existing zoom arithmetic tests explicitly supply a measured reference.
        referenceSubjectFrac: Float? = 0.30f
    ): GuidanceStepSequencer.Input {
        now += dtMs
        return GuidanceStepSequencer.Input(
            horizonAngleDeg = angle,
            subjectHeightFraction = subjectFrac,
            hasSubject = hasSubject,
            expectsSubject = expectsSubject,
            currentZoomRatio = zoom,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            nowMs = now,
            targetSubjectHeightFraction = referenceSubjectFrac
        )
    }

    // ── 步骤顺序 ─────────────────────────────────────────────

    @Test
    fun `visible face without measured reference height never auto zooms`() {
        repeat(60) {
            val output = seq.onFrame(
                input(subjectFrac = 0.1f, hasSubject = true, expectsSubject = true,
                    referenceSubjectFrac = null), GuidanceCue.SUBJECT_TARGET, 0f
            )
            assertNull(output.zoomTarget)
        }
    }

    @Test
    fun `level completion cannot replace missing composition evidence`() {
        repeat(80) {
            val output = seq.onFrame(input(angle = 0f), GuidanceCue.NONE, 0f)
            assertEquals(0f, output.progress, 0f)
        }
    }

    @Test
    fun `intermediate steps cannot announce overall aligned`() {
        repeat(15) {
            val output = seq.onFrame(input(angle = 0f), GuidanceCue.NONE, 1f)
            if (output.step != GuidanceStep.DONE) assertTrue(output.progress < 0.72f)
        }
    }

    @Test
    fun `风景场景无主体 - 广角端 ZOOM 短暂停留后跳过 直达 DONE`() {
        // 首帧：最小停留 700ms 未到 → 仍在 ZOOM（用户能感知"分析画面"）
        var out = seq.onFrame(input(angle = 0f, expectsSubject = false), GuidanceCue.NONE, 0.5f)
        assertEquals(GuidanceStep.ZOOM, out.step)

        // 700ms 后：无主体+非人像+广角端 → 跳过 ZOOM 进 LEVEL
        repeat(7) {
            out = seq.onFrame(input(angle = 0f, expectsSubject = false), GuidanceCue.NONE, 0.5f)
        }
        assertEquals(GuidanceStep.LEVEL, out.step)

        // 水平稳定 6 帧 + 停留 → SUBJECT；不期望主体 → DONE
        repeat(20) {
            out = seq.onFrame(input(angle = 0f, expectsSubject = false), GuidanceCue.NONE, 0.5f)
        }
        assertEquals(GuidanceStep.DONE, out.step)
    }

    @Test
    fun `非人像模板但画面有人脸 - 也会自动变焦`() {
        // 风景模板（expectsSubject=false）但检测到人脸占高 0.21 → 仍应触发变焦 ~2x
        var zoomTarget: Float? = null
        repeat(9) {
            val out = seq.onFrame(
                input(angle = 0f, subjectFrac = 0.21f, hasSubject = true, expectsSubject = false, zoom = 1.0f),
                GuidanceCue.NONE, 0.4f
            )
            if (out.zoomTarget != null) zoomTarget = out.zoomTarget
        }
        assertNotNull("画面有人脸时即使非人像模板也应自动变焦", zoomTarget)
        assertEquals(1.25f, zoomTarget!!, 0.05f)
    }

    @Test
    fun `用户已拉远且画面无人 - 尊重用户焦段`() {
        // 无真实主体时不能猜测，更不能强制覆盖用户手动变焦。
        var zoomTarget: Float? = null
        repeat(11) {
            val out = seq.onFrame(
                input(angle = 0f, hasSubject = false, expectsSubject = false, zoom = 2.5f),
                GuidanceCue.NONE, 0.4f
            )
            if (out.zoomTarget != null) zoomTarget = out.zoomTarget
        }
        assertNull(zoomTarget)
    }

    @Test
    fun `人像场景 - 主体偏小触发放大变焦`() {
        // 人脸占高 0.21，保守策略单次最多只放大 25%。
        var zoomTarget: Float? = null
        var out: GuidanceStepSequencer.Output
        repeat(9) {
            out = seq.onFrame(
                input(angle = 0f, subjectFrac = 0.21f, hasSubject = true, expectsSubject = true, zoom = 1.0f),
                GuidanceCue.NONE, 0.4f
            )
            if (out.zoomTarget != null) zoomTarget = out.zoomTarget
        }
        assertNotNull("主体占高 0.21 应触发自动变焦", zoomTarget)
        assertEquals(1.25f, zoomTarget!!, 0.05f)
    }

    @Test
    fun `变焦目标被夹在硬件上限与 AUTO_ZOOM_CAP`() {
        // 主体极小也只能单次放大 25%，不会突然裁脸。
        var zoomTarget: Float? = null
        repeat(9) {
            val out = seq.onFrame(
                input(angle = 0f, subjectFrac = 0.1f, hasSubject = true, expectsSubject = true, zoom = 1.0f, maxZoom = 8.0f),
                GuidanceCue.NONE, 0.4f
            )
            if (out.zoomTarget != null) zoomTarget = out.zoomTarget
        }
        assertEquals(1.25f, zoomTarget!!, 0.01f)
    }

    @Test
    fun `变焦到位并稳定后进入 LEVEL`() {
        var zoomTarget: Float? = null
        repeat(9) {
            val out = seq.onFrame(
                input(angle = 0f, subjectFrac = 0.21f, hasSubject = true, expectsSubject = true, zoom = 1.0f),
                GuidanceCue.NONE, 0.4f
            )
            if (out.zoomTarget != null) zoomTarget = out.zoomTarget
        }
        assertNotNull(zoomTarget)

        // UI 把倍率打到目标 → 回调 → 稳定 400ms
        seq.notifyZoomApplied(now)
        var out = seq.onFrame(
            input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true, zoom = zoomTarget!!, dtMs = 500),
            GuidanceCue.NONE, 0.6f
        )
        assertEquals(GuidanceStep.LEVEL, out.step)
    }

    @Test
    fun `用户手动变焦取消自动变焦`() {
        repeat(3) {
            seq.onFrame(
                input(angle = 0f, subjectFrac = 0.21f, hasSubject = true, expectsSubject = true),
                GuidanceCue.NONE, 0.4f
            )
        }
        seq.cancelAutoZoom()
        // 取消后：仍需决策完成（采样窗）才前进，但绝不再发 zoomTarget
        var out: GuidanceStepSequencer.Output
        repeat(10) {
            out = seq.onFrame(
                input(angle = 0f, subjectFrac = 0.21f, hasSubject = true, expectsSubject = true),
                GuidanceCue.NONE, 0.4f
            )
            assertNull(out.zoomTarget)
        }
        val final = seq.onFrame(
            input(angle = 0f, subjectFrac = 0.21f, hasSubject = true, expectsSubject = true),
            GuidanceCue.NONE, 0.4f
        )
        assertTrue(final.step == GuidanceStep.LEVEL || final.step == GuidanceStep.SUBJECT || final.step == GuidanceStep.DONE)
    }

    // ── LEVEL 指令过滤 ────────────────────────────────────────

    @Test
    fun `LEVEL 步只放行 ROLL 指令`() {
        // 快速通过 ZOOM（非人像无主体，900ms 窗口）
        repeat(10) { seq.onFrame(input(angle = 15f), GuidanceCue.NONE, 0.5f) }
        // 现在应在 LEVEL，倾斜 15°（> 12° 阈值）
        val out = seq.onFrame(input(angle = 15f), GuidanceCue.MOVE_LEFT, 0.5f)
        assertEquals(GuidanceStep.LEVEL, out.step)
        // 原始 cue 是 MOVE_LEFT，LEVEL 步应按角度改发 ROLL
        assertEquals(GuidanceCue.ROLL_COUNTERCLOCKWISE, out.cue)
    }

    @Test
    fun `LEVEL 步负角度发顺时针指令`() {
        repeat(10) { seq.onFrame(input(angle = -15f), GuidanceCue.NONE, 0.5f) }
        val out = seq.onFrame(input(angle = -15f), GuidanceCue.MOVE_UP, 0.5f)
        assertEquals(GuidanceCue.ROLL_CLOCKWISE, out.cue)
    }

    @Test
    fun `LEVEL 无传感器信号 30 帧后跳过`() {
        repeat(10) { seq.onFrame(input(angle = null), GuidanceCue.NONE, 0.5f) }
        var step = seq.step
        repeat(35) {
            seq.onFrame(input(angle = null), GuidanceCue.NONE, 0.5f)
            step = seq.step
        }
        assertTrue(step == GuidanceStep.SUBJECT || step == GuidanceStep.DONE)
    }

    // ── SUBJECT 指令过滤与推进 ────────────────────────────────

    private fun driveToSubjectStep() {
        // 人像场景、端平、主体已在场 → ZOOM 窗过后 → LEVEL 6 帧达标 → SUBJECT
        repeat(10) {
            seq.onFrame(input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true), GuidanceCue.NONE, 0.5f)
        }
        repeat(8) {
            seq.onFrame(input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true), GuidanceCue.NONE, 0.5f)
        }
    }

    @Test
    fun `SUBJECT 步只放行移动类指令`() {
        driveToSubjectStep()
        val out = seq.onFrame(
            input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true),
            GuidanceCue.MOVE_LEFT, 0.6f
        )
        assertEquals(GuidanceStep.SUBJECT, out.step)
        assertEquals(GuidanceCue.MOVE_LEFT, out.cue)
    }

    @Test
    fun `SUBJECT 步 ROLL 指令被过滤为 NONE`() {
        driveToSubjectStep()
        val out = seq.onFrame(
            input(angle = 1f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true),
            GuidanceCue.ROLL_CLOCKWISE, 0.6f
        )
        assertEquals(GuidanceStep.SUBJECT, out.step)
        assertEquals(GuidanceCue.NONE, out.cue)
    }

    @Test
    fun `subject adjustment returns to level when reframing tilts the phone`() {
        driveToSubjectStep()
        val out = seq.onFrame(input(angle = 14f, hasSubject = true, expectsSubject = true),
            GuidanceCue.ROLL_COUNTERCLOCKWISE, .5f)
        assertEquals(GuidanceStep.LEVEL, out.step)
        assertEquals(GuidanceCue.ROLL_COUNTERCLOCKWISE, out.cue)
        assertTrue(out.progress < .72f)
    }

    @Test
    fun `SUBJECT 方向抖动需要连续三帧才切换`() {
        driveToSubjectStep()
        val first = seq.onFrame(
            input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true),
            GuidanceCue.MOVE_LEFT, 0.6f
        )
        assertEquals(GuidanceCue.MOVE_LEFT, first.cue)

        val oneFrameBack = seq.onFrame(
            input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true),
            GuidanceCue.MOVE_RIGHT, 0.6f
        )
        assertEquals("单帧反向不能立刻改箭头", GuidanceCue.MOVE_LEFT, oneFrameBack.cue)

        var last = oneFrameBack
        repeat(3) {
            last = seq.onFrame(
                input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true),
                GuidanceCue.MOVE_RIGHT, 0.6f
            )
        }
        assertEquals("反向持续三帧后才切换", GuidanceCue.MOVE_RIGHT, last.cue)
    }

    @Test
    fun `SUBJECT 轻微回摆不立刻退回 LEVEL`() {
        driveToSubjectStep()
        repeat(3) {
            val out = seq.onFrame(
                input(angle = 10f, hasSubject = true, expectsSubject = true),
                GuidanceCue.ROLL_COUNTERCLOCKWISE, 0.5f
            )
            assertEquals("9°–14° 的短暂回摆不应打断主体步骤", GuidanceStep.SUBJECT, out.step)
        }
        val out = seq.onFrame(
            input(angle = 10f, hasSubject = true, expectsSubject = true),
            GuidanceCue.ROLL_COUNTERCLOCKWISE, 0.5f
        )
        assertEquals("持续回摆四帧才回到 LEVEL", GuidanceStep.LEVEL, out.step)
    }

    @Test
    fun `SUBJECT 对齐 6 帧后进入 DONE`() {
        driveToSubjectStep()
        var step = seq.step
        repeat(8) {
            seq.onFrame(
                input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true),
                GuidanceCue.SUBJECT_ALIGNED, 0.97f
            )
            step = seq.step
        }
        assertEquals(GuidanceStep.DONE, step)
    }

    // ── DONE 守护与回退 ───────────────────────────────────────

    private fun driveToDone() {
        driveToSubjectStep()
        repeat(8) {
            seq.onFrame(
                input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true),
                GuidanceCue.SUBJECT_ALIGNED, 0.97f
            )
        }
    }

    @Test
    fun `DONE 放行全部指令`() {
        driveToDone()
        val out = seq.onFrame(
            input(angle = 0f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true),
            GuidanceCue.MOVE_RIGHT, 0.8f
        )
        assertEquals(GuidanceStep.DONE, out.step)
        assertEquals(GuidanceCue.MOVE_RIGHT, out.cue)
    }

    @Test
    fun `DONE 水平被打乱后回退 LEVEL`() {
        driveToDone()
        var step = seq.step
        repeat(5) {
            seq.onFrame(
                input(angle = 18f, subjectFrac = 0.42f, hasSubject = true, expectsSubject = true),  // v3: >16°
                GuidanceCue.ROLL_COUNTERCLOCKWISE, 0.4f
            )
            step = seq.step
        }
        assertEquals(GuidanceStep.LEVEL, step)
    }

    @Test
    fun `done upside down observations revoke progress immediately and regress after consecutive frames`() {
        driveToDone()
        repeat(GuidanceStepSequencer.DONE_REGRESS_FRAMES) { frame ->
            val out = seq.onFrame(input(angle = 0f, hasSubject = true, expectsSubject = true)
                .copy(isUpright = false), GuidanceCue.SUBJECT_ALIGNED, 1f)
            assertEquals(GuidanceCue.DEVICE_UPSIDE_DOWN, out.cue)
            assertEquals(0f, out.progress, 0f)
            assertTrue(out.hint.contains("倒置"))
            if (frame == GuidanceStepSequencer.DONE_REGRESS_FRAMES - 1) {
                assertEquals(GuidanceStep.LEVEL, out.step)
            }
        }
    }

    @Test
    fun `invalid zoom range never crashes or emits a guessed zoom`() {
        for ((minimum, maximum) in listOf(2f to 4f, 1f to .8f, Float.NaN to 4f)) {
            seq.reset(now)
            repeat(10) {
                val out = seq.onFrame(input(subjectFrac = .1f, hasSubject = true,
                    expectsSubject = true, minZoom = minimum, maxZoom = maximum),
                    GuidanceCue.SUBJECT_TARGET, .2f)
                assertNull(out.zoomTarget)
            }
        }
    }

    @Test
    fun `DONE 主体丢失后回退 SUBJECT`() {
        driveToDone()
        var step = seq.step
        repeat(12) {
            seq.onFrame(
                input(angle = 0f, subjectFrac = null, hasSubject = false, expectsSubject = true),
                GuidanceCue.SUBJECT_TARGET, 0.3f
            )
            step = seq.step
        }
        assertEquals(GuidanceStep.SUBJECT, step)
    }

    @Test
    fun `ZOOM 步超时兜底前进`() {
        // 一直不给决策条件满足的机会？采样窗 900ms 必触发决策；
        // 但若 zoomTarget 发出后永远不到位，4.5s 超时也必须前进。
        var zoomTarget: Float? = null
        repeat(9) {
            val out = seq.onFrame(
                input(angle = 0f, subjectFrac = 0.21f, hasSubject = true, expectsSubject = true, zoom = 1.0f),
                GuidanceCue.NONE, 0.3f
            )
            if (out.zoomTarget != null) zoomTarget = out.zoomTarget
        }
        assertNotNull(zoomTarget)
        // 不回调 notifyZoomApplied，快进到超时
        var step = seq.step
        repeat(50) {
            seq.onFrame(
                input(angle = 0f, subjectFrac = 0.21f, hasSubject = true, expectsSubject = true, zoom = 1.0f),
                GuidanceCue.NONE, 0.3f
            )
            step = seq.step
        }
        assertTrue(step != GuidanceStep.ZOOM)
    }

    @Test
    fun `提示文案随步骤变化`() {
        // 人像模板 → 首帧在 ZOOM，提示分析/变焦
        val zoomOut = seq.onFrame(input(expectsSubject = true), GuidanceCue.NONE, 0.5f)
        assertEquals(GuidanceStep.ZOOM, zoomOut.step)
        assertTrue(zoomOut.hint.contains("分析") || zoomOut.hint.contains("变焦"))
        // 风景无主体广角端 → 停留后进 LEVEL，提示端平
        seq.reset(now)
        var out: GuidanceStepSequencer.Output? = null
        repeat(8) {
            out = seq.onFrame(input(angle = 3f, expectsSubject = false), GuidanceCue.NONE, 0.5f)
        }
        assertEquals(GuidanceStep.LEVEL, out!!.step)
        assertTrue(out!!.hint.contains("端平") || out!!.hint.contains("校准"))
    }
}
