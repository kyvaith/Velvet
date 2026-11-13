package pl.kyvaith.velvet.playback

import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test

class NativePlaybackSessionProviderTest {
    private val nativeId = "NFANDROID1-PRV-T-VELVETTEST-1234-" + "A".repeat(64)

    @Test fun `native identity is separate and cookie refresh retains the captured playback context`() {
        val source = GuardedSource(context())
        val provider = provider(source)
        val initial = provider.playbackContext()
        val bound = BoundPlaybackSession(initial, provider)
        assertEquals(nativeId, initial.esn)
        assertEquals("NFCDCH-source-browser", source.value.esn)
        assertEquals(source.value.profileGuid, initial.profileGuid)
        assertEquals(KodiAndroidPlaybackProfile.USER_AGENT, initial.userAgent)
        assertEquals("SyntheticBrowser/1", source.value.userAgent)
        assertEquals(source.value.authUrl, initial.authUrl)
        assertEquals(source.value.browserVersion, initial.browserVersion)
        assertEquals(source.value.osName, initial.osName)
        assertEquals(source.value.osVersion, initial.osVersion)
        source.value = source.value.copy(cookieHeader = "NetflixId=renewed; SecureNetflixId=renewed-secure")
        val refreshed = bound.current()
        assertEquals(nativeId, refreshed.esn)
        assertEquals(source.value.cookieHeader, refreshed.cookieHeader)
        assertEquals(initial.clientVersion, refreshed.clientVersion)
        assertEquals(initial.userAgent, refreshed.userAgent)
        assertFalse(refreshed.toString().contains(nativeId))
        assertFalse(refreshed.toString().contains("renewed-secure"))
    }

    @Test fun `browser identity changes are detected even though native identity is unchanged`() {
        val source = GuardedSource(context())
        val provider = provider(source)
        val bound = BoundPlaybackSession(provider.playbackContext(), provider)
        source.value = source.value.copy(esn = "NFCDCH-new-browser")
        assertSessionChanged { bound.current() }
        assertSessionChanged { provider.updateCookies(listOf(cookie())) }
        assertSessionChanged { provider.loadNativeAuthentication() }
        assertSessionChanged { provider.saveNativeAuthentication("late-owner-tokens") }
        assertEquals(0, source.writes)
        assertEquals(0, source.nativeReads)
        assertEquals(0, source.nativeWrites)
    }

    @Test fun `profile changes cannot reuse the native playback context or apply its cookies`() {
        val source = GuardedSource(context())
        val provider = provider(source)
        source.value = source.value.copy(profileGuid = "other-profile")
        assertSessionChanged { provider.playbackContext() }
        assertSessionChanged { provider.updateCookies(listOf(cookie())) }
        assertSessionChanged { provider.loadNativeAuthentication() }
        assertSessionChanged { provider.saveNativeAuthentication("late-owner-tokens") }
        assertEquals(0, source.writes)
        assertEquals(0, source.nativeReads)
        assertEquals(0, source.nativeWrites)
    }

    @Test fun `logout generation is checked even when profile and both identity strings match`() {
        val source = GuardedSource(context())
        val provider = provider(source)
        source.generation++
        assertSessionChanged { provider.playbackContext() }
        assertSessionChanged { provider.updateCookies(listOf(cookie())) }
        assertSessionChanged { provider.loadNativeAuthentication() }
        assertSessionChanged { provider.saveNativeAuthentication("late-owner-tokens") }
        assertEquals(0, source.writes)
        assertEquals(0, source.nativeReads)
        assertEquals(0, source.nativeWrites)
    }

    @Test fun `renewed cookies are delegated to the original guarded account provider`() {
        val source = GuardedSource(context())
        val provider = provider(source)
        val cookies = listOf(cookie())
        val initial = source.value
        provider.updateCookies(cookies)
        assertEquals(1, source.writes)
        assertSame(cookies, source.lastCookies)
        assertEquals(initial.esn, source.value.esn)
        assertEquals(initial.profileGuid, source.value.profileGuid)
        assertEquals(nativeId, provider.playbackContext().esn)
    }

    @Test fun `atomic source write still rejects logout after the preliminary ownership check`() {
        val source = GuardedSource(context())
        val provider = provider(source)
        val before = source.value
        source.beforeWrite = { source.generation++ }
        assertSessionChanged { provider.updateCookies(listOf(cookie())) }
        assertEquals(0, source.writes)
        assertEquals(before.cookieHeader, source.value.cookieHeader)
    }

    @Test fun `native authentication is saved loaded and cleared through the original guarded account`() {
        val source = GuardedSource(context())
        val provider = provider(source)
        val bound = BoundPlaybackSession(provider.playbackContext(), provider)
        val initial = source.value

        bound.saveNativeAuthentication("synthetic-native-owner-token-pair")

        assertEquals("synthetic-native-owner-token-pair", bound.loadNativeAuthentication())
        assertEquals(1, source.nativeWrites)
        assertEquals(1, source.nativeReads)
        assertEquals(initial, source.value)
        assertEquals(nativeId, bound.current().esn)
        bound.saveNativeAuthentication("")
        assertEquals("", bound.loadNativeAuthentication())
        assertEquals(2, source.nativeWrites)
        assertEquals(2, source.nativeReads)
    }

