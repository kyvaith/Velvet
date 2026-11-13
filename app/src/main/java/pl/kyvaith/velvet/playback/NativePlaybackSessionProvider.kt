// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import okhttp3.Cookie

/**
 * The authenticated source keeps ownership of the account generation, profile and web ESN.
 * Native playback receives a separate, immutable device identity. Do not write it back into the
 * catalog session: a refreshed browser ESN must still invalidate an already captured owner.
 */
internal class NativePlaybackSessionProvider(
    private val source: BoundPlaybackSession,
    private val nativeIdentity: String,
) : NetflixSessionProvider {
    init {
        if (!nativeIdentity.startsWith("NFANDROID1-") || nativeIdentity.length !in 32..512 ||
            nativeIdentity.any { it.code !in 0x21..0x7e }) {
            throw NetflixPlaybackException(PlaybackStage.SESSION,
                message = "Nieprawidłowa tożsamość natywnego klienta odtwarzania.", retryable = false)
        }
    }

    override fun playbackContext(): PlaybackContext = source.current().copy(
        esn = nativeIdentity,
        userAgent = KodiAndroidPlaybackProfile.USER_AGENT,
    )

    override fun updateCookies(cookies: List<Cookie>) = source.updateCookies(cookies)

    override fun loadNativeAuthentication(): String = source.loadNativeAuthentication()

    override fun saveNativeAuthentication(value: String) = source.saveNativeAuthentication(value)
}
