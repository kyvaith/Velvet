/*
 * SPDX-License-Identifier: MIT
 * Native owner-token bootstrap/profile switching follows msl_requests.py and
 * msl_request_builder.py, atrHusK/plugin.video.netflix bf91b55089b06bd892eb6cd1457b3a87341fb5bf.
 * Copyright (C) 2017 Sebastian Golasch; 2018 Caphm; 2020 Stefano Gottardo.
 * USER_ID_TOKEN reauthentication follows Netflix/msl 63721e8fa2fee08b1e194b15cf906933590b6467.
 * Original Kotlin implementation. See THIRD_PARTY_NOTICES.md.
 */
package pl.kyvaith.velvet.playback

import org.json.JSONObject
import org.json.JSONTokener
import java.io.Closeable
import java.util.UUID

/** Explicitly entered credentials, consumed by authenticate(); never written to storage. */
class NetflixPlaybackCredentials(email: String, password: CharArray) : Closeable {
    private val emailChars = email.trim().toCharArray()
    private val passwordChars = password.copyOf()
    private var closed = false

    @Synchronized
    internal fun authenticationData(): JSONObject {
        if (closed || emailChars.isEmpty() || emailChars.size > 320 || passwordChars.isEmpty() || passwordChars.size > 4096) {
            throw playbackAuthorizationUnavailable("Brak danych natywnej autoryzacji odtwarzania.")
        }
        // JSON requires short-lived Strings. The char buffers and serialized header bytes are
        // cleared after use; the JVM cannot promise immediate erasure of immutable Strings.
        return JSONObject().put("email", String(emailChars)).put("password", String(passwordChars))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        emailChars.fill('\u0000')
        passwordChars.fill('\u0000')
    }

    override fun toString(): String = "NetflixPlaybackCredentials(redacted)"
}

/** Selects exactly one account mechanism, independently of native transport key exchange. */
internal sealed class MslAuthentication(val scheme: String, val mode: String) {
    abstract fun apply(header: JSONObject, context: PlaybackContext)
    override fun toString(): String = "MslAuthentication($mode,redacted)"

    /** NFToken is supplied separately to the native HTTP transport, never as MSL userauthdata. */
    object None : MslAuthentication("NONE", "token_consume") {
        override fun apply(header: JSONObject, context: PlaybackContext) = Unit
    }

    /** HTTP and MSL must use the same unambiguous, unmodified authentication cookie values. */
    object NetflixCookies : MslAuthentication("NETFLIXID", "cookies") {
        override fun apply(header: JSONObject, context: PlaybackContext) {
            val cookies = PlaybackCookies.snapshot(context.cookieHeader)
            header.put("userauthdata", JSONObject().put("scheme", scheme).put("authdata", JSONObject()
                .put("netflixid", cookies.netflixId).put("securenetflixid", cookies.secureNetflixId)))
        }
    }

    class EmailPassword(private val credentials: NetflixPlaybackCredentials) : MslAuthentication("EMAIL_PASSWORD", "owner_login") {
        override fun apply(header: JSONObject, context: PlaybackContext) {
            header.put("userauthdata", JSONObject().put("scheme", scheme).put("authdata", credentials.authenticationData()))
        }
    }

    class UserToken(private val token: JSONObject) : MslAuthentication("USER_ID_TOKEN", "profile_token") {
        override fun apply(header: JSONObject, context: PlaybackContext) { header.put("useridtoken", token) }
    }

    class SwitchProfile(private val ownerToken: JSONObject) : MslAuthentication("SWITCH_PROFILE", "profile_switch") {
        override fun apply(header: JSONObject, context: PlaybackContext) {
            if (context.profileGuid.isBlank()) throw playbackAuthorizationUnavailable("Wybierz profil Netflix przed autoryzacją odtwarzania.")
            header.put("userauthdata", JSONObject().put("scheme", scheme).put("authdata", JSONObject()
                .put("useridtoken", ownerToken).put("profileguid", context.profileGuid)))
        }
    }

    /** A previously issued pair for this same native identity; not a web-to-native migration. */
    class TokenReauthentication(private val pair: NativeMslTokenPair, val owner: Boolean) :
        MslAuthentication("USER_ID_TOKEN", if (owner) "owner_reauthentication" else "profile_reauthentication") {
        override fun apply(header: JSONObject, context: PlaybackContext) {
            if (pair.identity != context.esn) throw playbackAuthorizationUnavailable()
            header.put("userauthdata", JSONObject().put("scheme", scheme).put("authdata", JSONObject()
                .put("mastertoken", pair.masterToken).put("useridtoken", pair.userToken)))
        }
    }
}

