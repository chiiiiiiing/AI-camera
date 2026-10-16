package com.example.aicamera.pipeline

import org.junit.Assert.*
import org.junit.Test

class NanoDetRuntimeTest {
    private fun heads(): MutableMap<String, Array<FloatArray>> = mutableMapOf<String, Array<FloatArray>>().apply {
        for (level in NanoDetRuntime.STRIDES.indices) {
            val count = (416 / NanoDetRuntime.STRIDES[level]).let { it * it }
            put(NanoDetRuntime.CLASS_NAMES[level], Array(count) { FloatArray(80) })
            put(NanoDetRuntime.BOX_NAMES[level], Array(count) { FloatArray(32) })
        }
    }

    @Test fun `red RGB pixel is normalized in BGR order`() {
        val tensor = NanoDetRuntime.pixelsToInput(IntArray(224*224) { -0x10000 },224,224)
        assertEquals(3*416*416,tensor.size)
        assertEquals(-103.53f/57.375f,tensor[0],.000001f)
        assertEquals(-116.28f/57.12f,tensor[416*416],.000001f)
        assertEquals((255f-123.675f)/58.395f,tensor[2*416*416],.000001f)
    }

    @Test fun `real named heads decode distribution mean at stride16 regardless map order`() {
        val heads = heads()
        heads.getValue("814")[10*26+12][41] = .8f
        val result = NanoDetRuntime.decode(heads.entries.reversed().associate { it.toPair() }).single()
        assertEquals(41,result.classId)
        assertEquals(.8f,result.confidence,0f)
        assertEquals((199.5f-56)/416,result.box.left,.000001f)
        assertEquals((167.5f-56)/416,result.box.top,.000001f)
        assertEquals((199.5f+56)/416,result.box.right,.000001f)
        assertEquals((167.5f+56)/416,result.box.bottom,.000001f)
    }

    @Test fun `NMS suppresses overlap across different classes`() {
        val heads = heads()
        heads.getValue("792")[20*52+20][39] = .9f
        heads.getValue("792")[20*52+21][41] = .8f
        assertEquals(1,NanoDetRuntime.decode(heads).size)
    }

    @Test fun `softmax is finite for large logits and rejects invalid rows`() {
        val heads = heads()
        heads.getValue("792")[20*52+20][39] = .9f
        heads.getValue("795")[20*52+20].fill(10000f)
        heads.getValue("792")[20*52+22][41] = Float.NaN
        assertEquals(1,NanoDetRuntime.decode(heads).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing head fails closed`() {
        NanoDetRuntime.decode(heads().apply { remove("839") })
    }

    @Test fun `all low scores produce no invented object`() {
        assertTrue(NanoDetRuntime.decode(heads()).isEmpty())
    }
}
