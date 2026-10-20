package com.example.aicamera.guidance

import org.junit.Assert.*
import org.junit.Test

class TargetDwellGateTest {
    @Test fun `replayed samples never accumulate dwell time`() {
        val gate = TargetDwellGate()
        for (t in 1000L..2400L step 100) assertFalse(gate.update("target", 1000, t, true))
    }
    @Test fun `long sampling gap and changed target restart dwell`() {
        val gate = TargetDwellGate()
        gate.update("a", 1000, 1000, true)
        gate.update("a", 1500, 1500, true)
        assertFalse(gate.update("a", 2400, 2400, true))
        assertFalse(gate.update("b", 2900, 2900, true))
        assertFalse(gate.update("b", 3400, 3400, true))
        assertTrue(gate.update("b", 3900, 3900, true))
    }
}
