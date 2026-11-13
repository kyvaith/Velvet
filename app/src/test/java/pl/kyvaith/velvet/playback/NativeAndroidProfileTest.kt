package pl.kyvaith.velvet.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class NativeAndroidProfileTest {
    private val device = NativeAndroidProfile.Device("Google", "Pixel 9 Pro XL", "17", 37,
        "sample board", "sample_device", "TEST/123", "sample_hardware")

    @Test fun `native config preserves repeated paths and real device values after form decoding`() {
        val encoded = String(NativeAndroidProfile.configPayload(device, "12345", "pl-PL"), Charsets.UTF_8)
        assertTrue(encoded.endsWith("&"))
        val pairs = encoded.removeSuffix("&").split('&').map {
            val separator = it.indexOf('=')
            URLDecoder.decode(it.substring(0, separator), "UTF-8") to
                URLDecoder.decode(it.substring(separator + 1), "UTF-8")
        }
        val paths = pairs.filter { it.first == "path" }.map { JSONArray(it.second) }
        assertEquals(7, paths.size)
        assertTrue(paths.any { it.optString(0) == "accountConfig" })
        assertTrue(paths.any { it.optJSONArray(0)?.optString(0) == "streamingConfig2" && it.optString(1) == "8505" })
        val fields = pairs.filterNot { it.first == "path" }.toMap()
        assertEquals("Pixel 9 Pro XL", fields["model"])
        assertEquals("sample board", fields["osBoard"])
        assertEquals("TEST/123", fields["osDisplay"])
        assertEquals("12345", fields["sid"])
        assertEquals("37", fields["api"])
        assertEquals("androidNative", fields["netflixClientPlatform"])
        assertFalse(fields.containsKey("password"))
        assertFalse(fields.containsKey("netflixId"))
        assertFalse(fields.containsKey("secureNetflixId"))
    }

    @Test fun `device headers cannot carry account fields or line breaks from the device description`() {
        val ua = NativeAndroidProfile.userAgent(device.copy(model = "Pixel\r\nCookie: invalid"), "pl-PL")
        assertFalse(ua.contains('\r'))
        assertFalse(ua.contains('\n'))
        assertTrue(ua.contains("Android 17"))
        val headers = NativeAndroidProfile.headers(device)
        assertEquals("37", headers["X-Netflix.androidApi"])
        assertEquals("PHONE", headers["X-Netflix.deviceFormFactor"])
        assertFalse(headers.keys.any { it.equals("Cookie", true) || it.equals("X-Netflix.nftoken", true) })
        assertEquals("", device.boardPlatform)
        assertFalse(headers.values.any { it == "sample_hardware" || it == "sample board" })
    }

    @Test fun `native playback cookie access accepts only the fixed HTTPS route`() {
        val endpoint = NativeAndroidProfile.PLAYBACK_ENDPOINT
        assertEquals("native_nftoken_v3", NativeAndroidProfile.ID)
        assertTrue(NativeCookieOrigins.accepts(endpoint.toHttpUrl()))
        for (url in listOf(
            endpoint.replace("https://", "http://"),
            endpoint.replace("android.prod.cloud.netflix.com", "www.netflix.com"),
            endpoint.replace("android.prod.cloud.netflix.com", "android.prod.cloud.netflix.com.untrusted.example"),
            endpoint.replace("android.prod.cloud.netflix.com", "android.prod.cloud.netflix.com:8443"),
            endpoint.replace("https://", "https://private@"),
            "$endpoint?route=license", "$endpoint#fragment", "$endpoint/extra",
            "https://android.prod.cloud.netflix.com/nq/android/playback/~1.0.0/router",
        )) assertFalse(NativeCookieOrigins.accepts(url.toHttpUrl()))
    }
}
