package com.example.aicamera.pipeline

import org.junit.Assert.*
import org.junit.Test

class ModelOutputSamplerTest {
    @Test
    fun `thirty camera frames per second run only two model samples`() {
        val sampler = ModelOutputSampler()
        var calls = 0
        val results = (0 until 30).map { frame ->
            sampler.sample(frame * 1000L / 30, 500) { ++calls }
        }
        assertEquals(2, calls)
        assertEquals(List(15) { 1 } + List(15) { 2 }, results)
        assertEquals(3, sampler.sample(1000, 500) { ++calls })
    }

    @Test
    fun `session reset never reuses the previous camera output`() {
        val sampler = ModelOutputSampler()
        sampler.sample(1000, 500) { "back camera" }
        sampler.reset()
        assertEquals("front camera", sampler.sample(1001, 500) { "front camera" })
    }

    @Test
    fun `null and failed predictions do not revive stale guidance`() {
        val sampler = ModelOutputSampler()
        sampler.sample(0, 500) { "old scene" }
        assertNull(sampler.sample(500, 500) { null })
        assertEquals("new scene", sampler.sample(501, 500) { "new scene" })
        try {
            sampler.sample(1001, 500) { error("model failed") }
            fail("Expected prediction failure")
        } catch (_: IllegalStateException) { }
        assertEquals("recovered", sampler.sample(1002, 500) { "recovered" })
    }

    @Test
    fun `unthrottled inference and clock rollback sample immediately`() {
        val sampler = ModelOutputSampler()
        assertEquals(1, sampler.sample(100, 0) { 1 })
        assertEquals(2, sampler.sample(100, 0) { 2 })
        assertEquals(3, sampler.sample(99, 500) { 3 })
    }
}
