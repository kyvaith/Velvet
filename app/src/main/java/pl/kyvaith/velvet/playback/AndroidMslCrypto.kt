/*
 * SPDX-License-Identifier: MIT
 * Protocol mapping adapted from android_crypto.py, atrHusK/plugin.video.netflix,
 * commit bf91b55089b06bd892eb6cd1457b3a87341fb5bf.
 * Copyright (C) 2017 Sebastian Golasch; 2018 Caphm.
 * Original Kotlin MediaDrm facade, lifecycle and validation: Velvet contributors.
 * See THIRD_PARTY_NOTICES.md.
 */
package pl.kyvaith.velvet.playback

import org.json.JSONObject
import pl.kyvaith.velvet.diagnostics.AppLog
import java.security.SecureRandom

/**
 * Android's WIDEVINE exchange establishes protected MSL transport keys in CryptoSession.
 * These are not the movie keys. Only opaque key identifiers enter Kotlin; encryption,
 * decryption and HMAC run inside the platform CDM. KEY_TYPE_OFFLINE may persist a protected
 * key set in that CDM. Velvet retains its opaque handle in memory and requests local removal
 * on normal close; process death or a removal failure can leave an orphaned platform entry.
 * The app does not serialize transport keys/key-set handles or enumerate other stored licenses.
 */
