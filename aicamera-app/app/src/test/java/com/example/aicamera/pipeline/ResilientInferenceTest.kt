package com.example.aicamera.pipeline

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * ResilientInference 熔断器单测：连续异常/连续空输出 → 回退 Fake；
 * 熔断前每帧也兜底；熔断不可逆。
 */
class ResilientInferenceTest {

    @Test fun `successful unknown analysis does not permanently disable automatic recognition`() {
        var calls=0
        val primary=object:IInference {
            override val typeName="V9-ONNX"
            override fun predict(buffer:ByteBuffer, frame:PreprocessedFrame):Any {
                calls++
                return FakeInferenceOutput(SceneType.NORMAL,CompositionStrategy.ADAPTIVE,0f,128f,emptyList(),
                    analysisCompleted=true,sceneAnalysis=com.example.aicamera.guidance.SceneAnalysisObservation(
                        calls.toLong(),calls*500L,1,com.example.aicamera.guidance.SceneFamily.UNKNOWN,
                        com.example.aicamera.guidance.SceneLayout(List(9){.5f},List(9){.02f},.5f,0f,0f)))
            }
        }
        val wrapper=ResilientInference(primary)
        repeat(ResilientInference.EMPTY_BREAKER_THRESHOLD+5) {
            val out=wrapper.predict(ByteBuffer.allocate(0),frame()) as FakeInferenceOutput
            assertTrue(out.analysisCompleted)
            assertEquals(0f,out.confidence,0f)
            assertEquals(com.example.aicamera.guidance.SceneFamily.UNKNOWN,out.sceneAnalysis!!.family)
        }
        assertFalse(wrapper.isDegraded)
        assertEquals(ResilientInference.EMPTY_BREAKER_THRESHOLD+5,calls)
    }

    private fun frame(): PreprocessedFrame {
        val buf = ByteBuffer.allocateDirect(4 * 3 * 4) // 4 像素 RGB float
        repeat(4) {
            buf.putFloat(0.5f); buf.putFloat(0.5f); buf.putFloat(0.5f)
        }
        buf.rewind()
        return PreprocessedFrame(
            buffer = buf, width = 2, height = 2, channels = 3,
            rotation = 0, originalWidth = 2, originalHeight = 2,
            originalBitmap = null
        )
    }

    private class ThrowingInference : IInference {
        override val typeName = "V9-ONNX"
        override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any? {
            throw RuntimeException("native crash")
        }
    }

    private class EmptyInference : IInference {
        override val typeName = "V9-ONNX"
        override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any =
            FakeInferenceOutput(
                sceneType = SceneType.NORMAL,
                compositionStrategy = CompositionStrategy.RULE_OF_THIRDS,
                confidence = 0f, luminance = 128f,
                guideLines = emptyList()
            )
    }

    private class GoodInference : IInference {
        override val typeName = "V9-ONNX"
        override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any =
            FakeInferenceOutput(
                sceneType = SceneType.NORMAL,
                compositionStrategy = CompositionStrategy.RULE_OF_THIRDS,
                confidence = 0.9f, luminance = 128f,
                guideLines = listOf(FakeGuideLine("rule_of_thirds_grid", true, emptyMap()))
            )
    }

    @Test
    fun `连续异常达到阈值后熔断回退 Fake`() {
        var degradedReason: String? = null
        val r = ResilientInference(primary = ThrowingInference(), onDegraded = { degradedReason = it })

        // 阈值前每帧也由 Fake 兜底（指引不中断）
        repeat(ResilientInference.ERROR_BREAKER_THRESHOLD - 1) {
            val out = r.predict(ByteBuffer.allocate(0).also { it.rewind() }, frame())
            assertTrue(out is FakeInferenceOutput)
            assertFalse(r.isDegraded)
        }
        // 达到阈值 → 熔断
        r.predict(ByteBuffer.allocate(0), frame())
        assertTrue(r.isDegraded)
        assertNotNull(degradedReason)
        assertEquals("FakeInference", r.typeName)
    }

    @Test
    fun `连续空输出达到阈值后熔断`() {
        val r = ResilientInference(primary = EmptyInference())
        repeat(ResilientInference.EMPTY_BREAKER_THRESHOLD - 1) {
            r.predict(ByteBuffer.allocate(0), frame())
        }
        assertFalse(r.isDegraded)
        r.predict(ByteBuffer.allocate(0), frame())
        assertTrue(r.isDegraded)
    }

