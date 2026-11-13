// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import okhttp3.Cookie

/** Access playback credentials without allowing an in-flight player to change their owner. */
internal class BoundPlaybackSession(
    private val initial: PlaybackContext,
    private val provider: NetflixSessionProvider,
) {
    fun current(): PlaybackContext {
        val fresh = provider.playbackContext() // The application also checks its captured generation.
        requireSameIdentity(fresh)
        return initial.copy(cookieHeader = fresh.cookieHeader)
    }

    fun updateCookies(cookies: List<Cookie>) {
        requireSameIdentity(provider.playbackContext())
        // The provider must check generation/identity again atomically with the write.
        provider.updateCookies(cookies)
    }

    fun loadNativeAuthentication(): String {
        requireSameIdentity(provider.playbackContext())
        // The provider also checks generation/identity atomically with this read.
        return provider.loadNativeAuthentication()
    }

    fun saveNativeAuthentication(value: String) {
        requireSameIdentity(provider.playbackContext())
        // A login or profile change after this check must invalidate the provider's write.
        provider.saveNativeAuthentication(value)
    }

    private fun requireSameIdentity(fresh: PlaybackContext) {
        if (fresh.profileGuid != initial.profileGuid || fresh.esn != initial.esn) throw NetflixPlaybackException(
            PlaybackStage.SESSION, message = "Sesja lub profil zmieniły się podczas odtwarzania. Otwórz materiał ponownie.", retryable = false,
        )
    }
}
