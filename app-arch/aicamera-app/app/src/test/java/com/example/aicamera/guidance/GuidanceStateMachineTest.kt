package com.example.aicamera.guidance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GuidanceStateMachine 滞回行为单元测试。
 *
 * 关键参数（来自队友设计）：alignedThreshold=0.92，framesToAlign=3，
 * framesToExitAligned=2，nearThreshold=0.72。
 */
class GuidanceStateMachineTest {

    private val machine = GuidanceStateMachine()

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

        state = machine.reduceFrame(state, GuidanceCue.NONE, 0.5f)
        state = machine.reduceFrame(state, GuidanceCue.NONE, 0.5f)
        assertEquals(GuidancePhase.NEUTRAL, state.phase)
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
