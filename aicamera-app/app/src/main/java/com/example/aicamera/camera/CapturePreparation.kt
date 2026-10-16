package com.example.aicamera.camera

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A non-returning AF/AE future must not keep a cancelled or timed-out capture alive. */
internal suspend fun <T> awaitCapturePreparation(
    timeoutMs: Long,
    cancel: () -> Unit,
    subscribe: ((T) -> Unit) -> Unit
): T? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        subscribe { result ->
            // CameraX invokes a future listener once. Late completion after cancellation
            // must not resume the old capture coroutine.
            if (continuation.isActive) continuation.resume(result)
        }
    }
}
