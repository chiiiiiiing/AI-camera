package com.example.aicamera.pipeline

import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class TechniqueVisualAnalyzerTest {
    private fun gray(value: Int) = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
    private fun image(width: Int = 96, height: Int = 96, sample: (Int, Int) -> Int) =
        IntArray(width * height) { gray(sample(it % width, it / width)) }
    private val center = NormalizedBox(.35f, .3f, .65f, .7f)

    @Test fun `blank image does not establish a symmetric photographic composition`() {
        val evidence = TechniqueVisualAnalyzer.analyze(image { _, _ -> 128 }, 96, 96)!!
        assertNull(evidence.bilateralSymmetry)
        assertNull(evidence.subjectBackgroundLumaDifference)
        assertEquals(0f, evidence.leftBackground!!.edgeDensity, .00001f)
        assertEquals(0f, evidence.rightBackground!!.brightFraction, .00001f)
    }

    @Test fun `visible symmetrical structure is distinguished from a one sided pattern`() {
        val symmetric = image { x, y -> if ((minOf(x, 95 - x) / 6 + y / 8) % 2 == 0) 210 else 40 }
        val asymmetric = image { x, y -> if (x < 48 && (x / 6 + y / 8) % 2 == 0) 210 else 40 }
        val a = TechniqueVisualAnalyzer.analyze(symmetric, 96, 96)!!
        val b = TechniqueVisualAnalyzer.analyze(asymmetric, 96, 96)!!
        assertTrue(a.bilateralSymmetry!! > .99f)
        assertTrue(b.bilateralSymmetry!! < .7f)
    }

    @Test fun `left and right detail and highlights reflect only observed background`() {
        val evidence = TechniqueVisualAnalyzer.analyze(image { x, y ->
            when { x < 32 -> if ((x / 4 + y / 4) % 2 == 0) 255 else 20; else -> 80 }
        }, 96, 96, center)!!
        assertTrue(evidence.leftBackground!!.edgeDensity > .08f)
        assertEquals(0f, evidence.rightBackground!!.edgeDensity, .00001f)
        assertTrue(evidence.leftBackground.brightFraction > .45f)
        assertEquals(0f, evidence.rightBackground.brightFraction, .00001f)
    }

    @Test fun `subject texture and bright silhouette are excluded from background measurements`() {
        val subject = NormalizedBox(.12f, .15f, .28f, .85f)
        val evidence = TechniqueVisualAnalyzer.analyze(image { x, y ->
            if ((x + .5f) / 96 in subject.left..subject.right && (y + .5f) / 96 in subject.top..subject.bottom)
                if ((x + y) % 2 == 0) 255 else 0
            else 60
        }, 96, 96, subject)!!
        assertEquals(0f, evidence.leftBackground!!.edgeDensity, .00001f)
        assertEquals(0f, evidence.leftBackground.brightFraction, .00001f)
        assertTrue(evidence.subjectMeanLuma!! > .45f)
    }

    @Test fun `subject separation measures local surroundings without declaring low contrast bad`() {
        val separated = image { x, y ->
            if ((x + .5f) / 96 in center.left..center.right && (y + .5f) / 96 in center.top..center.bottom) 200 else 60
        }
        val a = TechniqueVisualAnalyzer.analyze(separated, 96, 96, center)!!
        val b = TechniqueVisualAnalyzer.analyze(image { _, _ -> 200 }, 96, 96, center)!!
        assertEquals(140f / 255, a.subjectBackgroundLumaDifference!!, .001f)
        assertEquals(0f, b.subjectBackgroundLumaDifference!!, .001f)
        assertEquals(200f / 255, b.subjectMeanLuma!!, .001f)
    }

    @Test fun `detail distribution detects upper versus lower texture without horizon inference`() {
        val evidence = TechniqueVisualAnalyzer.analyze(image { x, y ->
            if (y < 32 && (x / 4 + y / 4) % 2 == 0) 230 else 70
        }, 96, 96)!!
        assertTrue(evidence.upperTexture!! > .05f)
        assertEquals(0f, evidence.lowerTexture!!, .00001f)
    }

    @Test fun `missing support remains absent instead of returning invented zero measurements`() {
        val evidence = TechniqueVisualAnalyzer.analyze(image { _, _ -> 150 }, 96, 96,
            NormalizedBox(0f, 0f, 1f, 1f))!!
        assertNull(evidence.leftBackground)
        assertNull(evidence.rightBackground)
        assertNull(evidence.surroundingsMeanLuma)
        assertNull(evidence.subjectBackgroundLumaDifference)
        assertNull(evidence.upperTexture)
        assertNull(evidence.lowerTexture)
    }

    @Test fun `invalid inputs cannot create actionable appearance evidence`() {
        assertNull(TechniqueVisualAnalyzer.analyze(IntArray(10), 96, 96))
        assertNull(TechniqueVisualAnalyzer.analyze(IntArray(16), Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(TechniqueVisualAnalyzer.analyze(image { _, _ -> 80 }, 96, 96,
            NormalizedBox(Float.NaN, .1f, .4f, .5f)))
        assertNull(TechniqueVisualAnalyzer.analyze(image { _, _ -> 80 }, 96, 96,
            NormalizedBox(.7f, .1f, .4f, .5f)))
    }

    @Test fun `sampling remains bounded while preserving portrait aspect ratio`() {
        val evidence = TechniqueVisualAnalyzer.analyze(image(384, 576) { x, y -> (x + y) % 256 },
            384, 576, center)!!
        assertEquals(64, evidence.sampleWidth)
        assertEquals(96, evidence.sampleHeight)
        val measurements = listOfNotNull(evidence.leftBackground?.edgeDensity, evidence.leftBackground?.meanLuma,
            evidence.rightBackground?.brightFraction, evidence.subjectBackgroundLumaDifference,
            evidence.bilateralSymmetry, evidence.upperTexture, evidence.lowerTexture)
        assertTrue(measurements.all { it.isFinite() && it in 0f..1f })
    }
}
