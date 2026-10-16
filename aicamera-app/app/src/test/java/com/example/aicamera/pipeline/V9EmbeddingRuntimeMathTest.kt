package com.example.aicamera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class V9EmbeddingRuntimeMathTest {
    @Test
    fun `normalization produces a finite unit vector`() {
        val result = V9EmbeddingRuntime.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, result[0], 0.0001f)
        assertEquals(0.8f, result[1], 0.0001f)
        assertEquals(1.0, sqrt(result.sumOf { (it * it).toDouble() }), 0.0001)
    }

    @Test
    fun `zero and non finite vectors become safe zero vectors`() {
        assertArrayEquals(floatArrayOf(0f, 0f), V9EmbeddingRuntime.l2Normalize(floatArrayOf(0f, 0f)), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), V9EmbeddingRuntime.l2Normalize(floatArrayOf(Float.NaN, 1f)), 0f)
        assertFalse(V9EmbeddingRuntime.isUsableEmbedding(FloatArray(V9EmbeddingRuntime.EMBEDDING_DIM)))
        assertFalse(V9EmbeddingRuntime.isUsableEmbedding(FloatArray(V9EmbeddingRuntime.EMBEDDING_DIM) { Float.NaN }))
    }

    @Test
    fun `cosine rejects malformed vectors instead of throwing`() {
        assertEquals(Float.NEGATIVE_INFINITY, V9EmbeddingRuntime.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 0f)))
        assertEquals(Float.NEGATIVE_INFINITY, V9EmbeddingRuntime.cosineSimilarity(floatArrayOf(Float.NaN), floatArrayOf(1f)))
        assertTrue(V9EmbeddingRuntime.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)).isFinite())
    }

    @Test
    fun `normalization does not overflow for large finite components`() {
        val result = V9EmbeddingRuntime.l2Normalize(floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE))
        assertEquals(1f / sqrt(2f), result[0], 0.0001f)
        assertEquals(result[0], result[1], 0f)
        assertTrue(V9EmbeddingRuntime.isUsableEmbedding(FloatArray(512) { Float.MAX_VALUE }))
    }

    @Test
    fun `model signatures permit dynamic batch but reject mismatched spatial dimensions`() {
        val expected = longArrayOf(1, 3, 224, 224)
        assertTrue(V9EmbeddingRuntime.matchesModelShape(longArrayOf(-1, 3, 224, 224), expected))
        assertTrue(V9EmbeddingRuntime.matchesModelShape(expected, expected))
        assertFalse(V9EmbeddingRuntime.matchesModelShape(longArrayOf(1, 224, 224, 3), expected))
        assertFalse(V9EmbeddingRuntime.matchesModelShape(longArrayOf(1, 3, -1, -1), expected))
        assertFalse(V9EmbeddingRuntime.matchesModelShape(longArrayOf(2, 3, 224, 224), expected))
    }
}
