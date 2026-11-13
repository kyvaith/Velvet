// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionDataTest {
    @Test fun `sessions saved before native authentication was introduced still load`() {
        val previous = JSONObject()
            .put("cookieHeader", "NetflixId=fixture; SecureNetflixId=fixture-secure")
            .put("userAgent", "SyntheticBrowser/1")
            .put("profileGuid", "fixture-profile")
            .put("esn", "fixture-web-esn")

        val loaded = SessionData.fromJson(previous)

        assertEquals("", loaded.nativePlaybackAuth)
        assertEquals("fixture-profile", loaded.profileGuid)
        assertEquals("fixture-web-esn", loaded.esn)
        assertEquals(previous.getString("cookieHeader"), loaded.cookieHeader)
    }

    @Test fun `native owner tokens round trip without entering the playback context or diagnostic text`() {
        val nativeAuth = JSONObject()
            .put("identity", "synthetic-native-identity")
            .put("mastertoken", JSONObject().put("tokendata", "synthetic-master-token"))
            .put("useridtoken", JSONObject().put("tokendata", "synthetic-user-token"))
            .toString()
        val original = SessionData(
            cookieHeader = "NetflixId=fixture; SecureNetflixId=fixture-secure",
            userAgent = "SyntheticBrowser/1",
            esn = "fixture-web-esn",
            profileGuid = "fixture-profile",
            nativePlaybackAuth = nativeAuth,
        )

        val restored = SessionData.fromJson(original.toJson())

        assertEquals(original, restored)
        assertEquals(nativeAuth, restored.nativePlaybackAuth)
        assertEquals(original.copy(nativePlaybackAuth = "").playbackContext(), restored.playbackContext())
        assertEquals("SessionData(redacted)", restored.toString())
        assertFalse(restored.toString().contains("synthetic-master-token"))
        assertFalse(restored.toString().contains("synthetic-user-token"))
    }

    @Test fun `session serialization does not retain login credentials from extra input fields`() {
        val input = JSONObject()
            .put("cookieHeader", "NetflixId=fixture; SecureNetflixId=fixture-secure")
            .put("userAgent", "SyntheticBrowser/1")
            .put("email", "synthetic-user@example.test")
            .put("password", "synthetic-password-must-not-persist")
            .put("credentials", JSONObject().put("password", "nested-password-must-not-persist"))

        val serialized = SessionData.fromJson(input).toJson()

        assertFalse(serialized.has("email"))
        assertFalse(serialized.has("password"))
        assertFalse(serialized.has("credentials"))
        assertFalse(serialized.toString().contains("must-not-persist"))
        assertFalse(serialized.toString().contains("synthetic-user@example.test"))
        assertEquals("", serialized.getString("nativePlaybackAuth"))
    }
}
