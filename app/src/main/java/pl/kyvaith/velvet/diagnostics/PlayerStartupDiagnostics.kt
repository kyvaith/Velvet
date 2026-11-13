// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.diagnostics

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * Observes startup on the player's application thread. Never retains a URI, headers, Format,
 * DataSpec, exception, or title. The existing UI timer calls tick; this owns no background work.
 */
@UnstableApi
internal class PlayerStartupDiagnostics : AnalyticsListener {
    private data class PendingLoad(val startedAtMs: Long, val fields: Map<String, Any?>)

    private val operationId = AppLog.nextOperationId()
    private val startedAtMs = SystemClock.elapsedRealtime()
    private val window = StartupDiagnosticWindow()
    private val active = linkedMapOf<Long, PendingLoad>()
    private var loadCount = 0L
    private var completedLoadCount = 0L
    private var failedLoadCount = 0L
    private var canceledLoadCount = 0L
    private var bufferingSinceMs: Long? = null
    private var waitSnapshots = 0
    private var renderedFirstFrame = false
    private var closed = false

    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData, retryCount: Int) {
        if (closed) return
        val taskId = loadEventInfo.loadTaskId
        if (retryCount == 0) {
            loadCount++
            val fields = loadFields(loadEventInfo, mediaLoadData)
            // A player normally has only a handful of concurrent loads. Bound even abnormal input.
            if (active.size < 64) active[taskId] = PendingLoad(loadEventInfo.elapsedRealtimeMs,
                fields - setOf("responseBytes", "durationMs"))
            if (window.beginLoad(taskId)) AppLog.info("player_load", "started", fields)
        }
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData) {
        if (closed) return
        completedLoadCount++
        active.remove(loadEventInfo.loadTaskId)
        if (window.finishLoad(loadEventInfo.loadTaskId)) {
            AppLog.info("player_load", "completed", loadFields(loadEventInfo, mediaLoadData))
        }
    }

    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData) {
        if (closed) return
        canceledLoadCount++
        active.remove(loadEventInfo.loadTaskId)
        if (window.finishLoad(loadEventInfo.loadTaskId)) {
            AppLog.info("player_load", "canceled", loadFields(loadEventInfo, mediaLoadData))
        }
    }

    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        if (closed) return
        failedLoadCount++
        if (wasCanceled) {
            active.remove(loadEventInfo.loadTaskId)
            window.finishLoad(loadEventInfo.loadTaskId)
        }
        if (window.allowError()) {
            val status = generateSequence<Throwable>(error) { it.cause }.take(6)
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            AppLog.error("player_load", "failed", error, loadFields(loadEventInfo, mediaLoadData) + mapOf(
                "httpStatus" to status, "outcome" to if (wasCanceled) "canceled" else "retry_or_fail"))
        }
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        if (closed || !window.allowTracks()) return
        for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            val groups = tracks.groups.filter { it.type == type }
            val selected = groups.flatMap { group ->
                (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) }
            }
            val codecs = selected.map(::codec).distinct()
            val drmCount = selected.count { (it.drmInitData?.schemeDataCount ?: 0) > 0 }
            AppLog.info("player", "startup_tracks", mapOf(
                "operationId" to operationId, "trackType" to trackType(type),
                "trackCount" to groups.sumOf { it.length }, "selectedTrackCount" to selected.size,
                "supportedTrackCount" to groups.sumOf { group -> (0 until group.length).count { group.isTrackSupported(it) } },
                "drmTrackCount" to drmCount, "hasDrmInitData" to (drmCount > 0),
                "codec" to when (codecs.size) { 0 -> "none"; 1 -> codecs.single(); else -> "mixed" },
            ))
        }
    }

    override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
        if (closed || !window.allowFormat()) return
        AppLog.info("player", "startup_format", formatFields(mediaLoadData) + ("operationId" to operationId))
    }

    override fun onDrmSessionAcquired(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (closed || !window.allowDrmEvent()) return
        AppLog.info("player_drm", "session_acquired", mapOf("operationId" to operationId, "outcome" to when (state) {
            DrmSession.STATE_OPENING -> "opening"
            DrmSession.STATE_OPENED -> "opened"
            DrmSession.STATE_OPENED_WITH_KEYS -> "keys_loaded"
            DrmSession.STATE_ERROR -> "error"
            DrmSession.STATE_RELEASED -> "released"
            else -> "unknown"
        }))
    }

    override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime) {
        if (closed || !window.allowDrmEvent()) return
        AppLog.info("player_drm", "keys_loaded", mapOf("operationId" to operationId))
    }

    override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) {
        if (!closed && window.allowError()) AppLog.error("player_drm", "session_error", error,
            mapOf("operationId" to operationId))
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (closed) return
        if (state == Player.STATE_READY && window.markReady()) {
            AppLog.info("player", "startup_ready", summaryFields())
        }
        bufferingSinceMs = if (state == Player.STATE_BUFFERING) SystemClock.elapsedRealtime() else null
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (closed || renderedFirstFrame) return
        renderedFirstFrame = true
        AppLog.info("player", "first_frame", mapOf("operationId" to operationId,
            "durationMs" to (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0)))
    }

    /** Two snapshots can expose a stuck load even when the user closes before the network timeout. */
    fun tick(player: Player) {
        if (closed || window.reachedReady || waitSnapshots >= 2) return
        val since = bufferingSinceMs ?: return
        val waitingMs = SystemClock.elapsedRealtime() - since
        if (waitingMs < if (waitSnapshots == 0) 10_000 else 25_000) return
        waitSnapshots++
        AppLog.warn("player", "startup_wait", summaryFields() + playerFields(player) + ("durationMs" to waitingMs))
        logPending("pending")
    }

    /** Called before removing the listener/releasing the player, so a canceled startup is preserved. */
    fun close(player: Player) {
        if (closed) return
        closed = true
        AppLog.info("player", "startup_closed", summaryFields() + playerFields(player))
        logPending("pending_at_close")
        active.clear()
    }

    private fun logPending(event: String) {
        val now = SystemClock.elapsedRealtime()
        active.values.take(8).forEach { load ->
            AppLog.info("player_load", event, load.fields + ("durationMs" to (now - load.startedAtMs).coerceAtLeast(0)))
        }
    }

    private fun summaryFields(): Map<String, Any?> = mapOf(
        "operationId" to operationId, "durationMs" to (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0),
        "activeLoadCount" to active.size, "loadCount" to loadCount, "completedLoadCount" to completedLoadCount,
        "failedLoadCount" to failedLoadCount, "canceledLoadCount" to canceledLoadCount,
        "suppressedCount" to window.suppressedCount,
    )

    private fun playerFields(player: Player): Map<String, Any?> = mapOf(
        "isLoading" to player.isLoading, "playWhenReady" to player.playWhenReady,
        "bufferedDurationMs" to player.totalBufferedDuration.coerceAtLeast(0),
        "playerState" to when (player.playbackState) {
            Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "unknown"
        },
    )

    private fun loadFields(info: LoadEventInfo, data: MediaLoadData): Map<String, Any?> =
        formatFields(data) + mapOf(
            "operationId" to operationId, "loadTaskId" to info.loadTaskId,
            "dataType" to when (data.dataType) {
                // Media3 can merge the adjacent initialization and SIDX byte ranges in one request.
                C.DATA_TYPE_MEDIA_INITIALIZATION -> if (info.dataSpec.position > 0) "index" else "init_or_index"
                C.DATA_TYPE_MEDIA -> "media"
                C.DATA_TYPE_MANIFEST -> "manifest"
                C.DATA_TYPE_DRM -> "drm"
                else -> "other"
            },
            "durationMs" to info.loadDurationMs, "responseBytes" to info.bytesLoaded,
            "rangeLength" to info.dataSpec.length,
        )

    private fun formatFields(data: MediaLoadData): Map<String, Any?> = mapOf(
        "trackType" to trackType(data.trackType), "codec" to codec(data.trackFormat),
        "hasDrmInitData" to ((data.trackFormat?.drmInitData?.schemeDataCount ?: 0) > 0),
    )

    private fun trackType(type: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "text"
        else -> "unknown"
    }

    private fun codec(format: Format?): String = startupDiagnosticCodec(format?.sampleMimeType)
}

/** Unknown data is classified, never copied to the journal, even if it looks like a valid token. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun startupDiagnosticCodec(mimeType: String?): String = when (mimeType) {
    MimeTypes.VIDEO_H264 -> "h264"; MimeTypes.VIDEO_H265 -> "hevc"; MimeTypes.VIDEO_VP9 -> "vp9"
    MimeTypes.VIDEO_AV1 -> "av1"; MimeTypes.VIDEO_DOLBY_VISION -> "dolby_vision"
    MimeTypes.AUDIO_AAC -> "aac"; MimeTypes.AUDIO_AC3 -> "ac3"; MimeTypes.AUDIO_E_AC3 -> "eac3"
    MimeTypes.AUDIO_E_AC3_JOC -> "eac3"; MimeTypes.TEXT_VTT -> "webvtt"; MimeTypes.APPLICATION_TTML -> "ttml"
    null -> "unknown"; else -> "other"
}
