package com.example.aicamera.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortraitAutoZoomPolicyTest {
    @Test fun smallFaceOnlyZoomsOneConservativeStep() {
        assertEquals(1.25f, PortraitAutoZoomPolicy.targetRatio(1f, 0.10f, 0.15f, 1f, 8f)!!, 0.001f)
    }

    @Test fun automaticZoomNeverExceedsQualityCap() {
        assertEquals(1.8f, PortraitAutoZoomPolicy.targetRatio(1.6f, 0.10f, 0.12f, 1f, 10f)!!, 0.001f)
    }

    @Test fun invalidSubjectBoxDisablesAutomaticZoom() {
        assertNull(PortraitAutoZoomPolicy.targetRatio(1f, 0.01f, 0.02f, 1f, 8f))
    }
}
