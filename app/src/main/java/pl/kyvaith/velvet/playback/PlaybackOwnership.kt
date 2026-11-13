package pl.kyvaith.velvet.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * withContext can discard an already-created result when dispatching back into a cancelled
 * caller. Keep ownership outside that dispatch boundary until the result is actually delivered.
 * The builder remains responsible for resources acquired before it returns a complete value.
 */
internal suspend fun <T : Any> ownedPlaybackResult(
    dispatcher: CoroutineDispatcher,
    release: suspend (T) -> Unit,
    build: suspend () -> T,
): T {
    val staged = AtomicReference<T?>(null)
    try {
        val value = withContext(dispatcher) { build().also(staged::set) }
        currentCoroutineContext().ensureActive()
        staged.set(null)
        // No suspension between transferring ownership and returning to the caller.
        return value
    } finally {
        staged.getAndSet(null)?.let { value ->
            withContext(NonCancellable + dispatcher) { release(value) }
        }
    }
}
