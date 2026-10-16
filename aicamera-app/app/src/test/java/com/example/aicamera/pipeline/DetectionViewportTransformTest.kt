package com.example.aicamera.pipeline

import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DetectionViewportTransformTest {
    @Test fun `portrait landscape and odd padding map back without aspect stretch`() {
        for ((width,height) in listOf(1920 to 1080,1080 to 1920,4032 to 3024,301 to 100,100 to 301)) {
            val t = DetectionViewportTransform.forViewport(width,height)
            assertTrue(t.resizedWidth == 416 || t.resizedHeight == 416)
            val wanted = NormalizedBox(.12f,.18f,.76f,.86f)
            val detector = NormalizedBox((wanted.left*t.resizedWidth+t.padLeft)/416,
                (wanted.top*t.resizedHeight+t.padTop)/416,(wanted.right*t.resizedWidth+t.padLeft)/416,
                (wanted.bottom*t.resizedHeight+t.padTop)/416)
            val actual = requireNotNull(t.toViewport(detector))
            assertEquals(wanted.left,actual.left,.000001f); assertEquals(wanted.right,actual.right,.000001f)
            assertEquals(wanted.top,actual.top,.000001f); assertEquals(wanted.bottom,actual.bottom,.000001f)
        }
    }

    @Test fun `padding boxes cannot become subjects and edge intersection remains clipped`() {
        val t = DetectionViewportTransform.forViewport(1600,900)
        assertNull(t.toViewport(NormalizedBox(.2f,0f,.4f,.1f)))
        assertNull(t.toViewport(NormalizedBox(.2f,Float.NaN,.4f,.1f)))
        val edge = requireNotNull(t.toViewport(NormalizedBox(.2f,.2f,.4f,.6f)))
        assertEquals(0f,edge.top,0f)
    }

    @Test fun `native 416 sampling preserves detail lost by legacy 224 intermediary`() {
        val pixels = IntArray(416*416) { if (it%2 == 0) -0x1000000 else -1 }
        val t = DetectionViewportTransform.forViewport(416,416)
        val resized = NativeViewportPixels.resize(pixels,416,416,t)
        assertArrayEquals(pixels,resized)
        val native = NanoDetRuntime.letterboxPixelsToInput(NativeViewportPixels.letterbox(resized,t))
        val legacy = NanoDetRuntime.pixelsToInput(pixels,416,416)
        assertTrue(kotlin.math.abs(native[0]-legacy[0]) > .5f)
        assertEquals(255f/57.375f,native[1]-native[0],.000001f)
    }

    @Test fun `letterbox has exact gray padding and preserves channel order`() {
        val t = DetectionViewportTransform.forViewport(2,1)
        val red = NativeViewportPixels.resize(intArrayOf(-0x10000,-0x10000),2,1,t)
        val pixels = NativeViewportPixels.letterbox(red,t)
        assertEquals(0xff727272.toInt(),pixels[0])
        val input = NanoDetRuntime.letterboxPixelsToInput(pixels)
        val center = 208*416+208
        assertEquals((114f-103.53f)/57.375f,input[0],.000001f)
        assertEquals(-103.53f/57.375f,input[center],.000001f)
        assertEquals((255f-123.675f)/58.395f,input[2*416*416+center],.000001f)
    }

    @Test fun `native pixel fixture matches Python rounded half pixel resize`() {
        val pixels = IntArray(7*3) { index ->
            val r=(index*31+3)%256; val g=(index*17+5)%256; val b=(index*11+7)%256
            -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        val t=DetectionViewportTransform.forViewport(7,3)
        assertEquals(416,t.resizedWidth); assertEquals(178,t.resizedHeight); assertEquals(119,t.padTop)
        val output=NativeViewportPixels.letterbox(NativeViewportPixels.resize(pixels,7,3,t),t)
        assertEquals(0xff030507.toInt(),output[119*416])
        assertEquals(0xff6f59e3.toInt(),output[296*416+415])
    }

    @Test fun `native crop interpolation matches full Python fixture including rounding boundaries`() {
        val width=997;val height=601
        val source=IntArray(width*height){index->
            val x=index%width;val y=index/width
            -0x1000000 or (((x*7+y*3)%256) shl 16) or (((x*2+y*11)%256) shl 8) or ((x*13+y*5)%256)
        }
        val transform=DetectionViewportTransform.forViewport(width,height)
        val native=NativeViewportPixels.resize(source,width,height,transform)
        // Canonical 0.1..0.9 crop of the 416x251 native image: [42,25,374,226].
        val crop=IntArray(332*201){index->native[(index/332+25)*416+index%332+42]}
        val resized=NativeViewportPixels.resize(crop,332,201,DetectionViewportTransform(332,201,224,224,0,0))
        val rgb=ByteArray(resized.size*3){index->(resized[index/3] ushr (16-(index%3)*8)).toByte()}
        val hash=MessageDigest.getInstance("SHA-256").digest(rgb).joinToString(""){"%02x".format(it)}
        assertEquals("02ba0dfa2ce44c08badee0fdb756366e2269fdb155e171029f0e4ef062e878fa",hash)
    }
}
