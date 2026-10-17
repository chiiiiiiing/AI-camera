package com.example.aicamera.viewmodel

import com.example.aicamera.guidance.SceneAnalysisObservation
import com.example.aicamera.guidance.SceneFamily
import com.example.aicamera.guidance.SceneLayout
import org.junit.Assert.*
import org.junit.Test

class SceneRecognitionStateTest {
    private fun scene(time: Long, family: SceneFamily = SceneFamily.PORTRAIT) =
        SceneAnalysisObservation(time, time, 7, family,
            SceneLayout(List(9) { .5f }, List(9) { .1f }, .5f, 0f, 0f))

    @Test fun `old queued frame cannot restore label even when its inference starts after invalidation`() {
        val state = SceneRecognitionState()
        val oldFrame = state.frameGeneration()
        state.invalidate(1000)
        state.accept(oldFrame, scene(1100), false, 1200)
        assertNull(state.observation.value)
        state.accept(state.frameGeneration(), scene(1200), false, 1250)
        assertEquals(1200L, state.observation.value!!.sampledAtMs)
    }

    @Test fun `old completion and old empty fallback cannot overwrite a new camera scene`() {
        val state = SceneRecognitionState()
        val oldFrame = state.frameGeneration()
        state.invalidate(1000, expectedIsFrontCamera = true)
        val current = scene(1100)
        state.accept(state.frameGeneration(), current, true, 1200)
        state.accept(oldFrame, scene(1200, SceneFamily.STILL_LIFE), false, 1300)
        state.accept(oldFrame, null, false, 1400)
        assertEquals(current, state.observation.value)
    }

    @Test fun `buffered frame from wrong lens is rejected even with a current generation`() {
        val state = SceneRecognitionState()
        state.invalidate(1000, expectedIsFrontCamera = true)
        state.accept(state.frameGeneration(), scene(1100), false, 1200)
        assertNull(state.observation.value)
        state.accept(state.frameGeneration(), scene(1200), true, 1300)
        assertNotNull(state.observation.value)
        state.invalidate(1400) // Pausing keeps the expected lens.
        state.accept(state.frameGeneration(), scene(1500), false, 1600)
        assertNull(state.observation.value)
    }

    @Test fun `pre-invalidation sample and out of order samples cannot reinstate older modes`() {
        val state = SceneRecognitionState()
        state.invalidate(1000)
        state.accept(state.frameGeneration(), scene(1000), false, 1100)
        assertNull(state.observation.value)
        val current = scene(1300, SceneFamily.NATURAL_LANDSCAPE)
        state.accept(state.frameGeneration(), current, false, 1350)
        state.accept(state.frameGeneration(), scene(1200), false, 1400)
        assertEquals(current, state.observation.value)
    }

    @Test fun `repeated cached evidence cannot renew the label and expired or future samples are rejected`() {
        val state = SceneRecognitionState()
        val cached = scene(1000)
        state.accept(state.frameGeneration(), cached, false, 2000)
        state.accept(state.frameGeneration(), cached, false, 2499)
        assertEquals(1000L, state.observation.value!!.sampledAtMs)
        assertFalse(state.observation.value!!.isFresh(2501))
        state.accept(state.frameGeneration(), cached, false, 2501)
        assertNull(state.observation.value)
        state.accept(state.frameGeneration(), scene(4000), false, 3000)
        assertNull(state.observation.value)
        state.accept(state.frameGeneration(), scene(3001), false, 3002)
        assertNotNull(state.observation.value)
    }

    @Test fun `unknown observations and inference fallback withdraw a previously recognized mode`() {
        val state = SceneRecognitionState()
        state.accept(state.frameGeneration(), scene(1000), false, 1100)
        state.accept(state.frameGeneration(), scene(1200, SceneFamily.UNKNOWN), false, 1300)
        assertEquals(SceneFamily.UNKNOWN, state.observation.value!!.family)
        state.accept(state.frameGeneration(), null, false, 1400)
        assertNull(state.observation.value)
    }
}
