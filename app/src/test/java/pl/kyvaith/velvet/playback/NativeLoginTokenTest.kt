package pl.kyvaith.velvet.playback

import org.junit.Assert.*
import org.junit.Test

class NativeLoginTokenTest {
    @Test fun `token is opaque redacted and unavailable after close`() {
        val secret = "synthetic-private-token%2B+=="
        val token = NativeLoginToken(secret)
        assertEquals(secret, token.value())
        assertFalse(token.toString().contains(secret))
        token.close()
        token.close()
        val error = assertThrows(NetflixPlaybackException::class.java) { token.value() }
        assertEquals(PlaybackStage.AUTHENTICATION, error.stage)
        assertFalse(error.message.orEmpty().contains(secret))
        assertNull(error.cause)
    }

    @Test fun `invalid header material is rejected without retaining the supplied value`() {
        for (value in listOf("", "private token", "private\r\nX-Secret: value", "x".repeat(16_385))) {
            val error = assertThrows(NetflixPlaybackException::class.java) { NativeLoginToken(value) }
            assertEquals(PlaybackStage.AUTHENTICATION, error.stage)
            assertEquals(false, error.retryable)
            assertFalse(error.message.orEmpty().contains("private"))
            assertNull(error.cause)
        }
    }
}
