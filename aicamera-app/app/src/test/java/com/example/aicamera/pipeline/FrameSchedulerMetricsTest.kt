package com.example.aicamera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSchedulerMetricsTest {
    @Test
    fun `latency percentiles retain tail latency rather than reporting only the average`() {
        val latencies = listOf(10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 150L, 200L)
        assertEquals(14L, latencyPercentile(latencies, 0.5))
        assertEquals(150L, latencyPercentile(latencies, 0.9))
        assertEquals(0L, latencyPercentile(emptyList(), 0.9))
        assertEquals(80L, latencyPercentile(listOf(80L), 0.9))
    }

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
