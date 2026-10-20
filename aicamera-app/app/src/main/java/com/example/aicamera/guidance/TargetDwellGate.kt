package com.example.aicamera.guidance

/** A second of distinct, current position samples inside the visible target, not exact overlap. */
class TargetDwellGate {
    private var context: String? = null
    private var enteredAt = -1L
    private var lastSampleAt = -1L
    private var samples = 0
    fun reset() { context = null; enteredAt = -1L; lastSampleAt = -1L; samples = 0 }

    fun update(key: String, sampledAt: Long, now: Long, inside: Boolean): Boolean {
        if (!inside || sampledAt < 0 || now < sampledAt || now - sampledAt > 750) { reset(); return false }
        if (context != key || sampledAt < lastSampleAt ||
            (lastSampleAt >= 0 && sampledAt - lastSampleAt > 750)) reset()
        context = key
        if (sampledAt > lastSampleAt) {
            if (enteredAt < 0) enteredAt = sampledAt
            lastSampleAt = sampledAt
            samples++
        }
        return samples >= 3 && lastSampleAt - enteredAt >= 1000
    }
}
