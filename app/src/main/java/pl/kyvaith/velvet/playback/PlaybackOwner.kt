// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

/** Prepared playback belongs to its captured account generation, profile and web identity. */
internal class PlaybackOwner(
    private val generation: Long,
    context: PlaybackContext,
) {
    private val profile = context.profileGuid
    private val identity = context.esn

    fun requireOwner(currentGeneration: Long, context: PlaybackContext) {
        if (generation != currentGeneration || profile != context.profileGuid || identity != context.esn) {
            throw NetflixPlaybackException(PlaybackStage.SESSION,
                message = "Konto lub profil zmieniły się. Ponownie wybierz materiał do odtworzenia.", retryable = false)
        }
    }

    override fun toString(): String = "PlaybackOwner(redacted)"
}
