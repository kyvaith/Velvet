// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import pl.kyvaith.velvet.diagnostics.AppLog

/** One unambiguous view of the cookies sent in HTTP and in NETFLIXID authentication. */
internal class PlaybackCookieSnapshot(
    val header: String,
    val netflixId: String,
    val secureNetflixId: String,
    val cookieCount: Int,
    val authCookieDuplicateCount: Int,
    val hasNfvdid: Boolean,
) {
    override fun toString(): String = "PlaybackCookieSnapshot(redacted)"
}

internal object PlaybackCookies {
    private val authenticationNames = setOf("NetflixId", "SecureNetflixId")
    private val namePattern = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun snapshot(header: String): PlaybackCookieSnapshot {
        val entries = LinkedHashSet<Pair<String, String>>()
        val authentication = LinkedHashMap<String, String>()
        var duplicateCount = 0
        header.split(';').forEach { part ->
            // Whitespace separating cookie pairs is not part of a cookie's opaque value.
            // Never URL-decode, unquote or trim the value itself.
            val pair = part.trimStart(' ', '\t')
            if (pair.isEmpty()) return@forEach
            val separator = pair.indexOf('=')
            if (separator <= 0) invalid()
            val name = pair.substring(0, separator)
            val value = pair.substring(separator + 1)
            // Raw whitespace/control bytes are not valid cookie-octets. Reject them instead
            // of letting an HTTP header implementation silently trim an authentication value.
            if (!namePattern.matches(name) || value.any { it.code !in 0x21..0x7e }) invalid()
            if (name in authenticationNames) {
                val previous = authentication[name]
                if (previous != null) {
                    duplicateCount++
                    if (previous != value) {
                        AppLog.warn("session", "playback_cookies_ambiguous", mapOf(
                            "cookieConflict" to true, "authCookieDuplicateCount" to duplicateCount))
                        throw NetflixPlaybackException(PlaybackStage.SESSION,
                            message = "Sesja zawiera sprzeczne pliki cookie Netflix. Otwórz konto Netflix w ustawieniach Velvet i ponownie wybierz profil.",
                            retryable = false)
                    }
                } else authentication[name] = value
            }
            // Identical duplicates are harmless; distinct non-authentication cookies retain
            // their original order instead of arbitrarily selecting a domain/path winner.
            entries += name to value
        }
        val netflixId = authentication["NetflixId"]
        val secureNetflixId = authentication["SecureNetflixId"]
        if (netflixId.isNullOrBlank() || secureNetflixId.isNullOrBlank()) {
            throw NetflixPlaybackException(PlaybackStage.SESSION,
                message = "Brakuje danych sesji Netflix wymaganych do odtwarzania. Otwórz konto Netflix i wybierz profil.",
                retryable = false)
        }
        return PlaybackCookieSnapshot(
            header = entries.joinToString("; ") { (name, value) -> "$name=$value" },
            netflixId = netflixId,
            secureNetflixId = secureNetflixId,
            cookieCount = entries.size,
            authCookieDuplicateCount = duplicateCount,
            hasNfvdid = entries.any { (name, value) -> name == "nfvdid" && value.isNotBlank() },
        )
    }

    private fun invalid(): Nothing = throw NetflixPlaybackException(PlaybackStage.SESSION,
        message = "Sesja Netflix zawiera nieprawidłowe dane cookies. Otwórz konto Netflix i wybierz profil.",
        retryable = false)
}
