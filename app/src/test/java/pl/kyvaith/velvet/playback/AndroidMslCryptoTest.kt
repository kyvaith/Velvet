package pl.kyvaith.velvet.playback

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Synthetic opaque CDM handles and independent JCA server fixtures. These test the adapter,
 * protocol, padding and resource ownership, not real Widevine certification/Netflix approval.
 */
class AndroidMslCryptoTest {
    @Test fun `identity receives actual properties and native request uses the Kodi generic contract`() {
        val drm = FakeDrm()
        var observed: Pair<String, String>? = null
        create(drm, identityFor = { system, level -> observed = system to level; "own-phone-client" }).use { crypto ->
            assertEquals("67890" to "L1", observed)
            assertEquals("own-phone-client", crypto.identity)
            assertEquals("L1", crypto.securityLevel)
            val wire = MslWire(crypto).handshake(crypto.identity, "pl-PL")
            val header = MslWire.parseObjects(wire.wire).first()
            assertEquals("own-phone-client", header.getJSONObject("entityauthdata").getJSONObject("authdata").getString("identity"))
            val request = MslEncoding.json(MslEncoding.decode(header.getString("headerdata")))
                .getJSONArray("keyrequestdata").getJSONObject(0)
            assertEquals("WIDEVINE", request.getString("scheme"))
            assertArrayEquals(drm.challenge, MslEncoding.decode(request.getJSONObject("keydata").getString("keyrequest")))
            assertArrayEquals(byteArrayOf(10, 122, 0, 108, 56, 43), drm.requestInit)
            assertEquals("application/xml", drm.requestMime)
            assertEquals(2, drm.requestKeyType)
            assertTrue(drm.requestParameters!!.isEmpty())
            assertEquals("AES/CBC/NoPadding", drm.cipherAlgorithm)
            assertEquals("HmacSHA256", drm.macAlgorithm)
            assertTrue(drm.events.indexOf("crypto_session") < drm.events.indexOf("key_request"))
        }
    }

