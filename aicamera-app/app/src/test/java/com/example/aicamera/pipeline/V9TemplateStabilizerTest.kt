package com.example.aicamera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V9TemplateStabilizerTest {
    private val stabilizer = V9TemplateStabilizer()
    private fun hit(id: String, score: Float) = SearchHit(id, score, score)

    @Test
    fun `challenger must lead for consecutive confident samples`() {
        assertEquals("a", stabilizer.select(listOf(hit("a", 0.9f), hit("b", 0.8f))).templateId)
        val challenge = listOf(hit("b", 0.9f), hit("a", 0.8f))
        assertEquals("a", stabilizer.select(challenge).templateId)
        assertEquals("b", stabilizer.select(challenge).templateId)
    }

    @Test
    fun `ambiguous sample interrupts a pending switch`() {
        stabilizer.select(listOf(hit("a", 0.9f)))
        val challenge = listOf(hit("b", 0.9f), hit("a", 0.8f))
        stabilizer.select(challenge)
        assertEquals("a", stabilizer.select(listOf(hit("b", 0.805f), hit("a", 0.8f))).templateId)
        assertEquals("a", stabilizer.select(challenge).templateId)
        assertEquals("b", stabilizer.select(challenge).templateId)
    }

    @Test
    fun `removed incumbent is replaced and new incumbent actually stays stable`() {
        stabilizer.select(listOf(hit("a", 0.9f)))
        assertEquals("b", stabilizer.select(listOf(hit("b", 0.805f), hit("c", 0.8f))).templateId)
        assertEquals("b", stabilizer.select(listOf(hit("c", 0.805f), hit("b", 0.8f))).templateId)
    }

    @Test
    fun `reset discards old scene and pending challenge`() {
        stabilizer.select(listOf(hit("a", 0.9f)))
        stabilizer.reset()
        assertEquals("b", stabilizer.select(listOf(hit("b", 0.805f), hit("a", 0.8f))).templateId)
    }

    @Test
    fun `metadata without a subject does not invent a target`() {
        assertNull(v9SubjectTarget("none"))
        assertNull(v9SubjectTarget("unknown"))
        assertEquals(0.5f to 0.5f, v9SubjectTarget("center"))
        assertEquals(2f / 3f to 0.5f, v9SubjectTarget("right_third"))
    }
}
