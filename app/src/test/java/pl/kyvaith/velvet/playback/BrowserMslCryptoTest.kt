package pl.kyvaith.velvet.playback

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Independent local server fixtures exercise real RSA, AES and HMAC; no Netflix account. */
class BrowserMslCryptoTest {
    private val aes = ByteArray(16) { (it + 10).toByte() }
    private val hmac = ByteArray(32) { (it + 20).toByte() }

    @Test fun `browser handshake advertises asymmetric wrapped not device Widevine`() {
        BrowserMslCrypto("observed-web-identity").use { crypto ->
            val request = MslWire(crypto).handshake("observed-web-identity", "pl-PL")
            val outer = MslWire.parseObjects(request.wire).first()
            assertEquals("NONE", outer.getJSONObject("entityauthdata").getString("scheme"))
            assertEquals("observed-web-identity", outer.getJSONObject("entityauthdata").getJSONObject("authdata").getString("identity"))
            val header = MslEncoding.json(MslEncoding.decode(outer.getString("headerdata")))
            val data = header.getJSONArray("keyrequestdata").getJSONObject(0)
            assertEquals("ASYMMETRIC_WRAPPED", data.getString("scheme"))
            assertEquals("JWK_RSA", data.getJSONObject("keydata").getString("mechanism"))
            assertEquals(2048, (publicKey(data) as RSAPublicKey).modulus.bitLength())
            assertFalse(request.wire.contains("WIDEVINE"))
            assertFalse(header.has("userauthdata"))
        }
    }

