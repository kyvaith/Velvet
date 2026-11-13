// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupDiagnosticWindowTest {
    @Test fun readyStopsNormalLoadsButKeepsTheirPendingCompletionsAndErrors() {
        val window = StartupDiagnosticWindow()
        assertTrue(window.beginLoad(101))
        assertTrue(window.markReady())
        assertFalse(window.markReady())
        assertFalse(window.beginLoad(102))
        assertTrue(window.finishLoad(101))
        assertFalse(window.finishLoad(101))
        assertFalse(window.finishLoad(102))
        assertTrue(window.allowError())
        assertEquals(1L, window.suppressedCount)
    }

    @Test fun retriesDoNotExhaustAdmissionOrCreateDuplicateCompletion() {
        val window = StartupDiagnosticWindow(loadLimit = 2)
        assertTrue(window.beginLoad(10))
        repeat(100) { assertFalse(window.beginLoad(10)) }
        assertTrue(window.beginLoad(11))
        assertFalse(window.beginLoad(12))
        assertTrue(window.finishLoad(10))
        assertFalse(window.finishLoad(10))
        assertTrue(window.finishLoad(11))
        assertEquals(1L, window.suppressedCount)
    }

    @Test fun boundedErrorsAndLongPlaybackKeepCountingSuppression() {
        val window = StartupDiagnosticWindow(loadLimit = 1, errorLimit = 2)
        assertTrue(window.beginLoad(0))
        window.markReady()
        repeat(1000) { assertFalse(window.beginLoad(it + 1L)) }
        assertTrue(window.allowError())
        assertTrue(window.allowError())
        repeat(100) { assertFalse(window.allowError()) }
        assertEquals(1100L, window.suppressedCount)
    }

    @Test fun codecClassificationNeverCopiesUntrustedMetadata() {
        assertEquals("h264", startupDiagnosticCodec("video/avc"))
        assertEquals("unknown", startupDiagnosticCodec(null))
        for (value in listOf("https://cdn.example.test/title?token=private", "private_account_token", "avc1.640028")) {
            assertEquals("other", startupDiagnosticCodec(value))
        }
    }
}
