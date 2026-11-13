package pl.kyvaith.velvet.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A synthetic peer checks the documented MSL contracts with independent token ownership.
 * It cannot establish which authentication factories Netflix enables for an Android client,
 * nor certify the device, account, real Widevine CDM, or SWITCH_PROFILE-to-owner behavior.
 */
class NativeMslAuthenticationTest {
    @Test fun `explicit login selects profile before persisting owner and license uses ordinary profile token`() {
        val fixture = Fixture()
        val credentials = credentials()
        fixture.newSession().use { session ->
            session.authenticate(credentials)
            assertEquals(listOf("EMAIL_PASSWORD"), fixture.peer.schemes)
            assertEquals("", fixture.provider.record)
            assertTrue(fixture.provider.writes.isEmpty())
            assertTrue(failure { credentials.authenticationData() }.authenticationRejected)
            fixture.manifest(session)
            val stored = NativeMslTokenPair.readOwnerRecord(fixture.provider.record, fixture.context.esn, fixture.now)
            assertEquals("owner", fixture.peer.roleOf(stored.userToken))
            assertEquals(1, fixture.provider.writes.size)
            fixture.license(session)
            assertEquals(listOf("EMAIL_PASSWORD", "SWITCH_PROFILE", "HEADER_TOKEN"), fixture.peer.schemes)
            val profileAuth = fixture.peer.headers[1].getJSONObject("userauthdata").getJSONObject("authdata")
            assertEquals(fixture.context.profileGuid, profileAuth.getString("profileguid"))
            assertEquals("owner", fixture.peer.roleOf(profileAuth.getJSONObject("useridtoken")))
            val licenseHeader = fixture.peer.headers.last()
            assertFalse(licenseHeader.has("userauthdata"))
            assertEquals("profile", fixture.peer.roleOf(licenseHeader.getJSONObject("useridtoken")))
            assertEquals(1, fixture.peer.handshakes)
            assertFalse(fixture.peer.wires.any { it.contains("secret-password") || it.contains("owner@example.invalid") })
            assertFalse(fixture.provider.record.contains("secret-password"))
            assertTrue(fixture.peer.contexts.all { it.cookieHeader == fixture.context.cookieHeader })
        }
    }

    @Test fun `fresh native session reauthenticates its own old pair then obtains selected profile token`() {
        val fixture = Fixture()
        fixture.establish()
        val previous = fixture.provider.record
        fixture.peer.resetObservations()
        fixture.provider.writes.clear()
        fixture.newSession().use { session ->
            session.authenticate(null)
            assertEquals(listOf("USER_ID_TOKEN"), fixture.peer.schemes)
            assertEquals(previous, fixture.provider.record)
            assertTrue(fixture.provider.writes.isEmpty())
            val oldAuth = fixture.peer.headers.single().getJSONObject("userauthdata").getJSONObject("authdata")
            val old = NativeMslTokenPair.readOwnerRecord(previous, fixture.context.esn, fixture.now)
            assertEquals(old.masterToken.toString(), oldAuth.getJSONObject("mastertoken").toString())
            assertEquals("owner", fixture.peer.roleOf(oldAuth.getJSONObject("useridtoken")))
            fixture.manifest(session)
            assertEquals(listOf("USER_ID_TOKEN", "SWITCH_PROFILE"), fixture.peer.schemes)
            assertNotEquals(previous, fixture.provider.record)
            val fresh = NativeMslTokenPair.readOwnerRecord(fixture.provider.record, fixture.context.esn, fixture.now)
            assertNotEquals(metadata(old.masterToken).getLong("serialnumber"), metadata(fresh.masterToken).getLong("serialnumber"))
            assertEquals("owner", fixture.peer.roleOf(fresh.userToken))
        }
    }

    @Test fun `native cookie and entity mismatch is rejected by the peer rather than accepted by the fixture`() {
        val fixture = Fixture()
        val crypto = FixtureCrypto()
        val wire = MslWire(crypto)
        val handshake = wire.handshake(fixture.context.esn, fixture.context.locale)
        val response = fixture.peer.netflixPost("https://www.netflix.com/fixture", handshake.wire,
            fixture.context, PlaybackStage.KEY_EXCHANGE, 1)
        val master = wire.readHandshake(response, handshake.messageId).getJSONObject("mastertoken")
        val request = wire.request(fixture.context, master, JSONObject().put("url", "/manifest"), MslAuthentication.NetflixCookies)
        val rejected = fixture.peer.netflixPost("https://www.netflix.com/fixture", request.wire,
            fixture.context, PlaybackStage.MANIFEST, 2)
        val error = failure { wire.readResponse(rejected, request.messageId, PlaybackStage.MANIFEST) }
        assertEquals("205032", error.serviceCode)
        assertEquals(7, error.mslResponseCode)
        assertTrue(error.authenticationRejected)
    }

