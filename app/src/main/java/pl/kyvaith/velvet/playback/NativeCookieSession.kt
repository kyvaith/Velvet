// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** HTTP cookie routing is independent from the NETFLIXID pair serialized inside MSL. */
internal interface NativeCookieSession {
    fun cookieHeaderFor(url: HttpUrl): String
    fun updateCookiesFrom(url: HttpUrl, cookies: List<Cookie>)
}

/** Only fixed first-party account/playback requests may access the native account cookie jar. */
internal object NativeCookieOrigins {
    private val nativeEndpoints = setOf(
        NativeAndroidProfile.GRAPHQL_ENDPOINT,
        NativeAndroidProfile.CONFIG_ENDPOINT,
        NativeAndroidProfile.HANDSHAKE_ENDPOINT,
        NativeAndroidProfile.PLAYBACK_ENDPOINT,
    ).map { it.toHttpUrl() }.toSet()
    private val playbackPaths = setOf(
        NetflixMslSession.MANIFEST_ENDPOINT,
        NetflixMslSession.LICENSE_ENDPOINT,
        NetflixMslSession.LOGBLOB_ENDPOINT,
    ).map { it.toHttpUrl().encodedPath }.toSet()

    fun accepts(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null) return false
        return url in nativeEndpoints || (url.host == "www.netflix.com" && url.encodedPath in playbackPaths)
    }
}
