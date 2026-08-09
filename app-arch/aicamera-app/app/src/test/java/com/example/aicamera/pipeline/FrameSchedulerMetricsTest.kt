package com.example.aicamera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSchedulerMetricsTest {
    @Test
    fun `effective fps uses the completed-frame time window`() {
        assertEquals(2f, effectiveFpsFromCompletionTimes(listOf(1_000L, 1_500L, 2_000L)), 0.001f)
    }

    @Test
    fun `effective fps is zero for insufficient or invalid time windows`() {
        assertEquals(0f, effectiveFpsFromCompletionTimes(emptyList()), 0f)
        assertEquals(0f, effectiveFpsFromCompletionTimes(listOf(1_000L)), 0f)
        assertEquals(0f, effectiveFpsFromCompletionTimes(listOf(2_000L, 1_000L)), 0f)
        assertEquals(0f, effectiveFpsFromCompletionTimes(listOf(1_000L, 1_000L)), 0f)
    }
}