    @Test fun `missing foreign malformed and expired cached records require explicit login without sending user auth`() {
        val mutations: List<(Fixture, String) -> String> = listOf(
            { _, _ -> "" },
            { _, record -> JSONObject(record).put("identity", "NFANDROID1-FOREIGN-" + "B".repeat(64)).toString() },
            { _, _ -> "{bad-json" },
            { fixture, record -> fixture.now += 1001; record },
        )
        for (mutate in mutations) {
            val fixture = Fixture()
            fixture.establish()
            fixture.provider.record = mutate(fixture, fixture.provider.record)
            fixture.peer.resetObservations()
            fixture.provider.writes.clear()
            fixture.newSession().use { session ->
                assertTrue(failure { session.authenticate(null) }.authenticationRejected)
                assertTrue(fixture.peer.schemes.isEmpty())
                assertEquals("", fixture.provider.record)
                assertEquals(listOf(""), fixture.provider.writes)
            }
        }
    }

    @Test fun `cached reauthentication rejects once and unsupported factory also permits explicit login`() {
        for ((internalCode, responseCode) in listOf(205032 to 7, 205000 to 1, 205001 to 4)) {
            val fixture = Fixture()
            fixture.establish()
            fixture.peer.resetObservations()
            fixture.provider.writes.clear()
            fixture.peer.rejection = { scheme, _, _ ->
                if (scheme == "USER_ID_TOKEN") Rejection(internalCode, responseCode) else null
            }
            fixture.newSession().use { session ->
                val error = failure { session.authenticate(null) }
                assertTrue(error.authenticationRejected)
                assertEquals(internalCode.toString(), error.serviceCode)
                assertEquals(responseCode, error.mslResponseCode)
                assertEquals(listOf("USER_ID_TOKEN"), fixture.peer.schemes)
                assertEquals(listOf(""), fixture.provider.writes)
            }
            fixture.peer.rejection = { _, _, _ -> null }
            fixture.peer.resetObservations()
            fixture.newSession().use { session ->
                session.authenticate(credentials())
                fixture.manifest(session)
                assertEquals(listOf("EMAIL_PASSWORD", "SWITCH_PROFILE"), fixture.peer.schemes)
            }
        }
    }

    @Test fun `HTTP server error network failure and transient MSL response preserve cached native owner`() {
        for (kind in listOf("http", "network", "msl")) {
            val fixture = Fixture()
            fixture.establish()
            val previous = fixture.provider.record
            fixture.peer.resetObservations()
            fixture.provider.writes.clear()
            if (kind == "msl") fixture.peer.rejection = { _, _, _ -> Rejection(200002, 2) }
            else fixture.peer.beforeAuthenticated = { _, _ ->
                if (kind == "http") throw NetflixPlaybackException(PlaybackStage.AUTHENTICATION, "503", "Fixture temporary HTTP failure", retryable = true)
                else throw IOException("Synthetic network failure")
            }
            fixture.newSession().use { session ->
                assertFalse(failure { session.authenticate(null) }.authenticationRejected)
                assertEquals(previous, fixture.provider.record)
                assertTrue(fixture.provider.writes.isEmpty())
                assertEquals(listOf("USER_ID_TOKEN"), fixture.peer.schemes)
            }
        }
    }

