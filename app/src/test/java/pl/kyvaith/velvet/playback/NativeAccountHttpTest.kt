package pl.kyvaith.velvet.playback

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pl.kyvaith.velvet.diagnostics.AppLog
import pl.kyvaith.velvet.diagnostics.SessionLog
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class NativeAccountHttpTest {
    @Test fun `issuer sends only captured web credentials and the exact mobile upgrade operation`() {
        var inheritedCalls = 0
        val jar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> { inheritedCalls++; return emptyList() }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { inheritedCalls++ }
        }
        TlsFixture {
            cookieJar(jar)
            addInterceptor { chain -> inheritedCalls++; chain.proceed(chain.request()) }
            addNetworkInterceptor { chain -> inheritedCalls++; chain.proceed(chain.request()) }
        }.use { tls ->
            tls.enqueue(pairResponse("ignored-issuer-private", "ignored-issuer-secure")
                .setBody("""{"data":{"createAutoLoginToken":"synthetic-private-token%2B+=="}}"""))
            var nativeCookieCalls = 0
            val nativeCookies = object : NativeCookieSession {
                override fun cookieHeaderFor(url: HttpUrl): String { nativeCookieCalls++; return "" }
                override fun updateCookiesFrom(url: HttpUrl, cookies: List<Cookie>) { nativeCookieCalls++ }
            }
            val http = NativeAccountHttp(tls.client, NATIVE_UA, nativeCookies,
                mapOf("X-Netflix.appVer" to "native-fixture"))

            http.createLoginToken(web()).use { assertEquals("synthetic-private-token%2B+==", it.value()) }

            val request = tls.nextRequest()
            assertEquals("POST", request.method)
            assertEquals("android13.prod.ftl.netflix.com", request.getHeader("Host"))
            assertEquals("/graphql", request.path)
            assertEquals(web().userAgent, request.getHeader("User-Agent"))
            assertEquals(web().cookieHeader, request.getHeader("Cookie"))
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertEquals("CreateAutoLoginToken", request.getHeader("X-Netflix.request.name"))
            assertNull(request.getHeader("X-Netflix.nftoken"))
            assertNull(request.getHeader("X-Netflix.esn"))
            assertNull(request.getHeader("X-Netflix.esnPrefix"))
            assertNull(request.getHeader("Content-Encoding"))
            assertNull(request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("operationName", "variables", "extensions"), body.keys().asSequence().toSet())
            assertEquals("CreateAutoLoginToken", body.getString("operationName"))
            assertEquals("MOBILE_DEVICE_UPGRADE", body.getJSONObject("variables").getString("scope"))
            val query = body.getJSONObject("extensions").getJSONObject("persistedQuery")
            assertEquals(102, query.getInt("version"))
            assertEquals("76e97129-f4b5-41a0-a73c-12e674896849", query.getString("id"))
            assertEquals(0, nativeCookieCalls)
            assertEquals(0, inheritedCalls)
        }
    }

    @Test fun `consume accepts NetflixId without Secure and carries the complete pair through config account and manifest`() = TlsFixture().use { tls ->
        tls.enqueue(pairResponse("native-first", "native-secure"))
        tls.enqueue(pairResponse("native-rotated", "native-rotated-secure"))
        tls.enqueue(MockResponse().setBody("account-wire-response"))
        tls.enqueue(MockResponse().setBody("manifest-wire-response"))
        val source = Source(web())
        val provider = provider(source)
        val http = NativeAccountHttp(tls.client, NATIVE_UA, provider,
            mapOf("X-Netflix.appVer" to "native-fixture", "X-Netflix.androidApi" to "37"))

        assertEquals("native-wire-response", http.nativePost(NativeMslOperation.CONSUME_TOKEN,
            "consume-wire-fixture", provider.playbackContext(), 1, "private-one-use-token"))
        val consume = tls.nextRequest()
        assertEquals("android.prod.cloud.netflix.com", consume.getHeader("Host"))
        assertEquals("nfvdid=web-device", consume.getHeader("Cookie"))
        assertEquals("private-one-use-token", consume.getHeader("X-Netflix.nftoken"))
        assertEquals("ConsumeAutoLoginToken", consume.getHeader("X-Netflix.request.name"))
        assertEquals("application/json", consume.getHeader("Content-Type"))
        assertEquals("msl_v1", consume.getHeader("Content-Encoding"))
        assertEquals(NATIVE_UA, consume.getHeader("User-Agent"))
        assertEquals(NATIVE_ESN, consume.getHeader("X-Netflix.esn"))
        assertEquals(NATIVE_ESN.substringBeforeLast('-') + "-", consume.getHeader("X-Netflix.esnPrefix"))
        assertEquals("native-fixture", consume.getHeader("X-Netflix.appVer"))
        assertEquals("consume-wire-fixture", consume.body.readUtf8())

        val context = provider.playbackContext()
        val auth = JSONObject()
        MslAuthentication.NetflixCookies.apply(auth, context)
        http.nativePost(NativeMslOperation.FETCH_CONFIG, auth.toString(), context, 2)
        val config = tls.nextRequest()
        assertEquals("/nq/androidui/samurai/v1/config", config.path)
        assertEquals("application/x-www-form-urlencoded", config.getHeader("Content-Type"))
        assertEquals("FetchConfigDataMslRequest", config.getHeader("X-Netflix.Request.NqTracking"))
        assertNull(config.getHeader("X-Netflix.nftoken"))
        assertNull(config.getHeader("X-Netflix.request.name"))
        val pair = PlaybackCookies.snapshot(config.getHeader("Cookie")!!)
        val authData = JSONObject(config.body.readUtf8()).getJSONObject("userauthdata").getJSONObject("authdata")
        assertEquals("native-first", pair.netflixId)
        assertEquals(pair.netflixId, authData.getString("netflixid"))
        assertEquals(pair.secureNetflixId, authData.getString("securenetflixid"))

        http.nativePost(NativeMslOperation.FETCH_ACCOUNT, "account-wire-fixture", provider.playbackContext(), 3)
        val account = tls.nextRequest()
        assertEquals("AccountQuery", account.getHeader("X-Netflix.request.name"))
        assertEquals("application/json", account.getHeader("Content-Type"))
        assertEquals("native-rotated", PlaybackCookies.snapshot(account.getHeader("Cookie")!!).netflixId)
        assertNull(account.getHeader("X-Netflix.nftoken"))

        http.netflixPost(NetflixMslSession.MANIFEST_ENDPOINT, "manifest-wire-fixture", provider.playbackContext(), PlaybackStage.MANIFEST, 4)
        val manifest = tls.nextRequest()
        assertEquals("www.netflix.com", manifest.getHeader("Host"))
        assertEquals("native-rotated", PlaybackCookies.snapshot(manifest.getHeader("Cookie")!!).netflixId)
        assertEquals("text/plain", manifest.getHeader("Content-Type"))
        assertNull(manifest.getHeader("X-Netflix.nftoken"))
        assertNull(manifest.getHeader("Origin"))
        assertNull(manifest.getHeader("Referer"))
        assertNull(manifest.getHeader("Sec-CH-UA"))
        assertEquals(web(), source.value)
        assertEquals(0, source.writes)
    }

    @Test fun `native handshake uses the fixed first party endpoint and no account authentication cookie`() = TlsFixture().use { tls ->
        tls.enqueue(MockResponse().setBody("handshake-wire-response"))
        val provider = provider(Source(web()))
        val http = NativeAccountHttp(tls.client, NATIVE_UA, provider)
        http.netflixPost(NativeAndroidProfile.HANDSHAKE_ENDPOINT, "handshake-wire-fixture",
            provider.playbackContext(), PlaybackStage.KEY_EXCHANGE, 1)
        val request = tls.nextRequest()
        assertEquals("android.prod.ftl.netflix.com", request.getHeader("Host"))
        assertEquals("/nq/androidui/pbo_license/~1.0.0/router", request.path)
        assertEquals("nfvdid=web-device", request.getHeader("Cookie"))
        assertEquals(NATIVE_ESN, request.getHeader("X-Netflix.esn"))
        assertEquals("application/msl; charset=UTF-8", request.getHeader("Content-Type"))
        assertEquals("msl_v1", request.getHeader("Content-Encoding"))
        assertNull(request.getHeader("X-Netflix.nftoken"))
        assertEquals("handshake-wire-fixture", request.body.readUtf8())
    }

    @Test fun `native manifest and license use the fixed Android router and scoped native cookies`() = TlsFixture().use { tls ->
        val source = Source(web())
        val provider = provider(source)
        provider.updateCookiesFrom(NativeAndroidProfile.GRAPHQL_ENDPOINT.toHttpUrl(), listOf(
            Cookie.Builder().name("NetflixId").value("private-native-playback")
                .hostOnlyDomain("android.prod.cloud.netflix.com").path("/nq/androidui").httpOnly().build(),
            Cookie.Builder().name("SecureNetflixId").value("private-native-playback-secure")
                .hostOnlyDomain("android.prod.cloud.netflix.com").path("/nq/androidui").secure().httpOnly().build(),
        ))
        val http = NativeAccountHttp(tls.client, NATIVE_UA, provider,
            mapOf("X-Netflix.appVer" to "8.143.5", "X-Netflix.androidApi" to "37"))
        for ((operation, stage, requestName) in listOf(
            Triple(NativePlaybackOperation.MANIFEST, PlaybackStage.MANIFEST, "licensedManifest"),
            Triple(NativePlaybackOperation.LICENSE, PlaybackStage.LICENSE, "license"),
        )) {
            tls.enqueue(MockResponse().setBody("private-native-response"))
            assertEquals("private-native-response", http.playbackPost(operation, "private-native-wire",
                provider.playbackContext(), stage, 1))
            val request = tls.nextRequest()
            assertEquals("POST", request.method)
            assertEquals("android.prod.cloud.netflix.com", request.getHeader("Host"))
            assertEquals("/nq/androidui/samurai/~8.143.0/api", request.path)
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertEquals("msl_v1", request.getHeader("Content-Encoding"))
            assertEquals(NATIVE_UA, request.getHeader("User-Agent"))
            assertEquals(NATIVE_ESN, request.getHeader("X-Netflix.esn"))
            assertEquals("8.143.5", request.getHeader("X-Netflix.appVer"))
            assertEquals("37", request.getHeader("X-Netflix.androidApi"))
            assertEquals(requestName, request.getHeader("X-Netflix.client.request.name"))
            val routing = JSONObject(request.getHeader("X-Netflix.Request.Routing")!!)
            assertEquals(setOf("path"), routing.keys().asSequence().toSet())
            assertEquals("/nq/android/playback/~1.0.0/router", routing.getString("path"))
            assertEquals("private-native-playback", PlaybackCookies.snapshot(request.getHeader("Cookie")!!).netflixId)
            assertNull(request.getHeader("X-Netflix.nftoken"))
            assertNull(request.getHeader("X-Netflix.request.name"))
            assertNull(request.getHeader("Origin"))
            assertNull(request.getHeader("Referer"))
            assertEquals("private-native-wire", request.body.readUtf8())
        }
        assertEquals("nfvdid=web-device", provider.cookieHeaderFor(NetflixMslSession.MANIFEST_ENDPOINT.toHttpUrl()))
        assertEquals(web(), source.value)
        assertEquals(0, source.writes)
    }

    @Test fun `native playback does not widen credentials from a nonmatching cookie path`() = withJournal { journal ->
        TlsFixture().use { tls ->
            val provider = provider(Source(web()))
            provider.updateCookiesFrom(NativeAndroidProfile.GRAPHQL_ENDPOINT.toHttpUrl(), listOf(
                Cookie.Builder().name("NetflixId").value("private-graphql-only")
                    .hostOnlyDomain("android.prod.cloud.netflix.com").path("/graphql").build(),
                Cookie.Builder().name("SecureNetflixId").value("private-graphql-only-secure")
                    .hostOnlyDomain("android.prod.cloud.netflix.com").path("/graphql").secure().build(),
            ))
            val context = provider.playbackContext()
            assertEquals("private-graphql-only", PlaybackCookies.snapshot(context.cookieHeader).netflixId)
            val http = NativeAccountHttp(tls.client, NATIVE_UA, provider)
            tls.enqueue(MockResponse().setBody("private-native-response"))
            http.playbackPost(NativePlaybackOperation.MANIFEST, "private-native-wire", context, PlaybackStage.MANIFEST, 1)
            val request = tls.nextRequest()
            assertEquals("nfvdid=web-device", request.getHeader("Cookie"))
            assertEquals("private-native-wire", request.body.readUtf8())
            val exported = journal.exportText("Synthetic diagnostics")
            assertFalse(exported.contains("private-"))
            assertFalse(exported.contains(NATIVE_ESN))
            assertFalse(exported.contains("https://"))
        }
    }

    @Test fun `native playback refuses redirects without importing cookies or forwarding credentials`() = TlsFixture().use { tls ->
        val provider = provider(Source(web()))
        provider.updateCookiesFrom(NativeAndroidProfile.GRAPHQL_ENDPOINT.toHttpUrl(), nativePair("native-original", "native-original-secure"))
        val original = provider.playbackContext().cookieHeader
        val http = NativeAccountHttp(tls.client, NATIVE_UA, provider)
        tls.enqueue(pairResponse("private-redirect", "private-redirect-secure").setResponseCode(302)
            .addHeader("Location", "https://untrusted.example/private-destination"))
        val error = rejected { http.playbackPost(NativePlaybackOperation.LICENSE, "private-native-wire",
            provider.playbackContext(), PlaybackStage.LICENSE, 1) }
        assertEquals(PlaybackStage.LICENSE, error.stage)
        assertEquals("HTTP_302", error.serviceCode)
        assertEquals(original, provider.playbackContext().cookieHeader)
        assertEquals("android.prod.cloud.netflix.com", tls.nextRequest().getHeader("Host"))
        assertNull(tls.server.takeRequest(150, TimeUnit.MILLISECONDS))
    }

    @Test fun `manifest accepts a full cookie rotation alongside host only and insecure tracking cookies`() = TlsFixture().use { tls ->
        val source = Source(web())
        val provider = provider(source)
        provider.updateCookiesFrom(NativeAndroidProfile.GRAPHQL_ENDPOINT.toHttpUrl(), nativePair("native-original", "native-original-secure"))
        val http = NativeAccountHttp(tls.client, NATIVE_UA, provider)
        tls.enqueue(pairResponse("manifest-rotated", "manifest-rotated-secure")
            .addHeader("Set-Cookie", "tracking=private-host-only-metric; Path=/; Secure")
            .addHeader("Set-Cookie", "metric=private-insecure-metric; Domain=.netflix.com; Path=/"))

        assertEquals("native-wire-response", http.netflixPost(NetflixMslSession.MANIFEST_ENDPOINT,
            "manifest-wire-fixture", provider.playbackContext(), PlaybackStage.MANIFEST, 1))

        tls.nextRequest()
        val snapshot = PlaybackCookies.snapshot(provider.playbackContext().cookieHeader)
        assertEquals("manifest-rotated", snapshot.netflixId)
        assertEquals("manifest-rotated-secure", snapshot.secureNetflixId)
        assertFalse(snapshot.header.contains("tracking="))
        assertFalse(snapshot.header.contains("metric="))
        val wwwCookies = provider.cookieHeaderFor(NetflixMslSession.MANIFEST_ENDPOINT.toHttpUrl())
        assertTrue(wwwCookies.contains("tracking=private-host-only-metric"))
        assertTrue(wwwCookies.contains("metric=private-insecure-metric"))
        val nativeCookies = provider.cookieHeaderFor(NativeAndroidProfile.GRAPHQL_ENDPOINT.toHttpUrl())
        assertFalse(nativeCookies.contains("tracking="))
        assertTrue(nativeCookies.contains("metric=private-insecure-metric"))
        assertEquals(web(), source.value)
        assertEquals(0, source.writes)
    }

    @Test fun `manifest rejects invalid domains and partial authentication pairs without changing the jar`() = withJournal { journal ->
        val responses = listOf(
            MockResponse().setBody("manifest-wire-response")
                .addHeader("Set-Cookie", "NetflixId=private-invalid-domain; Domain=untrusted.example; Path=/")
                .addHeader("Set-Cookie", "SecureNetflixId=private-invalid-domain-secure; Domain=untrusted.example; Path=/; Secure"),
            MockResponse().setBody("manifest-wire-response")
                .addHeader("Set-Cookie", "NetflixId=private-partial; Domain=.netflix.com; Path=/"),
        )
        for (response in responses) TlsFixture().use { tls ->
            val provider = provider(Source(web()))
            provider.updateCookiesFrom(NativeAndroidProfile.GRAPHQL_ENDPOINT.toHttpUrl(),
                nativePair("native-original", "native-original-secure"))
            val previous = provider.playbackContext().cookieHeader
            val previousHttp = provider.cookieHeaderFor(NetflixMslSession.MANIFEST_ENDPOINT.toHttpUrl())
            val http = NativeAccountHttp(tls.client, NATIVE_UA, provider)
            tls.enqueue(response.addHeader("Set-Cookie", "tracking=private-new-metric; Path=/; Secure"))

            val error = rejected { http.netflixPost(NetflixMslSession.MANIFEST_ENDPOINT,
                "manifest-wire-fixture", provider.playbackContext(), PlaybackStage.MANIFEST, 1) }

            assertEquals(PlaybackStage.SESSION, error.stage)
            assertEquals(previous, provider.playbackContext().cookieHeader)
            assertEquals(previousHttp, provider.cookieHeaderFor(NetflixMslSession.MANIFEST_ENDPOINT.toHttpUrl()))
        }
        assertFalse(journal.exportText("Synthetic diagnostics").contains("private-"))
    }

    @Test fun `host only auth cookies retain their path and stay out of sibling paths and other Netflix hosts`() = withJournal { journal ->
        TlsFixture().use { tls ->
            val source = Source(web())
            val provider = provider(source)
            val http = NativeAccountHttp(tls.client, NATIVE_UA, provider)
            tls.enqueue(MockResponse().setBody("consume-wire-response")
                .addHeader("Set-Cookie", "NetflixId=private-native-host; Path=/nq/androidui; HttpOnly")
                .addHeader("Set-Cookie", "SecureNetflixId=private-native-host-secure; Path=/nq/androidui; Secure; HttpOnly"))
            tls.enqueue(MockResponse().setBody("config-wire-response"))
            tls.enqueue(MockResponse().setBody("account-wire-response"))
            tls.enqueue(MockResponse().setBody("manifest-wire-response"))

            assertEquals("consume-wire-response", http.nativePost(NativeMslOperation.CONSUME_TOKEN,
                "consume-wire", provider.playbackContext(), 1, "private-token"))
            assertEquals("nfvdid=web-device", tls.nextRequest().getHeader("Cookie"))
            val context = provider.playbackContext()
            val msl = JSONObject()
            MslAuthentication.NetflixCookies.apply(msl, context)
            val credentials = msl.getJSONObject("userauthdata").getJSONObject("authdata")
            assertEquals("private-native-host", credentials.getString("netflixid"))
            assertEquals("private-native-host-secure", credentials.getString("securenetflixid"))

            assertEquals("config-wire-response", http.nativePost(NativeMslOperation.FETCH_CONFIG,
                msl.toString(), context, 2))
            val config = tls.nextRequest()
            assertEquals("android.prod.cloud.netflix.com", config.getHeader("Host"))
            assertEquals("private-native-host", PlaybackCookies.snapshot(config.getHeader("Cookie")!!).netflixId)
            assertEquals("private-native-host", JSONObject(config.body.readUtf8()).getJSONObject("userauthdata")
                .getJSONObject("authdata").getString("netflixid"))

            assertEquals("account-wire-response", http.nativePost(NativeMslOperation.FETCH_ACCOUNT,
                msl.toString(), context, 3))
            val account = tls.nextRequest()
            assertEquals("/graphql", account.path)
            assertEquals("nfvdid=web-device", account.getHeader("Cookie"))
            assertEquals("private-native-host", JSONObject(account.body.readUtf8()).getJSONObject("userauthdata")
                .getJSONObject("authdata").getString("netflixid"))
            assertNull(account.getHeader("X-Netflix.nftoken"))

            assertEquals("manifest-wire-response", http.netflixPost(NetflixMslSession.MANIFEST_ENDPOINT,
                "manifest-wire", context, PlaybackStage.MANIFEST, 4))
            val manifest = tls.nextRequest()
            assertEquals("www.netflix.com", manifest.getHeader("Host"))
            assertEquals("nfvdid=web-device", manifest.getHeader("Cookie"))
            assertNull(manifest.getHeader("X-Netflix.nftoken"))
            assertEquals("nfvdid=web-device", provider.cookieHeaderFor(NativeAndroidProfile.HANDSHAKE_ENDPOINT.toHttpUrl()))
            assertEquals("private-native-host", PlaybackCookies.snapshot(provider.playbackContext().cookieHeader).netflixId)
            assertEquals(web(), source.value)
            assertEquals(0, source.writes)
            val exported = journal.exportText("Synthetic diagnostics")
            val events = exported.lineSequence().filter { it.startsWith('{') }.map(::JSONObject).toList()
            val attributes = events.filter { it.getString("event") == "native_cookie_attributes" }
                .map { it.getJSONObject("data") }
            assertEquals(2, attributes.size)
            assertEquals(setOf("netflix_id", "secure_netflix_id"), attributes.map { it.getString("cookieKind") }.toSet())
            attributes.forEach { item ->
                assertEquals("request_host", item.getString("cookieDomainScope"))
                assertTrue(item.getBoolean("cookieHostOnly"))
                assertTrue(item.getBoolean("cookieHttpOnly"))
                assertFalse(item.getBoolean("cookieRootPath"))
                assertFalse(item.getBoolean("cookieMatchesRequest"))
                assertFalse(item.getBoolean("cookieExpired"))
                assertEquals(item.getString("cookieKind") == "secure_netflix_id", item.getBoolean("cookieSecure"))
            }
            val batch = events.first { it.getString("event") == "native_cookie_batch_received" }.getJSONObject("data")
            assertEquals(2, batch.getInt("setCookieCount"))
            assertEquals(2, batch.getInt("cookieCount"))
            assertEquals(2, batch.getInt("authCookieCount"))
            val bodyIndex = events.indexOfFirst { it.getString("event") == "native_http_body_received" }
            val batchIndex = events.indexOfFirst { it.getString("event") == "native_cookie_batch_received" }
            assertTrue(bodyIndex >= 0 && bodyIndex < batchIndex)
            assertFalse(exported.contains("private-"))
            assertFalse(exported.contains(NATIVE_ESN))
            assertFalse(exported.contains("https://"))
        }
    }

    @Test fun `tokens arbitrary destinations and unsafe header overrides stop before transport`() = TlsFixture().use { tls ->
        val context = provider(Source(web())).playbackContext()
        val http = NativeAccountHttp(tls.client, NATIVE_UA, inertCookies())
        for (url in listOf("http://www.netflix.com/path", "https://untrusted.example/private-token",
            "https://user:private@www.netflix.com/path", "https://www.netflix.com:8443/path",
            "https://www.netflix.com/login", NativeAndroidProfile.HANDSHAKE_ENDPOINT + "?token=private",
            NativeAndroidProfile.HANDSHAKE_ENDPOINT + "#private")) {
            rejected { http.netflixPost(url, "private-wire", context, PlaybackStage.KEY_EXCHANGE, 1) }
        }
        rejected { http.nativePost(NativeMslOperation.CONSUME_TOKEN, "wire", context, 1, null) }
        rejected { http.nativePost(NativeMslOperation.FETCH_CONFIG, "wire", context, 1, "private-token") }
        rejected { http.nativePost(NativeMslOperation.FETCH_ACCOUNT, "wire", context, 1, "private-token") }
        for (name in listOf("Cookie", "Authorization", "Proxy-Authorization", "X-Netflix.nftoken", "X-Netflix.esn", "Host",
            "X-Netflix.client.request.name", "X-Netflix.Request.Routing")) {
            rejected { NativeAccountHttp(tls.client, NATIVE_UA, inertCookies(), mapOf(name to "private")) }
        }
        assertEquals(0, tls.server.requestCount)
    }

    @Test fun `redirect and GraphQL refusal never expose secrets in logs or follow the token elsewhere`() = withJournal { journal ->
        TlsFixture().use { tls ->
            val http = NativeAccountHttp(tls.client, NATIVE_UA, inertCookies())
            tls.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.example/private-redirect-token")
                .setBody("private-redirect-body"))
            val redirect = rejected { http.createLoginToken(web()) }
            assertEquals("HTTP_302", redirect.serviceCode)
            tls.nextRequest()
            assertNull(tls.server.takeRequest(150, TimeUnit.MILLISECONDS))

            tls.enqueue(MockResponse().setBody("""{"errors":[{"message":"private-server-token private-server-cookie"}],"data":{"createAutoLoginToken":"private-response-token"}}"""))
            rejected { http.createLoginToken(web()) }
            tls.nextRequest()
            val text = journal.exportText("Synthetic diagnostics")
            for (secret in listOf("web-private", "web-secure-private", "private-server-token", "private-server-cookie",
                "private-response-token", "private-redirect-token", "private-redirect-body", "https://", NATIVE_ESN)) {
                assertFalse("Diagnostic leaked synthetic material", text.contains(secret))
            }
        }
    }

    @Test fun `malformed token shapes do not fall back to another field or scope`() = TlsFixture().use { tls ->
        val http = NativeAccountHttp(tls.client, NATIVE_UA, inertCookies())
        for (body in listOf(
            """{"data":{"createAutoLoginToken":{"token":"private-nested"}}}""",
            """{"data":{"nfToken":"private-other"}}""",
            """{"data":{"createAutoLoginToken":null}}""",
            """{"data":{"createAutoLoginToken":"private\nheader"}}""",
            "private-invalid-json",
        )) {
            tls.enqueue(MockResponse().setBody(body))
            rejected { http.createLoginToken(web()) }
            assertEquals("MOBILE_DEVICE_UPGRADE", JSONObject(tls.nextRequest().body.readUtf8())
                .getJSONObject("variables").getString("scope"))
        }
    }

    @Test fun `late native cookie responses cannot update an account profile or browser replacement`() {
        val changes: List<(Source) -> Unit> = listOf(
            { it.generation++ },
            { it.value = it.value.copy(profileGuid = "changed-profile") },
            { it.value = it.value.copy(esn = "changed-browser") },
        )
        for (change in changes) TlsFixture().use { tls ->
            val source = Source(web())
            val provider = provider(source)
            val context = provider.playbackContext()
            val http = NativeAccountHttp(tls.client, NATIVE_UA, provider)
            tls.beforeResponse = { change(source) }
            tls.enqueue(pairResponse("late-private", "late-private-secure"))
            val error = rejected { http.nativePost(NativeMslOperation.CONSUME_TOKEN, "wire", context, 1, "private-token") }
            assertEquals(PlaybackStage.SESSION, error.stage)
            assertEquals(0, source.writes)
            assertEquals(web().cookieHeader, source.value.cookieHeader)
            rejected { provider.playbackContext() }
        }
    }

    @Test fun `invalid cookie domain partial pair and HTTP refusal never produce native account credentials`() = withJournal { journal ->
        val responses = listOf(
            MockResponse().setBody("wire").addHeader("Set-Cookie", "NetflixId=private-foreign; Domain=untrusted.example; Path=/; Secure")
                .addHeader("Set-Cookie", "SecureNetflixId=private-foreign-secure; Domain=untrusted.example; Path=/; Secure"),
            MockResponse().setBody("wire").addHeader("Set-Cookie", "NetflixId=private-partial; Domain=.netflix.com; Path=/; Secure"),
            pairResponse("private-refused", "private-refused-secure").setResponseCode(403),
        )
        for (response in responses) TlsFixture().use { tls ->
            val source = Source(web())
            val provider = provider(source)
            val http = NativeAccountHttp(tls.client, NATIVE_UA, provider)
            tls.enqueue(response)
            rejected { http.nativePost(NativeMslOperation.CONSUME_TOKEN, "wire", provider.playbackContext(), 1, "private-token") }
            assertEquals("nfvdid=web-device", provider.playbackContext().cookieHeader)
            assertEquals(0, source.writes)
        }
        val exported = journal.exportText("Synthetic diagnostics")
        assertTrue(exported.contains("native_http_rejected"))
        assertFalse(exported.contains("private-"))
        assertFalse(exported.contains("untrusted.example"))
    }

    @Test fun `native transport does not mix JSON credentials into the HTTP cookie pair`() = TlsFixture().use { tls ->
        val provider = provider(Source(web()))
        val http = NativeAccountHttp(tls.client, NATIVE_UA, provider)
        val body = """{"credentials":{"netflixId":"private-json-first","secureNetflixId":"private-json-second"}}"""
        tls.enqueue(MockResponse().setBody(body))
        assertEquals(body, http.nativePost(NativeMslOperation.CONSUME_TOKEN, "wire", provider.playbackContext(), 1, "private-token"))
        assertEquals("nfvdid=web-device", provider.playbackContext().cookieHeader)
        rejected { http.nativePost(NativeMslOperation.FETCH_CONFIG, "wire", provider.playbackContext(), 2) }
        tls.nextRequest()
        assertNull(tls.server.takeRequest(150, TimeUnit.MILLISECONDS))
    }

    private fun inertCookies() = object : NativeCookieSession {
        override fun cookieHeaderFor(url: HttpUrl): String = ""
        override fun updateCookiesFrom(url: HttpUrl, cookies: List<Cookie>) = Unit
    }

    private fun provider(source: Source) = NativeAccountSessionProvider(BoundPlaybackSession(source.value, source), NATIVE_ESN, NATIVE_UA)

    private fun web() = PlaybackContext("NetflixId=web-private; SecureNetflixId=web-secure-private; nfvdid=web-device",
        "web-auth-private", "NFCDCH-web-private", "profile-private", "BrowserFixture/1")

    private fun pairResponse(first: String, second: String) = MockResponse().setBody("native-wire-response")
        .addHeader("Set-Cookie", "NetflixId=$first; Domain=.netflix.com; Path=/; HttpOnly")
        .addHeader("Set-Cookie", "SecureNetflixId=$second; Domain=.netflix.com; Path=/; Secure; HttpOnly")

    private fun nativePair(first: String, second: String) = listOf(
        Cookie.Builder().name("NetflixId").value(first).domain("netflix.com").path("/").httpOnly().build(),
        Cookie.Builder().name("SecureNetflixId").value(second).domain("netflix.com").path("/").secure().httpOnly().build(),
    )

    private fun rejected(block: () -> Unit): NetflixPlaybackException {
        val error = assertThrows(NetflixPlaybackException::class.java) { block() }
        assertFalse(error.message.orEmpty().contains("private"))
        assertFalse(error.message.orEmpty().contains("https://"))
        assertNull(error.cause)
        return error
    }

    private fun withJournal(block: (SessionLog) -> Unit) = synchronized(AppLog) {
        val directory = Files.createTempDirectory("velvet-native-http-test").toFile()
        val field = AppLog::class.java.getDeclaredField("journal").apply { isAccessible = true }
        val previous = field.get(null)
        try {
            val journal = SessionLog(directory)
            field.set(null, journal)
            block(journal)
        } finally {
            field.set(null, previous)
            directory.deleteRecursively()
        }
    }

    private class Source(@Volatile var value: PlaybackContext) : NetflixSessionProvider {
        @Volatile var generation = 0
        var writes = 0
        override fun playbackContext(): PlaybackContext {
            if (generation != 0) throw NetflixPlaybackException(PlaybackStage.SESSION, message = "Synthetic owner changed", retryable = false)
            return value
        }
        override fun updateCookies(cookies: List<Cookie>) { writes++ }
    }

    /** Real HTTPS via a local CONNECT proxy; production host/port checks remain unchanged. */
    private class TlsFixture(customize: OkHttpClient.Builder.() -> Unit = {}) : Closeable {
        val server = MockWebServer()
        val client: OkHttpClient
        private val responses = LinkedBlockingQueue<MockResponse>()
        @Volatile var beforeResponse: (RecordedRequest) -> Unit = {}

        init {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(TEST_CERTIFICATE)))
            val key = KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(TEST_PRIVATE_KEY)))
            val password = "local-test-only".toCharArray()
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("fixture", key, password, arrayOf(certificate))
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val trustManager = trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trusts.trustManagers, SecureRandom()) }
            server.useHttps(ssl.socketFactory, true)
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.dispatcher = object : Dispatcher() {
                override fun peek(): MockResponse = tunnel()
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "CONNECT") return tunnel()
                    beforeResponse(request)
                    return responses.poll(2, TimeUnit.SECONDS) ?: MockResponse().setResponseCode(500)
                }
            }
            client = OkHttpClient.Builder().proxy(server.toProxyAddress())
                .sslSocketFactory(ssl.socketFactory, trustManager).protocols(listOf(Protocol.HTTP_1_1))
                .apply(customize).build()
        }

        fun enqueue(response: MockResponse) { responses.add(response) }

        fun nextRequest(): RecordedRequest {
            while (true) {
                val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("No HTTP request received")
                if (request.method != "CONNECT") return request
                assertNull(request.getHeader("Cookie"))
                assertNull(request.getHeader("X-Netflix.nftoken"))
                assertNull(request.getHeader("Proxy-Authorization"))
            }
        }

        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
        }

        private fun tunnel() = MockResponse().setResponseCode(200).setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
    }

    companion object {
        private const val NATIVE_UA = "NativeAndroidFixture/1"
        private val NATIVE_ESN = "NFANDROID1-PRV-P-VELVETTEST-1234-" + "A".repeat(64)
        // Generated exclusively for this local test. This self-signed key/certificate is not
        // trusted by the app, any real Netflix server, the APK signer or a user device.
        private const val TEST_PRIVATE_KEY = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgcbfPAcZwPeNDbIZq94wRhxYhr4qUM38jSktDeoPIHoehRANCAAR04CdBjKaZ49jJ+7Qj0b51A9508bM9AucALw7oLkYwAYdkXmaBmfdO4bHtri8gsis0krbpMGgD0l8SAkj57L8T"
        private const val TEST_CERTIFICATE = "MIIB0zCCAXmgAwIBAgIBAjAKBggqhkjOPQQDAjAoMSYwJAYDVQQDDB1WZWx2ZXQgbmF0aXZlIEhUVFAgbG9jYWwgdGVzdDAeFw0yMDAxMDEwMDAwMDBaFw00MDAxMDEwMDAwMDBaMCgxJjAkBgNVBAMMHVZlbHZldCBuYXRpdmUgSFRUUCBsb2NhbCB0ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEdOAnQYymmePYyfu0I9G+dQPedPGzPQLnAC8O6C5GMAGHZF5mgZn3TuGx7a4vILIrNJK26TBoA9JfEgJI+ey/E6OBkzCBkDCBjQYDVR0RBIGFMIGCgg93d3cubmV0ZmxpeC5jb22CHmFuZHJvaWQxMy5wcm9kLmZ0bC5uZXRmbGl4LmNvbYIeYW5kcm9pZC5wcm9kLmNsb3VkLm5ldGZsaXguY29tghxhbmRyb2lkLnByb2QuZnRsLm5ldGZsaXguY29tghF1bnRydXN0ZWQuZXhhbXBsZTAKBggqhkjOPQQDAgNIADBFAiAm67pqghWDU7NWfWTv/I2srlkm6GE+Rkj6t2O3XkrSVgIhAPCnOXlLbFGsbsIMjoaQwXliS3MuyBvSCX3yGKKaE+cF"
    }
}
