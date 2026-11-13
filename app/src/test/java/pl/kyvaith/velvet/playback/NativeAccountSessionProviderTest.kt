package pl.kyvaith.velvet.playback

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NativeAccountSessionProviderTest {
    private val nativeId = "NFANDROID1-PRV-P-VELVETTEST-1234-" + "A".repeat(64)
    private val graphql = NativeAndroidProfile.GRAPHQL_ENDPOINT.toHttpUrl()
    private val config = NativeAndroidProfile.CONFIG_ENDPOINT.toHttpUrl()
    private val manifest = NetflixMslSession.MANIFEST_ENDPOINT.toHttpUrl()

    @Test fun `native session starts with only source nfvdid and separate identity`() {
        val source = Source(webContext())
        val provider = provider(source)
        val native = provider.playbackContext()
        assertEquals("nfvdid=device%2B+==", native.cookieHeader)
        assertEquals(native.cookieHeader, provider.cookieHeaderFor(graphql))
        assertEquals(nativeId, native.esn)
        assertEquals("NativeAndroidFixture/1", native.userAgent)
        assertEquals("", native.authUrl)
        assertEquals(source.value.profileGuid, native.profileGuid)
        assertEquals(source.value.clientVersion, native.clientVersion)
        assertEquals(source.value.browserVersion, native.browserVersion)
        assertEquals(source.value.osName, native.osName)
        assertEquals(source.value.osVersion, native.osVersion)
        assertEquals(source.value.locale, native.locale)
        assertEquals(0, source.writes)
        assertFalse(provider.toString().contains(nativeId))
        assertFalse(native.toString().contains("device%2B"))
    }

    @Test fun `fresh native pairs and later rotation never modify or borrow the browser session`() {
        val source = Source(webContext())
        val provider = provider(source)
        val browser = source.value
        provider.updateCookiesFrom(graphql, pair("native-first", "native-secure"))
        val first = PlaybackCookies.snapshot(provider.playbackContext().cookieHeader)
        assertEquals("native-first", first.netflixId)
        assertEquals("native-secure", first.secureNetflixId)
        assertEquals(browser, source.value)

        source.value = browser.copy(cookieHeader = "NetflixId=web-rotated; SecureNetflixId=web-secure; nfvdid=web-device-rotated")
        assertEquals("native-first", PlaybackCookies.snapshot(provider.playbackContext().cookieHeader).netflixId)
        provider.updateCookiesFrom(graphql, listOf(
            cookie("NetflixId", "native-rotated", domain = graphql.host, hostOnly = true, secure = false),
            cookie("SecureNetflixId", "native-rotated-secure", domain = graphql.host, hostOnly = true),
        ))
        val rotated = PlaybackCookies.snapshot(provider.playbackContext().cookieHeader)
        assertEquals("native-rotated", rotated.netflixId)
        assertEquals("native-rotated-secure", rotated.secureNetflixId)
        assertTrue(rotated.header.contains("nfvdid=device%2B+=="))
        assertEquals("nfvdid=device%2B+==", provider.cookieHeaderFor(manifest))
        val nativeHttp = PlaybackCookies.snapshot(provider.cookieHeaderFor(graphql))
        assertEquals("native-rotated", nativeHttp.netflixId)
        assertEquals(0, nativeHttp.authCookieDuplicateCount)
        assertFalse(provider.cookieHeaderFor(graphql).contains("native-first"))
        assertEquals(0, source.writes)
        assertEquals(0, source.authReads)
        assertEquals(0, source.authWrites)
    }

    @Test fun `partial conflicting and expired auth responses leave both old stores unchanged`() {
        val source = Source(webContext())
        val provider = provider(source)
        provider.updateCookiesFrom(graphql, pair("native-original", "native-original-secure"))
        val before = provider.playbackContext().cookieHeader
        val beforeHttp = provider.cookieHeaderFor(config)
        val invalidUpdates = listOf(
            listOf(cookie("NetflixId", "partial-private")),
            listOf(cookie("SecureNetflixId", "partial-private")),
            pair("first-private", "secure-private") + cookie("NetflixId", "second-private"),
            pair("", "secure-private"),
            listOf(cookie("NetflixId", "expired-private", expiresAt = 1), cookie("SecureNetflixId", "secure-private")),
        )
        for (updates in invalidUpdates) {
            sessionFailure { provider.updateCookiesFrom(graphql, updates + cookie("newAux", "not-committed")) }
            assertEquals(before, provider.playbackContext().cookieHeader)
            assertEquals(beforeHttp, provider.cookieHeaderFor(config))
        }
    }

    @Test fun `host only and path scoped pair remains usable for MSL without widening HTTP cookies`() {
        val provider = provider(Source(webContext()))
        val scoped = listOf(
            cookie("NetflixId", "native-private", domain = graphql.host, path = "/nq/androidui", hostOnly = true, secure = false),
            cookie("SecureNetflixId", "native-secure-private", domain = graphql.host, path = "/nq/androidui", hostOnly = true),
        )
        // The response path may differ from the cookie path selected by its own server.
        provider.updateCookiesFrom(graphql, scoped)
        val credentials = PlaybackCookies.snapshot(provider.playbackContext().cookieHeader)
        assertEquals("native-private", credentials.netflixId)
        assertEquals("native-secure-private", credentials.secureNetflixId)
        assertTrue(provider.cookieHeaderFor(config).contains("NetflixId=native-private"))
        assertTrue(provider.cookieHeaderFor(config).contains("SecureNetflixId=native-secure-private"))
        assertEquals("nfvdid=device%2B+==", provider.cookieHeaderFor(graphql))
        assertEquals("nfvdid=device%2B+==", provider.cookieHeaderFor(manifest))
        assertEquals("nfvdid=device%2B+==", provider.cookieHeaderFor(NativeAndroidProfile.HANDSHAKE_ENDPOINT.toHttpUrl()))
    }

    @Test fun `domain cookies without Secure remain valid on every permitted HTTPS target`() {
        val provider = provider(Source(webContext()))
        provider.updateCookiesFrom(graphql, listOf(
            cookie("NetflixId", "native-private", secure = false),
            cookie("SecureNetflixId", "native-secure-private"),
        ))
        for (url in listOf(graphql, config, manifest, NetflixMslSession.LICENSE_ENDPOINT.toHttpUrl())) {
            val cookies = PlaybackCookies.snapshot(provider.cookieHeaderFor(url))
            assertEquals("native-private", cookies.netflixId)
            assertEquals("native-secure-private", cookies.secureNetflixId)
        }
        sessionFailure { provider.cookieHeaderFor(manifest.newBuilder().scheme("http").build()) }
    }

    @Test fun `jar keeps domain and path variants and orders narrower paths before wider ones`() {
        val provider = provider(Source(webContext()))
        provider.updateCookiesFrom(graphql, listOf(
            cookie("theme", "root", secure = false),
            cookie("theme", "cloud", domain = "prod.cloud.netflix.com"),
            cookie("theme", "narrow", domain = graphql.host, path = "/nq/androidui", hostOnly = true),
        ))
        val configHeader = provider.cookieHeaderFor(config)
        assertTrue(configHeader.startsWith("theme=narrow; "))
        assertTrue(configHeader.indexOf("theme=root") < configHeader.indexOf("theme=cloud"))
        assertFalse(provider.cookieHeaderFor(graphql).contains("theme=narrow"))
        assertTrue(provider.cookieHeaderFor(manifest).contains("theme=root"))
        assertFalse(provider.cookieHeaderFor(manifest).contains("theme=cloud"))
        assertFalse(provider.playbackContext().cookieHeader.contains("theme="))
        provider.updateCookiesFrom(graphql, listOf(cookie("theme", "replaced", secure = false)))
        assertFalse(provider.cookieHeaderFor(config).contains("theme=root"))
        assertTrue(provider.cookieHeaderFor(config).contains("theme=narrow"))
        assertTrue(provider.cookieHeaderFor(config).contains("theme=cloud"))
        assertTrue(provider.cookieHeaderFor(config).indexOf("theme=replaced") < provider.cookieHeaderFor(config).indexOf("theme=cloud"))
    }

    @Test fun `expiry and auxiliary deletion are honored independently from Cookie matches`() {
        var now = 1_000_000L
        val provider = provider(Source(webContext())) { now }
        provider.updateCookiesFrom(graphql, listOf(
            cookie("NetflixId", "native-private", expiresAt = now + 1000),
            cookie("SecureNetflixId", "native-secure-private", expiresAt = now + 1000),
            cookie("temporary", "short", expiresAt = now + 100),
            cookie("deleted", "before"),
        ))
        provider.updateCookiesFrom(graphql, listOf(cookie("deleted", "", expiresAt = 1)))
        assertFalse(provider.cookieHeaderFor(graphql).contains("deleted="))
        now += 101
        assertFalse(provider.cookieHeaderFor(graphql).contains("temporary="))
        assertTrue(provider.cookieHeaderFor(graphql).contains("NetflixId="))
        now += 1000
        assertEquals("nfvdid=device%2B+==", provider.cookieHeaderFor(graphql))
        assertEquals("nfvdid=device%2B+==", provider.playbackContext().cookieHeader)
        sessionFailure { PlaybackCookies.snapshot(provider.playbackContext().cookieHeader) }
    }

    @Test fun `foreign origins and auth scopes fail atomically while invalid auxiliary cookies are ignored`() {
        val provider = provider(Source(webContext()))
        provider.updateCookiesFrom(graphql, pair("native-private", "native-secure-private"))
        val before = provider.playbackContext().cookieHeader
        val beforeHttp = provider.cookieHeaderFor(graphql)
        val foreign = "https://www.netflix.com.evil.example/msl/playapi/cadmium/licensedmanifest/1".toHttpUrl()
        sessionFailure { provider.updateCookiesFrom(foreign, pair("foreign-private", "foreign-secure-private")) }
        sessionFailure { provider.cookieHeaderFor(foreign) }
        val invalidAuth = listOf(
            cookie("NetflixId", "other-private", domain = "other.example"),
            cookie("NetflixId", "other-private", domain = "www.netflix.com", hostOnly = true),
            cookie("NetflixId", "value;injection"),
        )
        for (invalid in invalidAuth) {
            sessionFailure { provider.updateCookiesFrom(graphql, listOf(invalid, cookie("SecureNetflixId", "new-private"))) }
            assertEquals(before, provider.playbackContext().cookieHeader)
            assertEquals(beforeHttp, provider.cookieHeaderFor(graphql))
        }
        provider.updateCookiesFrom(graphql, listOf(
            cookie("ignored", "foreign", domain = "other.example"),
            cookie("ignored", "value;injection"),
            cookie("bad name", "ignored"),
            cookie("accepted", "own", secure = false, domain = graphql.host, hostOnly = true),
        ))
        assertEquals(before, provider.playbackContext().cookieHeader)
        assertTrue(provider.cookieHeaderFor(graphql).contains("accepted=own"))
        assertFalse(provider.cookieHeaderFor(graphql).contains("ignored"))
        assertFalse(provider.cookieHeaderFor(manifest).contains("accepted="))
    }

    @Test fun `native jar accepts only fixed endpoints and rejects originless mutations`() {
        val provider = provider(Source(webContext()))
        val invalidUrls = listOf(
            graphql.newBuilder().addQueryParameter("extra", "value").build(),
            graphql.newBuilder().port(444).build(),
            graphql.newBuilder().username("private").build(),
            graphql.newBuilder().fragment("private").build(),
            "https://www.netflix.com/browse".toHttpUrl(),
            "https://android.prod.cloud.netflix.com/other".toHttpUrl(),
            "https://cdn.netflix.com/graphql".toHttpUrl(),
        )
        for (url in invalidUrls) {
            sessionFailure { provider.cookieHeaderFor(url) }
            sessionFailure { provider.updateCookiesFrom(url, emptyList()) }
        }
        val withQuery = manifest.newBuilder().addQueryParameter("reqName", "fixture").build()
        assertEquals("nfvdid=device%2B+==", provider.cookieHeaderFor(withQuery))
        sessionFailure { provider.updateCookies(pair("private", "secure-private")) }
        sessionFailure { provider.updateCookies(listOf(cookie("auxiliary", "private"))) }
        assertEquals("nfvdid=device%2B+==", provider.playbackContext().cookieHeader)
    }

    @Test fun `identical duplicate cookies collapse while opaque pair values remain unchanged`() {
        val provider = provider(Source(webContext()))
        val cookies = pair("native%2B+==", "secure%2F+==")
        provider.updateCookiesFrom(graphql, cookies + cookies)
        val snapshot = PlaybackCookies.snapshot(provider.playbackContext().cookieHeader)
        assertEquals(0, snapshot.authCookieDuplicateCount)
        assertEquals("native%2B+==", snapshot.netflixId)
        assertEquals("secure%2F+==", snapshot.secureNetflixId)
        val http = PlaybackCookies.snapshot(provider.cookieHeaderFor(graphql))
        assertEquals(0, http.authCookieDuplicateCount)
    }

    @Test fun `account generation profile and web identity changes invalidate every native access`() {
        for (change in ownerChanges()) {
            val source = Source(webContext())
            val provider = provider(source)
            provider.updateCookiesFrom(graphql, pair("native-private", "native-private-secure"))
            change(source)
            sessionFailure { provider.playbackContext() }
            sessionFailure { provider.cookieHeaderFor(graphql) }
            sessionFailure { provider.updateCookiesFrom(graphql, pair("late-private", "late-private-secure")) }
            sessionFailure { provider.loadNativeAuthentication() }
            sessionFailure { provider.saveNativeAuthentication("late-private-token") }
            assertEquals(0, source.writes)
            assertEquals(0, source.authReads)
            assertEquals(0, source.authWrites)
        }
    }

    @Test fun `owner change during local cookie update discards the candidate session`() {
        for (change in ownerChanges()) {
            val source = Source(webContext())
            val provider = provider(source)
            val previousReads = source.reads
            source.onRead = { if (source.reads == previousReads + 2) change(source) }
            sessionFailure { provider.updateCookiesFrom(graphql, pair("late-private", "late-private-secure")) }
            sessionFailure { provider.playbackContext() }
            sessionFailure { provider.cookieHeaderFor(graphql) }
            assertEquals(0, source.writes)
        }
    }

    @Test fun `owner change while reading an HTTP header prevents returning old cookies`() {
        for (change in ownerChanges()) {
            val source = Source(webContext())
            val provider = provider(source)
            provider.updateCookiesFrom(graphql, pair("native-private", "native-private-secure"))
            val previousReads = source.reads
            source.onRead = { if (source.reads == previousReads + 2) change(source) }
            sessionFailure { provider.cookieHeaderFor(graphql) }
            sessionFailure { provider.playbackContext() }
            assertEquals(0, source.writes)
        }
    }

    @Test fun `owner record remains in this playback memory and close makes the provider unusable`() {
        val source = Source(webContext())
        val provider = provider(source)
        provider.saveNativeAuthentication("private-owner-token")
        assertEquals("private-owner-token", provider.loadNativeAuthentication())
        provider.saveNativeAuthentication("")
        assertEquals("", provider.loadNativeAuthentication())
        provider.saveNativeAuthentication("replacement-private-owner-token")
        assertEquals("replacement-private-owner-token", provider.loadNativeAuthentication())
        provider.updateCookiesFrom(graphql, pair("native-private", "native-private-secure"))
        provider.close()
        provider.close()
        sessionFailure { provider.playbackContext() }
        sessionFailure { provider.cookieHeaderFor(graphql) }
        sessionFailure { provider.updateCookiesFrom(graphql, pair("late-private", "late-private-secure")) }
        sessionFailure { provider.loadNativeAuthentication() }
        sessionFailure { provider.saveNativeAuthentication("late-private-token") }
        assertEquals(0, source.authReads)
        assertEquals(0, source.authWrites)
    }

    @Test fun `conflicting source device cookies fail rather than selecting an arbitrary browser device`() {
        val source = Source(webContext().copy(cookieHeader = webContext().cookieHeader + "; nfvdid=other-private-device"))
        sessionFailure { provider(source) }
    }

    private fun provider(source: Source, clock: () -> Long = System::currentTimeMillis) = NativeAccountSessionProvider(
        BoundPlaybackSession(source.value, source), nativeId, "NativeAndroidFixture/1", nowMillis = clock,
    )

    private fun pair(first: String, second: String) = listOf(cookie("NetflixId", first), cookie("SecureNetflixId", second))

    private fun cookie(name: String, value: String, domain: String = "netflix.com", path: String = "/",
        hostOnly: Boolean = false, secure: Boolean = true, expiresAt: Long? = null): Cookie = Cookie.Builder()
        .name(name).value(value).apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            path(path)
            if (secure) secure()
            httpOnly()
            expiresAt?.let { timestamp -> this.expiresAt(timestamp) }
        }.build()

    private fun webContext() = PlaybackContext(
        "NetflixId=web-private; SecureNetflixId=web-private-secure; nfvdid=device%2B+==; OptanonConsent=web-consent",
        "web-private-auth", "NFCDCH-browser-private", "profile-private", "BrowserFixture/1",
        clientVersion = "cadmium-fixture", browserVersion = "browser-fixture", osName = "browser-os", osVersion = "browser-os-version",
    )

    private fun ownerChanges(): List<(Source) -> Unit> = listOf(
        { it.generation++ },
        { it.value = it.value.copy(profileGuid = "changed-profile") },
        { it.value = it.value.copy(esn = "changed-web-esn") },
    )

    private fun sessionFailure(block: () -> Unit) {
        val error = assertThrows(NetflixPlaybackException::class.java) { block() }
        assertEquals(PlaybackStage.SESSION, error.stage)
        assertEquals(false, error.retryable)
        assertFalse(error.message.orEmpty().contains("private"))
    }

    private class Source(var value: PlaybackContext) : NetflixSessionProvider {
        var generation = 0
        var reads = 0
        var writes = 0
        var authReads = 0
        var authWrites = 0
        var onRead: () -> Unit = {}
        override fun playbackContext(): PlaybackContext {
            reads++
            onRead()
            if (generation != 0) throw NetflixPlaybackException(PlaybackStage.SESSION, message = "Synthetic owner changed", retryable = false)
            return value
        }
        override fun updateCookies(cookies: List<Cookie>) { writes++ }
        override fun loadNativeAuthentication(): String { authReads++; return "stored-private-owner" }
        override fun saveNativeAuthentication(value: String) { authWrites++ }
    }
}
