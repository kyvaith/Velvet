package pl.kyvaith.velvet.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackOwnershipTest {
    @Test fun `cancelled dispatch releases an already constructed playback exactly once`() = runTest {
        val worker = StandardTestDispatcher(testScheduler, "playback-worker")
        val allocated = Any()
        var released = 0
        var delivered = false
        val task = launch {
            ownedPlaybackResult(worker, release = { value: Any ->
                assertSame(allocated, value)
                delay(1) // Cleanup must continue despite cancellation of the caller.
                released++
            }) {
                // A result has been constructed, but the withContext handoff has not happened.
                currentCoroutineContext().cancel()
                allocated
            }
            delivered = true
        }
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(delivered)
        assertEquals(1, released)
    }

    @Test fun `success transfers resource ownership without prematurely releasing it`() = runTest {
        val worker = StandardTestDispatcher(testScheduler, "playback-worker")
        val allocated = Any()
        var released = 0
        val result = ownedPlaybackResult(worker, release = { _: Any -> released++ }) { allocated }
        assertSame(allocated, result)
        assertEquals(0, released)
    }
}
