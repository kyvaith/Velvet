package pl.kyvaith.velvet.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackOwnerTest {
    private val original = PlaybackContext("NetflixId=old", "auth", "WEB-A", "profile-a", "ua")

    @Test fun `rotating cookies preserves playback ownership for the same account and profile`() {
        PlaybackOwner(4, original)
            .requireOwner(4, original.copy(cookieHeader="NetflixId=rotated", authUrl="new-auth"))
    }

    @Test fun `logout relogin profile switch and browser identity changes invalidate playback ownership`() {
        val owner = PlaybackOwner(4, original)
        val replacements = listOf(5L to original, 4L to original.copy(profileGuid="profile-b"),
            4L to original.copy(esn="WEB-B"))
        replacements.forEach { (generation, context) ->
            try { owner.requireOwner(generation, context); fail("Stale playback owner was accepted") }
            catch (failure: NetflixPlaybackException) {
                assertEquals(PlaybackStage.SESSION, failure.stage)
                assertFalse(failure.authenticationRejected)
                assertEquals(false, failure.retryable)
            }
        }
    }

    @Test fun `playback owner string never exposes account or browser identity`() {
        assertEquals("PlaybackOwner(redacted)", PlaybackOwner(4, original).toString())
    }
}