    @Test fun `owner bootstrap requires a fully authenticated response and a valid bound user token`() {
        for (fault in listOf("header", "payload", "missing", "binding", "expired")) {
            val fixture = Fixture()
            fixture.peer.headerTransform = { _, _, header ->
                when (fault) {
                    "missing" -> header.remove("useridtoken")
                    "binding" -> header.put("useridtoken", fixture.peer.invalidUser(masterSerial = 99999, expiration = fixture.now + 100))
                    "expired" -> header.put("useridtoken", fixture.peer.invalidUser(masterSerial = 101, expiration = fixture.now))
                }
            }
            fixture.peer.responseTransform = { _, _, parts ->
                val index = if (fault == "header") 0 else if (fault == "payload") 1 else -1
                if (index >= 0) parts[index].put("signature", MslEncoding.encode(ByteArray(32)))
            }
            fixture.newSession().use { session ->
                failure { session.authenticate(credentials()) }
                assertEquals("", fixture.provider.record)
                assertTrue(fixture.provider.writes.isEmpty())
                assertEquals(listOf("EMAIL_PASSWORD"), fixture.peer.schemes)
            }
        }
    }

    @Test fun `profile confirmation gates persistence even with valid signed owner token in memory`() {
        for (fault in listOf("missing", "binding", "payload", "profile_change")) {
            val fixture = Fixture()
            fixture.newSession().use { session ->
                session.authenticate(credentials())
                fixture.peer.headerTransform = { _, stage, header ->
                    if (stage == PlaybackStage.MANIFEST) when (fault) {
                        "missing" -> header.remove("useridtoken")
                        "binding" -> header.put("useridtoken", fixture.peer.invalidUser(99999, fixture.now + 100))
                    }
                }
                fixture.peer.responseTransform = { _, stage, parts ->
                    if (stage == PlaybackStage.MANIFEST && fault == "payload") parts[1].put("signature", MslEncoding.encode(ByteArray(32)))
                }
                fixture.peer.onResponse = { _, stage ->
                    if (stage == PlaybackStage.MANIFEST && fault == "profile_change") fixture.provider.value = fixture.context.copy(profileGuid = "different-profile")
                }
                failure { fixture.manifest(session) }
                assertEquals("", fixture.provider.record)
                assertTrue(fixture.provider.writes.isEmpty())
            }
        }
    }

    @Test fun `credentials from another account never replace persisted owner before selected profile is accepted`() {
        val fixture = Fixture()
        fixture.establish()
        val previous = fixture.provider.record
        fixture.provider.writes.clear()
        fixture.peer.resetObservations()
        fixture.newSession().use { session ->
            session.authenticate(NetflixPlaybackCredentials("other@example.invalid", "other-password".toCharArray()))
            assertEquals(previous, fixture.provider.record)
            assertTrue(fixture.provider.writes.isEmpty())
            assertTrue(failure { fixture.manifest(session) }.authenticationRejected)
            assertTrue(fixture.provider.writes.all { it.isEmpty() })
            assertEquals(listOf("EMAIL_PASSWORD", "SWITCH_PROFILE"), fixture.peer.schemes)
        }
    }

    @Test fun `master renewal reauthenticates owner and old profile pair once without sending profile switch to license`() {
        val fixture = Fixture()
        fixture.newSession().use { session ->
            session.authenticate(credentials())
            fixture.manifest(session)
            val previous = fixture.provider.record
            fixture.provider.writes.clear()
            fixture.peer.resetObservations()
            fixture.now += 975
            fixture.license(session)
            assertEquals(listOf("USER_ID_TOKEN", "USER_ID_TOKEN", "HEADER_TOKEN"), fixture.peer.schemes)
            assertEquals(listOf("owner", "profile", "profile"), fixture.peer.requestRoles)
            assertEquals(listOf(PlaybackStage.AUTHENTICATION, PlaybackStage.AUTHENTICATION, PlaybackStage.LICENSE), fixture.peer.stages)
            assertNotEquals(previous, fixture.provider.record)
            assertEquals(1, fixture.provider.writes.size)
            val record = NativeMslTokenPair.readOwnerRecord(fixture.provider.record, fixture.context.esn, fixture.now)
            assertEquals("owner", fixture.peer.roleOf(record.userToken))
            assertEquals(2, fixture.peer.handshakes)
        }
    }

    @Test fun `failed profile reauthentication cannot commit pending renewed owner or send a license`() {
        val fixture = Fixture()
        fixture.newSession().use { session ->
            session.authenticate(credentials())
            fixture.manifest(session)
            fixture.provider.writes.clear()
            fixture.peer.resetObservations()
            fixture.now += 975
            fixture.peer.rejection = { scheme, role, _ ->
                if (scheme == "USER_ID_TOKEN" && role == "profile") Rejection(205032, 7) else null
            }
            assertTrue(failure { fixture.license(session) }.authenticationRejected)
            assertEquals(listOf("USER_ID_TOKEN", "USER_ID_TOKEN"), fixture.peer.schemes)
            assertTrue(fixture.peer.stages.none { it == PlaybackStage.LICENSE })
            assertEquals(listOf(""), fixture.provider.writes)
        }
    }

