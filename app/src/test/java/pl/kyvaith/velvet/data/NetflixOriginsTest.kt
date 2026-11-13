package pl.kyvaith.velvet.data

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NetflixOriginsTest {
    @Test fun apiCredentialsAreRestrictedToExactHttpsOrigins() {
        listOf("https://netflix.com/browse", "https://www.netflix.com/nq/website/memberapi/release", "https://web.prod.cloud.netflix.com/graphql")
            .forEach { assertTrue(it, NetflixOrigins.isApi(it.toHttpUrl())) }
        listOf(
            "http://www.netflix.com/browse", "https://www.netflix.com:8443/", "https://www.netflix.com.evil.example/",
            "https://evilnetflix.com/", "https://api.netflix.com/", "https://images.nflxso.net/",
            "https://www.netflix.com@evil.example/", "https://name:password@www.netflix.com/", "https://www.netfliх.com/",
        ).forEach { assertFalse(it, NetflixOrigins.isApi(it.toHttpUrl())) }
    }

    @Test fun loginNavigationDoesNotExpandToGraphqlCdnOrNonHttpsPages() {
        assertTrue(NetflixOrigins.isLogin("https://www.netflix.com/login"))
        assertTrue(NetflixOrigins.isLogin("https://netflix.com/browse"))
        listOf(null, "javascript:alert(1)", "file:///tmp/session", "https://web.prod.cloud.netflix.com/graphql", "https://www.netflix.com.evil.example/login", "http://www.netflix.com/")
            .forEach { assertFalse(it, NetflixOrigins.isLogin(it)) }
    }

    @Test fun cookieUpdatesRespectOriginPathExpiryAndValuesContainingEquals() {
        val now = 1_700_000_000_000L
        fun cookie(name: String, value: String, host: String = "netflix.com", path: String = "/", expires: Long = now + 60_000) =
            Cookie.Builder().name(name).value(value).domain(host).path(path).expiresAt(expires).secure().build()
        val merged = NetflixOrigins.mergeCookies(
            "NetflixId=old; SecureNetflixId=secure; expires=remove; unchanged=a=b=", listOf(
                cookie("NetflixId", "fresh"), cookie("expires", "", expires = now),
                cookie("external", "bad", host = "evil.example"), cookie("scoped", "bad", path = "/login"),
            ), now,
        )
        val pairs = merged.split("; ").associate { val p = it.split('=', limit = 2); p[0] to p[1] }
        assertEquals(mapOf("NetflixId" to "fresh", "SecureNetflixId" to "secure", "unchanged" to "a=b="), pairs)
    }

    @Test fun requiresBothNonEmptyAuthenticationCookieValues() {
        assertTrue(NetflixOrigins.hasAuthCookies("other=1; NetflixId=valid; SecureNetflixId=also-valid"))
        assertFalse(NetflixOrigins.hasAuthCookies("NetflixId=valid"))
        assertFalse(NetflixOrigins.hasAuthCookies("NetflixId=; SecureNetflixId="))
        assertFalse(NetflixOrigins.hasAuthCookies("NetflixId; SecureNetflixId"))
        assertFalse(NetflixOrigins.hasAuthCookies("notNetflixId=x; SecureNetflixId=y"))
    }

    @Test fun routeDiagnosticsDistinguishMobileLandingWithoutExposingAddresses() {
        val routes = mapOf(
            "/m?token=secret" to "mobile_landing",
            "/pl-en/m" to "mobile_landing",
            "/browse?profile=secret" to "browse",
            "/pl/login?next=secret" to "login",
            "/SwitchProfile?tkn=secret" to "profile_switch",
            "/profiles/manage" to "profiles",
            "/YourAccount" to "account",
            "/watch/12345678?token=secret" to "watch",
            "/title/12345678" to "title",
            "/nq/website/memberapi/release/pathEvaluator" to "falcor",
            "/nq/website/memberapi/release/metadata" to "metadata",
            "/pl" to "landing",
            "/unrecognized/private-value?login=secret" to "netflix_page_other",
        )
        routes.forEach { (path, expected) -> assertEquals(expected, NetflixOrigins.pageTag("https://www.netflix.com$path")) }
        assertEquals("graphql", NetflixOrigins.pageTag("https://web.prod.cloud.netflix.com/graphql"))
        assertEquals("untrusted", NetflixOrigins.pageTag("https://netflix.com.evil.example/browse"))
        assertEquals("untrusted", NetflixOrigins.pageTag("https://secret:password@www.netflix.com/login"))
        assertEquals("untrusted", NetflixOrigins.pageTag(null as String?))
    }
}
