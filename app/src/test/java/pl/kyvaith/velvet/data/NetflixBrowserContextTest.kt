package pl.kyvaith.velvet.data

import org.junit.Assert.*
import org.junit.Test

class NetflixBrowserContextTest {
    private val webView = "Mozilla/5.0 (Linux; Android 17; Pixel 9 Pro XL Build/TEST; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/158.0.8127.25 Mobile Safari/537.36"
    private val desktop = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.8127.25 Safari/537.36"

    @Test fun desktopModeRetainsInstalledChromiumAndRemovesMobileWebViewRoutingTokens() {
        val result = NetflixBrowserContext.desktopUserAgent(webView)
        assertEquals(desktop, result)
        assertEquals("desktop_site", NetflixBrowserContext.contextTag(result))
        assertFalse(result.contains("Android"))
        assertFalse(result.contains("Mobile"))
        assertFalse(result.contains("Version/4.0"))
        assertFalse(result.contains("wv"))
    }

    @Test fun desktopModeIsIdempotentAndDoesNotInventAnEngineVersion() {
        assertEquals(desktop, NetflixBrowserContext.desktopUserAgent(desktop))
        assertEquals("unknown-browser", NetflixBrowserContext.desktopUserAgent("unknown-browser"))
        assertEquals("Mozilla/5.0 (Android) Mobile", NetflixBrowserContext.desktopUserAgent("Mozilla/5.0 (Android) Mobile"))
        assertEquals("mobile", NetflixBrowserContext.contextTag(webView))
    }

    @Test fun migratingBrowserContextPreservesCredentialsAndProfileButInvalidatesDerivedPlayerIdentity() {
        val previous = SessionData(cookieHeader="NetflixId=test; SecureNetflixId=test", userAgent=webView,
            authUrl="auth-test", esn="old-page-esn", profileGuid="profile-test", buildIdentifier="build-old",
            clientVersion="client-old", uiVersion="ui-old", browserVersion="158", osName="Android", osVersion="17")
        val migrated = NetflixBrowserContext.migratedSession(previous)!!
        assertEquals(previous.cookieHeader, migrated.cookieHeader)
        assertEquals(previous.authUrl, migrated.authUrl)
        assertEquals(previous.profileGuid, migrated.profileGuid)
        assertEquals(desktop, migrated.userAgent)
        assertTrue(migrated.esn.isEmpty())
        assertTrue(migrated.clientVersion.isEmpty())
        assertTrue(migrated.uiVersion.isEmpty())
        assertTrue(migrated.browserVersion.isEmpty())
        assertTrue(migrated.osName.isEmpty())
        assertNull(NetflixBrowserContext.migratedSession(migrated))
    }
    @Test fun lowEntropyHintsMatchDesktopModeAndRealChromiumMajor() {
        val hints = NetflixBrowserContext.lowEntropyClientHints(desktop)
        assertEquals("\"Chromium\";v=\"158\"", hints["Sec-CH-UA"])
        assertEquals("?0", hints["Sec-CH-UA-Mobile"])
        assertEquals("\"Linux\"", hints["Sec-CH-UA-Platform"])
        assertEquals(3, hints.size)
        assertEquals("\"Chromium\";v=\"159\"", NetflixBrowserContext.lowEntropyClientHints(
            desktop.replace("158.0.8127.25", "159.1.2.3"))["Sec-CH-UA"])
        assertTrue(NetflixBrowserContext.lowEntropyClientHints(webView).isEmpty())
        assertTrue(NetflixBrowserContext.lowEntropyClientHints("unknown-browser").isEmpty())
        assertTrue(NetflixBrowserContext.lowEntropyClientHints("Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/158.0").isEmpty())
    }
}
