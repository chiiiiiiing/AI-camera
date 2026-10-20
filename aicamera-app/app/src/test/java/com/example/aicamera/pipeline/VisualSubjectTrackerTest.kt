package com.example.aicamera.pipeline

import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class VisualSubjectTrackerTest {
    private fun image(dx: Int = 0, brightness: Int = 0): IntArray = IntArray(64 * 64) { n ->
        val x = n % 64 - dx; val y = n / 64
        val v = if (x !in 0..63) 0 else (40 + ((x * 1973 xor y * 9277 xor (x * y * 31)) and 127) + brightness).coerceIn(0, 255)
        (255 shl 24) or (v shl 16) or (v shl 8) or v
    }
    @Test fun `image translation updates position while semantic detections are absent`() {
        val tracker = VisualSubjectTracker()
        val box = NormalizedBox(.25f, .25f, .5f, .5f)
        tracker.update(image(), 64, 64, 1000, 1, box)
        val first = tracker.update(image(4), 64, 64, 1200, 1)!!
        assertEquals(box.centerX + 4f / 64, first.centerX, .02f)
        val next = tracker.update(image(8), 64, 64, 1400, 1)!!
        assertEquals(box.centerX + 8f / 64, next.centerX, .02f)
    }
    @Test fun `correlation tolerates brightness change and does not expire a valid image match`() {
        val tracker = VisualSubjectTracker()
        tracker.update(image(), 64, 64, 1000, 1, NormalizedBox(.25f, .25f, .5f, .5f))
        assertNotNull(tracker.update(image(4, 25), 64, 64, 20000, 1))
    }
    @Test fun `blank image and changed viewport cannot invent a tracked position`() {
        val tracker = VisualSubjectTracker()
        tracker.update(image(), 64, 64, 1000, 1, NormalizedBox(.25f, .25f, .5f, .5f))
        assertNull(tracker.update(IntArray(4096) { -1 }, 64, 64, 1200, 1))
        assertNull(tracker.update(image(4), 64, 64, 1400, 2))
    }
    @Test fun `repeating texture cannot choose an arbitrary matching subject`() {
        val repeated = IntArray(4096) { n ->
            val v = if (n % 8 < 4) 60 else 200
            (255 shl 24) or (v shl 16) or (v shl 8) or v
        }
        val tracker = VisualSubjectTracker()
        tracker.update(repeated, 64, 64, 1000, 1, NormalizedBox(.25f, .25f, .5f, .5f))
        assertNull(tracker.update(repeated, 64, 64, 1200, 1))
    }
}
