package com.example.aicamera.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoZoomRecommendationStabilizerTest {
    @Test
    fun `new target must lead twice before switching`() {
        val stabilizer = AutoZoomRecommendationStabilizer()
        assertEquals(AutoZoomScene.PORTRAIT, stabilizer.stabilize(rec(AutoZoomScene.PORTRAIT)).scene)
        assertEquals(AutoZoomScene.PORTRAIT, stabilizer.stabilize(rec(AutoZoomScene.FOOD)).scene)
        assertEquals(AutoZoomScene.FOOD, stabilizer.stabilize(rec(AutoZoomScene.FOOD)).scene)
    }

    @Test
    fun `low confidence keeps incumbent`() {
        val stabilizer = AutoZoomRecommendationStabilizer()
        stabilizer.stabilize(rec(AutoZoomScene.PORTRAIT))
        repeat(3) {
            assertEquals(AutoZoomScene.PORTRAIT, stabilizer.stabilize(rec(AutoZoomScene.FOOD, confidence = 0.40f)).scene)
        }
    }

    @Test
    fun `safe disable applies immediately and reset clears incumbent`() {
        val stabilizer = AutoZoomRecommendationStabilizer()
        stabilizer.stabilize(rec(AutoZoomScene.PORTRAIT))
        val disabled = stabilizer.stabilize(AutoZoomRecommendations.disabled(AutoZoomReason.LOW_LIGHT_SAFETY))
        assertFalse(disabled.shouldAutoZoom)
        assertTrue(disabled.isStable)

        stabilizer.stabilize(rec(AutoZoomScene.PORTRAIT))
        stabilizer.reset()
        assertEquals(AutoZoomScene.FOOD, stabilizer.stabilize(rec(AutoZoomScene.FOOD)).scene)
    }

    private fun rec(scene: AutoZoomScene, confidence: Float = 0.8f): AutoZoomRecommendation {
        val (framing, ratio, reason) = when (scene) {
            AutoZoomScene.PORTRAIT -> Triple(ZoomFraming.CLOSE, 1.6f, AutoZoomReason.PORTRAIT)
            AutoZoomScene.FOOD -> Triple(ZoomFraming.CLOSE, 1.4f, AutoZoomReason.FOOD)
            else -> Triple(ZoomFraming.WIDE, 1f, AutoZoomReason.FALLBACK)
        }
        return AutoZoomRecommendation(
            scene = scene,
            framing = framing,
            suggestedZoomRatio = ratio,
            confidence = confidence,
            sourceTemplateId = "source",
            isStable = false,
            shouldAutoZoom = true,
            reasons = listOf(reason)
        )
    }
}