    @Test fun `atomic native authentication read rejects owner changes after the preliminary check`() {
        for (changeOwner in ownerChanges()) {
            val source = GuardedSource(context())
            val provider = provider(source)
            source.nativeAuthentication = "synthetic-old-owner-pair"
            source.beforeNativeRead = { changeOwner(source) }

            assertSessionChanged { provider.loadNativeAuthentication() }

            assertEquals(0, source.nativeReads)
            assertEquals("synthetic-old-owner-pair", source.nativeAuthentication)
        }
    }

    @Test fun `atomic native authentication save rejects owner changes after the preliminary check`() {
        for (changeOwner in ownerChanges()) {
            val source = GuardedSource(context())
            val provider = provider(source)
            source.nativeAuthentication = "synthetic-existing-owner-pair"
            source.beforeNativeWrite = { changeOwner(source) }

            assertSessionChanged { provider.saveNativeAuthentication("synthetic-late-owner-pair") }

            assertEquals(0, source.nativeWrites)
            assertEquals("synthetic-existing-owner-pair", source.nativeAuthentication)
        }
    }

    @Test fun `browser or malformed native identity fails without exposing the supplied value`() {
        for (invalid in listOf("NFCDCH-private-browser-identifier", "NFANDROID1-short", nativeId + "\n")) {
            val source = GuardedSource(context())
            val error = assertThrows(NetflixPlaybackException::class.java) {
                NativePlaybackSessionProvider(BoundPlaybackSession(source.value, source), invalid)
            }
            assertEquals(PlaybackStage.SESSION, error.stage)
            assertEquals(false, error.retryable)
            assertFalse(error.message.orEmpty().contains(invalid))
        }
    }

    private fun provider(source: GuardedSource) =
        NativePlaybackSessionProvider(BoundPlaybackSession(source.value, source), nativeId)

    private fun ownerChanges(): List<(GuardedSource) -> Unit> = listOf(
        { it.generation++ },
        { it.value = it.value.copy(profileGuid = "changed-profile") },
        { it.value = it.value.copy(esn = "changed-web-identity") },
    )

    private fun assertSessionChanged(action: () -> Unit) {
        val error = assertThrows(NetflixPlaybackException::class.java) { action() }
        assertEquals(PlaybackStage.SESSION, error.stage)
        assertEquals(false, error.retryable)
    }

    private fun context() = PlaybackContext(
        cookieHeader = "NetflixId=original; SecureNetflixId=original-secure",
        authUrl = "synthetic-auth", esn = "NFCDCH-source-browser", profileGuid = "profile",
        userAgent = "SyntheticBrowser/1", clientVersion = "client-1",
        browserVersion = "server-browser-version", osName = "server-platform", osVersion = "server-os-version",
    )

    private fun cookie() = Cookie.Builder().name("NetflixId").value("renewed")
        .hostOnlyDomain("www.netflix.com").path("/").secure().httpOnly().build()

    /** Models the atomic generation contract implemented by StoredPlaybackSession/SessionStore. */
    private class GuardedSource(var value: PlaybackContext) : NetflixSessionProvider {
        private val ownerProfile = value.profileGuid
        private val ownerEsn = value.esn
        var generation = 0
        var writes = 0
        var lastCookies: List<Cookie>? = null
        var beforeWrite: () -> Unit = {}
        var nativeAuthentication = ""
        var nativeReads = 0
        var nativeWrites = 0
        var beforeNativeRead: () -> Unit = {}
        var beforeNativeWrite: () -> Unit = {}

        override fun playbackContext(): PlaybackContext {
            requireGeneration()
            return value
        }

        override fun updateCookies(cookies: List<Cookie>) {
            beforeWrite()
            requireOwner()
            writes++
            lastCookies = cookies
            value = value.copy(cookieHeader = "NetflixId=renewed; SecureNetflixId=original-secure")
        }

        override fun loadNativeAuthentication(): String {
            beforeNativeRead()
            requireOwner()
            nativeReads++
            return nativeAuthentication
        }

        override fun saveNativeAuthentication(value: String) {
            beforeNativeWrite()
            requireOwner()
            nativeWrites++
            nativeAuthentication = value
        }

        private fun requireOwner() {
            requireGeneration()
            if (value.profileGuid != ownerProfile || value.esn != ownerEsn) {
                throw NetflixPlaybackException(PlaybackStage.SESSION,
                    message = "Synthetic owner changed", retryable = false)
            }
        }

        private fun requireGeneration() {
            if (generation != 0) throw NetflixPlaybackException(PlaybackStage.SESSION,
                message = "Synthetic owner changed", retryable = false)
        }
    }
}
