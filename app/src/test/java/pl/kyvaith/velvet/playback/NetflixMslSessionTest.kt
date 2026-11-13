package pl.kyvaith.velvet.playback

import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NetflixMslSessionTest {
    private val initial = PlaybackContext(
        "NetflixId=login-a; SecureNetflixId=login-b", "auth", "observed-browser-esn", "chosen-profile", "observed-ua",
        clientVersion = "client-version", browserVersion = "browser-version", osName = "observed-os", osVersion = "os-version",
    )

    @Test fun `manifest and license always use fresh NETFLIXID despite a valid returned user token`() {
        val provider = SessionProvider(initial)
        val bound = BoundPlaybackSession(initial, provider)
        val crypto = FixtureCrypto()
        val peer = FixturePeer(crypto) { stage ->
            when (stage) {
                PlaybackStage.KEY_EXCHANGE -> bound.updateCookies(cookies("handshake-a", "handshake-b"))
                PlaybackStage.MANIFEST -> bound.updateCookies(cookies("manifest-a", "manifest-b"))
                else -> Unit
            }
        }
        val session = NetflixMslSession(bound, peer, crypto)
        try {
            session.exchange(NetflixMslSession.MANIFEST_ENDPOINT, "licensedManifest", JSONObject(), PlaybackStage.MANIFEST)
            val link = "/license/fixture?token=complete-link&requestId=fixture"
            val challenge = byteArrayOf(0, 1, 127, -128, -1)
            val sid = DeviceDrmSessionId.encode(byteArrayOf(0, -1, 42))
            val params = licenseRequestParams(challenge, sid, "shared-xid", session.context, 123456L)
            session.exchange(NetflixMslSession.LICENSE_ENDPOINT, link, params, PlaybackStage.LICENSE, "drmSessionId")

            assertEquals(3, peer.totalRequests) // one handshake; manifest and license share its keys
            assertEquals(2, peer.authenticatedHeaders.size)
            val expectedCookies = listOf("handshake-a" to "handshake-b", "manifest-a" to "manifest-b")
            peer.authenticatedHeaders.forEachIndexed { index, header ->
                assertFalse("A returned useridtoken must not change the auth family", header.has("useridtoken"))
                val auth = header.getJSONObject("userauthdata")
                assertEquals("NETFLIXID", auth.getString("scheme"))
                assertEquals(expectedCookies[index].first, auth.getJSONObject("authdata").getString("netflixid"))
                assertEquals(expectedCookies[index].second, auth.getJSONObject("authdata").getString("securenetflixid"))
                assertEquals(initial.esn, header.getString("sender"))
                val httpCookies = MslWire.parseCookieHeader(peer.authenticatedContexts[index].cookieHeader)
                assertEquals(expectedCookies[index].first, httpCookies["NetflixId"])
                assertEquals(expectedCookies[index].second, httpCookies["SecureNetflixId"])
            }
            val license = peer.applicationRequests[1]
            assertEquals(link, license.getString("url")) // do not truncate opaque href/query data
            assertEquals("drmSessionId", license.getString("echo"))
            val request = license.getJSONArray("params").getJSONObject(0)
            assertEquals(sid, request.getString("drmSessionId"))
            assertArrayEquals(challenge, MslEncoding.decode(request.getString("challengeBase64")))
            assertEquals("shared-xid", request.getString("xid"))
            assertEquals(123456L, request.getLong("clientTime"))
            assertEquals(initial.clientVersion, request.getString("clientVersion"))
            assertEquals(initial.browserVersion, request.getString("platform"))
            assertEquals(initial.osName, request.getString("osName"))
            assertEquals(initial.osVersion, request.getString("osVersion"))
        } finally { session.close() }
        assertTrue(crypto.closed)
    }

    @Test fun `Widevine cookie mode authenticates manifest and license without a native token cache or password`() {
        val source = SessionProvider(initial)
        val nativeId = AndroidPlaybackIdentity.create("Google", "Pixel 9 Pro XL", "12345", "L1", "A".repeat(64))
        val nativeProvider = NativePlaybackSessionProvider(BoundPlaybackSession(initial, source), nativeId)
        val nativeBound = BoundPlaybackSession(nativeProvider.playbackContext(), nativeProvider)
        val crypto = FixtureCrypto("WIDEVINE")
        val peer = FixturePeer(crypto) { stage ->
            when (stage) {
                PlaybackStage.KEY_EXCHANGE -> nativeBound.updateCookies(cookies("native-handshake-a", "native-handshake-b"))
                PlaybackStage.MANIFEST -> nativeBound.updateCookies(cookies("native-manifest-a", "native-manifest-b"))
                else -> Unit
            }
        }
        val session = NetflixMslSession(nativeBound, peer, crypto,
            authenticationMode = MslAuthenticationMode.COOKIE_SESSION)
        try {
            val params = KodiManifestRequest.create("80100172", "native-shared-xid", session.context,
                listOf("heaac-2-dash", "playready-h264mpl30-dash"), "Disconnected", 123456L)
            session.exchange(session.endpoint(NetflixMslSession.MANIFEST_ENDPOINT, "prefetch/licensedManifest"),
                "licensedManifest", params, PlaybackStage.MANIFEST)
            val challenge = byteArrayOf(0, 127, -128, -1)
            val licenseHref = "/license/fixture?opaque=preserved"
            session.exchange(session.endpoint(NetflixMslSession.LICENSE_ENDPOINT, "license"), licenseHref,
                licenseRequestParams(challenge, "real-player-sid", "native-shared-xid", session.context, 123456L),
                PlaybackStage.LICENSE, "drmSessionId")

            assertEquals(listOf(nativeId), peer.handshakeIdentities)
            assertEquals(listOf("WIDEVINE"), peer.handshakeSchemes)
            assertEquals(3, peer.totalRequests) // no logblob/EMAIL_PASSWORD/USER_ID_TOKEN bootstrap
            assertEquals(listOf("licensedManifest", licenseHref), peer.applicationRequests.map { it.getString("url") })
            assertEquals(0, source.nativeReads)
            assertEquals(0, source.nativeWrites)
            assertEquals(initial.esn, source.value.esn)
            assertEquals(2, source.updates)
            peer.authenticatedHeaders.forEachIndexed { index, header ->
                val prefix = if (index == 0) "native-handshake" else "native-manifest"
                assertFalse(header.has("useridtoken")) // response UIT never changes the L1 authentication family
                assertEquals(nativeId, header.getString("sender"))
                val auth = header.getJSONObject("userauthdata")
                assertEquals("NETFLIXID", auth.getString("scheme"))
                assertEquals("$prefix-a", auth.getJSONObject("authdata").getString("netflixid"))
                assertEquals("$prefix-b", auth.getJSONObject("authdata").getString("securenetflixid"))
            }
            val license = peer.applicationRequests.last().getJSONArray("params").getJSONObject(0)
            assertEquals("real-player-sid", license.getString("drmSessionId"))
            assertEquals("native-shared-xid", license.getString("xid"))
            assertArrayEquals(challenge, MslEncoding.decode(license.getString("challengeBase64")))

            // The native identity must not hide an account/browser-identity change.
            source.value = source.value.copy(esn = "different-browser-owner")
            val changed = failureOf {
                session.exchange(NetflixMslSession.LICENSE_ENDPOINT, licenseHref, JSONArray(), PlaybackStage.LICENSE)
            }
            assertEquals(PlaybackStage.SESSION, changed.stage)
            assertEquals(3, peer.totalRequests)
        } finally { session.close() }
        assertTrue(crypto.closed)
    }

    @Test fun `Widevine cookie mode still rejects malformed native master tokens before user data is sent`() {
        val nativeContext = initial.copy(esn = "NFANDROID1-PRV-T-FIXTURE-12345-" + "A".repeat(64))
        val provider = SessionProvider(nativeContext)
        val crypto = FixtureCrypto("WIDEVINE")
        val peer = FixturePeer(crypto)
        val invalidHandshake = MslTransport { url, wire, context, stage, operationId ->
            val response = peer.netflixPost(url, wire, context, stage, operationId)
            assertEquals(PlaybackStage.KEY_EXCHANGE, stage)
            val outer = JSONObject(response)
            val header = MslEncoding.json(MslEncoding.decode(outer.getString("headerdata")))
            val token = header.getJSONObject("keyresponsedata").getJSONObject("mastertoken")
            val data = MslEncoding.json(MslEncoding.decode(token.getString("tokendata")))
            data.remove("sequencenumber")
            token.put("tokendata", MslEncoding.encode(data.toString().toByteArray()))
            outer.put("headerdata", MslEncoding.encode(header.toString().toByteArray())).toString()
        }
        NetflixMslSession(BoundPlaybackSession(nativeContext, provider), invalidHandshake, crypto,
            authenticationMode = MslAuthenticationMode.COOKIE_SESSION).use { session ->
            failureOf { session.exchange(NetflixMslSession.MANIFEST_ENDPOINT, "licensedManifest", JSONObject(), PlaybackStage.MANIFEST) }
            assertEquals(1, peer.totalRequests)
            assertTrue(peer.authenticatedHeaders.isEmpty())
            assertEquals(0, provider.nativeReads)
        }
        assertTrue(crypto.closed)
    }

    @Test fun `switching profile or ESN stops license before sending a request`() {
        for (changed in listOf(initial.copy(profileGuid = "different-profile"), initial.copy(esn = "different-esn"))) {
            val provider = SessionProvider(initial)
            val crypto = FixtureCrypto()
            val peer = FixturePeer(crypto)
            val session = NetflixMslSession(BoundPlaybackSession(initial, provider), peer, crypto)
            try {
                session.exchange(NetflixMslSession.MANIFEST_ENDPOINT, "licensedManifest", JSONObject(), PlaybackStage.MANIFEST)
                provider.value = changed
                val failure = failureOf {
                    session.exchange(NetflixMslSession.LICENSE_ENDPOINT, "/license/fixture", JSONArray(), PlaybackStage.LICENSE)
                }
                assertEquals(PlaybackStage.SESSION, failure.stage)
                assertEquals(false, failure.retryable)
                assertEquals(2, peer.totalRequests)
            } finally { session.close() }
        }
    }

    @Test fun `late playback cookies cannot update a different profile`() {
        val provider = SessionProvider(initial)
        val bound = BoundPlaybackSession(initial, provider)
        provider.value = initial.copy(profileGuid = "other-profile")
        val failure = failureOf { bound.updateCookies(cookies("late-a", "late-b")) }
        assertEquals(PlaybackStage.SESSION, failure.stage)
        assertEquals(0, provider.updates)
        assertEquals(initial.cookieHeader, provider.value.cookieHeader)
    }

    @Test fun `cookie refresh preserves the playback client metadata`() {
        val provider = SessionProvider(initial)
        val bound = BoundPlaybackSession(initial, provider)
        provider.value = initial.copy(cookieHeader = "NetflixId=new-a; SecureNetflixId=new-b", clientVersion = "new-ui-version")
        assertEquals(provider.value.cookieHeader, bound.current().cookieHeader)
        assertEquals(initial.clientVersion, bound.current().clientVersion)
    }

    @Test fun `explicit native token mode requires a prior account token independently of cookie mode`() {
        val source = SessionProvider(initial)
        val sourceBound = BoundPlaybackSession(initial, source)
        val nativeId = "NFANDROID1-PRV-P-VELVETTEST-1234-" + "A".repeat(64)
        val nativeProvider = NativePlaybackSessionProvider(sourceBound, nativeId)
        val nativeBound = BoundPlaybackSession(nativeProvider.playbackContext(), nativeProvider)
        // This fixture tests MSL composition and ownership; it does not emulate a hardware CDM.
        val crypto = FixtureCrypto("WIDEVINE")
        val peer = FixturePeer(crypto)
        val session = NetflixMslSession(nativeBound, peer, crypto,
            authenticationMode = MslAuthenticationMode.NATIVE_USER_TOKEN)
        try {
            val missing = failureOf { session.authenticate(null) }
            assertTrue(missing.authenticationRejected)
            assertEquals(listOf(nativeId), peer.handshakeIdentities)
            assertEquals(listOf("WIDEVINE"), peer.handshakeSchemes)
            assertEquals(1, peer.totalRequests)
            assertEquals(0, source.updates)
            assertEquals(initial.esn, source.value.esn)
            assertTrue(peer.authenticatedHeaders.isEmpty())
            // A browser identity change must remain visible behind the stable native identity.
            source.value = source.value.copy(esn = "changed-source-browser")
            val error = failureOf {
                session.exchange(NetflixMslSession.LICENSE_ENDPOINT, "/license/fixture", JSONArray(), PlaybackStage.LICENSE)
            }
            assertEquals(PlaybackStage.SESSION, error.stage)
            assertEquals(1, peer.totalRequests)
        } finally { session.close() }
        assertTrue(crypto.closed)
    }

    private class SessionProvider(var value: PlaybackContext) : NetflixSessionProvider {
        var updates = 0
        var nativeReads = 0
        var nativeWrites = 0
        override fun playbackContext(): PlaybackContext = value
        override fun loadNativeAuthentication(): String { nativeReads++; return "" }
        override fun saveNativeAuthentication(value: String) { nativeWrites++ }
        override fun updateCookies(cookies: List<Cookie>) {
            updates++
            value = value.copy(cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" })
        }
    }

    private class FixturePeer(
        private val crypto: FixtureCrypto,
        private val onResponse: (PlaybackStage) -> Unit = {},
    ) : MslTransport {
        var totalRequests = 0
        val authenticatedHeaders = mutableListOf<JSONObject>()
        val authenticatedContexts = mutableListOf<PlaybackContext>()
        val applicationRequests = mutableListOf<JSONObject>()
        val handshakeIdentities = mutableListOf<String>()
        val handshakeSchemes = mutableListOf<String>()
        private val expiration = System.currentTimeMillis() / 1000 + 3600
        private val master = JSONObject().put("tokendata", MslEncoding.encode(JSONObject()
            .put("expiration", expiration).put("serialnumber", 17).put("sequencenumber", 4).toString().toByteArray()))
            .put("signature", MslEncoding.encode(byteArrayOf(1, 2, 3)))

        override fun netflixPost(url: String, wire: String, context: PlaybackContext, stage: PlaybackStage, operationId: Long): String {
            totalRequests++
            val messages = MslWire.parseObjects(wire)
            if (stage == PlaybackStage.KEY_EXCHANGE) {
                val requestHeader = MslEncoding.json(MslEncoding.decode(messages[0].getString("headerdata")))
                handshakeIdentities += messages[0].getJSONObject("entityauthdata").getJSONObject("authdata").getString("identity")
                handshakeSchemes += requestHeader.getJSONArray("keyrequestdata").getJSONObject(0).getString("scheme")
                val header = JSONObject().put("messageid", MslWire.responseMessageId(requestHeader.getLong("messageid")))
                    .put("keyresponsedata", JSONObject().put("scheme", crypto.keyExchangeScheme).put("mastertoken", master))
                onResponse(stage)
                return JSONObject().put("headerdata", MslEncoding.encode(header.toString().toByteArray())).toString()
            }
            val requestHeader = readSigned(messages[0], "headerdata")
            val payload = readSigned(messages[1], "payload")
            val data = MslEncoding.json(MslEncoding.decode(payload.getString("data")))
            authenticatedHeaders += requestHeader
            authenticatedContexts += context
            applicationRequests += data
            val id = MslWire.responseMessageId(requestHeader.getLong("messageid"))
            // 0.1.1 switched to this token for the next request. That regression must fail this test.
            val userToken = JSONObject().put("tokendata", MslEncoding.encode(JSONObject()
                .put("mtserialnumber", 17).put("serialnumber", 9).put("expiration", expiration).toString().toByteArray()))
                .put("signature", MslEncoding.encode(byteArrayOf(4, 5, 6)))
            val header = signed(JSONObject().put("messageid", id).put("useridtoken", userToken), "headerdata")
            val result: Any = if (stage == PlaybackStage.LICENSE) JSONArray().put(JSONObject().put("ok", true)) else JSONObject().put("ok", true)
            val response = JSONObject().put("result", result).toString().toByteArray()
            val chunk = signed(JSONObject().put("messageid", id).put("sequencenumber", 1).put("endofmsg", true)
                .put("data", MslEncoding.encode(response)), "payload")
            onResponse(stage)
            return header.toString() + chunk.toString()
        }

        private fun readSigned(value: JSONObject, field: String): JSONObject {
            val encrypted = MslEncoding.decode(value.getString(field))
            assertTrue(crypto.verify(encrypted, MslEncoding.decode(value.getString("signature"))))
            return MslEncoding.json(crypto.decrypt(encrypted))
        }

        private fun signed(value: JSONObject, field: String): JSONObject {
            val encrypted = crypto.encrypt(value.toString().toByteArray())
            return JSONObject().put(field, MslEncoding.encode(encrypted)).put("signature", MslEncoding.encode(crypto.sign(encrypted)))
        }
    }

    /** Synthetic peer keys test sequencing/auth; real crypto adapters have their own unit tests. */
    private class FixtureCrypto(override val keyExchangeScheme: String = "ASYMMETRIC_WRAPPED") : MslCrypto {
        var closed = false
        private val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
        private val hmac = SecretKeySpec(ByteArray(32) { (it + 16).toByte() }, "HmacSHA256")
        override fun keyRequest() = JSONObject().put("scheme", keyExchangeScheme)
        override fun acceptKeyResponse(keyResponse: JSONObject) = Unit
        override fun close() { closed = true }
        override fun encrypt(plaintext: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(plaintext)
        override fun decrypt(envelope: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE).doFinal(envelope)
        private fun cipher(mode: Int) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, key, IvParameterSpec(ByteArray(16))) }
        override fun sign(message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(hmac) }.doFinal(message)
        override fun verify(message: ByteArray, signature: ByteArray): Boolean = MessageDigest.isEqual(sign(message), signature)
    }

    private fun cookies(first: String, second: String): List<Cookie> = listOf(
        Cookie.Builder().name("NetflixId").value(first).domain("netflix.com").path("/").secure().build(),
        Cookie.Builder().name("SecureNetflixId").value(second).domain("netflix.com").path("/").secure().build(),
    )

    private fun failureOf(block: () -> Unit): NetflixPlaybackException {
        try { block(); fail("Expected session failure") }
        catch (expected: NetflixPlaybackException) { return expected }
        throw AssertionError()
    }
}
