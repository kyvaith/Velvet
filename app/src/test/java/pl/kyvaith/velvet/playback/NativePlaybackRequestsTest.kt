package pl.kyvaith.velvet.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePlaybackRequestsTest {
    private val device = NativeAndroidProfile.Device("Google", "Pixel fixture", "17", 37,
        "board-is-not-platform", "device", "build", "hardware-is-not-platform", "actual-board-platform")

    @Test fun `manifest uses Android batch contract and omits uninitialized DRM fields`() {
        val params = NativePlaybackRequests.manifest("12345", device, listOf("playready-h264mpl40-dash", "imsc1.1"))
        val envelope = NativePlaybackRequests.envelope(NativePlaybackOperation.MANIFEST, "/licensedManifest", params, "pl-PL")
        assertEquals(setOf("version", "url", "languages", "params"), envelope.keys().asSequence().toSet())
        assertEquals(2, envelope.getInt("version"))
        assertEquals("/licensedManifest", envelope.getString("url"))
        assertEquals("pl-PL", envelope.getJSONArray("languages").getString(0))
        assertEquals(1, envelope.getJSONArray("params").length())
        val item = params.getJSONObject(0)
        assertTrue(item.get("viewableId") is String)
        assertEquals("12345", item.getString("viewableId"))
        assertEquals("STANDARD", item.getString("flavor"))
        assertEquals("auto", item.getString("cellularCap"))
        assertEquals("android", item.getString("osName"))
        assertEquals("37", item.getString("osVersion"))
        assertEquals("android", item.getString("uiPlatform"))
        assertEquals("samurai", item.getString("application"))
        assertEquals("streaming", item.getString("player"))
        assertEquals("8.143.5", item.getString("clientVersion"))
        assertEquals("8.143.5", item.getString("uiVersion"))
        assertEquals("actual-board-platform", item.getString("hardware"))
        for (key in listOf("challenge", "challenges", "profileGroups", "licenseType", "xid", "platform", "drmVersion", "type")) {
            assertFalse(key, item.has(key))
        }
    }

    @Test fun `missing platform is omitted instead of advertising a different device property`() {
        val item = NativePlaybackRequests.manifest("1", device.copy(boardPlatform = ""), listOf("imsc1.1")).getJSONObject(0)
        assertFalse(item.has("hardware"))
        assertFalse(item.toString().contains("hardware-is-not-platform"))
        assertFalse(item.toString().contains("board-is-not-platform"))
    }

    @Test fun `license uses actual challenge and seconds in an object without web session fields`() {
        val challenge = byteArrayOf(0, 1, 2, -1, 32)
        val params = NativePlaybackRequests.license(challenge, "1700000000123456", 1700000000L)
        val body = NativePlaybackRequests.envelope(NativePlaybackOperation.LICENSE, "/license/synthetic-operation", params, "pl-PL")
        assertEquals(setOf("clientTime", "challengeBase64", "xid"), body.getJSONObject("params").keys().asSequence().toSet())
        assertEquals(1700000000L, params.getLong("clientTime"))
        assertArrayEquals(challenge, MslEncoding.decode(params.getString("challengeBase64")))
        assertEquals("1700000000123456", params.getString("xid"))
        assertEquals("/license/synthetic-operation", body.getString("url"))
        assertFalse(body.has("echo"))
        assertFalse(body.has("id"))
    }

    @Test fun `legacy request shapes are rejected before serialization`() {
        failure { NativePlaybackRequests.envelope(NativePlaybackOperation.MANIFEST, "/licensedManifest", JSONObject(), "pl-PL") }
        failure { NativePlaybackRequests.envelope(NativePlaybackOperation.LICENSE, "/license/fixture", JSONArray(), "pl-PL") }
        failure { NativePlaybackRequests.envelope(NativePlaybackOperation.MANIFEST, "licensedManifest", JSONArray().put(JSONObject()), "pl-PL") }
        for (path in listOf("https://outside.invalid/license", "//outside.invalid/license", "/license/\nsecret")) {
            failure { NativePlaybackRequests.envelope(NativePlaybackOperation.LICENSE, path, JSONObject(), "pl-PL") }
        }
    }

    @Test fun `native license metadata is not mixed into CDM bytes or treated as echoed scope`() {
        val actual = byteArrayOf(1, 2, 3, -128)
        val response = JSONObject().put("licenseResponseBase64", MslEncoding.encode(actual))
            .put("drmSessionId", 7).put("providerSessionToken", "PRIVATE_PROVIDER_TOKEN")
        assertArrayEquals(actual, NativePlaybackRequests.licenseResponse(response))
    }

    @Test fun `malformed native license responses fail without exposing metadata`() {
        for (value in listOf(JSONArray().put(JSONObject()), JSONObject(),
            JSONObject().put("licenseResponseBase64", ""),
            JSONObject().put("licenseResponseBase64", "PRIVATE_INVALID_@@@"))) {
            val error = failure { NativePlaybackRequests.licenseResponse(value) }
            assertEquals(PlaybackStage.LICENSE, error.stage)
            assertFalse(error.message.orEmpty().contains("PRIVATE"))
            assertNull(error.cause)
        }
    }

    @Test fun `manifest selection follows requested movie identity instead of first array entry`() {
        val wanted = JSONObject().put("movieId", "12345").put("duration", 60000)
        val result = JSONArray().put(JSONObject().put("movieId", "999")).put(wanted)
        assertSame(wanted, NativePlaybackRequests.selectManifest(result, "12345"))
        assertSame(wanted, NativePlaybackRequests.selectManifest(JSONArray().put(wanted), "12345"))
    }

    @Test fun `missing duplicate malformed and legacy manifest results fail closed`() {
        for (result in listOf(JSONObject().put("movieId", "1"), JSONArray(), JSONArray().put("private-response"),
            JSONArray().put(JSONObject().put("movieId", "2")),
            JSONArray().put(JSONObject().put("movieId", "1")).put(JSONObject().put("movieId", "1")))) {
            val error = failure { NativePlaybackRequests.selectManifest(result, "1") }
            assertFalse(error.message.orEmpty().contains("private-response"))
            assertNull(error.cause)
        }
    }

    @Test fun `invalid playback inputs cannot create license or manifest requests`() {
        for (video in listOf("0", "-1", "title", "9223372036854775808")) {
            failure { NativePlaybackRequests.manifest(video, device, listOf("imsc1.1")) }
        }
        failure { NativePlaybackRequests.manifest("1", device, emptyList()) }
        failure { NativePlaybackRequests.license(byteArrayOf(), "123", 1) }
        failure { NativePlaybackRequests.license(byteArrayOf(1), "not-a-session-xid", 1) }
        failure { NativePlaybackRequests.license(byteArrayOf(1), "123", 0) }
    }

    private fun failure(block: () -> Unit): NetflixPlaybackException {
        try { block(); fail("Expected playback validation failure") }
        catch (error: NetflixPlaybackException) { return error }
        throw AssertionError()
    }
}
