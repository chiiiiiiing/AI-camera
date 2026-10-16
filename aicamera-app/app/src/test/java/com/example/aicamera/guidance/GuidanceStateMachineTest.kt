package com.example.aicamera.guidance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GuidanceStateMachine 滞回行为单元测试。
 *
 * 关键参数（v2 已放宽以降低用户操作难度）：
 * alignedThreshold=0.72，nearThreshold=0.50，framesToAlign=3，framesToExitAligned=2。
 */
class GuidanceStateMachineTest {

    private val machine = GuidanceStateMachine()

    @Test
    fun `corrective cues cannot align even above the relaxed progress threshold`() {
        val corrections = GuidanceCue.values().filter {
            it != GuidanceCue.NONE && it != GuidanceCue.SUBJECT_ALIGNED
        }
        for (cue in corrections) {
            var state = GuidanceState()
            repeat(5) { state = machine.reduceFrame(state, cue, 1f) }
            assertEquals("Corrective cue $cue must remain visible", cue, state.cue)
            assertEquals(GuidancePhase.NEAR, state.phase)
            assertEquals(0, state.stableFrameCount)
        }
    }

    @Test
    fun `corrective cues exit aligned through the existing hysteresis`() {
        var state = GuidanceState()
        repeat(3) { state = machine.reduceFrame(state, GuidanceCue.SUBJECT_ALIGNED, 0.95f) }
        state = machine.reduceFrame(state, GuidanceCue.ROLL_CLOCKWISE, 0.9f)
        assertEquals(GuidancePhase.ALIGNED, state.phase)
        state = machine.reduceFrame(state, GuidanceCue.ROLL_CLOCKWISE, 0.9f)
        assertEquals(GuidancePhase.NEAR, state.phase)
        assertEquals(GuidanceCue.ROLL_CLOCKWISE, state.cue)
        assertEquals(0, state.stableFrameCount)
    }

    @Test
    fun `three consecutive stable frames reach ALIGNED`() {
        var state = GuidanceState()
        state = machine.reduceFrame(state, GuidanceCue.NONE, 0.95f)
        assertEquals(GuidancePhase.NEAR, state.phase)
        assertEquals(1, state.stableFrameCount)

        state = machine.reduceFrame(state, GuidanceCue.NONE, 0.95f)
        assertEquals(GuidancePhase.NEAR, state.phase)
        assertEquals(2, state.stableFrameCount)

        state = machine.reduceFrame(state, GuidanceCue.NONE, 0.95f)
        assertEquals(GuidancePhase.ALIGNED, state.phase)
        assertEquals(GuidanceCue.NONE, state.cue)
    }

    @Test
    fun `one unstable frame does not exit ALIGNED (hysteresis)`() {
        var state = GuidanceState()
        repeat(3) { state = machine.reduceFrame(state, GuidanceCue.NONE, 0.95f) }
        assertEquals(GuidancePhase.ALIGNED, state.phase)

        // 第 1 帧不稳：保持 ALIGNED
        state = machine.reduceFrame(state, GuidanceCue.NONE, 0.5f)
        assertEquals(GuidancePhase.ALIGNED, state.phase)
        assertEquals(1, state.unstableFrameCount)
    }

    @Test
    fun `two consecutive unstable frames exit ALIGNED`() {
        var state = GuidanceState()
        repeat(3) { state = machine.reduceFrame(state, GuidanceCue.NONE, 0.95f) }
        assertEquals(GuidancePhase.ALIGNED, state.phase)

        // 0.5 >= nearThreshold(0.50) → exits ALIGNED into NEAR
        state = machine.reduceFrame(state, GuidanceCue.NONE, 0.5f)
        state = machine.reduceFrame(state, GuidanceCue.NONE, 0.5f)
        assertEquals(GuidancePhase.NEAR, state.phase)
    }

    @Test
    fun `low progress with NONE cue stays NEUTRAL`() {
        val state = machine.reduceFrame(GuidanceState(), GuidanceCue.NONE, 0.3f)
        assertEquals(GuidancePhase.NEUTRAL, state.phase)
    }

    @Test
    fun `low progress with movement cue enters ADJUST`() {
        val state = machine.reduceFrame(GuidanceState(), GuidanceCue.MOVE_LEFT, 0.3f)
        assertEquals(GuidancePhase.ADJUST, state.phase)
    }

    @Test
    fun `progress above nearThreshold enters NEAR`() {
        val state = machine.reduceFrame(GuidanceState(), GuidanceCue.MOVE_LEFT, 0.8f)
        assertEquals(GuidancePhase.NEAR, state.phase)
    }

    @Test
    fun `non-finite progress is clamped to zero`() {
        val state = machine.reduceFrame(GuidanceState(), GuidanceCue.NONE, Float.NaN)
        assertEquals(0f, state.progress)
        assertEquals(GuidancePhase.NEUTRAL, state.phase)
    }
}
