package pl.kyvaith.velvet.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Account values come from this user's session; playback ESN can identify this device's own client. */
data class PlaybackContext(
    val cookieHeader: String,
    val authUrl: String,
    val esn: String,
    val profileGuid: String,
    val userAgent: String,
    val clientVersion: String = "",
    val uiVersion: String = "",
    val browserVersion: String = "",
    val osName: String = "",
    val osVersion: String = "",
    val buildIdentifier: String = "",
    val locale: String = "pl-PL",
) {
    // Kotlin's generated data-class toString would disclose session cookies.
    override fun toString(): String = "PlaybackContext(redacted)"
}

fun interface NetflixSessionProvider {
    fun playbackContext(): PlaybackContext
    /** Apply only to the same account generation/profile that created this playback. */
    fun updateCookies(cookies: List<Cookie>) = Unit
    /** Opaque owner tokens from this client's native session; never a password or web token. */
    fun loadNativeAuthentication(): String = ""
    /** The provider must recheck account/profile ownership atomically with this write. */
    fun saveNativeAuthentication(value: String) = Unit
}

data class PlaybackRequest(val videoId: String, val title: String, val resumePositionMs: Long = 0)

data class SubtitleTrack(
    val id: String,
    val language: String,
    val label: String,
    val forced: Boolean,
    val hearingImpaired: Boolean,
)

enum class PlaybackStage { SESSION, PROVISIONING, KEY_EXCHANGE, AUTHENTICATION, MANIFEST, LICENSE, MEDIA, RELEASE }

/** Messages contain no response bodies, cookies, DRM challenges, tokens or stream URLs. */
class NetflixPlaybackException(
    val stage: PlaybackStage,
    val serviceCode: String? = null,
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean? = null,
    val mslResponseCode: Int? = null,
    /** Error category for token invalidation; never an instruction to open a password form. */
    val authenticationRejected: Boolean = false,
) : IOException(message, cause)

/**
 * Owns one Netflix playback and its separate MSL session. The player owns its own Widevine
 * session. Release the player first, then call close(); close is idempotent and runs on IO.
 * Stream URLs and the MPD stay in memory; this object deliberately has no data-class toString.
 */
@UnstableApi
class PreparedPlayback internal constructor(
    val manifest: String,
    val licenseCallback: MediaDrmCallback,
    val request: PlaybackRequest,
    val durationMs: Long,
    val subtitles: List<SubtitleTrack>,
    private val sourceFactory: () -> MediaSource,
    private val release: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    fun createMediaSource(): MediaSource {
        check(!closed.get()) { "Sesja odtwarzania została zamknięta." }
        return sourceFactory()
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (closed.compareAndSet(false, true)) release()
    }
}