/** Opaque, server-issued tokens, not transport/device keys. No generated toString. */
internal class NativeMslTokenPair(
    val identity: String,
    val masterToken: JSONObject,
    val userToken: JSONObject,
) {
    override fun toString(): String = "NativeMslTokenPair(redacted)"

    fun validate(nowSeconds: Long) {
        if (!identity.startsWith("NFANDROID1-") || identity.length !in 32..512 || identity.any { it.code !in 0x21..0x7e }) {
            invalidAuthentication()
        }
        MslTokenValidation.user(userToken, masterToken, nowSeconds)
    }

    /** Only the account-owner pair may cross the persistence boundary. */
    fun ownerRecord(): String = JSONObject().put("version", 1).put("role", "owner").put("identity", identity)
        .put("mastertoken", masterToken).put("useridtoken", userToken).toString().also {
            if (it.length > MAX_AUTH_RECORD_CHARS) invalidAuthentication()
        }

    companion object {
        fun readOwnerRecord(value: String, expectedIdentity: String, nowSeconds: Long): NativeMslTokenPair {
            try {
                if (value.isBlank() || value.length > MAX_AUTH_RECORD_CHARS) invalidAuthentication()
                val root = boundedJson(value, MAX_AUTH_RECORD_CHARS)
                if (root.optInt("version") != 1 || root.optString("role") != "owner" ||
                    root.opt("identity") !is String || root.getString("identity") != expectedIdentity) invalidAuthentication()
                return NativeMslTokenPair(expectedIdentity, root.getJSONObject("mastertoken"), root.getJSONObject("useridtoken"))
                    .also { it.validate(nowSeconds) }
            } catch (_: Exception) {
                throw playbackAuthorizationUnavailable()
            }
        }
    }
}

internal object MslTokenValidation {
    class Master(val serial: Long, val sequence: Long, val expiration: Long)

    fun master(token: JSONObject, nowSeconds: Long): Master = validate {
        val data = tokenData(token)
        val serial = number(data, "serialnumber")
        val sequence = number(data, "sequencenumber")
        val expiration = number(data, "expiration")
        checkTimes(data, expiration, nowSeconds)
        Master(serial, sequence, expiration)
    }

    fun user(token: JSONObject, masterToken: JSONObject, nowSeconds: Long): Long = validate {
        val master = master(masterToken, nowSeconds)
        val data = tokenData(token)
        if (number(data, "mtserialnumber") != master.serial) invalidAuthentication()
        number(data, "serialnumber")
        val expiration = number(data, "expiration")
        checkTimes(data, expiration, nowSeconds)
        expiration
    }

    private fun tokenData(token: JSONObject): JSONObject {
        if (token.toString().length > MAX_TOKEN_CHARS) invalidAuthentication()
        val encoded = token.opt("tokendata") as? String ?: invalidAuthentication()
        val signature = token.opt("signature") as? String ?: invalidAuthentication()
        if (encoded.isEmpty() || encoded.length > MAX_TOKEN_CHARS || signature.isEmpty() || signature.length > 8192) invalidAuthentication()
        val signatureBytes = MslEncoding.decode(signature)
        try { if (signatureBytes.isEmpty()) invalidAuthentication() } finally { signatureBytes.fill(0) }
        val bytes = MslEncoding.decode(encoded)
        return try { boundedJson(String(bytes, Charsets.UTF_8), MAX_TOKEN_CHARS) }
        finally { bytes.fill(0) }
    }

    private fun number(value: JSONObject, key: String): Long {
        val raw = value.opt(key)
        if (raw !is Number || !raw.toString().matches(Regex("[0-9]{1,16}"))) invalidAuthentication()
        return raw.toString().toLongOrNull()?.takeIf { it in 0..9007199254740991L } ?: invalidAuthentication()
    }

    private fun checkTimes(data: JSONObject, expiration: Long, nowSeconds: Long) {
        if (expiration <= nowSeconds) invalidAuthentication()
        if (data.has("renewalwindow") && number(data, "renewalwindow") > expiration) invalidAuthentication()
    }

    private inline fun <T> validate(block: () -> T): T = try { block() }
    catch (_: Exception) { invalidAuthentication() }
}

/** Actual bootstrap metadata only; no fabricated browser/device or playback measurements. */
internal fun nativeLogblobParams(context: PlaybackContext, nowMillis: Long): JSONObject {
    val entry = JSONObject().put("type", "startup").put("sev", "info")
        .put("appLogSeqNum", 0).put("uniqueLogId", UUID.randomUUID().toString())
        .put("appId", nowMillis).put("esn", context.esn)
        .put("clienttime", nowMillis).put("client_utc", nowMillis / 1000)
    return JSONObject().put("logblobs", JSONObject().put("entries", org.json.JSONArray().put(entry)).toString())
}

internal fun playbackAuthorizationUnavailable(message: String = "Velvet nie uzyskał autoryzacji odtwarzania od Netflixa. Odtwarzanie w tej wersji pozostaje niedostępne.",
                                   serviceCode: String? = null, mslResponseCode: Int? = null): NetflixPlaybackException =
    NetflixPlaybackException(PlaybackStage.AUTHENTICATION, serviceCode, message, retryable = false,
        authenticationRejected = true, mslResponseCode = mslResponseCode)

private const val MAX_TOKEN_CHARS = 64 * 1024
private const val MAX_AUTH_RECORD_CHARS = 2 * MAX_TOKEN_CHARS + 2048

private fun invalidAuthentication(): Nothing = throw NetflixPlaybackException(PlaybackStage.AUTHENTICATION,
    message = "Netflix zwrócił nieprawidłowe lub wygasłe dane autoryzacji odtwarzania.", retryable = false)

/** Bound bytes and nesting before JSONObject allocation; reject trailing JSON values as well. */
private fun boundedJson(value: String, limit: Int): JSONObject {
    if (value.length !in 1..limit) invalidAuthentication()
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in value) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 12) invalidAuthentication()
            '}', ']' -> if (--depth < 0) invalidAuthentication()
        }
    }
    if (depth != 0 || quoted) invalidAuthentication()
    val parser = JSONTokener(value)
    val result = parser.nextValue() as? JSONObject ?: invalidAuthentication()
    if (parser.nextClean() != '\u0000') invalidAuthentication()
    return result
}
