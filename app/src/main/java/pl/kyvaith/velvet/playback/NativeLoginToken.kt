// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import java.io.Closeable

/** Short-lived hand-off credential. Never serialize it or include it in a diagnostic value. */
internal class NativeLoginToken(value: String) : Closeable {
    private var contents: CharArray? = value.takeIf {
        it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH && it.all { character -> character.code in 0x21..0x7e }
    }?.toCharArray() ?: throw NetflixPlaybackException(
        PlaybackStage.AUTHENTICATION,
        message = "Netflix nie zwrócił prawidłowego tokenu aktywacji odtwarzania.",
        retryable = false,
    )

    @Synchronized
    fun value(): String = contents?.concatToString() ?: throw NetflixPlaybackException(
        PlaybackStage.AUTHENTICATION,
        message = "Próba aktywacji odtwarzania została zakończona. Wybierz materiał ponownie.",
        retryable = false,
    )

    @Synchronized
    override fun close() {
        contents?.fill('\u0000')
        contents = null
    }

    override fun toString(): String = "NativeLoginToken(redacted)"

    private companion object {
        const val MAX_TOKEN_LENGTH = 16_384
    }
}
