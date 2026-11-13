package pl.kyvaith.velvet.playback

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pl.kyvaith.velvet.data.NetflixOrigins
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class PlaybackHttpTest {
    @Test fun `native HTTP ignores inherited cookie jar and logging interceptors on response`() {
        var cookieReads = 0
        var cookieWrites = 0
        var interceptorCalls = 0
        val jar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                cookieReads++
                return listOf(Cookie.Builder().name("NetflixId").value("browser-account").domain(url.host).build())
            }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookieWrites++ }
        }
        val inherited = OkHttpClient.Builder().cookieJar(jar).addInterceptor { chain ->
            interceptorCalls++
            chain.proceed(chain.request())
        }.addNetworkInterceptor { chain ->
            interceptorCalls++
            chain.proceed(chain.request())
        }.build()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "NetflixId=different-account; Path=/").setBody("fixture"))
            val transport = PlaybackHttp(inherited, "test-ua")
            transport.publicClient.newCall(Request.Builder().url(server.url("/fixture")).build()).execute().use {
                assertEquals(200, it.code)
            }
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
        assertEquals(0, cookieReads)
        assertEquals(0, cookieWrites)
        assertEquals(0, interceptorCalls)
    }

    @Test fun `credentials cannot be sent through an arbitrary MSL destination`() {
        val transport = PlaybackHttp(OkHttpClient(), "test-ua")
        val context = PlaybackContext("NetflixId=fixture", "auth", "web", "profile", "ua")
        for (url in listOf("http://www.netflix.com/path", "https://other.example/path",
            "https://user:password@www.netflix.com/path", "https://www.netflix.com:8443/path")) {
            try {
                transport.netflixPost(url, "encrypted-fixture", context, PlaybackStage.AUTHENTICATION, 1)
                fail("Unsafe destination accepted")
            } catch (error: NetflixPlaybackException) {
                assertEquals(PlaybackStage.AUTHENTICATION, error.stage)
                assertEquals(false, error.retryable)
                assertFalse(error.message.orEmpty().contains(url))
            }
        }
    }

    @Test fun `real MSL HTTP uses the canonical auth pair and exact Kodi playback headers`() = withTlsServer { server, client ->
        server.enqueue(MockResponse().setBody("response-fixture"))
        val context = context().copy(cookieHeader =
            "NetflixId=raw%3D+==; NetflixId=raw%3D+==; SecureNetflixId=raw%2F==; nfvdid=device-fixture")
        val header = JSONObject()
        MslAuthentication.NetflixCookies.apply(header, context)
        val wire = header.put("fixture", "Zażółć").toString()

        val result = PlaybackHttp(client, "provision-ua").netflixPost(
            ENDPOINT, wire, context, PlaybackStage.MANIFEST, 1)

        assertEquals("response-fixture", result)
        val request = nextApplicationRequest(server)
        assertEquals("POST", request.method)
        assertEquals("www.netflix.com", request.getHeader("Host"))
        assertEquals(context.userAgent, request.getHeader("User-Agent"))
        assertEquals("*/*", request.getHeader("Accept"))
        assertEquals("text/plain", request.getHeader("Content-Type"))
        assertNull(request.getHeader("Origin"))
        assertNull(request.getHeader("Referer"))
        assertNull(request.getHeader("Sec-CH-UA"))
        assertNull(request.getHeader("Sec-CH-UA-Mobile"))
        assertNull(request.getHeader("Sec-CH-UA-Platform"))
        assertEquals("NetflixId=raw%3D+==; SecureNetflixId=raw%2F==; nfvdid=device-fixture", request.getHeader("Cookie"))
        assertEquals(wire, request.body.readUtf8())
        val http = PlaybackCookies.snapshot(request.getHeader("Cookie")!!)
        val auth = header.getJSONObject("userauthdata").getJSONObject("authdata")
        assertEquals(http.netflixId, auth.getString("netflixid"))
        assertEquals(http.secureNetflixId, auth.getString("securenetflixid"))
    }

    @Test fun `real Set-Cookie rotation feeds the next manifest and license HTTP and MSL pair`() = withTlsServer { server, client ->
        server.enqueue(rotatedResponse("handshake-a", "handshake-b"))
        server.enqueue(rotatedResponse("manifest-a", "manifest-b"))
        server.enqueue(MockResponse().setBody("license-fixture"))
        val source = GuardedSource(context())
        val bound = BoundPlaybackSession(source.value, source)
        val transport = PlaybackHttp(client, "provision-ua", bound::updateCookies)

        transport.netflixPost(ENDPOINT, "handshake-fixture", bound.current(), PlaybackStage.KEY_EXCHANGE, 1)
        val handshakeRequest = nextApplicationRequest(server)
        assertEquals("login-a", PlaybackCookies.snapshot(handshakeRequest.getHeader("Cookie")!!).netflixId)
        val expectedPairs = listOf("handshake-a" to "handshake-b", "manifest-a" to "manifest-b")
        for ((index, pair) in expectedPairs.withIndex()) {
            val current = bound.current()
            val authHeader = JSONObject()
            MslAuthentication.NetflixCookies.apply(authHeader, current)
            transport.netflixPost(ENDPOINT, authHeader.toString(), current,
                if (index == 0) PlaybackStage.MANIFEST else PlaybackStage.LICENSE, index.toLong() + 2)
            val request = nextApplicationRequest(server)
            val httpCookies = PlaybackCookies.snapshot(request.getHeader("Cookie")!!)
            val auth = JSONObject(request.body.readUtf8()).getJSONObject("userauthdata").getJSONObject("authdata")
            assertEquals(pair.first, httpCookies.netflixId)
            assertEquals(pair.second, httpCookies.secureNetflixId)
            assertEquals(httpCookies.netflixId, auth.getString("netflixid"))
            assertEquals(httpCookies.secureNetflixId, auth.getString("securenetflixid"))
        }
        assertEquals(2, source.writes)
        assertEquals("observed-browser-esn", source.value.esn)
        assertEquals("chosen-profile", source.value.profileGuid)
    }

    @Test fun `MSL cookie updates exclude unrelated domains and paths`() = withTlsServer { server, client ->
        server.enqueue(MockResponse().setBody("response-fixture")
            .addHeader("Set-Cookie", "NetflixId=renewed; Domain=.netflix.com; Path=/; Secure; HttpOnly")
            .addHeader("Set-Cookie", "SecureNetflixId=renewed-secure; Path=/; Secure; HttpOnly")
            .addHeader("Set-Cookie", "nfvdid=device; Domain=.netflix.com; Path=/; Secure")
            .addHeader("Set-Cookie", "foreign=ignored; Domain=other.example; Path=/")
            .addHeader("Set-Cookie", "scoped=ignored; Domain=.netflix.com; Path=/login"))
        val updates = mutableListOf<List<Cookie>>()
        val transport = PlaybackHttp(client, "ua") { updates += it }
        transport.netflixPost(ENDPOINT, "wire", context(), PlaybackStage.MANIFEST, 1)

        assertEquals(1, updates.size)
        assertEquals(listOf("NetflixId", "SecureNetflixId", "nfvdid"), updates.single().map { it.name })
        assertEquals(listOf("renewed", "renewed-secure", "device"), updates.single().map { it.value })
    }

    @Test fun `account profile and browser changes while HTTP is in flight reject late cookie updates`() {
        val changes: List<(GuardedSource) -> Unit> = listOf(
            { it.generation++ },
            { it.value = it.value.copy(profileGuid = "replacement-profile") },
            { it.value = it.value.copy(esn = "replacement-browser") },
        )
        for (changeOwner in changes) withTlsServer { server, client ->
            val source = GuardedSource(context())
            val bound = BoundPlaybackSession(source.value, source)
            val transport = PlaybackHttp(client, "ua", bound::updateCookies)
            server.dispatcher = object : Dispatcher() {
                override fun peek(): MockResponse = tunnelResponse()
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "CONNECT") return tunnelResponse()
                    changeOwner(source)
                    return rotatedResponse("late-private-a", "late-private-b")
                }
            }
            sessionFailure { transport.netflixPost(ENDPOINT, "wire", bound.current(), PlaybackStage.MANIFEST, 1) }

            assertEquals(0, source.writes)
            assertEquals(context().cookieHeader, source.value.cookieHeader)
        }
    }

    @Test fun `atomic provider guard also rejects logout after the callback preliminary owner check`() = withTlsServer { server, client ->
        server.enqueue(rotatedResponse("late-private-a", "late-private-b"))
        val source = GuardedSource(context())
        val bound = BoundPlaybackSession(source.value, source)
        val transport = PlaybackHttp(client, "ua", bound::updateCookies)
        source.beforeWrite = { source.generation++ }

        sessionFailure { transport.netflixPost(ENDPOINT, "wire", bound.current(), PlaybackStage.MANIFEST, 1) }

        assertEquals(0, source.writes)
        assertEquals(context().cookieHeader, source.value.cookieHeader)
    }

    @Test fun `conflicting authentication cookies stop before HTTP is sent`() = withTlsServer { server, client ->
        val transport = PlaybackHttp(client, "ua")
        val context = context().copy(cookieHeader = "NetflixId=first; SecureNetflixId=secure; NetflixId=second")
        sessionFailure { transport.netflixPost(ENDPOINT, "wire", context, PlaybackStage.MANIFEST, 1) }
        assertEquals(0, server.requestCount)
    }

    @Test fun `provisioning sends no account cookies and never imports response cookies`() = withTlsServer { server, client ->
        server.enqueue(rotatedResponse("unrelated-account", "unrelated-secure"))
        var updates = 0
        val transport = PlaybackHttp(client, "provision-ua") { updates++ }
        transport.provision("https://provision.example/provision", "synthetic-request".toByteArray())

        val request = nextApplicationRequest(server)
        assertEquals("provision-ua", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Cookie"))
        assertEquals(0, updates)
    }

    private fun context() = PlaybackContext(
        "NetflixId=login-a; SecureNetflixId=login-b", "auth", "observed-browser-esn", "chosen-profile", "kodi-playback-ua")

    private class GuardedSource(@Volatile var value: PlaybackContext) : NetflixSessionProvider {
        private val originalProfile = value.profileGuid
        private val originalEsn = value.esn
        @Volatile var generation = 0
        var writes = 0
        var beforeWrite: (() -> Unit)? = null

        override fun playbackContext(): PlaybackContext {
            requireOwner()
            return value
        }

        @Synchronized
        override fun updateCookies(cookies: List<Cookie>) {
            beforeWrite?.invoke()
            requireOwner()
            value = value.copy(cookieHeader = NetflixOrigins.mergeCookies(value.cookieHeader, cookies))
            writes++
        }

        private fun requireOwner() {
            if (generation != 0 || value.profileGuid != originalProfile || value.esn != originalEsn) {
                throw NetflixPlaybackException(PlaybackStage.SESSION, message = "Synthetic owner changed", retryable = false)
            }
        }
    }

    private fun sessionFailure(action: () -> Unit) {
        try { action(); fail("Stale or ambiguous session was accepted") }
        catch (error: NetflixPlaybackException) {
            assertEquals(PlaybackStage.SESSION, error.stage)
            assertEquals(false, error.retryable)
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    private fun rotatedResponse(first: String, second: String): MockResponse = MockResponse().setBody("response-fixture")
        .addHeader("Set-Cookie", "NetflixId=$first; Domain=.netflix.com; Path=/; Secure; HttpOnly")
        .addHeader("Set-Cookie", "SecureNetflixId=$second; Domain=.netflix.com; Path=/; Secure; HttpOnly")

    private fun nextApplicationRequest(server: MockWebServer): RecordedRequest {
        while (true) {
            val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("No HTTP request received")
            if (request.method != "CONNECT") return request
            assertNull("Account cookies must not reach the proxy CONNECT", request.getHeader("Cookie"))
        }
    }

    private fun tunnelResponse(): MockResponse = MockResponse().setResponseCode(200)
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)

    /** Keep the production HTTPS/host/port checks intact; a local CONNECT proxy handles every request. */
    private fun withTlsServer(block: (MockWebServer, OkHttpClient) -> Unit) {
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
        MockWebServer().use { server ->
            server.useHttps(ssl.socketFactory, true)
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.enqueue(tunnelResponse())
            val client = OkHttpClient.Builder().proxy(server.toProxyAddress())
                .sslSocketFactory(ssl.socketFactory, trustManager).protocols(listOf(Protocol.HTTP_1_1)).build()
            try { block(server, client) }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    companion object {
        private const val ENDPOINT = "https://www.netflix.com/msl/playapi/cadmium/licensedmanifest/1"
        // Generated exclusively for this local TLS fixture. This self-signed certificate/key
        // authenticates no real service and is trusted only by withTlsServer's test client.
        private const val TEST_PRIVATE_KEY = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgHM+qOkjU1GSekMdrLCurPiNjfvCehh9I3UTPmYWVuhWhRANCAAR0YJXk7gAsJHBkJ/DVokk6Wz82dF2bGK/sILlTzVpuxWQ4H0QsW1HAtUc4Hm9Oe2OSIWmKiDlSY2Q5bLSZjdQ6"
        private const val TEST_CERTIFICATE = "MIIBbDCCARKgAwIBAgIBATAKBggqhkjOPQQDAjAmMSQwIgYDVQQDDBtWZWx2ZXQgbG9jYWwgdHJhbnNwb3J0IHRlc3QwHhcNMjAwMTAxMDAwMDAwWhcNNDAwMTAxMDAwMDAwWjAmMSQwIgYDVQQDDBtWZWx2ZXQgbG9jYWwgdHJhbnNwb3J0IHRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAR0YJXk7gAsJHBkJ/DVokk6Wz82dF2bGK/sILlTzVpuxWQ4H0QsW1HAtUc4Hm9Oe2OSIWmKiDlSY2Q5bLSZjdQ6ozEwLzAtBgNVHREEJjAkgg93d3cubmV0ZmxpeC5jb22CEXByb3Zpc2lvbi5leGFtcGxlMAoGCCqGSM49BAMCA0gAMEUCICyFLIzb82q0AvN00HMF5sF0qg3/TlAG5SQ0+HKu12MYAiEAsJDu4AIgmQOQIu4d/ysBwlQWGt7c+8ZptRcLYv8K5c0="
    }
}
