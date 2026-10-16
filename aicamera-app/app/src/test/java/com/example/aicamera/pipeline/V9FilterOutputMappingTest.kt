package com.example.aicamera.pipeline

import com.example.aicamera.filter.FilterCandidate
import com.example.aicamera.filter.FilterIds
import com.example.aicamera.filter.FilterReason
import com.example.aicamera.filter.FilterRecommendation
import com.example.aicamera.guidance.GuidanceCue
import com.example.aicamera.zoom.AutoZoomReason
import com.example.aicamera.zoom.AutoZoomRecommendation
import com.example.aicamera.zoom.AutoZoomScene
import com.example.aicamera.zoom.ZoomFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class V9FilterOutputMappingTest {
    @Test
    fun `fake inference output does not fabricate filter recommendation`() {
        val fake = FakeInference().predictFromLuminance(128f)
        assertNull(fake.filterRecommendation)
        assertNull(fake.autoZoomRecommendation)
        assertEquals(0f, fake.confidence, 0f)
        assertEquals(0f, fake.compositionProgress, 0f)
    }

    @Test
    fun `real V9 adapter attaches recommendation without changing guidance`() {
        val base = FakeInferenceOutput(
            sceneType = SceneType.NORMAL,
            compositionStrategy = CompositionStrategy.RULE_OF_THIRDS,
            confidence = 0.8f,
            luminance = 128f,
            guideLines = emptyList(),
            guidanceCue = GuidanceCue.NONE,
            compositionProgress = 0.7f
        )
        val recommendation = FilterRecommendation(
            selectedFilterId = FilterIds.LANDSCAPE_CLEAR,
            confidence = 0.75f,
            candidates = listOf(
                FilterCandidate(FilterIds.LANDSCAPE_CLEAR, 0.8f, listOf(FilterReason.NATURAL_LANDSCAPE))
            ),
            sourceTemplateId = "template-1",
            isStable = true,
            shouldAutoPreview = true
        )

        val mapped = base.withV9FilterRecommendation(recommendation)
        assertSame(recommendation, mapped.filterRecommendation)
        assertEquals(base.guideLines, mapped.guideLines)
        assertEquals(base.guidanceCue, mapped.guidanceCue)
    }

    @Test
    fun `real V9 adapter attaches auto zoom recommendation without changing guidance`() {
        val base = FakeInferenceOutput(
            sceneType = SceneType.NORMAL,
            compositionStrategy = CompositionStrategy.RULE_OF_THIRDS,
            confidence = 0.8f,
            luminance = 128f,
            guideLines = emptyList(),
            guidanceCue = GuidanceCue.NONE
        )
        val recommendation = AutoZoomRecommendation(
            scene = AutoZoomScene.PORTRAIT,
            framing = ZoomFraming.CLOSE,
            suggestedZoomRatio = 1.6f,
            confidence = 0.8f,
            sourceTemplateId = "template-1",
            isStable = true,
            shouldAutoZoom = true,
            reasons = listOf(AutoZoomReason.PORTRAIT)
        )

        val mapped = base.withV9AutoZoomRecommendation(recommendation)
        assertSame(recommendation, mapped.autoZoomRecommendation)
        assertEquals(base.guideLines, mapped.guideLines)
        assertEquals(base.guidanceCue, mapped.guidanceCue)
    }

    @Test
    fun `legacy metadata construction gets safe lighting defaults`() {
        val meta = TemplateMeta(
            "id", "portrait", "closeup_portrait", "centered", "center",
            "not_visible", false, "medium", "soft", "front", "eye_level",
            "medium", "portrait", "summary"
        )
        assertEquals("medium", meta.lightingContrast)
        assertEquals("unknown", meta.timeOfDay)
    }
}
