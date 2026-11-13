/*
 * SPDX-License-Identifier: MIT
 * Protocol mapping adapted from default_crypto.py, CastagnaIT/plugin.video.netflix,
 * commit 7accc24cfbf2bf798c037996119e08e6334d64cb.
 * Copyright (C) 2017 Sebastian Golasch; 2017 Trummerjo; 2018 Caphm.
 * JCA implementation and validation: Velvet contributors. See THIRD_PARTY_NOTICES.md.
 */
package pl.kyvaith.velvet.playback

import org.json.JSONObject
import pl.kyvaith.velvet.diagnostics.AppLog
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Transport keys for the real browser identity obtained during login. They protect MSL
 * messages, not movie bytes. Media3 still obtains a separate content license from native
 * Widevine. There is no generated ESN, certificate substitution or L1/L3 change here.
 *
 * A web ESN must not be sent with Android's generic WIDEVINE key exchange. The reported
 * KEYX_INCORRECT_DATA (107016) is consistent with that cross-family mismatch. The web
 * exchange used by Kodi is ASYMMETRIC_WRAPPED/JWK_RSA: RSA-OAEP wraps JSON transport keys.
 */
internal class BrowserMslCrypto(private val identity: String) : MslCrypto {
    override val keyExchangeScheme = "ASYMMETRIC_WRAPPED"
    private val random = SecureRandom()
    private var pendingKeyPair: KeyPair? = null
    private var pendingKeyPairId: String? = null
    private var encryptionKey: ByteArray? = null
    private var hmacKey: ByteArray? = null
    private var envelopeKeyId: String? = null
    private var closed = false

    override fun keyRequest(): JSONObject = cryptoCall("Nie można przygotować przeglądarkowej wymiany kluczy MSL.") {
        if (identity.isBlank()) fail("Brak tożsamości zalogowanej sesji przeglądarkowej.")
        pendingKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        pendingKeyPairId = UUID.randomUUID().toString()
        JSONObject().put("scheme", keyExchangeScheme).put("keydata", JSONObject()
            .put("mechanism", "JWK_RSA").put("keypairid", pendingKeyPairId)
            .put("publickey", MslEncoding.encode(pendingKeyPair!!.public.encoded)))
    }

    override fun acceptKeyResponse(keyResponse: JSONObject) = cryptoCall("Nie można odczytać kluczy komunikacji MSL.") {
        val pair = pendingKeyPair ?: fail("Brak oczekującego żądania wymiany kluczy MSL.")
        if (keyResponse.optString("scheme") != keyExchangeScheme) fail("Odpowiedź ma inny typ wymiany kluczy MSL.")
        val data = keyResponse.getJSONObject("keydata")
        if (data.optString("keypairid") != pendingKeyPairId) fail("Odpowiedź wymiany kluczy MSL nie pasuje do żądania.")
        val tokenData = MslEncoding.json(MslEncoding.decode(keyResponse.getJSONObject("mastertoken").getString("tokendata")))
        val sequence = tokenData.optLong("sequencenumber", -1L)
        if (sequence !in 0..9007199254740991L) fail("Nieprawidłowy numer kluczy sesji MSL.")
        // SHA-1 here is the MSL JWK_RSA wrapping algorithm (RSA-OAEP/MGF1-SHA1), not a
        // signature or TLS setting. Explicit parameters avoid provider-specific defaults.
        val unwrap = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.DECRYPT_MODE, pair.private, OAEP_SHA1)
        }
        var nextEncryption: ByteArray? = null
        var nextHmac: ByteArray? = null
        try {
            nextEncryption = unwrapJwk(unwrap, data.getString("encryptionkey"), "A128CBC", 16)
            nextHmac = unwrapJwk(unwrap, data.getString("hmackey"), "HS256", 32)
            encryptionKey?.fill(0)
            hmacKey?.fill(0)
            encryptionKey = nextEncryption
            hmacKey = nextHmac
            envelopeKeyId = identity + "_" + sequence
            nextEncryption = null
            nextHmac = null
            pendingKeyPair = null
            pendingKeyPairId = null
        } finally {
            nextEncryption?.fill(0)
            nextHmac?.fill(0)
        }
    }

    private fun unwrapJwk(cipher: Cipher, encoded: String, expectedAlgorithm: String, expectedSize: Int): ByteArray {
        val plaintext = cipher.doFinal(MslEncoding.decode(encoded))
        try {
            val jwk = MslEncoding.json(plaintext)
            if (jwk.optString("kty") != "oct") fail("Klucz komunikacji MSL ma nieobsługiwany typ.")
            val algorithm = jwk.optString("alg")
            if (algorithm.isNotEmpty() && algorithm != expectedAlgorithm) fail("Nieprawidłowy algorytm klucza MSL.")
            val key = Base64.getUrlDecoder().decode(jwk.getString("k"))
            if (key.size != expectedSize) {
                key.fill(0)
                fail("Nieprawidłowa długość klucza komunikacji MSL.")
            }
            return key
        } finally { plaintext.fill(0) }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray = cryptoCall("Nie udało się zaszyfrować żądania MSL.") {
        val key = encryptionKey ?: fail("Sesja MSL nie ma klucza szyfrowania.")
        val iv = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        MslEncoding.utf8(JSONObject().put("version", 1).put("ciphertext", MslEncoding.encode(cipher.doFinal(plaintext)))
            .put("sha256", "AA==").put("keyid", envelopeKeyId).put("iv", MslEncoding.encode(iv)).toString())
    }

    override fun decrypt(envelope: ByteArray): ByteArray = cryptoCall("Nie udało się odszyfrować odpowiedzi MSL.") {
        val key = encryptionKey ?: fail("Sesja MSL nie ma klucza szyfrowania.")
        val value = MslEncoding.json(envelope)
        if (value.has("version") && value.optInt("version") != 1) fail("Nieobsługiwana koperta szyfrowania MSL.")
        val iv = MslEncoding.decode(value.getString("iv"))
        val ciphertext = MslEncoding.decode(value.getString("ciphertext"))
        if (iv.size != 16 || ciphertext.isEmpty() || ciphertext.size % 16 != 0) fail("Nieprawidłowy zaszyfrowany komunikat MSL.")
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(ciphertext)
    }

    override fun sign(message: ByteArray): ByteArray = cryptoCall("Nie udało się podpisać żądania MSL.") {
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hmacKey ?: fail("Sesja MSL nie ma klucza podpisu."), "HmacSHA256"))
        }.doFinal(message)
    }

    override fun verify(message: ByteArray, signature: ByteArray): Boolean = cryptoCall("Nie udało się sprawdzić podpisu MSL.") {
        signature.size == 32 && MessageDigest.isEqual(sign(message), signature)
    }

    private inline fun <T> cryptoCall(message: String, block: () -> T): T {
        if (closed) fail("Sesja MSL została zamknięta.")
        return try { block() } catch (error: NetflixPlaybackException) { throw error }
        catch (error: Exception) {
            AppLog.error("key_exchange", "transport_crypto_failed", error)
            fail(message)
        } // No provider exception text, keys or body in diagnostics.
    }

    override fun close() {
        if (closed) return
        closed = true
        encryptionKey?.fill(0)
        hmacKey?.fill(0)
        encryptionKey = null
        hmacKey = null
        envelopeKeyId = null
        pendingKeyPair = null
        pendingKeyPairId = null
    }

    companion object {
        private val OAEP_SHA1 = OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        private fun fail(message: String): Nothing = throw NetflixPlaybackException(PlaybackStage.KEY_EXCHANGE, message = message)
    }
}