    @Test fun `closing credentials during a blocked handshake prevents the later password request`() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val credentials = credentials()
        fixture.peer.onHandshake = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        fixture.newSession().use { session ->
            try {
                val future = executor.submit<NetflixPlaybackException> { failure { session.authenticate(credentials) } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                credentials.close()
                release.countDown()
                assertTrue(future.get(5, TimeUnit.SECONDS).authenticationRejected)
                assertTrue(fixture.peer.schemes.isEmpty())
                assertTrue(fixture.provider.writes.isEmpty())
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test fun `unsolicited key response or foreign master renewal is rejected before persistence`() {
        for (fault in listOf("keys", "master")) {
            val fixture = Fixture()
            fixture.newSession().use { session ->
                session.authenticate(credentials())
                fixture.peer.headerTransform = { _, stage, header ->
                    if (stage == PlaybackStage.MANIFEST && fault == "keys") header.put("keyresponsedata", JSONObject().put("scheme", "WIDEVINE"))
                }
                fixture.peer.responseTransform = { _, stage, parts ->
                    if (stage == PlaybackStage.MANIFEST && fault == "master") parts[0].put("mastertoken", fixture.peer.invalidMaster(99999, fixture.now + 1000))
                }
                assertEquals(PlaybackStage.KEY_EXCHANGE, failure { fixture.manifest(session) }.stage)
                assertTrue(fixture.provider.writes.isEmpty())
            }
        }
    }

    private class Fixture {
        var now = 1700000000L
        val context = PlaybackContext("NetflixId=browser-cookie; SecureNetflixId=browser-secure-cookie", "auth",
            "NFANDROID1-PRV-P-FIXTUREPHONE-1234-" + "A".repeat(64), "selected-profile", "fixture-agent")
        val provider = Provider(context)
        val peer = Peer { now }
        fun newSession() = NetflixMslSession(BoundPlaybackSession(context, provider), peer, FixtureCrypto(),
            authenticationMode = MslAuthenticationMode.NATIVE_USER_TOKEN) { now }
        fun manifest(session: NetflixMslSession) = session.exchange(NetflixMslSession.MANIFEST_ENDPOINT,
            "licensedManifest", JSONObject(), PlaybackStage.MANIFEST)
        fun license(session: NetflixMslSession) = session.exchange(NetflixMslSession.LICENSE_ENDPOINT,
            "/license/fixture", JSONArray(), PlaybackStage.LICENSE)
        fun establish() { newSession().use { it.authenticate(credentials()); manifest(it) } }
    }

    private class Provider(var value: PlaybackContext) : NetflixSessionProvider {
        var record = ""
        val writes = mutableListOf<String>()
        override fun playbackContext() = value
        override fun loadNativeAuthentication() = record
        override fun saveNativeAuthentication(value: String) { record = value; writes += value }
    }

    private class Rejection(val internalCode: Int, val responseCode: Int)
    private class IssuedMaster(val identity: String, val serial: Long, val expiration: Long, val token: JSONObject)
    private class IssuedUser(val role: String, val account: String, val masterSerial: Long, val expiration: Long, val token: JSONObject)

    private class Peer(private val now: () -> Long) : MslTransport {
        private val crypto = FixtureCrypto()
        private var masterSerial = 100L
        private var userSerial = 1000L
        private val masters = mutableMapOf<String, IssuedMaster>()
        private val users = mutableMapOf<String, IssuedUser>()
        var handshakes = 0
        val schemes = mutableListOf<String>()
        val requestRoles = mutableListOf<String>()
        val stages = mutableListOf<PlaybackStage>()
        val headers = mutableListOf<JSONObject>()
        val wires = mutableListOf<String>()
        val contexts = mutableListOf<PlaybackContext>()
        var onHandshake: () -> Unit = {}
        var beforeAuthenticated: (String, PlaybackStage) -> Unit = { _, _ -> }
        var rejection: (String, String, PlaybackStage) -> Rejection? = { _, _, _ -> null }
        var headerTransform: (String, PlaybackStage, JSONObject) -> Unit = { _, _, _ -> }
        var responseTransform: (String, PlaybackStage, MutableList<JSONObject>) -> Unit = { _, _, _ -> }
        var onResponse: (String, PlaybackStage) -> Unit = { _, _ -> }

        fun resetObservations() { schemes.clear(); requestRoles.clear(); stages.clear(); headers.clear(); wires.clear(); contexts.clear() }
        fun roleOf(token: JSONObject): String = user(token).role
        fun invalidUser(masterSerial: Long, expiration: Long) = opaque(JSONObject().put("serialnumber", ++userSerial)
            .put("mtserialnumber", masterSerial).put("expiration", expiration))
        fun invalidMaster(serial: Long, expiration: Long) = opaque(JSONObject().put("serialnumber", serial)
            .put("sequencenumber", 1).put("expiration", expiration))

        override fun netflixPost(url: String, wire: String, context: PlaybackContext, stage: PlaybackStage, operationId: Long): String {
            wires += wire
            val messages = MslWire.parseObjects(wire)
            if (stage == PlaybackStage.KEY_EXCHANGE) {
                handshakes++
                val identity = messages[0].getJSONObject("entityauthdata").getJSONObject("authdata").getString("identity")
                assertEquals(context.esn, identity)
                val request = MslEncoding.json(MslEncoding.decode(messages[0].getString("headerdata")))
                assertEquals("WIDEVINE", request.getJSONArray("keyrequestdata").getJSONObject(0).getString("scheme"))
                val token = invalidMaster(++masterSerial, now() + 1000)
                masters[token.getString("tokendata")] = IssuedMaster(identity, masterSerial, now() + 1000, token)
                val header = JSONObject().put("messageid", MslWire.responseMessageId(request.getLong("messageid")))
                    .put("keyresponsedata", JSONObject().put("scheme", "WIDEVINE").put("mastertoken", token))
                onHandshake()
                return JSONObject().put("headerdata", MslEncoding.encode(header.toString().toByteArray())).toString()
            }
            val header = readSigned(messages[0], "headerdata")
            val payload = readSigned(messages[1], "payload")
            val application = MslEncoding.json(MslEncoding.decode(payload.getString("data")))
            assertEquals(header.getLong("messageid"), payload.getLong("messageid"))
            val outer = master(messages[0].getJSONObject("mastertoken"))
            if (outer.identity != context.esn || header.getString("sender") != outer.identity) return reject(205032, 7)
            val auth = header.optJSONObject("userauthdata")
            val scheme = auth?.getString("scheme") ?: "HEADER_TOKEN"
            val data = auth?.getJSONObject("authdata")
            val role = when (scheme) {
                "USER_ID_TOKEN", "SWITCH_PROFILE" -> roleOf(data!!.getJSONObject("useridtoken"))
                "HEADER_TOKEN" -> roleOf(header.getJSONObject("useridtoken"))
                else -> "owner"
            }
            schemes += scheme; requestRoles += role; stages += stage; headers += header; contexts += context
            beforeAuthenticated(scheme, stage)
            rejection(scheme, role, stage)?.let { return reject(it.internalCode, it.responseCode) }
            var returned: JSONObject? = null
            when (scheme) {
                "NETFLIXID" -> return reject(205032, 7) // Deliberate browser-cookie/native-entity mismatch.
                "EMAIL_PASSWORD" -> {
                    assertEquals("/logblob", application.getString("url"))
                    val account = when {
                        data!!.getString("email") == "owner@example.invalid" && data.getString("password") == "secret-password" -> "catalog-account"
                        data.getString("email") == "other@example.invalid" && data.getString("password") == "other-password" -> "other-account"
                        else -> return reject(205001, 7)
                    }
                    returned = issueUser("owner", account, outer)
                }
                "USER_ID_TOKEN" -> {
                    assertEquals("/logblob", application.getString("url"))
                    val oldMaster = master(data!!.getJSONObject("mastertoken"))
                    val oldUser = user(data.getJSONObject("useridtoken"))
                    if (oldMaster.identity != outer.identity || oldUser.masterSerial != oldMaster.serial) return reject(205032, 7)
                    if (oldMaster.expiration <= now() || oldUser.expiration <= now()) return reject(205001, 7)
                    returned = issueUser(oldUser.role, oldUser.account, outer)
                }
                "SWITCH_PROFILE" -> {
                    assertEquals(PlaybackStage.MANIFEST, stage)
                    val owner = user(data!!.getJSONObject("useridtoken"))
                    if (owner.role != "owner" || owner.masterSerial != outer.serial || owner.account != "catalog-account" ||
                        data.getString("profileguid") != "selected-profile") return reject(205032, 7)
                    returned = issueUser("profile", owner.account, outer)
                }
                "HEADER_TOKEN" -> {
                    val selected = user(header.getJSONObject("useridtoken"))
                    if (selected.role != "profile" || selected.masterSerial != outer.serial || selected.expiration <= now()) return reject(205032, 7)
                }
                else -> return reject(205000, 1)
            }
            val id = MslWire.responseMessageId(header.getLong("messageid"))
            val responseHeader = JSONObject().put("messageid", id)
            if (returned != null) responseHeader.put("useridtoken", returned)
            headerTransform(scheme, stage, responseHeader)
            val result: Any = when (stage) {
                PlaybackStage.AUTHENTICATION -> JSONObject.NULL
                PlaybackStage.LICENSE -> JSONArray().put(JSONObject().put("ok", true))
                else -> JSONObject().put("ok", true)
            }
            val responsePayload = JSONObject().put("messageid", id).put("sequencenumber", 1).put("endofmsg", true)
                .put("data", MslEncoding.encode(JSONObject().put("result", result).toString().toByteArray()))
            val parts = mutableListOf(signed(responseHeader, "headerdata"), signed(responsePayload, "payload"))
            responseTransform(scheme, stage, parts)
            onResponse(scheme, stage)
            return parts.joinToString("")
        }

        private fun issueUser(role: String, account: String, master: IssuedMaster): JSONObject {
            val token = invalidUser(master.serial, master.expiration)
            users[token.getString("tokendata")] = IssuedUser(role, account, master.serial, master.expiration, token)
            return token
        }
        private fun master(token: JSONObject): IssuedMaster = masters[token.getString("tokendata")]!!.also {
            assertEquals(it.token.getString("signature"), token.getString("signature"))
        }
        private fun user(token: JSONObject): IssuedUser = users[token.getString("tokendata")]!!.also {
            assertEquals(it.token.getString("signature"), token.getString("signature"))
        }
        private fun opaque(data: JSONObject): JSONObject {
            val bytes = data.toString().toByteArray()
            return JSONObject().put("tokendata", MslEncoding.encode(bytes)).put("signature", MslEncoding.encode(crypto.sign(bytes)))
        }
        private fun reject(internalCode: Int, responseCode: Int) = JSONObject().put("errordata", MslEncoding.encode(JSONObject()
            .put("internalcode", internalCode).put("errorcode", responseCode).put("errormsg", "Fixture rejection")
            .toString().toByteArray())).toString()
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

    private class FixtureCrypto : MslCrypto {
        override val keyExchangeScheme = "WIDEVINE"
        private val key = SecretKeySpec(ByteArray(16) { (it + 4).toByte() }, "AES")
        private val hmac = SecretKeySpec(ByteArray(32) { (it + 32).toByte() }, "HmacSHA256")
        override fun keyRequest() = JSONObject().put("scheme", keyExchangeScheme)
        override fun acceptKeyResponse(keyResponse: JSONObject) = Unit
        override fun close() = Unit
        override fun encrypt(plaintext: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(plaintext)
        override fun decrypt(envelope: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE).doFinal(envelope)
        private fun cipher(mode: Int) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, key, IvParameterSpec(ByteArray(16))) }
        override fun sign(message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(hmac) }.doFinal(message)
        override fun verify(message: ByteArray, signature: ByteArray): Boolean = MessageDigest.isEqual(sign(message), signature)
    }

    companion object {
        private fun credentials() = NetflixPlaybackCredentials("owner@example.invalid", "secret-password".toCharArray())
        private fun metadata(token: JSONObject) = MslEncoding.json(MslEncoding.decode(token.getString("tokendata")))
        private fun failure(block: () -> Unit): NetflixPlaybackException {
            try { block(); fail("Expected protocol failure") } catch (error: NetflixPlaybackException) { return error }
            throw AssertionError()
        }
    }
}
