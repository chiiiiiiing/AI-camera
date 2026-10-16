package com.example.aicamera.camera

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CapturePreparationTest {
    @Test fun `non-returning focus callback times out and cancels future`() = runBlocking {
        var cancelled = false
        val result = awaitCapturePreparation<Boolean>(30, { cancelled = true }) { }
        assertNull(result)
        assertTrue(cancelled)
    }

    @Test fun `completed focus result is returned without cancellation`() = runBlocking {
        var cancelled = false
        assertEquals(true, awaitCapturePreparation(1000, { cancelled = true }) { complete -> complete(true) })
        assertFalse(cancelled)
    }

    @Test fun `late focus completion cannot revive a cancelled automatic capture`() = runBlocking {
        var cancelled = false
        var captured = false
        var callback: ((Boolean) -> Unit)? = null
        val work = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitCapturePreparation<Boolean>(10_000, { cancelled = true }) { callback = it }
            captured = true
        }
        work.cancelAndJoin()
        callback!!(true)
        assertTrue(cancelled)
        assertFalse(captured)
    }
}
