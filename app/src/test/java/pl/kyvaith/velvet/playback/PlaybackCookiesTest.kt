package pl.kyvaith.velvet.playback

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackCookiesTest {
    @Test fun `identical authentication duplicates collapse without changing opaque values`() {
        val first = "v%3D3%26data%3DAb+C/=="
        val second = "quoted%22value%22%2B%2F=="
        val snapshot = PlaybackCookies.snapshot(
            "NetflixId=$first; SecureNetflixId=$second; NetflixId=$first; nfvdid=device-fixture; SecureNetflixId=$second")

        assertEquals(first, snapshot.netflixId)
        assertEquals(second, snapshot.secureNetflixId)
        assertEquals("NetflixId=$first; SecureNetflixId=$second; nfvdid=device-fixture", snapshot.header)
        assertEquals(2, snapshot.authCookieDuplicateCount)
        assertEquals(3, snapshot.cookieCount)
        assertTrue(snapshot.hasNfvdid)
        assertEquals(snapshot.header, PlaybackCookies.snapshot(snapshot.header).header)
    }

    @Test fun `both conflicting authentication names fail instead of choosing an arbitrary value`() {
        for (name in listOf("NetflixId", "SecureNetflixId")) {
            val error = sessionFailure {
                PlaybackCookies.snapshot("NetflixId=private-a; SecureNetflixId=private-b; $name=private-conflict")
            }
            assertTrue(error.message.orEmpty().contains("sprzeczne"))
            assertFalse(error.message.orEmpty().contains("private-a"))
            assertFalse(error.message.orEmpty().contains("private-b"))
            assertFalse(error.message.orEmpty().contains("private-conflict"))
        }
    }

    @Test fun `MSL authentication uses exactly the snapshot pair and rejects the same conflicts`() {
        val raw = "NetflixId=fixture%3D+==; NetflixId=fixture%3D+==; SecureNetflixId=fixture%2F=="
        val snapshot = PlaybackCookies.snapshot(raw)
        val context = PlaybackContext(raw, "auth", "esn", "profile", "ua")
        val header = JSONObject()
        MslAuthentication.NetflixCookies.apply(header, context)
        val auth = header.getJSONObject("userauthdata")
        assertEquals("NETFLIXID", auth.getString("scheme"))
        assertEquals(snapshot.netflixId, auth.getJSONObject("authdata").getString("netflixid"))
        assertEquals(snapshot.secureNetflixId, auth.getJSONObject("authdata").getString("securenetflixid"))
        sessionFailure {
            MslAuthentication.NetflixCookies.apply(JSONObject(), context.copy(cookieHeader = "$raw; NetflixId=other"))
        }
    }

    @Test fun `missing empty malformed and header injection values fail locally`() {
        for (header in listOf("", "NetflixId=only-one", "NetflixId=; SecureNetflixId=present",
            "NetflixId=present; SecureNetflixId=", "NetflixId=present; SecureNetflixId=space value",
            "NetflixId=present; SecureNetflixId=trailing ", "NetflixId=present; SecureNetflixId= leading",
            "NetflixId=present; SecureNetflixId=bad\r\nX-Injected:bad",
            "NetflixId=present; SecureNetflixId=ok; malformed")) {
            sessionFailure { PlaybackCookies.snapshot(header) }
        }
    }

    @Test fun `distinct non authentication cookies keep their ordering and optional device cookie is reported`() {
        val snapshot = PlaybackCookies.snapshot("other=first; NetflixId=a; other=second; SecureNetflixId=b; empty=")
        assertEquals("other=first; NetflixId=a; other=second; SecureNetflixId=b; empty=", snapshot.header)
        assertFalse(snapshot.hasNfvdid)
        assertEquals(5, snapshot.cookieCount)
        assertEquals(0, snapshot.authCookieDuplicateCount)
    }

    @Test fun `snapshot string does not disclose authentication or device cookie values`() {
        val snapshot = PlaybackCookies.snapshot("NetflixId=private-a; SecureNetflixId=private-b; nfvdid=private-device")
        assertEquals("PlaybackCookieSnapshot(redacted)", snapshot.toString())
    }

    private fun sessionFailure(action: () -> Unit): NetflixPlaybackException {
        try { action(); fail("Invalid cookies were accepted") }
        catch (error: NetflixPlaybackException) {
            assertEquals(PlaybackStage.SESSION, error.stage)
            assertEquals(false, error.retryable)
            return error
        }
        error("Unreachable")
    }
}
