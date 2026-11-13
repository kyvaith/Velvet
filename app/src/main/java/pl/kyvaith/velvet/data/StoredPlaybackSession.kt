// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.data

import okhttp3.Cookie
import pl.kyvaith.velvet.playback.NetflixPlaybackException
import pl.kyvaith.velvet.playback.NetflixSessionProvider
import pl.kyvaith.velvet.playback.PlaybackContext
import pl.kyvaith.velvet.playback.PlaybackStage

/** Playback credentials may be accessed only by their captured account, profile and web client. */
class StoredPlaybackSession(
    private val sessions: SessionStore,
    private val ownerGeneration: Long,
    owner: SessionData,
) : NetflixSessionProvider {
    private val profile = owner.profileGuid
    private val esn = owner.esn

    override fun playbackContext(): PlaybackContext = owned {
        sessions.read(ownerGeneration) { current ->
            requireOwner(current ?: throw changed())
            current.playbackContext()
        }
    }

    override fun updateCookies(cookies: List<Cookie>) = owned {
        sessions.update(ownerGeneration) { current ->
            requireOwner(current)
            current.copy(cookieHeader = NetflixOrigins.mergeCookies(current.cookieHeader, cookies))
        }
        Unit
    }

    override fun loadNativeAuthentication(): String = owned {
        sessions.read(ownerGeneration) { current ->
            requireOwner(current ?: throw changed())
            current.nativePlaybackAuth
        }
    }

    override fun saveNativeAuthentication(value: String) = owned {
        sessions.update(ownerGeneration) { current ->
            requireOwner(current)
            current.copy(nativePlaybackAuth = value)
        }
        Unit
    }

    private fun requireOwner(current: SessionData) {
        if (current.profileGuid != profile || current.esn != esn) throw changed()
    }

    private inline fun <T> owned(block: () -> T): T = try {
        block()
    } catch (error: NetflixApiException) {
        throw changed(error)
    }

    private fun changed(cause: Throwable? = null) = NetflixPlaybackException(
        PlaybackStage.SESSION,
        message = "Sesja lub profil zostały zmienione. Uruchom film ponownie.",
        cause = cause,
        retryable = false,
    )
}
