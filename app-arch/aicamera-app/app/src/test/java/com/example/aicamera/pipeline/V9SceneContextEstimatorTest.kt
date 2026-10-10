package com.example.aicamera.pipeline

import com.example.aicamera.filter.FrameColorStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V9SceneContextEstimatorTest {
    private val estimator = V9SceneContextEstimator()
    private val mixedLight = FrameColorStats(0.55f, 0.14f, 0.4f, 0f, 0f, 0f)

    @Test
    fun `candidate consensus can overrule a different top one`() {
        val context = estimator.estimate(
            listOf(
                hit("food", 0.90f, "still_life", "food_still_life", "centered_subject"),
                hit("p1", 0.86f, "portrait", "closeup_portrait", "rule_of_thirds"),
                hit("p2", 0.83f, "portrait", "closeup_portrait", "rule_of_thirds"),
                hit("p3", 0.80f, "portrait", "closeup_portrait", "rule_of_thirds"),
                hit("p4", 0.77f, "portrait", "closeup_portrait", "rule_of_thirds")
            ),
            mixedLight
        )
        assertEquals("portrait", context.photoType)
        assertEquals("closeup_portrait", context.sceneCategory)
        assertEquals("rule_of_thirds", context.preferredComposition)
        assertEquals("mixed", context.lightingQuality)
    }

    @Test
    fun `ambiguous scene evidence remains unset while lighting stays usable`() {
        val context = estimator.estimate(
            listOf(
                hit("portrait", 0.90f, "portrait", "closeup_portrait", "centered_subject"),
                hit("food", 0.89f, "still_life", "food_still_life", "rule_of_thirds")
            ),
            mixedLight.copy(luminanceStdDev = 0.04f)
        )
        assertNull(context.photoType)
        assertNull(context.sceneCategory)
        assertEquals("soft", context.lightingQuality)
        assertEquals(0f, context.sceneConfidence, 0f)
    }

    @Test
    fun `unknown metadata cannot become scene evidence`() {
        val context = estimator.estimate(
            listOf(hit("unknown", 0.9f, "unknown", "unknown", "unknown")), mixedLight
        )
        assertNull(context.photoType)
        assertNull(context.sceneCategory)
        assertNull(context.preferredComposition)
        assertEquals(0f, context.sceneConfidence, 0f)
    }

    @Test
    fun `skipping missing metadata does not promote a lower rank vote`() {
        val context = V9SceneContextEstimator(V9SceneContextConfig(rankWeights = listOf(1f, 0.01f, 0.01f)))
            .estimate(listOf(
                SearchHit("missing", 0.9f),
                hit("portrait", 0.9f, "portrait", "closeup_portrait", "centered_subject"),
                hit("food", 0.9f, "still_life", "food_still_life", "rule_of_thirds")
            ), mixedLight)
        assertNull(context.photoType)
        assertNull(context.sceneCategory)
    }

    private fun hit(
        id: String,
        score: Float,
        photoType: String,
        scene: String,
        composition: String
    ) = SearchHit(
        templateId = id,
        cosine = score,
        metadata = TemplateMeta(
            templateId = id,
            photoType = photoType,
            sceneCategory = scene,
            compositionType = composition,
            subjectPosition = "center",
            horizonPosition = "not_visible",
            leadingLines = false,
            negativeSpace = "medium",
            lightingQuality = "mixed",
            lightingDirection = "front",
            cameraAngle = "eye_level",
            cameraDistance = "medium",
            cameraOrientation = "portrait",
            visualSummary = ""
        )
    )
}
