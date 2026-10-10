package com.example.aicamera.pipeline

/** Worker-thread-only cache. Geometry can update between expensive model samples. */
internal class ModelOutputSampler {
    private var sampledAtMs: Long? = null
    private var cachedOutput: Any? = null

    fun sample(nowMs: Long, intervalMs: Long, predict: () -> Any?): Any? {
        require(intervalMs >= 0) { "Inference interval must be non-negative" }
        val previous = sampledAtMs
        val cached = cachedOutput
        if (cached != null && previous != null && nowMs >= previous &&
            nowMs - previous < intervalMs
        ) return cached

        // A failed prediction must not revive the previous scene's recommendation.
        reset()
        return predict().also {
            cachedOutput = it
            sampledAtMs = nowMs
        }
    }

    fun reset() {
        sampledAtMs = null
        cachedOutput = null
    }
}