    @Test fun `opaque returned key IDs select protected operations and form the Android envelope keyid`() {
        val drm = FakeDrm()
        create(drm).use { crypto ->
            install(crypto)
            for (plaintext in listOf(ByteArray(0), ByteArray(16) { it.toByte() }, "Zażółć gęślą 🐈".toByteArray())) {
                val envelope = crypto.encrypt(plaintext)
                val value = MslEncoding.json(envelope)
                assertEquals(1, value.getInt("version"))
                assertEquals("AA==", value.getString("sha256"))
                assertEquals(MslEncoding.encode(ENC_ID), value.getString("keyid"))
                val server = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(AES, "AES"), IvParameterSpec(MslEncoding.decode(value.getString("iv"))))
                }
                assertArrayEquals(plaintext, server.doFinal(MslEncoding.decode(value.getString("ciphertext"))))
                assertArrayEquals(plaintext, crypto.decrypt(envelope))
                assertArrayEquals(ENC_ID, drm.lastEncryptionId)
            }
            assertArrayEquals(CDM_RESPONSE, drm.installedResponse)
        }
    }

    @Test fun `fresh IV and protected HMAC authenticate independent server data`() {
        val drm = FakeDrm()
        create(drm).use { crypto ->
            install(crypto)
            val first = crypto.encrypt("same plaintext".toByteArray())
            val second = crypto.encrypt("same plaintext".toByteArray())
            assertNotEquals(MslEncoding.json(first).getString("iv"), MslEncoding.json(second).getString("iv"))
            val serverMac = mac(first)
            assertArrayEquals(serverMac, crypto.sign(first))
            assertTrue(crypto.verify(first, serverMac))
            assertArrayEquals(MAC_ID, drm.lastHmacId)
            val tampered = first.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            assertFalse(crypto.verify(tampered, serverMac))
            assertFalse(crypto.verify(first, serverMac.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }))
            val verifiedCalls = drm.verifyCalls
            assertFalse(crypto.verify(first, ByteArray(31)))
            assertEquals(verifiedCalls, drm.verifyCalls)
        }
    }

    @Test fun `response requires a pending request and cannot be replayed after installation`() {
        val drm = FakeDrm()
        create(drm).use { crypto ->
            expectFailure { crypto.acceptKeyResponse(response()) }
            assertEquals(0, drm.provideCalls)
            install(crypto)
            expectFailure { crypto.acceptKeyResponse(response()) }
            assertEquals(1, drm.provideCalls)
        }
    }

    @Test fun `all key response fields are validated before CDM installation`() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("scheme", "ASYMMETRIC_WRAPPED") },
            { it.remove("keydata") },
            { it.getJSONObject("keydata").remove("cdmkeyresponse") },
            { it.getJSONObject("keydata").put("cdmkeyresponse", 7) },
            { it.getJSONObject("keydata").put("cdmkeyresponse", "!") },
            { it.getJSONObject("keydata").put("cdmkeyresponse", "") },
            { it.getJSONObject("keydata").put("encryptionkeyid", "") },
            { it.getJSONObject("keydata").put("hmackeyid", "!") },
            { it.getJSONObject("keydata").put("hmackeyid", MslEncoding.encode(ByteArray(257))) },
            { it.getJSONObject("keydata").put("cdmkeyresponse", "A".repeat(1_400_000)) },
        )
        for (mutate in mutations) {
            val drm = FakeDrm()
            create(drm).use { crypto ->
                crypto.keyRequest()
                expectFailure { crypto.acceptKeyResponse(response().also(mutate)) }
                assertEquals(0, drm.provideCalls)
                expectFailure { crypto.encrypt(byteArrayOf(1)) }
                crypto.acceptKeyResponse(response())
                assertEquals(1, drm.provideCalls)
            }
        }
    }

    @Test fun `malformed envelope lengths versions and PKCS padding are rejected`() {
        val drm = FakeDrm()
        create(drm).use { crypto ->
            install(crypto)
            val good = MslEncoding.json(crypto.encrypt("valid".toByteArray()))
            val badEnvelopes = listOf(
                JSONObject(good.toString()).put("iv", MslEncoding.encode(ByteArray(15))),
                JSONObject(good.toString()).put("iv", MslEncoding.encode(ByteArray(17))),
                JSONObject(good.toString()).put("ciphertext", MslEncoding.encode(ByteArray(15))),
                JSONObject(good.toString()).put("ciphertext", ""),
                JSONObject(good.toString()).put("version", 2),
            )
            for (bad in badEnvelopes) expectFailure { crypto.decrypt(bad.toString().toByteArray()) }
            assertEquals(0, drm.decryptCalls)
            for (invalidPad in listOf(ByteArray(16), ByteArray(16) { 17 }, ByteArray(16) { if (it == 15) 2 else 1 })) {
                val iv = ByteArray(16)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES, "AES"), IvParameterSpec(iv))
                }
                val bad = JSONObject().put("version", 1).put("iv", MslEncoding.encode(iv))
                    .put("ciphertext", MslEncoding.encode(cipher.doFinal(invalidPad)))
                expectFailure { crypto.decrypt(bad.toString().toByteArray()) }
            }
        }
    }

    @Test fun `message and native challenge bounds are checked`() {
        for (challenge in listOf(ByteArray(0), ByteArray(1024 * 1024 + 1))) {
            val drm = FakeDrm().apply { this.challenge = challenge }
            create(drm).use { crypto -> expectFailure { crypto.keyRequest() } }
            assertTrue(drm.closed)
        }
        create(FakeDrm()).use { crypto ->
            install(crypto)
            val excessive = ByteArray(MAX_MSL_BYTES + 1)
            expectFailure { crypto.decrypt(excessive) }
            expectFailure { crypto.encrypt(excessive) }
            expectFailure { crypto.sign(excessive) }
            expectFailure { crypto.verify(excessive, ByteArray(32)) }
        }
    }

    @Test fun `native response must confirm offline key installation`() {
        for (keySet in listOf(ByteArray(0), ByteArray(4097))) {
            val drm = FakeDrm().apply { returnedKeySet = keySet }
            val crypto = create(drm)
            crypto.keyRequest()
            val error = expectFailure { crypto.acceptKeyResponse(response()) }
            assertEquals(PlaybackStage.KEY_EXCHANGE, error.stage)
            assertTrue(drm.closed)
            assertTrue(drm.events.contains("close_session"))
            if (keySet.isNotEmpty()) assertTrue(drm.events.contains("remove_offline_license"))
        }
    }

    @Test fun `new exchange closes previous session and removes only its owned offline license`() {
        val drm = FakeDrm()
        create(drm).use { crypto ->
            install(crypto)
            drm.events.clear()
            crypto.keyRequest()
            assertEquals(listOf("remove_keys", "remove_offline_license", "close_session", "open_session", "crypto_session",
                "property_systemId", "property_securityLevel", "key_request"), drm.events)
            assertArrayEquals(KEY_SET, drm.removedKeySets.single())
            assertEquals(2, drm.openCalls)
            expectFailure { crypto.sign(byteArrayOf(1)) }
            crypto.acceptKeyResponse(response())
            assertEquals(2, drm.provideCalls)
        }
    }

    @Test fun `failed cleanup still attempts every release step and close is idempotent`() {
        for (failure in listOf("remove_keys", "remove_offline_license", "close_session", "close_drm")) {
            val drm = FakeDrm()
            val crypto = create(drm)
            install(crypto)
            drm.events.clear()
            drm.failure = failure
            crypto.close()
            assertEquals(listOf("remove_keys", "remove_offline_license", "close_session", "close_drm"), drm.events)
            val count = drm.events.size
            crypto.close()
            assertEquals(count, drm.events.size)
            expectFailure { crypto.keyRequest() }
            expectFailure { crypto.sign(byteArrayOf(1)) }
        }
    }

    @Test fun `failure to delete an old key set prevents creating another exchange`() {
        val drm = FakeDrm()
        val crypto = create(drm)
        install(crypto)
        drm.failure = "remove_offline_license"
        expectFailure { crypto.keyRequest() }
        assertEquals(1, drm.openCalls)
        assertTrue(drm.closed)
    }

    @Test fun `factory provisions once then reads device properties from its real facade`() {
        val drm = FakeDrm().apply { openProvisionFailures = 1 }
        var provisionCalls = 0
        create(drm, provision = { url, request ->
            provisionCalls++
            assertEquals("https://drm.example.invalid/provision", url)
            assertArrayEquals(PROVISION_REQUEST, request)
            PROVISION_RESPONSE.copyOf()
        }).use { crypto ->
            assertEquals(1, provisionCalls)
            assertEquals(2, drm.openCalls)
            assertEquals(1, drm.provisionCalls)
            assertArrayEquals(PROVISION_RESPONSE, drm.installedProvision)
            assertEquals("L1", crypto.securityLevel)
        }
        assertTrue(drm.closed)
    }

    @Test fun `provisioning is bounded and repeated demand releases MediaDrm`() {
        val drm = FakeDrm().apply { openProvisionFailures = 2 }
        val error = expectFailure { create(drm) }
        assertEquals(PlaybackStage.PROVISIONING, error.stage)
        assertEquals(2, drm.openCalls)
        assertEquals(1, drm.provisionCalls)
        assertTrue(drm.closed)
        assertNull(error.cause)
    }

    @Test fun `provisioning transport failure is redacted and closes factory resources`() {
        val drm = FakeDrm().apply { openProvisionFailures = 1 }
        val error = expectFailure { create(drm, provision = { _, _ -> throw IOException(SENSITIVE) }) }
        assertEquals(PlaybackStage.PROVISIONING, error.stage)
        assertFalse(error.toString().contains(SENSITIVE))
        assertNull(error.cause)
        assertTrue(drm.closed)
    }

    @Test fun `a key request can provision after open without switching identity or security level`() {
        val drm = FakeDrm().apply { keyRequestProvisionFailures = 1 }
        create(drm).use { crypto ->
            val originalIdentity = crypto.identity
            crypto.keyRequest()
            assertEquals(1, drm.openCalls)
            assertEquals(2, drm.requestCalls)
            assertEquals(1, drm.provisionCalls)
            assertEquals(originalIdentity, crypto.identity)
            crypto.acceptKeyResponse(response())
        }
    }

    @Test fun `factory failure after opening a session releases that scope and the native object`() {
        for (failure in listOf("crypto_session", "property_systemId", "property_securityLevel")) {
            val drm = FakeDrm().apply { this.failure = failure }
            val error = expectFailure { create(drm) }
            assertEquals(PlaybackStage.KEY_EXCHANGE, error.stage)
            assertNull(error.cause)
            assertFalse(error.toString().contains(SENSITIVE))
            assertTrue(drm.events.contains("remove_keys"))
            assertTrue(drm.events.contains("close_session"))
            assertTrue(drm.closed)
        }
    }

    @Test fun `native failures expose no raw text and release even after an install failure`() {
        for (failure in listOf("provide_key_response", "encrypt", "decrypt", "sign", "verify")) {
            val drm = FakeDrm()
            val crypto = create(drm)
            crypto.keyRequest()
            if (failure != "provide_key_response") crypto.acceptKeyResponse(response())
            val validEnvelope = if (failure == "decrypt") crypto.encrypt(byteArrayOf(1)) else ByteArray(0)
            drm.failure = failure
            val error = expectFailure {
                when (failure) {
                    "provide_key_response" -> crypto.acceptKeyResponse(response())
                    "encrypt" -> crypto.encrypt(byteArrayOf(1))
                    "decrypt" -> crypto.decrypt(validEnvelope)
                    "sign" -> crypto.sign(byteArrayOf(1))
                    "verify" -> crypto.verify(byteArrayOf(1), ByteArray(32))
                }
            }
            assertEquals(PlaybackStage.KEY_EXCHANGE, error.stage)
            assertNull(error.cause)
            assertFalse(error.toString().contains(SENSITIVE))
            assertTrue(drm.events.contains("close_session"))
            assertTrue(drm.closed)
        }
    }

    private fun create(drm: FakeDrm,
                       provision: (String, ByteArray) -> ByteArray = { _, _ -> PROVISION_RESPONSE.copyOf() },
                       identityFor: (String, String) -> String = { _, _ -> "own-native-client" }) =
        AndroidMslCrypto.create({ drm }, provision, identityFor)

    private fun install(crypto: AndroidMslCrypto) {
        crypto.keyRequest()
        crypto.acceptKeyResponse(response())
    }

    private fun response(): JSONObject = JSONObject().put("scheme", "WIDEVINE").put("keydata", JSONObject()
        .put("cdmkeyresponse", MslEncoding.encode(CDM_RESPONSE))
        .put("encryptionkeyid", MslEncoding.encode(ENC_ID))
        .put("hmackeyid", MslEncoding.encode(MAC_ID)))

    private fun expectFailure(block: () -> Unit): NetflixPlaybackException {
        try { block(); fail("Expected a safe playback failure") } catch (error: NetflixPlaybackException) { return error }
        throw AssertionError()
    }

    private class FakeDrm : NativeMslDrm {
        val events = mutableListOf<String>()
        var failure: String? = null
        var openProvisionFailures = 0
        var keyRequestProvisionFailures = 0
        var openCalls = 0
        var requestCalls = 0
        var provideCalls = 0
        var provisionCalls = 0
        var verifyCalls = 0
        var decryptCalls = 0
        var closed = false
        var requestInit: ByteArray? = null
        var requestMime: String? = null
        var requestKeyType: Int? = null
        var requestParameters: Map<String, String>? = null
        var cipherAlgorithm: String? = null
        var macAlgorithm: String? = null
        var installedResponse: ByteArray? = null
        var installedProvision: ByteArray? = null
        var lastEncryptionId: ByteArray? = null
        var lastHmacId: ByteArray? = null
        val removedKeySets = mutableListOf<ByteArray>()
        var challenge = byteArrayOf(1, 4, 7, 11, 19)
        var returnedKeySet = KEY_SET.copyOf()

        private fun event(name: String) {
            events += name
            if (failure == name) throw IllegalStateException(SENSITIVE)
        }

        override fun property(name: String): String {
            event("property_$name")
            return when (name) { "systemId" -> "67890"; "securityLevel" -> "L1"; else -> error("Unexpected property") }
        }

        override fun openSession(): ByteArray {
            openCalls++
            event("open_session")
            if (openCalls <= openProvisionFailures) throw NativeMslProvisionRequired()
            return byteArrayOf(openCalls.toByte(), 21, 42)
        }

        override fun cryptoSession(scope: ByteArray, cipher: String, mac: String): NativeMslCryptoSession {
            event("crypto_session")
            cipherAlgorithm = cipher
            macAlgorithm = mac
            return object : NativeMslCryptoSession {
                override fun encrypt(keyId: ByteArray, input: ByteArray, iv: ByteArray): ByteArray {
                    event("encrypt")
                    assertArrayEquals(ENC_ID, keyId)
                    lastEncryptionId = keyId.copyOf()
                    return aes(Cipher.ENCRYPT_MODE, input, iv)
                }
                override fun decrypt(keyId: ByteArray, input: ByteArray, iv: ByteArray): ByteArray {
                    event("decrypt")
                    decryptCalls++
                    assertArrayEquals(ENC_ID, keyId)
                    lastEncryptionId = keyId.copyOf()
                    return aes(Cipher.DECRYPT_MODE, input, iv)
                }
                override fun sign(keyId: ByteArray, input: ByteArray): ByteArray {
                    event("sign")
                    assertArrayEquals(MAC_ID, keyId)
                    lastHmacId = keyId.copyOf()
                    return AndroidMslCryptoTest.mac(input)
                }
                override fun verify(keyId: ByteArray, input: ByteArray, signature: ByteArray): Boolean {
                    event("verify")
                    verifyCalls++
                    assertArrayEquals(MAC_ID, keyId)
                    lastHmacId = keyId.copyOf()
                    return MessageDigest.isEqual(AndroidMslCryptoTest.mac(input), signature)
                }
            }
        }

        override fun keyRequest(scope: ByteArray, init: ByteArray, mime: String, keyType: Int,
                                parameters: Map<String, String>): ByteArray {
            event("key_request")
            requestCalls++
            if (requestCalls <= keyRequestProvisionFailures) throw NativeMslProvisionRequired()
            requestInit = init.copyOf()
            requestMime = mime
            requestKeyType = keyType
            requestParameters = parameters.toMap()
            return challenge.copyOf()
        }

        override fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray {
            event("provide_key_response")
            provideCalls++
            installedResponse = response.copyOf()
            return returnedKeySet.copyOf()
        }

        override fun provisionRequest(): NativeMslProvisionRequest {
            event("provision_request")
            provisionCalls++
            return NativeMslProvisionRequest("https://drm.example.invalid/provision", PROVISION_REQUEST.copyOf())
        }
        override fun provideProvisionResponse(response: ByteArray) {
            event("provide_provision_response")
            installedProvision = response.copyOf()
        }
        override fun removeKeys(scope: ByteArray) = event("remove_keys")
        override fun removeOfflineLicense(keySetId: ByteArray) {
            removedKeySets += keySetId.copyOf()
            event("remove_offline_license")
        }
        override fun closeSession(scope: ByteArray) = event("close_session")
        override fun close() { closed = true; event("close_drm") }
    }

    companion object {
        private const val SENSITIVE = "native-secret-id-and-request-url-do-not-disclose"
        private val AES = ByteArray(16) { (it + 10).toByte() }
        private val HMAC = ByteArray(32) { (it + 20).toByte() }
        private val ENC_ID = byteArrayOf(37, 41, 43, 47, 53)
        private val MAC_ID = byteArrayOf(59, 61, 67, 71, 73, 79, 83)
        private val KEY_SET = byteArrayOf(7, 14, 28, 56)
        private val CDM_RESPONSE = byteArrayOf(9, 18, 27, 36)
        private val PROVISION_REQUEST = byteArrayOf(11, 22, 33)
        private val PROVISION_RESPONSE = byteArrayOf(44, 55, 66)

        private fun aes(mode: Int, input: ByteArray, iv: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(mode, SecretKeySpec(AES, "AES"), IvParameterSpec(iv))
        }.doFinal(input)
        private fun mac(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(HMAC, "HmacSHA256"))
        }.doFinal(input)
    }
}
