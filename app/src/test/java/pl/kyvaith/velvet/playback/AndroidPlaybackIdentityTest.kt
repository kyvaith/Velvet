package pl.kyvaith.velvet.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidPlaybackIdentityTest {
    private val suffix = "A".repeat(64)

    @Test fun `native phone profile preserves this installations suffix when leaving Kodi profile`() {
        val storage = MemoryStorage()
        var generated = 0
        val store = AndroidPlaybackIdentityStore(storage, "Google", "Pixel 9 Pro XL") { generated++; suffix }
        assertEquals("NFANDROID1-PRV-T-GOOGLPIXEL=9=PRO=XL-12345-$suffix", store.identityFor("12345", "L1"))
        assertEquals("NFANDROID1-PRV-P-GOOGLPIXEL=9=PRO=XL-12345-$suffix", store.identityForPhone("12345", "L1"))
        assertEquals(1, generated)
        assertEquals(1, storage.writes)
        assertEquals(suffix, storage.value)
    }

    @Test fun `Kodi mobile category uses actual model system and security properties`() {
        assertEquals("NFANDROID1-PRV-T-GOOGLPIXEL=9=PRO=XL-12345-$suffix",
            AndroidPlaybackIdentity.create("Google", "Pixel 9 Pro XL", "12345", "L1", suffix))
        assertEquals("NFANDROID1-PRV-T-L3-GOOGLPIXEL=9=PRO=XL-9876-$suffix",
            AndroidPlaybackIdentity.create("Google", "Pixel 9 Pro XL", "9876", "L3", suffix))
    }

    @Test fun `product normalization has pinned widths and is locale independent`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("NFANDROID1-PRV-T-LG===MI=PHONE-77-$suffix",
                AndroidPlaybackIdentity.create("LG", "mi phone", "77", "L1", suffix))
            assertEquals("NFANDROID1-PRV-T-ABCDE${"Z".repeat(45)}-77-$suffix",
                AndroidPlaybackIdentity.create("abcdef", "z".repeat(60), "77", "L1", suffix))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `missing or unsupported native properties have no fallback identity`() {
        for (systemId in listOf("", "0", "-1", "01", "123 45", "4294967296", "private-system-value")) {
            assertInvalid { AndroidPlaybackIdentity.create("Google", "Pixel", systemId, "L1", suffix) }
        }
        for (level in listOf("", "L2", "UNKNOWN", "private-level-value")) {
            assertInvalid { AndroidPlaybackIdentity.create("Google", "Pixel", "12345", level, suffix) }
        }
        for (missing in listOf("", "   ", "unknown")) {
            assertInvalid { AndroidPlaybackIdentity.create(missing, "Pixel", "12345", "L1", suffix) }
            assertInvalid { AndroidPlaybackIdentity.create("Google", missing, "12345", "L1", suffix) }
        }
        for (invalid in listOf("", "A".repeat(63), "A".repeat(65), "G".repeat(64), "a".repeat(64))) {
            assertInvalid { AndroidPlaybackIdentity.create("Google", "Pixel", "12345", "L1", invalid) }
        }
    }

    @Test fun `installation suffix survives new store instances and native property changes`() {
        val storage = MemoryStorage()
        var generated = 0
        fun store() = AndroidPlaybackIdentityStore(storage, "Google", "Pixel") { generated++; suffix }
        val first = store().identityFor("12345", "L1")
        assertEquals(first, store().identityFor("12345", "L1"))
        assertTrue(store().identityFor("54321", "L3").endsWith("54321-$suffix"))
        assertEquals(1, generated)
        assertEquals(1, storage.writes)
        assertEquals(suffix, storage.value)
    }

    @Test fun `concurrent store instances create one persisted suffix`() {
        val storage = MemoryStorage()
        var generated = 0
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = List(2) { Callable {
                AndroidPlaybackIdentityStore(storage, "Google", "Pixel") {
                    generated++
                    if (generated == 1) suffix else "B".repeat(64)
                }.identityFor("12345", "L1")
            } }
            val values = executor.invokeAll(jobs).map { it.get(2, TimeUnit.SECONDS) }
            assertEquals(values[0], values[1])
            assertEquals(1, generated)
            assertEquals(1, storage.writes)
        } finally { executor.shutdownNow() }
    }

    @Test fun `corrupt persisted identity is reported rather than silently rotated`() {
        val storage = MemoryStorage("private-invalid-persisted-value")
        var generated = false
        val store = AndroidPlaybackIdentityStore(storage, "Google", "Pixel") { generated = true; suffix }
        val failure = failure { store.identityFor("12345", "L1") }
        assertFalse(generated)
        assertEquals(0, storage.writes)
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains("private-invalid-persisted-value"))
        assertEquals(false, failure.retryable)
    }

    @Test fun `storage write failure never returns an ephemeral identity or raw exception`() {
        val storage = object : AndroidIdentityStorage {
            override fun read(): String? = null
            override fun write(value: String) { throw IOException("private-path-and-device-value") }
        }
        val failure = failure {
            AndroidPlaybackIdentityStore(storage, "Google", "Pixel") { suffix }.identityFor("12345", "L1")
        }
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains("private-path-and-device-value"))
        assertEquals(PlaybackStage.KEY_EXCHANGE, failure.stage)
    }

    @Test fun `a write that cannot be read back is not considered persisted`() {
        val storage = object : AndroidIdentityStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        }
        failure { AndroidPlaybackIdentityStore(storage, "Google", "Pixel") { suffix }.identityFor("12345", "L1") }
    }

    @Test fun `invalid native properties do not mint or write an installation suffix`() {
        val storage = MemoryStorage()
        var generated = false
        failure {
            AndroidPlaybackIdentityStore(storage, "Google", "Pixel") { generated = true; suffix }.identityFor("", "L1")
        }
        assertFalse(generated)
        assertEquals(0, storage.writes)
        assertNull(storage.value)
    }

    private class MemoryStorage(var value: String? = null) : AndroidIdentityStorage {
        var writes = 0
        override fun read(): String? = value
        override fun write(value: String) { writes++; this.value = value }
    }

    private fun assertInvalid(block: () -> Unit) {
        try { block(); fail("Expected invalid identity properties") }
        catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("private-")) }
    }

    private fun failure(block: () -> Unit): NetflixPlaybackException {
        try { block(); fail("Expected identity failure") }
        catch (error: NetflixPlaybackException) { return error }
        throw AssertionError("Expected identity failure")
    }
}
