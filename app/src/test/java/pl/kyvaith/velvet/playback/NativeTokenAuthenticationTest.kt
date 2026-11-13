package pl.kyvaith.velvet.playback

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independent signed peer; no Netflix account, token, device keys, or network is used. */
class NativeTokenAuthenticationTest {
    @Test fun `token activation obtains owner only after account and commits only after selected profile`() {
        val fixture = Fixture()
        val token = NativeLoginToken(LOGIN_TOKEN)
        fixture.session().use { session ->
            session.authenticateWithToken(token, CONFIG)
            assertTrue(runCatching { token.value() }.isFailure)
            assertEquals(OPERATIONS, fixture.peer.operations)
            assertEquals(listOf(HANDSHAKE), fixture.peer.handshakeUrls)
            assertTrue(fixture.provider.writes.isEmpty())
            assertEquals(listOf(LOGIN_TOKEN, null, null), fixture.peer.loginTokens)
            assertEquals(listOf("NONE", "NETFLIXID", "NETFLIXID"), fixture.peer.nativeSchemes)
            assertTrue(fixture.peer.wires.none { it.contains(LOGIN_TOKEN) })
            assertEquals("", fixture.provider.record)
            fixture.manifest(session)
            assertEquals(listOf("SWITCH_PROFILE"), fixture.peer.playapiSchemes)
            val stored = NativeMslTokenPair.readOwnerRecord(fixture.provider.record, fixture.context.esn, NOW)
            assertEquals(OWNER_SERIAL, metadata(stored.userToken).getLong("serialnumber"))
            assertEquals(1, fixture.provider.writes.size)
            fixture.license(session)
            assertEquals(listOf("SWITCH_PROFILE", "HEADER_TOKEN"), fixture.peer.playapiSchemes)
        }
    }

