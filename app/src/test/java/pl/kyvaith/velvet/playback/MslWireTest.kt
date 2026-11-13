package pl.kyvaith.velvet.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MslWireTest {
    private val crypto = FixtureCrypto()

    @Test fun `concatenated objects preserve braces and escaped quotes inside strings`() {
        val value = JSONObject().put("text", "a}{b \" quoted").toString() + "\n" + JSONObject().put("payload", "abc")
        val objects = MslWire.parseObjects(value)
        assertEquals(2, objects.size)
        assertEquals("a}{b \" quoted", objects[0].getString("text"))
    }

    @Test fun `non object and incomplete json responses are rejected`() {
        expectFailure { MslWire.parseObjects("{}[1]") }
        expectFailure { MslWire.parseObjects("{\"payload\":") }
    }

    @Test fun `MSL response ID increments request ID and wraps at maximum safe integer`() {
        assertEquals(43L, MslWire.responseMessageId(42))
        assertEquals(0L, MslWire.responseMessageId(9007199254740991L))
        val result = MslWire(crypto).readResponse(response(43, listOf(chunk(43, 1, true, "{\"result\":{\"ok\":true}}"))), 42, PlaybackStage.MANIFEST)
        assertTrue((result.result as JSONObject).getBoolean("ok"))
    }

    @Test fun `response bound to another request is rejected`() {
        expectFailure { MslWire(crypto).readResponse(response(99, listOf(chunk(99, 1, true, "{\"result\":{}}"))), 42, PlaybackStage.MANIFEST) }
    }

    @Test fun `authenticated null result is permitted only for an explicitly selected bootstrap exchange`() {
        val value = response(43, listOf(chunk(43, 1, true, "{\"result\":null}")))
        expectFailure { MslWire(crypto).readResponse(value, 42, PlaybackStage.MANIFEST) }
        val decoded = MslWire(crypto).readResponse(value, 42, PlaybackStage.AUTHENTICATION, allowNullResult = true)
        assertSame(JSONObject.NULL, decoded.result)
        assertEquals("MslResponse(redacted)", decoded.toString())
        val missing = response(43, listOf(chunk(43, 1, true, "{}")))
        expectFailure { MslWire(crypto).readResponse(missing, 42, PlaybackStage.AUTHENTICATION, allowNullResult = true) }
    }

    @Test fun `raw native JSON retains authentication and does not relax the result contract`() {
        val body = "{\"data\":{\"account\":{\"ownerGuid\":\"fixture-owner\"}}}"
        val value = response(43, listOf(chunk(43, 1, false, body.substring(0, 10), true),
            chunk(43, 2, true, body.substring(10), true)))
        val parsed = MslWire(crypto).readRawJsonResponse(value, 42, PlaybackStage.AUTHENTICATION)
        assertEquals("fixture-owner", parsed.body.getJSONObject("data").getJSONObject("account").getString("ownerGuid"))
        assertEquals("MslRawResponse(redacted)", parsed.toString())
        expectFailure { MslWire(crypto).readResponse(value, 42, PlaybackStage.AUTHENTICATION) }
        for (index in 0..2) {
            val tampered = MslWire.parseObjects(value)
            tampered[index].put("signature", MslEncoding.encode(ByteArray(32)))
            expectFailure { MslWire(crypto).readRawJsonResponse(tampered.joinToString(""), 42, PlaybackStage.AUTHENTICATION) }
        }
    }

    @Test fun `raw native JSON rejects wrong ids sequences termination trailing values and excessive depth`() {
        val invalid = listOf(
            response(44, listOf(chunk(44, 1, true, "{}"))),
            response(43, listOf(chunk(44, 1, true, "{}"))),
            response(43, listOf(chunk(43, 2, true, "{}"))),
            response(43, listOf(chunk(43, 1, false, "{}"))),
            response(43, listOf(chunk(43, 1, true, "{}"), chunk(43, 2, true, "{}"))),
            response(43, listOf(chunk(43, 1, true, "{}{}"))),
            response(43, listOf(chunk(43, 1, true, "[]"))),
            response(43, listOf(chunk(43, 1, true, "{\"a\":".repeat(129) + "0" + "}".repeat(129)))),
        )
        for (value in invalid) expectFailure { MslWire(crypto).readRawJsonResponse(value, 42, PlaybackStage.AUTHENTICATION) }
    }

    @Test fun `raw native response keeps numeric service errors`() {
        val body = JSONObject().put("errordata", MslEncoding.encode(JSONObject().put("internalcode", 205032)
            .put("errorcode", 7).toString().toByteArray())).toString()
        val error = expectFailure { MslWire(crypto).readRawJsonResponse(body, 42, PlaybackStage.AUTHENTICATION) }
        assertEquals("205032", error.serviceCode)
        assertEquals(7, error.mslResponseCode)
    }

    @Test fun `raw native gzip cannot expand beyond the response byte limit`() {
        val oversized = "{\"data\":\"" + "x".repeat(MAX_MSL_BYTES) + "\"}"
        val value = response(43, listOf(chunk(43, 1, true, oversized, gzip = true)))
        expectFailure { MslWire(crypto).readRawJsonResponse(value, 42, PlaybackStage.AUTHENTICATION) }
    }

    @Test fun `raw request keeps exact form bytes and no auth mechanism when NFToken is external`() {
        val bytes = "method=get&path=%5B%22accountConfig%22%5D&path=%5B%22deviceConfig%22%5D&locale=pl-PL&".toByteArray()
        val context = PlaybackContext("nfvdid=fixture", "", "own-native-entity", "selected", "ua")
        val request = MslWire(crypto).requestBytes(context, JSONObject().put("tokendata", "fixture"), bytes, MslAuthentication.None)
        val parts = MslWire.parseObjects(request.wire)
        val headerEnvelope = MslEncoding.decode(parts[0].getString("headerdata"))
        assertTrue(crypto.verify(headerEnvelope, MslEncoding.decode(parts[0].getString("signature"))))
        val header = MslEncoding.json(crypto.decrypt(headerEnvelope))
        assertFalse(header.has("userauthdata"))
        assertFalse(header.has("useridtoken"))
        val payloadEnvelope = MslEncoding.decode(parts[1].getString("payload"))
        assertTrue(crypto.verify(payloadEnvelope, MslEncoding.decode(parts[1].getString("signature"))))
        val payload = MslEncoding.json(crypto.decrypt(payloadEnvelope))
        assertArrayEquals(bytes, MslEncoding.decode(payload.getString("data")))
        assertEquals(request.messageId, payload.getLong("messageid"))
        assertTrue(payload.getBoolean("endofmsg"))
        assertEquals(1L, payload.getLong("sequencenumber"))
    }

    @Test fun `authenticated gzip chunks are concatenated in sequence`() {
        val parts = listOf(chunk(43, 1, false, "{\"result\":{", true), chunk(43, 2, true, "\"value\":123}}", true))
        val parsed = MslWire(crypto).readResponse(response(43, parts), 42, PlaybackStage.MANIFEST)
        assertEquals(123, (parsed.result as JSONObject).getInt("value"))
    }

    @Test fun `tampered header or payload signature is rejected`() {
        val valid = response(43, listOf(chunk(43, 1, true, "{\"result\":{}}")))
        val headerTamper = MslWire.parseObjects(valid)
        headerTamper[0].put("signature", MslEncoding.encode(ByteArray(32)))
        expectFailure { MslWire(crypto).readResponse(headerTamper.joinToString(""), 42, PlaybackStage.MANIFEST) }
        val payloadTamper = MslWire.parseObjects(valid)
        payloadTamper[1].put("signature", MslEncoding.encode(ByteArray(32)))
        expectFailure { MslWire(crypto).readResponse(payloadTamper.joinToString(""), 42, PlaybackStage.MANIFEST) }
    }

    @Test fun `missing duplicated and trailing chunks are rejected`() {
        expectFailure { MslWire(crypto).readResponse(response(43, listOf(chunk(43, 2, true, "{\"result\":{}}"))), 42, PlaybackStage.MANIFEST) }
        expectFailure { MslWire(crypto).readResponse(response(43, listOf(chunk(43, 1, false, "{\"result\":{}}"))), 42, PlaybackStage.MANIFEST) }
        expectFailure { MslWire(crypto).readResponse(response(43, listOf(chunk(43, 1, true, "{\"result\":{}}"), chunk(43, 2, true, ""))), 42, PlaybackStage.MANIFEST) }
    }

    @Test fun `cookie values containing equals are not truncated`() {
        assertEquals("abc==", MslWire.parseCookieHeader("x=1; NetflixId=abc==; SecureNetflixId=hello%3D")["NetflixId"])
        assertEquals("hello%3D", MslWire.parseCookieHeader("x=1; NetflixId=abc==; SecureNetflixId=hello%3D")["SecureNetflixId"])
    }

    @Test fun `outgoing sensitive auth is encrypted and message payload signed`() {
        val ctx = PlaybackContext("NetflixId=secret-one; SecureNetflixId=secret-two", "auth", "observed-esn", "profile", "ua")
        val message = MslWire(crypto).request(ctx, JSONObject().put("tokendata", "fixture"), JSONObject().put("test", true))
        assertFalse(message.wire.contains("secret-one"))
        val objects = MslWire.parseObjects(message.wire)
        val encrypted = MslEncoding.decode(objects[0].getString("headerdata"))
        assertTrue(crypto.verify(encrypted, MslEncoding.decode(objects[0].getString("signature"))))
        val header = JSONObject(String(crypto.decrypt(encrypted), Charsets.UTF_8))
        assertEquals("secret-one", header.getJSONObject("userauthdata").getJSONObject("authdata").getString("netflixid"))
        assertEquals(message.messageId, header.getLong("messageid"))
        assertEquals("PlaybackContext(redacted)", ctx.toString())
    }

    @Test fun `service rejection is exposed as error with code rather than a manifest`() {
        val error = JSONObject().put("errormsg", "Request failed validation during key exchange").put("internalcode", 207006)
        val wire = JSONObject().put("errordata", MslEncoding.encode(error.toString().toByteArray())).toString()
        val exception = expectFailure { MslWire(crypto).readHandshake(wire, 1) }
        assertEquals("207006", exception.serviceCode)
    }

    @Test fun `padding uses byte length including unicode and rejects malformed padding`() {
        val bytes = "Zażółć gęślą jaźń 🐈".toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, MslEncoding.unpad(MslEncoding.pad(bytes)))
        expectFailure { MslEncoding.unpad(ByteArray(16)) }
        val invalid = MslEncoding.pad(byteArrayOf(1, 2, 3))
        invalid[invalid.lastIndex - 1] = 0
        expectFailure { MslEncoding.unpad(invalid) }
    }

    @Test fun `device DRM session ids remain tied to actual bytes`() {
        assertEquals("sid123", DeviceDrmSessionId.encode("sid123".toByteArray()))
        assertEquals(MslEncoding.encode(byteArrayOf(0, -1, 32)), DeviceDrmSessionId.encode(byteArrayOf(0, -1, 32)))
        assertNotEquals(DeviceDrmSessionId.encode(byteArrayOf(0, 1)), DeviceDrmSessionId.encode(byteArrayOf(0, 2)))
    }

    private fun response(id: Long, chunks: List<JSONObject>): String {
        val header = crypto.encrypt(JSONObject().put("messageid", id).toString().toByteArray())
        return JSONObject().put("headerdata", MslEncoding.encode(header)).put("signature", MslEncoding.encode(crypto.sign(header))).toString() + chunks.joinToString("")
    }

    private fun chunk(id: Long, sequence: Long, last: Boolean, content: String, gzip: Boolean = false): JSONObject {
        var bytes = content.toByteArray()
        if (gzip) {
            val output = ByteArrayOutputStream()
            GZIPOutputStream(output).use { it.write(bytes) }
            bytes = output.toByteArray()
        }
        val value = JSONObject().put("messageid", id).put("sequencenumber", sequence).put("endofmsg", last)
            .put("data", MslEncoding.encode(bytes))
        if (gzip) value.put("compressionalgo", "GZIP")
        val envelope = crypto.encrypt(value.toString().toByteArray())
        return JSONObject().put("payload", MslEncoding.encode(envelope)).put("signature", MslEncoding.encode(crypto.sign(envelope)))
    }

    private fun expectFailure(block: () -> Unit): NetflixPlaybackException {
        try { block(); fail("Expected a protocol failure") } catch (expected: NetflixPlaybackException) { return expected }
        throw AssertionError()
    }

    /** Test fixtures use JCA-only ephemeral keys. Production never links this implementation. */
    private class FixtureCrypto : MslCrypto {
        override val keyExchangeScheme = "WIDEVINE"
        private val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
        private val hmac = SecretKeySpec(ByteArray(32) { (it + 32).toByte() }, "HmacSHA256")
        override fun keyRequest() = JSONObject().put("scheme", "WIDEVINE")
        override fun acceptKeyResponse(keyResponse: JSONObject) = Unit
        override fun close() = Unit
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val iv = ByteArray(16) { 7 }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            return JSONObject().put("iv", MslEncoding.encode(iv)).put("ciphertext", MslEncoding.encode(cipher.doFinal(plaintext))).toString().toByteArray()
        }
        override fun decrypt(envelope: ByteArray): ByteArray {
            val value = JSONObject(String(envelope))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(MslEncoding.decode(value.getString("iv"))))
            return cipher.doFinal(MslEncoding.decode(value.getString("ciphertext")))
        }
        override fun sign(message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(hmac) }.doFinal(message)
        override fun verify(message: ByteArray, signature: ByteArray) = MessageDigest.isEqual(sign(message), signature)
    }
}