internal class AndroidMslCrypto private constructor(
    private val drm: NativeMslDrm,
    private val provision: (String, ByteArray) -> ByteArray,
) : MslCrypto {
    override val keyExchangeScheme = "WIDEVINE"
    var identity: String = ""
        private set
    var securityLevel: String = ""
        private set

    private val random = SecureRandom()
    private var systemId = ""
    /** Public CDM model identifier for native config; never a device secret or a content key. */
    val widevineSystemId: String get() = systemId
    private var scope: ByteArray? = null
    private var protectedCrypto: NativeMslCryptoSession? = null
    private var encryptionKeyId: ByteArray? = null
    private var hmacKeyId: ByteArray? = null
    private val ownedKeySets = mutableListOf<ByteArray>()
    private var requestIssued = false
    private var pendingResponse = false
    private var provisioningAttempted = false
    private var closed = false

    private fun initialize(identityFor: (String, String) -> String) {
        openSession()
        systemId = drm.property("systemId").trim()
        securityLevel = drm.property("securityLevel").trim()
        if (systemId.isEmpty() || securityLevel.isEmpty()) fail("Urządzenie nie udostępniło parametrów natywnego DRM.")
        identity = identityFor(systemId, securityLevel)
        if (identity.isBlank()) fail("Nie można utworzyć tożsamości natywnego klienta odtwarzania.")
        AppLog.info("key_exchange", "native_msl_ready", mapOf("keyExchange" to keyExchangeScheme,
            "securityLevel" to securityLevel.takeIf { it in setOf("L1", "L2", "L3") }.orEmpty().ifBlank { "UNKNOWN" }))
    }

    @Synchronized
    override fun keyRequest(): JSONObject = cryptoCall("key_request", "Nie można przygotować natywnej wymiany kluczy MSL.") {
        // A second request gets a fresh CDM session, so a pending response/old key set is not
        // accidentally reused. Kodi also reopens its native session when removing installed keys.
        if (requestIssued) {
            if (!releaseSession()) {
                close()
                fail("Nie można bezpiecznie odnowić natywnej sesji MSL.")
            }
            openSession()
            checkDeviceProperties()
        }
        requestIssued = true
        pendingResponse = false
        val challenge = try {
            requestChallenge()
        } catch (_: NativeMslProvisionRequired) {
            provisionDevice()
            checkDeviceProperties()
            requestChallenge()
        }
        try {
            if (challenge.isEmpty() || challenge.size > MAX_NATIVE_BYTES) fail("Urządzenie zwróciło nieprawidłowe żądanie kluczy MSL.")
            val result = JSONObject().put("scheme", keyExchangeScheme).put("keydata", JSONObject()
                .put("keyrequest", MslEncoding.encode(challenge)))
            pendingResponse = true
            AppLog.info("key_exchange", "native_msl_key_request", mapOf("keyExchange" to keyExchangeScheme,
                "challengeBytes" to challenge.size))
            result
        } finally { challenge.fill(0) }
    }

    private fun requestChallenge(): ByteArray = drm.keyRequest(requireScope(), GENERIC_INIT.copyOf(),
        "application/xml", OFFLINE_KEY_TYPE, emptyMap())

    @Synchronized
    override fun acceptKeyResponse(keyResponse: JSONObject) = cryptoCall("key_response", "Nie można przyjąć natywnych kluczy komunikacji MSL.") {
        if (!pendingResponse) fail("Brak oczekującego żądania natywnej wymiany kluczy MSL.")
        if (keyResponse.optString("scheme") != keyExchangeScheme) fail("Odpowiedź ma inny typ wymiany kluczy MSL.")
        val data = keyResponse.optJSONObject("keydata") ?: fail("Odpowiedź nie zawiera danych kluczy MSL.")
        var response: ByteArray? = null
        var nextEncryption: ByteArray? = null
        var nextHmac: ByteArray? = null
        try {
            // Validate all wire fields before installing a CDM response. Key IDs are opaque
            // handles, not AES/HMAC key bytes, and need not have the size of those keys.
            response = decodeField(data, "cdmkeyresponse", MAX_NATIVE_BYTES)
            nextEncryption = decodeField(data, "encryptionkeyid", MAX_KEY_ID_BYTES)
            nextHmac = decodeField(data, "hmackeyid", MAX_KEY_ID_BYTES)
            pendingResponse = false
            val keySet = drm.provideKeyResponse(requireScope(), response)
            // Record ownership before validating the result, so an unexpected key-set handle
            // is still removed during cleanup. KEY_TYPE_OFFLINE must return a nonempty handle.
            if (keySet.isNotEmpty()) ownedKeySets += keySet
            if (keySet.isEmpty() || keySet.size > MAX_HANDLE_BYTES) {
                close()
                fail("Urządzenie nie potwierdziło instalacji natywnych kluczy MSL.")
            }
            encryptionKeyId = nextEncryption
            hmacKeyId = nextHmac
            nextEncryption = null
            nextHmac = null
            AppLog.info("key_exchange", "native_msl_keys_installed", mapOf("keyExchange" to keyExchangeScheme,
                "responseBytes" to response.size))
        } finally {
            response?.fill(0)
            nextEncryption?.fill(0)
            nextHmac?.fill(0)
        }
    }

    @Synchronized
    override fun encrypt(plaintext: ByteArray): ByteArray = cryptoCall("encrypt", "Nie udało się zaszyfrować żądania MSL.") {
        val keyId = encryptionKeyId ?: fail("Sesja MSL nie ma klucza szyfrowania.")
        if (plaintext.size > MAX_MSL_BYTES - 16) fail("Zbyt duży komunikat MSL.")
        val iv = ByteArray(16).also(random::nextBytes)
        val padded = MslEncoding.pad(plaintext)
        try {
            val encrypted = requireCrypto().encrypt(keyId, padded, iv)
            if (encrypted.size != padded.size) fail("Urządzenie zwróciło nieprawidłowy zaszyfrowany komunikat MSL.")
            MslEncoding.utf8(JSONObject().put("version", 1).put("ciphertext", MslEncoding.encode(encrypted))
                .put("sha256", "AA==").put("keyid", MslEncoding.encode(keyId)).put("iv", MslEncoding.encode(iv)).toString())
                .also { if (it.size > MAX_MSL_BYTES) fail("Zbyt duży komunikat MSL.") }
        } finally { padded.fill(0) }
    }

    @Synchronized
    override fun decrypt(envelope: ByteArray): ByteArray = cryptoCall("decrypt", "Nie udało się odszyfrować odpowiedzi MSL.") {
        val keyId = encryptionKeyId ?: fail("Sesja MSL nie ma klucza szyfrowania.")
        boundedMessage(envelope)
        val value = MslEncoding.json(envelope)
        if (value.has("version") && value.optInt("version") != 1) fail("Nieobsługiwana koperta szyfrowania MSL.")
        val iv = decodeField(value, "iv", 16)
        val ciphertext = decodeField(value, "ciphertext", MAX_MSL_BYTES)
        if (iv.size != 16 || ciphertext.size % 16 != 0) fail("Nieprawidłowy zaszyfrowany komunikat MSL.")
        val padded = requireCrypto().decrypt(keyId, ciphertext, iv)
        try {
            if (padded.size != ciphertext.size) fail("Urządzenie zwróciło nieprawidłowy odszyfrowany komunikat MSL.")
            MslEncoding.unpad(padded)
        } finally { padded.fill(0) }
    }

    @Synchronized
    override fun sign(message: ByteArray): ByteArray = cryptoCall("sign", "Nie udało się podpisać żądania MSL.") {
        boundedMessage(message)
        val keyId = hmacKeyId ?: fail("Sesja MSL nie ma klucza podpisu.")
        requireCrypto().sign(keyId, message).also {
            if (it.size != 32) fail("Urządzenie zwróciło nieprawidłowy podpis MSL.")
        }
    }

    @Synchronized
    override fun verify(message: ByteArray, signature: ByteArray): Boolean = cryptoCall("verify", "Nie udało się sprawdzić podpisu MSL.") {
        boundedMessage(message)
        val keyId = hmacKeyId ?: fail("Sesja MSL nie ma klucza podpisu.")
        signature.size == 32 && requireCrypto().verify(keyId, message, signature)
    }

    private fun openSession() {
        try { openOnce() }
        catch (_: NativeMslProvisionRequired) {
            if (!releaseSession()) fail("Nie można przygotować natywnej sesji DRM.")
            provisionDevice()
            openOnce()
        }
    }

    private fun openOnce() {
        scope = drm.openSession()
        if (requireScope().isEmpty() || requireScope().size > MAX_HANDLE_BYTES) fail("Urządzenie zwróciło nieprawidłową sesję DRM.")
        protectedCrypto = drm.cryptoSession(requireScope(), "AES/CBC/NoPadding", "HmacSHA256")
    }

    private fun checkDeviceProperties() {
        if (drm.property("systemId").trim() != systemId || drm.property("securityLevel").trim() != securityLevel) {
            fail("Parametry natywnego DRM zmieniły się. Uruchom odtwarzanie ponownie.")
        }
    }

    private fun provisionDevice() {
        if (provisioningAttempted) provisionFailure()
        provisioningAttempted = true
        AppLog.info("provisioning", "native_msl_provision_start")
        var request: NativeMslProvisionRequest? = null
        var response: ByteArray? = null
        try {
            request = drm.provisionRequest()
            if (request.data.isEmpty() || request.data.size > MAX_NATIVE_BYTES) provisionFailure()
            response = provision(request.defaultUrl, request.data)
            if (response.isEmpty() || response.size > MAX_NATIVE_BYTES) provisionFailure()
            drm.provideProvisionResponse(response)
            AppLog.info("provisioning", "native_msl_provision_success", mapOf("responseBytes" to response.size))
        } catch (error: Exception) {
            AppLog.error("provisioning", "native_msl_provision_failed", error)
            provisionFailure()
        } finally {
            request?.data?.fill(0)
            response?.fill(0)
        }
    }

    private fun requireScope(): ByteArray = scope ?: fail("Brak otwartej natywnej sesji DRM.")
    private fun requireCrypto(): NativeMslCryptoSession = protectedCrypto ?: fail("Brak chronionej sesji kryptograficznej DRM.")

    private inline fun <T> cryptoCall(operation: String, message: String, block: () -> T): T {
        if (closed) fail("Sesja MSL została zamknięta.")
        return try { block() }
        catch (error: NetflixPlaybackException) {
            if (error.stage == PlaybackStage.PROVISIONING) close()
            throw error
        }
        catch (_: NativeMslProvisionRequired) {
            close()
            provisionFailure()
        } catch (error: Exception) {
            AppLog.error("key_exchange", "native_msl_crypto_failed", error, mapOf("operation" to operation))
            close()
            fail(message)
        }
    }

    /** Each cleanup step runs even if a preceding native call failed. */
    private fun releaseSession(): Boolean {
        pendingResponse = false
        encryptionKeyId?.fill(0)
        hmacKeyId?.fill(0)
        encryptionKeyId = null
        hmacKeyId = null
        protectedCrypto = null
        var success = true
        val oldScope = scope
        if (oldScope != null && !cleanup("remove_keys") { drm.removeKeys(oldScope) }) success = false
        for (keySet in ownedKeySets) {
            if (!cleanup("remove_offline_license") { drm.removeOfflineLicense(keySet) }) success = false
            keySet.fill(0)
        }
        ownedKeySets.clear()
        if (oldScope != null) {
            if (!cleanup("close_session") { drm.closeSession(oldScope) }) success = false
            oldScope.fill(0)
        }
        scope = null
        return success
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val released = releaseSession()
        val drmClosed = cleanup("close_drm") { drm.close() }
        AppLog.info("release", "native_msl_closed", mapOf("success" to (released && drmClosed)))
    }

    private inline fun cleanup(operation: String, action: () -> Unit): Boolean = try {
        action()
        AppLog.info("release", "native_msl_cleanup", mapOf("operation" to operation, "success" to true))
        true
    } catch (error: Exception) {
        AppLog.error("release", "native_msl_cleanup", error, mapOf("operation" to operation, "success" to false))
        false
    }

    companion object {
        private const val MAX_NATIVE_BYTES = 1024 * 1024
        private const val MAX_KEY_ID_BYTES = 256
        private const val MAX_HANDLE_BYTES = 4096
        // Kodi's generic operator-key initialization, not a movie PSSH. Empty optional params.
        private val GENERIC_INIT = byteArrayOf(10, 122, 0, 108, 56, 43)
        private const val OFFLINE_KEY_TYPE = 2 // android.media.MediaDrm.KEY_TYPE_OFFLINE

        fun create(transport: PlaybackHttp, identityFor: (String, String) -> String): AndroidMslCrypto =
            create({ PlatformNativeMslDrm.create() }, transport::provision, identityFor)

        /** Injection boundary exercises the protocol and lifecycle with no Android/JVM CDM. */
        internal fun create(drmFactory: () -> NativeMslDrm,
                            provision: (String, ByteArray) -> ByteArray,
                            identityFor: (String, String) -> String): AndroidMslCrypto {
            var openedDrm: NativeMslDrm? = null
            var result: AndroidMslCrypto? = null
            try {
                openedDrm = drmFactory()
                result = AndroidMslCrypto(openedDrm, provision)
                result.initialize(identityFor)
                return result
            } catch (error: Exception) {
                if (result != null) result.close()
                else if (openedDrm != null) {
                    try { openedDrm.close() } catch (releaseError: Exception) {
                        AppLog.error("release", "native_msl_cleanup", releaseError,
                            mapOf("operation" to "close_drm", "success" to false))
                    }
                }
                if (error is NetflixPlaybackException) throw error
                if (error is NativeMslProvisionRequired) provisionFailure()
                AppLog.error("key_exchange", "native_msl_crypto_failed", error, mapOf("operation" to "initialize"))
                fail("Nie można uruchomić natywnej wymiany kluczy MSL na tym urządzeniu.")
            }
        }

        private fun boundedMessage(bytes: ByteArray) {
            if (bytes.size > MAX_MSL_BYTES) fail("Zbyt duży komunikat MSL.")
        }

        private fun decodeField(value: JSONObject, name: String, maxBytes: Int): ByteArray {
            val encoded = value.opt(name) as? String ?: fail("Nieprawidłowe dane natywnej wymiany kluczy MSL.")
            if (encoded.isEmpty() || encoded.length > ((maxBytes + 2) / 3) * 4) fail("Nieprawidłowy rozmiar danych MSL.")
            return MslEncoding.decode(encoded).also {
                if (it.isEmpty() || it.size > maxBytes) {
                    it.fill(0)
                    fail("Nieprawidłowy rozmiar danych MSL.")
                }
            }
        }

        private fun fail(message: String): Nothing = throw NetflixPlaybackException(PlaybackStage.KEY_EXCHANGE, message = message)
        private fun provisionFailure(): Nothing = throw NetflixPlaybackException(PlaybackStage.PROVISIONING,
            message = "Nie udało się przygotować natywnego DRM urządzenia. Spróbuj ponownie uruchomić odtwarzanie.")
    }
}
