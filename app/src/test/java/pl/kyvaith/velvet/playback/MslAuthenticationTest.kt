package pl.kyvaith.velvet.playback

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MslAuthenticationTest {
    private val now = 1700000000L
    private val identity = "NFANDROID1-PRV-P-FIXTUREPHONE-1234-" + "A".repeat(64)

    @Test fun `credentials own a private copy are redacted and become unusable after close`() {
        val input = "test-password".toCharArray()
        val credentials = NetflixPlaybackCredentials("test@example.invalid", input)
        input.fill('x')
        assertEquals("test-password", credentials.authenticationData().getString("password"))
        assertFalse(credentials.toString().contains("test@example.invalid"))
        assertFalse(credentials.toString().contains("test-password"))
        credentials.close()
        credentials.close()
        assertTrue(failure { credentials.authenticationData() }.authenticationRejected)
    }

    @Test fun `owner record rejects foreign identities roles serial bindings expiry malformed signatures and depth`() {
        val valid = pair().ownerRecord()
        assertEquals(identity, NativeMslTokenPair.readOwnerRecord(valid, identity, now).identity)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("identity", identity.replace("A", "B")) },
            { it.put("role", "profile") },
            { it.put("version", 2) },
            { it.getJSONObject("mastertoken").put("signature", "!") },
            { it.put("useridtoken", token(8, now + 1000)) },
            { it.put("useridtoken", token(7, now)) },
            { it.put("mastertoken", master(now)) },
        )
        for (mutate in mutations) {
            val record = JSONObject(valid).also(mutate).toString()
            val error = failure { NativeMslTokenPair.readOwnerRecord(record, identity, now) }
            assertTrue(error.authenticationRejected)
            assertNull(error.cause)
        }
        for (invalid in listOf("", "[]", valid + "{}", "x".repeat(140_000), "{\"a\":".repeat(16) + "0" + "}".repeat(16))) {
            assertTrue(failure { NativeMslTokenPair.readOwnerRecord(invalid, identity, now) }.authenticationRejected)
        }
        assertFalse(pair().toString().contains(identity))
    }

    @Test fun `ordinary token formal reauthentication and profile switch have distinct exact nesting`() {
        val context = PlaybackContext("", "", identity, "selected-guid", "ua")
        val token = token(7, now + 1000)
        val ordinary = JSONObject().also { MslAuthentication.UserToken(token).apply(it, context) }
        assertTrue(ordinary.has("useridtoken"))
        assertFalse(ordinary.has("userauthdata"))
        val switched = JSONObject().also { MslAuthentication.SwitchProfile(token).apply(it, context) }
        assertEquals("SWITCH_PROFILE", switched.getJSONObject("userauthdata").getString("scheme"))
        assertEquals("selected-guid", switched.getJSONObject("userauthdata").getJSONObject("authdata").getString("profileguid"))
        assertFalse(switched.has("useridtoken"))
        val reauth = JSONObject().also { MslAuthentication.TokenReauthentication(pair(), true).apply(it, context) }
        val auth = reauth.getJSONObject("userauthdata")
        assertEquals("USER_ID_TOKEN", auth.getString("scheme"))
        assertTrue(auth.getJSONObject("authdata").has("mastertoken"))
        assertTrue(auth.getJSONObject("authdata").has("useridtoken"))
        assertFalse(reauth.has("useridtoken"))
        assertTrue(failure { MslAuthentication.TokenReauthentication(pair(), true).apply(JSONObject(), context.copy(esn = "web-identity")) }.authenticationRejected)
    }

    private fun pair() = NativeMslTokenPair(identity, master(now + 1000), token(7, now + 1000))
    private fun master(expiration: Long) = opaque(JSONObject().put("serialnumber", 7).put("sequencenumber", 1).put("expiration", expiration))
    private fun token(masterSerial: Int, expiration: Long) = opaque(JSONObject().put("serialnumber", 9)
        .put("mtserialnumber", masterSerial).put("expiration", expiration))
    private fun opaque(data: JSONObject) = JSONObject().put("tokendata", MslEncoding.encode(data.toString().toByteArray()))
        .put("signature", MslEncoding.encode(byteArrayOf(11, 22, 33)))
    private fun failure(block: () -> Unit): NetflixPlaybackException {
        try { block(); fail("Expected authentication failure") } catch (error: NetflixPlaybackException) { return error }
        throw AssertionError()
    }
}