    @Test
    fun `正常输出不熔断且透传`() {
        val r = ResilientInference(primary = GoodInference())
        repeat(ResilientInference.EMPTY_BREAKER_THRESHOLD + 5) {
            val out = r.predict(ByteBuffer.allocate(0), frame()) as FakeInferenceOutput
            assertEquals(1, out.guideLines.size)
        }
        assertFalse(r.isDegraded)
        assertEquals("V9-ONNX", r.typeName)
    }

    @Test
    fun `偶发异常不熔断 - 计数会被成功帧清零`() {
        var calls = 0
        val flaky = object : IInference {
            override val typeName = "V9-ONNX"
            override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any {
                calls++
                if (calls % 4 == 0) throw RuntimeException("flaky")
                return FakeInferenceOutput(
                    sceneType = SceneType.NORMAL,
                    compositionStrategy = CompositionStrategy.RULE_OF_THIRDS,
                    confidence = 0.9f, luminance = 128f,
                    guideLines = listOf(FakeGuideLine("rule_of_thirds_grid", true, emptyMap()))
                )
            }
        }
        val r = ResilientInference(primary = flaky)
        repeat(40) { r.predict(ByteBuffer.allocate(0), frame()) }
        assertFalse("偶发异常（每 4 帧 1 次）不应熔断", r.isDegraded)
    }

    @Test
    fun `zero confidence grid failures fall back immediately and eventually trip breaker`() {
        val primary = object : IInference {
            override val typeName = "V9-ONNX"
            override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any =
                (GoodInference().predict(buffer, frame) as FakeInferenceOutput).copy(confidence = 0f)
        }
        val r = ResilientInference(primary)
        repeat(ResilientInference.EMPTY_BREAKER_THRESHOLD) {
            val output = r.predict(ByteBuffer.allocate(0), frame()) as FakeInferenceOutput
            assertTrue(output.guideLines.isNotEmpty())
            assertEquals(0f, output.confidence, 0f)
            assertEquals(0f, output.compositionProgress, 0f)
            assertFalse(output.expectsSubject)
        }
        assertTrue(r.isDegraded)
    }

    @Test
    fun `wrapper forwards sampling and resets without rearming a tripped breaker`() {
        var primaryResets = 0
        var fallbackResets = 0
        val primary = object : IInference {
            override val typeName = "V9-ONNX"
            override val minInferenceIntervalMs = 500L
            override fun resetSession() { primaryResets++ }
            override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any? = error("broken")
        }
        val fallback = object : IInference {
            override val typeName = "Fallback"
            override val minInferenceIntervalMs = 20L
            override fun resetSession() { fallbackResets++ }
            override fun predict(buffer: ByteBuffer, frame: PreprocessedFrame): Any = "fallback"
        }
        val r = ResilientInference(primary, fallback)
        assertEquals(500L, r.minInferenceIntervalMs)
        repeat(ResilientInference.ERROR_BREAKER_THRESHOLD - 1) { r.predict(ByteBuffer.allocate(0), frame()) }
        r.resetSession()
        r.predict(ByteBuffer.allocate(0), frame())
        assertFalse(r.isDegraded)
        repeat(ResilientInference.ERROR_BREAKER_THRESHOLD) { r.predict(ByteBuffer.allocate(0), frame()) }
        assertTrue(r.isDegraded)
        assertEquals(20L, r.minInferenceIntervalMs)
        r.resetSession()
        assertTrue(r.isDegraded)
        assertEquals("fallback", r.predict(ByteBuffer.allocate(0), frame()))
        assertEquals(2, primaryResets)
        assertEquals(2, fallbackResets)
    }

    @Test
    fun `notification failure cannot suppress the fallback or repeat degradation`() {
        var notices = 0
        val r = ResilientInference(ThrowingInference(), onDegraded = {
            notices++
            error("observer unavailable")
        })
        repeat(ResilientInference.ERROR_BREAKER_THRESHOLD + 2) {
            assertTrue(r.predict(ByteBuffer.allocate(0), frame()) is FakeInferenceOutput)
        }
        assertEquals(1, notices)
        assertTrue(r.isDegraded)
    }
}