    @Test fun `an earlier valid owner UID is accepted after account even when account returns none`() {
        for (source in listOf(NativeMslOperation.CONSUME_TOKEN, NativeMslOperation.FETCH_CONFIG)) {
            val fixture = Fixture()
            fixture.peer.responseTokens.clear()
            fixture.peer.responseTokens[source] = fixture.peer.owner
            fixture.session().use { session ->
                session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG)
                assertEquals(OPERATIONS, fixture.peer.operations)
                assertTrue(fixture.provider.writes.isEmpty())
                fixture.manifest(session)
                assertEquals(1, fixture.provider.writes.size)
            }
        }
    }

    @Test fun `latest valid UID wins while earlier foreign UID cannot authorize the final session`() {
        val fixture = Fixture()
        fixture.peer.responseTokens[NativeMslOperation.CONSUME_TOKEN] = fixture.peer.user(13, MASTER_SERIAL + 1)
        fixture.peer.responseTokens[NativeMslOperation.FETCH_CONFIG] = fixture.peer.user(14)
        fixture.session().use { session ->
            session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG)
            fixture.manifest(session)
            val stored = NativeMslTokenPair.readOwnerRecord(fixture.provider.record, fixture.context.esn, NOW)
            assertEquals(OWNER_SERIAL, metadata(stored.userToken).getLong("serialnumber"))
        }
    }

    @Test fun `missing foreign expired or malformed owner UID fails after Account without persisting`() {
        for (fault in listOf("missing", "foreign", "expired", "malformed")) {
            val fixture = Fixture()
            fixture.peer.responseTokens.clear()
            when (fault) {
                "foreign" -> fixture.peer.responseTokens[NativeMslOperation.FETCH_ACCOUNT] = fixture.peer.user(55, MASTER_SERIAL + 1)
                "expired" -> fixture.peer.responseTokens[NativeMslOperation.FETCH_ACCOUNT] = fixture.peer.user(55, expiration = NOW)
                "malformed" -> fixture.peer.responseTokens[NativeMslOperation.FETCH_ACCOUNT] = JSONObject().put("tokendata", "not-base64")
            }
            val token = NativeLoginToken(LOGIN_TOKEN)
            fixture.session().use { session ->
                failure { session.authenticateWithToken(token, CONFIG) }
                assertEquals(OPERATIONS, fixture.peer.operations)
                assertTrue(fixture.provider.writes.isEmpty())
                assertTrue(runCatching { token.value() }.isFailure)
                failure { fixture.manifest(session) }
                assertTrue(fixture.peer.playapiSchemes.isEmpty())
            }
        }
    }

    @Test fun `consumed token must produce a complete new cookie pair before Config`() {
        for (cookies in listOf(null, "nfvdid=fixture; NetflixId=native-id", "NetflixId=one; NetflixId=two; SecureNetflixId=secure")) {
            val fixture = Fixture()
            fixture.peer.cookieUpdate = cookies
            fixture.session().use { session ->
                failure { session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG) }
                assertEquals(listOf(NativeMslOperation.CONSUME_TOKEN), fixture.peer.operations)
                assertTrue(fixture.provider.writes.isEmpty())
            }
        }
    }

    @Test fun `GraphQL errors abort even with partial data and never expose server text`() {
        for (operation in listOf(NativeMslOperation.CONSUME_TOKEN, NativeMslOperation.FETCH_ACCOUNT)) {
            val fixture = Fixture()
            fixture.peer.bodies.getValue(operation).put("errors", JSONArray().put(JSONObject()
                .put("message", "PRIVATE_SERVER_TEXT_" + LOGIN_TOKEN)))
            fixture.session().use { session ->
                val error = failure { session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG) }
                assertFalse(error.message.orEmpty().contains("PRIVATE_SERVER_TEXT"))
                assertFalse(error.message.orEmpty().contains(LOGIN_TOKEN))
                assertNull(error.cause)
                assertTrue(fixture.provider.writes.isEmpty())
                assertEquals(if (operation == NativeMslOperation.CONSUME_TOKEN) 1 else 3, fixture.peer.operations.size)
            }
        }
    }

    @Test fun `owner mismatch missing chosen profile inactive account and invalid GUIDs reject activation`() {
        for (fault in listOf("owner", "profile", "empty", "invalid", "inactive", "null-owner", "null-guid", "duplicate")) {
            val fixture = Fixture()
            val account = fixture.peer.bodies.getValue(NativeMslOperation.FETCH_ACCOUNT).getJSONObject("data").getJSONObject("account")
            when (fault) {
                "owner" -> account.put("ownerGuid", "different-owner")
                "profile" -> account.getJSONArray("profiles").getJSONObject(0).put("guid", "different-profile")
                "empty" -> account.put("profiles", JSONArray())
                "invalid" -> account.getJSONArray("profiles").getJSONObject(0).put("guid", "invalid_guid")
                "inactive" -> account.put("isNonMember", true)
                "null-owner" -> account.put("ownerGuid", JSONObject.NULL)
                "null-guid" -> account.getJSONArray("profiles").getJSONObject(0).put("guid", JSONObject.NULL)
                "duplicate" -> account.getJSONArray("profiles").put(JSONObject().put("guid", PROFILE))
            }
            fixture.session().use { session ->
                failure { session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG) }
                assertEquals(OPERATIONS, fixture.peer.operations)
                assertTrue(fixture.provider.writes.isEmpty())
                assertTrue(fixture.peer.playapiSchemes.isEmpty())
            }
        }
    }

    @Test fun `optional Consume owner does not replace mandatory account and profile verification`() {
        val fixture = Fixture()
        fixture.peer.bodies.getValue(NativeMslOperation.CONSUME_TOKEN).getJSONObject("data")
            .getJSONObject("deviceUpgrade").getJSONObject("account").remove("ownerGuid")
        fixture.session().use { session ->
            session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG)
            fixture.manifest(session)
            assertEquals(1, fixture.provider.writes.size)
        }
        val invalid = Fixture()
        invalid.peer.bodies.getValue(NativeMslOperation.CONSUME_TOKEN).getJSONObject("data")
            .getJSONObject("deviceUpgrade").getJSONObject("account").put("ownerGuid", JSONObject.NULL)
        invalid.session().use { session ->
            failure { session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG) }
            assertEquals(1, invalid.peer.operations.size)
        }
    }

    @Test fun `tampered native responses unexpected key responses and incompatible master renewal fail closed`() {
        for (fault in listOf("signature", "key-response", "master")) {
            val fixture = Fixture()
            when (fault) {
                "signature" -> fixture.peer.tamperOperation = NativeMslOperation.FETCH_CONFIG
                "key-response" -> fixture.peer.headerTransform = { operation, header ->
                    if (operation == NativeMslOperation.FETCH_CONFIG) header.put("keyresponsedata", JSONObject())
                }
                "master" -> fixture.peer.outerMaster = fixture.peer.master(MASTER_SERIAL + 1)
            }
            fixture.session().use { session ->
                failure { session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG) }
                assertTrue(fixture.provider.writes.isEmpty())
                assertEquals(if (fault == "master") 1 else 2, fixture.peer.operations.size)
            }
        }
    }

    @Test fun `account or profile changes while a native response is in flight cannot activate session`() {
        for (operation in OPERATIONS) {
            val fixture = Fixture()
            fixture.peer.onResponse = {
                if (it == operation) fixture.provider.context = fixture.context.copy(profileGuid = "replacement-profile")
            }
            fixture.session().use { session ->
                val error = failure { session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG) }
                assertEquals(PlaybackStage.SESSION, error.stage)
                assertTrue(fixture.provider.writes.isEmpty())
                assertTrue(fixture.peer.playapiSchemes.isEmpty())
            }
        }
    }

    @Test fun `service rejection retains MSL codes and never falls back to password or cached owner`() {
        val fixture = Fixture()
        fixture.peer.rejectOperation = NativeMslOperation.FETCH_CONFIG
        fixture.session().use { session ->
            val error = failure { session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG) }
            assertEquals("205032", error.serviceCode)
            assertEquals(7, error.mslResponseCode)
            assertTrue(fixture.provider.writes.isEmpty())
            failure { fixture.manifest(session) }
            assertTrue(fixture.peer.playapiSchemes.isEmpty())
        }
    }

    @Test fun `cancellation before and after each native network phase stops the next operation and closes token`() {
        // One check on each side of handshake, Consume, Config and Account.
        for (cancelAt in 1..8) {
            val fixture = Fixture()
            val token = NativeLoginToken(LOGIN_TOKEN)
            var checks = 0
            val cancelled = CancellationException("fixture cancellation")
            fixture.session().use { session ->
                try {
                    session.authenticateWithToken(token, CONFIG) { if (++checks == cancelAt) throw cancelled }
                    fail("Expected cancellation")
                } catch (error: CancellationException) {
                    assertSame(cancelled, error)
                }
                assertTrue(runCatching { token.value() }.isFailure)
                assertTrue(fixture.provider.writes.isEmpty())
                val expectedNativeOperations = when (cancelAt) { 1, 2, 3 -> 0; 4, 5 -> 1; 6, 7 -> 2; else -> 3 }
                assertEquals(expectedNativeOperations, fixture.peer.operations.size)
                assertEquals(if (cancelAt == 1) 0 else 1, fixture.peer.handshakeUrls.size)
            }
        }
    }

    @Test fun `native manifest requires a valid bound profile UIT before owner state is committed`() {
        for (fault in listOf("missing", "foreign", "expired", "malformed")) {
            val fixture = Fixture()
            fixture.peer.playbackToken = when (fault) {
                "missing" -> null
                "foreign" -> fixture.peer.user(PROFILE_SERIAL, MASTER_SERIAL + 1)
                "expired" -> fixture.peer.user(PROFILE_SERIAL, expiration = NOW)
                else -> JSONObject().put("tokendata", "invalid")
            }
            fixture.session().use { session ->
                session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG)
                failure { fixture.manifest(session) }
                assertTrue(fixture.provider.writes.isEmpty())
            }
        }
    }

    @Test fun `native playback tampering and an account change cannot commit profile tokens`() {
        for (fault in listOf("signature", "owner")) {
            val fixture = Fixture()
            if (fault == "signature") fixture.peer.tamperPlayback = true
            else fixture.peer.onPlaybackResponse = { fixture.provider.context = fixture.context.copy(profileGuid = "new-profile") }
            fixture.session().use { session ->
                session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG)
                failure { fixture.manifest(session) }
                assertTrue(fixture.provider.writes.isEmpty())
            }
        }
    }

    @Test fun `native manifest server error keeps numeric code without committing or changing transport`() {
        val fixture = Fixture()
        fixture.peer.playbackBody = JSONObject().put("result", JSONArray().put(JSONObject().put("movieId", "12345")
            .put("error", JSONObject().put("bladeRunnerCode", 1044).put("detail", "private-server-text"))))
        fixture.session().use { session ->
            session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG)
            val error = failure { fixture.manifest(session) }
            assertEquals("1044", error.serviceCode)
            assertFalse(error.message.orEmpty().contains("private-server-text"))
            assertTrue(fixture.provider.writes.isEmpty())
            assertEquals(1, fixture.peer.handshakeUrls.size)
        }
    }

    @Test fun `expired native entity session never enters legacy WWW reauthentication`() {
        val fixture = Fixture()
        fixture.session().use { session ->
            session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG)
            fixture.manifest(session)
            fixture.now = NOW + 980
            val error = failure { fixture.license(session) }
            assertEquals(PlaybackStage.AUTHENTICATION, error.stage)
            assertEquals(1, fixture.peer.handshakeUrls.size)
            assertEquals(listOf("SWITCH_PROFILE"), fixture.peer.playapiSchemes)
        }
    }

    @Test fun `native expiry threshold crossing cannot trigger a second handshake or WWW fallback`() {
        val fixture = Fixture()
        fixture.session().use { session ->
            session.authenticateWithToken(NativeLoginToken(LOGIN_TOKEN), CONFIG)
            fixture.manifest(session)
            var clockReads = 0
            fixture.clock = { if (clockReads++ == 0) NOW + 969 else NOW + 980 }
            fixture.license(session)
            assertEquals(1, fixture.peer.handshakeUrls.size)
            assertEquals(listOf("SWITCH_PROFILE", "HEADER_TOKEN"), fixture.peer.playapiSchemes)
        }
    }

    private class Fixture {
        val context = PlaybackContext("nfvdid=fixture", "", "NFANDROID1-PRV-P-FIXTURE-1234-" + "A".repeat(64), PROFILE, "fixture-agent")
        val provider = Provider(context)
        val peer = Peer(provider)
        var now = NOW
        var clock: () -> Long = { now }
        fun session() = NetflixMslSession(BoundPlaybackSession(context, provider), peer, TestCrypto(),
            authenticationMode = MslAuthenticationMode.NATIVE_USER_TOKEN, handshakeEndpoint = HANDSHAKE) { clock() }
        fun manifest(session: NetflixMslSession): JSONObject = NativePlaybackRequests.selectManifest(
            session.exchangeNativePlayback(NativePlaybackOperation.MANIFEST, "/licensedManifest",
                JSONArray().put(JSONObject().put("viewableId", "12345")), PlaybackStage.MANIFEST), "12345")
        fun license(session: NetflixMslSession) = session.exchangeNativePlayback(NativePlaybackOperation.LICENSE,
            "/license/fixture", NativePlaybackRequests.license(byteArrayOf(1, 2, 3), "1700000000", NOW), PlaybackStage.LICENSE)
    }

    private class Provider(var context: PlaybackContext) : NetflixSessionProvider {
        var record = ""
        val writes = mutableListOf<String>()
        override fun playbackContext() = context
        override fun loadNativeAuthentication(): String = throw AssertionError("Token bridge must not load a legacy owner record")
        override fun saveNativeAuthentication(value: String) { record = value; writes += value }
    }

    private class Peer(private val provider: Provider) : NativeMslTransport {
        private val crypto = TestCrypto()
        private val masterToken = master()
        val owner = user(OWNER_SERIAL)
        private val profile = user(PROFILE_SERIAL)
        val responseTokens = mutableMapOf(NativeMslOperation.FETCH_ACCOUNT to owner)
        val bodies = mutableMapOf(
            NativeMslOperation.CONSUME_TOKEN to JSONObject().put("data", JSONObject().put("deviceUpgrade",
                JSONObject().put("account", JSONObject().put("ownerGuid", OWNER)))),
            NativeMslOperation.FETCH_CONFIG to JSONObject().put("accountConfig", JSONObject().put("fixture", true)),
            NativeMslOperation.FETCH_ACCOUNT to JSONObject().put("data", JSONObject().put("account", JSONObject()
                .put("ownerGuid", OWNER).put("isNonMember", false)
                .put("profiles", JSONArray().put(JSONObject().put("guid", PROFILE).put("isAccountOwner", false))))),
        )
        val operations = mutableListOf<NativeMslOperation>()
        val nativeSchemes = mutableListOf<String>()
        val playapiSchemes = mutableListOf<String>()
        val loginTokens = mutableListOf<String?>()
        val handshakeUrls = mutableListOf<String>()
        val wires = mutableListOf<String>()
        var cookieUpdate: String? = NATIVE_COOKIES
        var tamperOperation: NativeMslOperation? = null
        var rejectOperation: NativeMslOperation? = null
        var outerMaster: JSONObject? = null
        var headerTransform: (NativeMslOperation, JSONObject) -> Unit = { _, _ -> }
        var onResponse: (NativeMslOperation) -> Unit = {}
        var playbackToken: JSONObject? = profile
        var playbackBody: JSONObject? = null
        var tamperPlayback = false
        var onPlaybackResponse: (NativePlaybackOperation) -> Unit = {}

        override fun netflixPost(url: String, wire: String, context: PlaybackContext, stage: PlaybackStage, operationId: Long): String {
            wires += wire
            val objects = MslWire.parseObjects(wire)
            if (stage == PlaybackStage.KEY_EXCHANGE) {
                handshakeUrls += url
                assertEquals(context.esn, objects[0].getJSONObject("entityauthdata").getJSONObject("authdata").getString("identity"))
                val header = MslEncoding.json(MslEncoding.decode(objects[0].getString("headerdata")))
                assertEquals("WIDEVINE", header.getJSONArray("keyrequestdata").getJSONObject(0).getString("scheme"))
                return JSONObject().put("headerdata", MslEncoding.encode(JSONObject()
                    .put("messageid", header.getLong("messageid") + 1)
                    .put("keyresponsedata", JSONObject().put("scheme", "WIDEVINE").put("mastertoken", masterToken))
                    .toString().toByteArray())).toString()
            }
            throw AssertionError("Native manifest/license must not use the legacy WWW transport")
        }

        override fun playbackPost(operation: NativePlaybackOperation, wire: String, context: PlaybackContext,
                                  stage: PlaybackStage, operationId: Long): String {
            wires += wire
            val request = readRequest(MslWire.parseObjects(wire), context)
            val body = JSONObject(String(request.second, Charsets.UTF_8))
            assertEquals(setOf("version", "url", "languages", "params"), body.keys().asSequence().toSet())
            assertEquals(2, body.getInt("version"))
            assertEquals(context.locale, body.getJSONArray("languages").getString(0))
            val auth = request.first.optJSONObject("userauthdata")
            val scheme = auth?.getString("scheme") ?: "HEADER_TOKEN"
            playapiSchemes += scheme
            if (operation == NativePlaybackOperation.MANIFEST) {
                assertEquals(PlaybackStage.MANIFEST, stage)
                assertEquals("/licensedManifest", body.getString("url"))
                assertEquals("12345", body.getJSONArray("params").getJSONObject(0).get("viewableId"))
                assertEquals("SWITCH_PROFILE", scheme)
                assertEquals(PROFILE, auth!!.getJSONObject("authdata").getString("profileguid"))
                assertEquals(OWNER_SERIAL, metadata(auth.getJSONObject("authdata").getJSONObject("useridtoken")).getLong("serialnumber"))
            } else {
                assertEquals(PlaybackStage.LICENSE, stage)
                assertEquals("/license/fixture", body.getString("url"))
                val params = body.getJSONObject("params")
                assertEquals(setOf("clientTime", "challengeBase64", "xid"), params.keys().asSequence().toSet())
                assertArrayEquals(byteArrayOf(1, 2, 3), MslEncoding.decode(params.getString("challengeBase64")))
                assertEquals("HEADER_TOKEN", scheme)
                assertEquals(PROFILE_SERIAL, metadata(request.first.getJSONObject("useridtoken")).getLong("serialnumber"))
            }
            val result = if (operation == NativePlaybackOperation.MANIFEST) {
                JSONArray().put(JSONObject().put("movieId", "12345").put("ok", true))
            } else JSONObject().put("licenseResponseBase64", "AQI=")
            val answer = response(request.first.getLong("messageid"), playbackBody ?: JSONObject().put("result", result),
                if (operation == NativePlaybackOperation.MANIFEST) playbackToken else null)
            onPlaybackResponse(operation)
            if (!tamperPlayback) return answer
            val parts = MslWire.parseObjects(answer)
            parts.last().put("signature", MslEncoding.encode(ByteArray(32)))
            return parts.joinToString("")
        }

        override fun nativePost(operation: NativeMslOperation, wire: String, context: PlaybackContext,
                                operationId: Long, loginToken: String?): String {
            assertEquals(OPERATIONS[operations.size], operation)
            operations += operation
            loginTokens += loginToken
            wires += wire
            val request = readRequest(MslWire.parseObjects(wire), context)
            val auth = request.first.optJSONObject("userauthdata")
            nativeSchemes += auth?.getString("scheme") ?: "NONE"
            if (operation == NativeMslOperation.CONSUME_TOKEN) {
                assertEquals(LOGIN_TOKEN, loginToken)
                assertFalse(request.first.has("userauthdata"))
                assertFalse(request.first.has("useridtoken"))
                assertEquals("nfvdid=fixture", context.cookieHeader)
                graphQl(request.second, "ConsumeAutoLoginToken", "dc3bac4c-760d-42c3-b41c-2cdb81e5fa5b", true)
                cookieUpdate?.let { provider.context = provider.context.copy(cookieHeader = it) }
            } else {
                assertNull(loginToken)
                assertEquals(NATIVE_COOKIES, context.cookieHeader)
                assertEquals("NETFLIXID", auth!!.getString("scheme"))
                assertEquals("native-id", auth.getJSONObject("authdata").getString("netflixid"))
                assertEquals("native-secure", auth.getJSONObject("authdata").getString("securenetflixid"))
                assertFalse(request.first.has("useridtoken"))
                if (operation == NativeMslOperation.FETCH_CONFIG) assertArrayEquals(CONFIG, request.second)
                else graphQl(request.second, "AccountQuery", "81b80497-2447-4ede-9e32-124ec928e107", false)
            }
            if (rejectOperation == operation) return JSONObject().put("errordata", MslEncoding.encode(JSONObject()
                .put("internalcode", 205032).put("errorcode", 7).toString().toByteArray())).toString()
            return response(request.first.getLong("messageid"), bodies.getValue(operation), responseTokens[operation], operation)
                .also { onResponse(operation) }
        }

        private fun graphQl(bytes: ByteArray, name: String, id: String, consume: Boolean) {
            val data = JSONObject(String(bytes, Charsets.UTF_8))
            assertEquals(3, data.length())
            assertEquals(name, data.getString("operationName"))
            val variables = data.getJSONObject("variables")
            assertEquals(if (consume) 1 else 0, variables.length())
            if (consume) assertEquals(false, variables.get("logOnly"))
            val extensions = data.getJSONObject("extensions")
            assertEquals(1, extensions.length())
            val persisted = extensions.getJSONObject("persistedQuery")
            assertEquals(2, persisted.length())
            assertEquals(102, persisted.getInt("version"))
            assertEquals(id, persisted.getString("id"))
        }

        private fun readRequest(objects: List<JSONObject>, context: PlaybackContext): Pair<JSONObject, ByteArray> {
            val sentMaster = objects[0].getJSONObject("mastertoken")
            assertEquals(masterToken.getString("tokendata"), sentMaster.getString("tokendata"))
            assertEquals(masterToken.getString("signature"), sentMaster.getString("signature"))
            val header = readSigned(objects[0], "headerdata")
            val payload = readSigned(objects[1], "payload")
            assertEquals(context.esn, header.getString("sender"))
            assertEquals(header.getLong("messageid"), payload.getLong("messageid"))
            assertEquals(1, payload.getInt("sequencenumber"))
            assertTrue(payload.getBoolean("endofmsg"))
            return header to MslEncoding.decode(payload.getString("data"))
        }

        private fun response(requestId: Long, body: JSONObject, token: JSONObject?, operation: NativeMslOperation? = null): String {
            val id = requestId + 1
            val header = JSONObject().put("messageid", id)
            token?.let { header.put("useridtoken", it) }
            operation?.let { headerTransform(it, header) }
            val outer = signed(header, "headerdata")
            if (operation != null) outerMaster?.let { outer.put("mastertoken", it) }
            val payload = signed(JSONObject().put("messageid", id).put("sequencenumber", 1).put("endofmsg", true)
                .put("data", MslEncoding.encode(body.toString().toByteArray())), "payload")
            if (operation != null && operation == tamperOperation) payload.put("signature", MslEncoding.encode(ByteArray(32)))
            return outer.toString() + payload.toString()
        }

        fun master(serial: Long = MASTER_SERIAL): JSONObject = opaque(JSONObject().put("serialnumber", serial)
            .put("sequencenumber", 1).put("expiration", NOW + 1000))
        fun user(serial: Long, masterSerial: Long = MASTER_SERIAL, expiration: Long = NOW + 1000): JSONObject =
            opaque(JSONObject().put("serialnumber", serial).put("mtserialnumber", masterSerial).put("expiration", expiration))
        private fun opaque(data: JSONObject): JSONObject = JSONObject().put("tokendata", MslEncoding.encode(data.toString().toByteArray()))
            .put("signature", MslEncoding.encode(crypto.sign(data.toString().toByteArray())))
        private fun readSigned(objectData: JSONObject, field: String): JSONObject {
            val bytes = MslEncoding.decode(objectData.getString(field))
            assertTrue(crypto.verify(bytes, MslEncoding.decode(objectData.getString("signature"))))
            return JSONObject(String(crypto.decrypt(bytes), Charsets.UTF_8))
        }
        private fun signed(data: JSONObject, field: String): JSONObject {
            val bytes = crypto.encrypt(data.toString().toByteArray())
            return JSONObject().put(field, MslEncoding.encode(bytes)).put("signature", MslEncoding.encode(crypto.sign(bytes)))
        }
    }

    private class TestCrypto : MslCrypto {
        override val keyExchangeScheme = "WIDEVINE"
        private val key = SecretKeySpec(ByteArray(16) { (it + 5).toByte() }, "AES")
        private val hmac = SecretKeySpec(ByteArray(32) { (it + 41).toByte() }, "HmacSHA256")
        override fun keyRequest() = JSONObject().put("scheme", keyExchangeScheme)
        override fun acceptKeyResponse(keyResponse: JSONObject) = Unit
        override fun close() = Unit
        override fun encrypt(plaintext: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(plaintext)
        override fun decrypt(envelope: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE).doFinal(envelope)
        private fun cipher(mode: Int) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, key, IvParameterSpec(ByteArray(16))) }
        override fun sign(message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(hmac) }.doFinal(message)
        override fun verify(message: ByteArray, signature: ByteArray) = MessageDigest.isEqual(sign(message), signature)
    }

    companion object {
        private const val NOW = 1700000000L
        private const val MASTER_SERIAL = 700L
        private const val OWNER_SERIAL = 800L
        private const val PROFILE_SERIAL = 900L
        private const val PROFILE = "selected-profile"
        private const val OWNER = "fixture-owner"
        private const val HANDSHAKE = "https://android.prod.ftl.netflix.com/nq/msl_v1/cadmium/pbo_license/1"
        private const val LOGIN_TOKEN = "synthetic-nftoken-0000000000000000000000000000000000000000000000000000000000000000"
        private const val NATIVE_COOKIES = "nfvdid=fixture; NetflixId=native-id; SecureNetflixId=native-secure"
        private val CONFIG = "method=get&path=%5B%22accountConfig%22%5D&path=%5B%22deviceConfig%22%5D&".toByteArray()
        private val OPERATIONS = listOf(NativeMslOperation.CONSUME_TOKEN, NativeMslOperation.FETCH_CONFIG, NativeMslOperation.FETCH_ACCOUNT)
        private fun metadata(token: JSONObject) = JSONObject(String(MslEncoding.decode(token.getString("tokendata")), Charsets.UTF_8))
        private fun failure(block: () -> Unit): NetflixPlaybackException {
            try { block(); fail("Expected authentication failure") } catch (error: NetflixPlaybackException) { return error }
            throw AssertionError()
        }
    }
}
