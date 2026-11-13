// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import okhttp3.Cookie
import okhttp3.HttpUrl
import pl.kyvaith.velvet.diagnostics.AppLog
import java.io.Closeable

/**
 * Owns a scoped HTTP jar and a separate complete NETFLIXID credential pair in memory.
 * Cookies retain the server's domain/path/hostOnly/Secure attributes. The credential pair is
 * passed explicitly to Netflix's encrypted MSL authentication, never used as a global HTTP jar.
 */
internal class NativeAccountSessionProvider(
    private val source: BoundPlaybackSession,
    private val nativeIdentity: String,
    private val userAgent: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : NetflixSessionProvider, NativeCookieSession, Closeable {
    private data class CookieKey(val name: String, val domain: String, val path: String)
    private class AuthenticationPair(val netflixId: Cookie, val secureNetflixId: Cookie) {
        fun validAt(now: Long): Boolean = netflixId.expiresAt > now && secureNetflixId.expiresAt > now
        fun header(): String = "NetflixId=${netflixId.value}; SecureNetflixId=${secureNetflixId.value}"
        override fun toString(): String = "NativeAuthenticationPair(redacted)"
    }

    private var closed = false
    private var cookies = linkedMapOf<CookieKey, Cookie>()
    private var initialDeviceCookie: Cookie? = null
    private var authentication: AuthenticationPair? = null
    private var ownerRecord: CharArray? = null

    init {
        if (!nativeIdentity.startsWith("NFANDROID1-") || nativeIdentity.length !in 32..512 ||
            nativeIdentity.any { it.code !in 0x21..0x7e } || userAgent.isBlank() ||
            userAgent.any { it.code !in 0x20..0x7e }) {
            throw sessionFailure("Nieprawidłowy kontekst natywnego klienta odtwarzania.")
        }
        val initial = source.current()
        val canonical = PlaybackCookies.snapshot(initial.cookieHeader)
        val deviceValues = canonical.header.split(';').map { it.trimStart(' ', '\t') }
            .filter { it.startsWith("nfvdid=") }.map { it.substringAfter('=') }.distinct()
        if (deviceValues.size > 1) throw sessionFailure("Sesja Netflix zawiera sprzeczny identyfikator przeglądarki.")
        deviceValues.singleOrNull()?.takeIf { it.isNotEmpty() }?.let { value ->
            val cookie = Cookie.Builder().name("nfvdid").value(value)
                .domain("netflix.com").path("/").secure().build()
            initialDeviceCookie = cookie
            cookies[cookie.key()] = cookie
        }
        requireSource()
    }

    @Synchronized
    override fun playbackContext(): PlaybackContext {
        val web = requireSource()
        removeExpired(nowMillis())
        val credentials = buildList {
            initialDeviceCookie?.let { add("${it.name}=${it.value}") }
            authentication?.let { add(it.header()) }
        }.joinToString("; ")
        val result = web.copy(cookieHeader = credentials, authUrl = "", esn = nativeIdentity, userAgent = userAgent)
        requireSource()
        return result
    }

    @Synchronized
    override fun cookieHeaderFor(url: HttpUrl): String {
        requireSource()
        requireOrigin(url)
        val now = nowMillis()
        removeExpired(now)
        // Cookie.matches implements host-only/domain/path/Secure matching, but not expiry.
        // LinkedHashMap preserves creation order, including on replacement of an existing key.
        val result = cookies.values.filter { it.expiresAt > now && it.matches(url) }
            .sortedByDescending { it.path.length }
            .joinToString("; ") { "${it.name}=${it.value}" }
        requireSource()
        return result
    }

    @Synchronized
    override fun updateCookiesFrom(url: HttpUrl, cookies: List<Cookie>) {
        requireSource()
        requireOrigin(url)
        val now = nowMillis()
        val updates = validateUpdates(url, cookies, now)
        val authUpdates = updates.filter { it.name in AUTHENTICATION_NAMES }
        val replacement = LinkedHashMap(this.cookies)
        replacement.entries.removeAll { it.value.expiresAt <= now }
        if (authUpdates.isNotEmpty()) {
            // This playback owns one current credential pair. Old broad cookies must not
            // shadow a freshly issued host-only/path-scoped pair on a later HTTP request.
            replacement.entries.removeAll { it.key.name in AUTHENTICATION_NAMES }
        }
        for (cookie in updates) {
            if (cookie.expiresAt <= now) replacement.remove(cookie.key())
            else replacement[cookie.key()] = cookie
        }
        val nextAuthentication = if (authUpdates.isEmpty()) authentication?.takeIf { it.validAt(now) }
            else AuthenticationPair(
                authUpdates.filter { it.name == "NetflixId" }.minBy { it.expiresAt },
                authUpdates.filter { it.name == "SecureNetflixId" }.minBy { it.expiresAt },
            )
        requireSource()
        this.cookies = replacement
        authentication = nextAuthentication
        // A source change during this local write discards the old owner's whole private state.
        requireSource()
    }

    /** Originless writes cannot decide the scope of credentials returned by a server. */
    @Synchronized
    override fun updateCookies(cookies: List<Cookie>) {
        requireSource()
        if (cookies.isNotEmpty()) throw sessionFailure("Brak adresu odpowiedzi dla aktualizacji natywnej sesji.")
    }

    @Synchronized
    override fun loadNativeAuthentication(): String {
        requireSource()
        val result = ownerRecord?.concatToString().orEmpty()
        requireSource()
        return result
    }

    @Synchronized
    override fun saveNativeAuthentication(value: String) {
        // The signed owner pair can renew this playback's MSL session but never reaches disk.
        requireSource()
        val replacement = value.takeIf { it.isNotEmpty() }?.toCharArray()
        try {
            requireSource()
            ownerRecord?.fill('\u0000')
            ownerRecord = replacement
            requireSource()
        } catch (error: Exception) {
            replacement?.fill('\u0000')
            throw error
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        cookies.clear()
        initialDeviceCookie = null
        authentication = null
        ownerRecord?.fill('\u0000')
        ownerRecord = null
    }

    override fun toString(): String = "NativeAccountSessionProvider(redacted)"

    private fun requireSource(): PlaybackContext {
        if (closed) throw sessionFailure("Natywna sesja odtwarzania została zamknięta.")
        return try { source.current() } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun requireOrigin(url: HttpUrl) {
        if (!NativeCookieOrigins.accepts(url)) throw sessionFailure("Nieprawidłowy adres natywnej sesji odtwarzania.")
    }

    private fun validateUpdates(origin: HttpUrl, updates: List<Cookie>, now: Long): List<Cookie> {
        val accepted = ArrayList<Cookie>(updates.size)
        for (cookie in updates) {
            val valid = validCookie(cookie) && issuedBy(origin, cookie)
            if (!valid && cookie.name in AUTHENTICATION_NAMES) {
                rejected(updates.size, "invalid_auth_cookie_origin")
                throw sessionFailure("Netflix zwrócił nieprawidłowy zakres danych natywnej autoryzacji.")
            }
            if (valid) accepted += cookie // Unusable auxiliary cookies never reject the MSL body.
        }
        val auth = accepted.filter { it.name in AUTHENTICATION_NAMES }.groupBy { it.name }
        if (auth.values.any { group -> group.map { it.value }.distinct().size > 1 }) {
            rejected(updates.size, "conflicting_auth_cookies")
            throw sessionFailure("Netflix zwrócił sprzeczne cookies natywnej sesji odtwarzania.")
        }
        if (auth.isNotEmpty() && auth.keys != AUTHENTICATION_NAMES) {
            rejected(updates.size, "partial_auth_cookie_pair")
            throw sessionFailure("Netflix nie zwrócił kompletnej pary cookies natywnej sesji odtwarzania.")
        }
        if (auth.values.flatten().any { it.value.isBlank() || it.expiresAt <= now }) {
            rejected(updates.size, "expired_auth_cookie_pair")
            throw sessionFailure("Netflix zwrócił nieważne cookies natywnej sesji odtwarzania.")
        }
        return accepted
    }

    private fun validCookie(cookie: Cookie): Boolean = COOKIE_NAME.matches(cookie.name) &&
        cookie.value.none { it.code !in 0x21..0x7e || it == ';' } && cookie.path.startsWith('/')

    private fun issuedBy(origin: HttpUrl, cookie: Cookie): Boolean {
        if (cookie.domain != "netflix.com" && !cookie.domain.endsWith(".netflix.com")) return false
        // A response may intentionally set a cookie for a different path on the same issuer.
        // Secure=false is valid over HTTPS; it is not an instruction to use insecure transport.
        return if (cookie.hostOnly) cookie.domain == origin.host
            else origin.host == cookie.domain || origin.host.endsWith(".${cookie.domain}")
    }

    private fun removeExpired(now: Long) {
        cookies.entries.removeAll { it.value.expiresAt <= now }
        if (authentication?.validAt(now) == false) authentication = null
    }

    private fun rejected(count: Int, reason: String) = AppLog.warn("session", "native_cookie_update_rejected",
        mapOf("cookieCount" to count, "reason" to reason))

    private fun Cookie.key() = CookieKey(name, domain, path)

    private fun sessionFailure(message: String) = NetflixPlaybackException(
        PlaybackStage.SESSION, message = message, retryable = false,
    )

    private companion object {
        val AUTHENTICATION_NAMES = setOf("NetflixId", "SecureNetflixId")
        val COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}
