package com.example.aicamera.pipeline

import com.example.aicamera.guidance.ObjectObservation
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class SubjectAssociationTest {
    private val previous = ObjectObservation(1, 1000, 1, 3, 41,
        NormalizedBox(.2f, .3f, .5f, .6f), .8f, 3, .75f)

    @Test fun `moderate camera motion retains association below old overlap threshold`() {
        val moved = ObjectDetection(41, .8f, NormalizedBox(.37f, .3f, .67f, .6f))
        assertTrue(MultiScenePolicy.overlap(previous.box, moved.box) < .5f)
        assertEquals(moved, MultiScenePolicy.associated(listOf(moved), previous, 1250))
    }

    @Test fun `tracked subject wins over a newly prominent distractor`() {
        val same = ObjectDetection(41, .6f, NormalizedBox(.23f, .3f, .53f, .6f))
        val distractor = ObjectDetection(63, .95f, NormalizedBox(.1f, .1f, .8f, .8f))
        assertEquals(same, MultiScenePolicy.associated(listOf(distractor, same), previous, 1250))
    }

    @Test fun `brief absence waits for original subject instead of switching to another class`() {
        val other = ObjectDetection(63, .95f, NormalizedBox(.1f, .1f, .8f, .8f))
        assertNull(MultiScenePolicy.associated(listOf(other), previous, 1250))
        assertEquals(other, MultiScenePolicy.associated(listOf(other), previous, 2500))
    }

    @Test fun `distant same class detection cannot silently replace a live track`() {
        val remote = ObjectDetection(41, .9f, NormalizedBox(.7f, .7f, .9f, .9f))
        assertNull(MultiScenePolicy.associated(listOf(remote), previous, 1250))
    }
}