    @Test fun `unwraps OAEP JWK keys then encrypts and signs with installed server keys`() {
        BrowserMslCrypto("observed-web-identity").use { crypto ->
            val request = crypto.keyRequest()
            crypto.acceptKeyResponse(keyResponse(request))
            val plaintext = "Zażółć gęślą 🐈".toByteArray()
            val envelope = crypto.encrypt(plaintext)
            val parsed = MslEncoding.json(envelope)
            assertEquals("observed-web-identity_17", parsed.getString("keyid"))
            val serverCipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(MslEncoding.decode(parsed.getString("iv"))))
            }
            assertArrayEquals(plaintext, serverCipher.doFinal(MslEncoding.decode(parsed.getString("ciphertext"))))
            val serverMac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmac, "HmacSHA256")) }.doFinal(envelope)
            assertArrayEquals(serverMac, crypto.sign(envelope))
            assertTrue(crypto.verify(envelope, serverMac))
            serverMac[0] = (serverMac[0].toInt() xor 1).toByte()
            assertFalse(crypto.verify(envelope, serverMac))
            assertArrayEquals(plaintext, crypto.decrypt(envelope))
        }
    }

    @Test fun `fresh IV is used for repeated plaintext`() {
        BrowserMslCrypto("observed-web-identity").use { crypto ->
            crypto.acceptKeyResponse(keyResponse(crypto.keyRequest()))
            val first = crypto.encrypt("same".toByteArray())
            val second = crypto.encrypt("same".toByteArray())
            assertFalse(first.contentEquals(second))
        }
    }

    @Test fun `wrong scheme and wrong keypair response cannot install keys`() {
        BrowserMslCrypto("observed-web-identity").use { crypto ->
            val request = crypto.keyRequest()
            expectFailure { crypto.acceptKeyResponse(keyResponse(request).put("scheme", "WIDEVINE")) }
            val wrong = keyResponse(request)
            wrong.getJSONObject("keydata").put("keypairid", "unrelated-request")
            expectFailure { crypto.acceptKeyResponse(wrong) }
            expectFailure { crypto.encrypt(byteArrayOf(1)) }
        }
    }

    @Test fun `read handshake enforces requested crypto family`() {
        BrowserMslCrypto("observed-web-identity").use { crypto ->
            val request = crypto.keyRequest()
            fun wire(scheme: String): String = JSONObject().put("headerdata", MslEncoding.encode(JSONObject()
                .put("messageid", 43).put("keyresponsedata", keyResponse(request).put("scheme", scheme)).toString().toByteArray())).toString()
            assertEquals("ASYMMETRIC_WRAPPED", MslWire(crypto).readHandshake(wire("ASYMMETRIC_WRAPPED"), 42).getString("scheme"))
            expectFailure { MslWire(crypto).readHandshake(wire("WIDEVINE"), 42) }
        }
    }

    @Test fun `new handshake keypair does not accept previous response and installed response cannot be replayed`() {
        BrowserMslCrypto("observed-web-identity").use { crypto ->
            val first = crypto.keyRequest()
            val second = crypto.keyRequest()
            assertNotEquals(first.getJSONObject("keydata").getString("publickey"), second.getJSONObject("keydata").getString("publickey"))
            expectFailure { crypto.acceptKeyResponse(keyResponse(first)) }
            val response = keyResponse(second)
            crypto.acceptKeyResponse(response)
            expectFailure { crypto.acceptKeyResponse(response) }
        }
    }

    @Test fun `wrong JWK type algorithm and length are rejected atomically`() {
        val badJwks = listOf(
            jwk(aes, "A128CBC").put("kty", "RSA"),
            jwk(aes, "HS256"),
            jwk(ByteArray(8), "A128CBC"),
        )
        for (bad in badJwks) BrowserMslCrypto("observed-web-identity").use { crypto ->
            val request = crypto.keyRequest()
            val response = keyResponse(request)
            response.getJSONObject("keydata").put("encryptionkey", wrap(publicKey(request), bad))
            expectFailure { crypto.acceptKeyResponse(response) }
            expectFailure { crypto.encrypt(byteArrayOf(1)) }
        }
        BrowserMslCrypto("observed-web-identity").use { crypto ->
            val request = crypto.keyRequest()
            val response = keyResponse(request)
            response.getJSONObject("keydata").put("hmackey", wrap(publicKey(request), jwk(ByteArray(8), "HS256")))
            expectFailure { crypto.acceptKeyResponse(response) }
            expectFailure { crypto.encrypt(byteArrayOf(1)) }
        }
    }

    @Test fun `tampered RSA ciphertext and malformed AES envelope fail safely`() {
        BrowserMslCrypto("observed-web-identity").use { crypto ->
            val request = crypto.keyRequest()
            val bad = keyResponse(request)
            bad.getJSONObject("keydata").put("encryptionkey", MslEncoding.encode(ByteArray(256)))
            val error = expectFailure { crypto.acceptKeyResponse(bad) }
            assertNull(error.cause)
            crypto.acceptKeyResponse(keyResponse(request))
            val envelope = MslEncoding.json(crypto.encrypt(byteArrayOf(1)))
            envelope.put("iv", MslEncoding.encode(ByteArray(15)))
            expectFailure { crypto.decrypt(envelope.toString().toByteArray()) }
        }
    }

    @Test fun `close rejects later use and remains idempotent`() {
        val crypto = BrowserMslCrypto("observed-web-identity")
        crypto.acceptKeyResponse(keyResponse(crypto.keyRequest()))
        crypto.close()
        crypto.close()
        expectFailure { crypto.encrypt(byteArrayOf(1)) }
        expectFailure { crypto.sign(byteArrayOf(1)) }
        expectFailure { crypto.keyRequest() }
    }

    private fun keyResponse(request: JSONObject): JSONObject = JSONObject()
        .put("scheme", "ASYMMETRIC_WRAPPED")
        .put("mastertoken", JSONObject().put("tokendata", MslEncoding.encode(JSONObject().put("sequencenumber", 17).toString().toByteArray())))
        .put("keydata", JSONObject()
            .put("keypairid", request.getJSONObject("keydata").getString("keypairid"))
            .put("encryptionkey", wrap(publicKey(request), jwk(aes, "A128CBC")))
            .put("hmackey", wrap(publicKey(request), jwk(hmac, "HS256"))))

    private fun jwk(key: ByteArray, algorithm: String): JSONObject = JSONObject().put("kty", "oct").put("alg", algorithm)
        .put("k", Base64.getUrlEncoder().withoutPadding().encodeToString(key))

    private fun publicKey(request: JSONObject): PublicKey = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(MslEncoding.decode(request.getJSONObject("keydata").getString("publickey"))))

    private fun wrap(key: PublicKey, jwk: JSONObject): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }
        return MslEncoding.encode(cipher.doFinal(jwk.toString().toByteArray()))
    }

    private fun expectFailure(block: () -> Unit): NetflixPlaybackException {
        try { block(); fail("Expected protocol failure") } catch (error: NetflixPlaybackException) { return error }
        throw AssertionError()
    }
}
