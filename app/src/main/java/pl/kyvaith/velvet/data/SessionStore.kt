// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import pl.kyvaith.velvet.playback.PlaybackContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SessionData(
    val cookieHeader: String,
    val userAgent: String,
    val authUrl: String = "",
    val esn: String = "",
    val profileGuid: String = "",
    val buildIdentifier: String = "",
    val clientVersion: String = "",
    val uiVersion: String = "",
    val browserVersion: String = "",
    val osName: String = "",
    val osVersion: String = "",
    val locale: String = "pl-PL",
    val apiUrl: String = "https://www.netflix.com/nq/website/memberapi/release",
    val isAdsPlan: Boolean = false,
    /** Serialized native identity and owner token pair. Passwords must never enter session storage. */
    val nativePlaybackAuth: String = "",
) {
    override fun toString() = "SessionData(redacted)"
    fun playbackContext() = PlaybackContext(
        cookieHeader = cookieHeader, authUrl = authUrl, esn = esn, profileGuid = profileGuid,
        userAgent = userAgent, clientVersion = clientVersion, uiVersion = uiVersion,
        browserVersion = browserVersion, osName = osName, osVersion = osVersion,
        buildIdentifier = buildIdentifier, locale = locale,
    )
    internal fun toJson() = json(
        "cookieHeader" to cookieHeader, "userAgent" to userAgent, "authUrl" to authUrl,
        "esn" to esn, "profileGuid" to profileGuid, "buildIdentifier" to buildIdentifier,
        "clientVersion" to clientVersion, "uiVersion" to uiVersion, "browserVersion" to browserVersion,
        "osName" to osName, "osVersion" to osVersion, "locale" to locale, "apiUrl" to apiUrl,
        "isAdsPlan" to isAdsPlan, "nativePlaybackAuth" to nativePlaybackAuth,
    )
    companion object {
        internal fun fromJson(j: JSONObject) = SessionData(
            cookieHeader=j.getString("cookieHeader"), userAgent=j.getString("userAgent"),
            authUrl=j.optString("authUrl"), esn=j.optString("esn"), profileGuid=j.optString("profileGuid"),
            buildIdentifier=j.optString("buildIdentifier"), clientVersion=j.optString("clientVersion"),
            uiVersion=j.optString("uiVersion"), browserVersion=j.optString("browserVersion"),
            osName=j.optString("osName"), osVersion=j.optString("osVersion"), locale=j.optString("locale", "pl-PL"),
            apiUrl=j.optString("apiUrl", "https://www.netflix.com/nq/website/memberapi/release"),
            isAdsPlan=j.optBoolean("isAdsPlan"), nativePlaybackAuth=j.optString("nativePlaybackAuth"),
        )
    }
}

/** Account tokens and local resume positions are encrypted using a non-exportable Android key. */
class SessionStore(context: Context, preferencesName: String = "velvet_private") {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var cached: SessionData? = null
    @Volatile var generation: Long = 0
        private set
    @Volatile var unreadableSession = false
        private set

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val parts = value.split(':', limit=2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    fun read(): SessionData? = synchronized(lock) {
        if (!loaded) {
            cached = prefs.getString("session", null)?.let { value ->
                try { SessionData.fromJson(JSONObject(decrypt(value))) }
                catch (_: Exception) {
                    unreadableSession = true
                    prefs.edit().clear().commit()
                    null
                }
            }
            loaded = true
        }
        cached
    }
    fun read(expectedGeneration: Long): SessionData? = synchronized(lock) {
        requireGeneration(expectedGeneration)
        read()
    }
    /** Validate a captured owner and read its values under the same lock as account changes. */
    fun <T> read(expectedGeneration: Long, transform: (SessionData?) -> T): T = synchronized(lock) {
        requireGeneration(expectedGeneration)
        transform(read())
    }
    fun save(session: SessionData, expectedGeneration: Long = generation) = synchronized(lock) {
        requireGeneration(expectedGeneration)
        check(prefs.edit().putString("session", encrypt(session.toJson().toString())).commit()) { "Nie udało się zapisać sesji." }
        cached = session
        loaded = true
        unreadableSession = false
    }
    fun beginSession(session: SessionData) = synchronized(lock) {
        generation++
        // A new login must not inherit native owner tokens copied from a previous account.
        save(session.copy(nativePlaybackAuth = ""), generation)
    }
    fun invalidateInFlight() = synchronized(lock) { generation++ }
    fun update(expectedGeneration: Long = generation, transform: (SessionData) -> SessionData) = synchronized(lock) {
        requireGeneration(expectedGeneration)
        read()?.let { save(transform(it),expectedGeneration) }
    }
    /** Replace derived client identity and invalidate outstanding work atomically with logout. */
    fun transition(expectedGeneration: Long, transform: (SessionData) -> SessionData): Long? = synchronized(lock) {
        requireGeneration(expectedGeneration)
        val current = read() ?: return@synchronized null
        val replacement = transform(current)
        generation++
        save(replacement, generation)
        generation
    }
    fun requireGeneration(expected: Long) {
        if(generation != expected) throw NetflixApiException("sesja",message="Sesja została zmieniona. Ponów operację.")
    }
    fun clear() = synchronized(lock) {
        generation++
        prefs.edit().clear().commit()
        cached = null
        loaded = true
        unreadableSession = false
    }

    fun resumeSeconds(profile: String, video: String): Long = synchronized(lock) {
        val positions = prefs.getString("positions", null)?.let {
            runCatching { JSONObject(decrypt(it)) }.getOrNull()
        } ?: return@synchronized 0
        positions.optLong("$profile:$video", 0)
    }
    fun savePosition(profile: String, video: String, seconds: Long, expectedGeneration: Long = generation) = synchronized(lock) {
        if (profile.isBlank() || video.isBlank()) return@synchronized
        if (generation != expectedGeneration) return@synchronized
        if (read()?.profileGuid != profile) return@synchronized
        val positions = prefs.getString("positions", null)?.let {
            runCatching { JSONObject(decrypt(it)) }.getOrNull()
        } ?: JSONObject()
        positions.put("$profile:$video", seconds.coerceAtLeast(0))
        // Bounded private history. No title names or stream URLs are stored.
        while (positions.length() > 500) positions.remove(positions.keys().next())
        prefs.edit().putString("positions", encrypt(positions.toString())).apply()
    }
    companion object { private const val KEY_ALIAS = "velvet_session_v1" }
}
