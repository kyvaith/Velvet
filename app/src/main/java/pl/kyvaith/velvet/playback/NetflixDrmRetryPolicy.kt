package pl.kyvaith.velvet.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import pl.kyvaith.velvet.diagnostics.AppLog
import java.util.Locale

/** Media3's default IOException policy retries a rejected license four times in total. */
@UnstableApi
internal class NetflixDrmRetryPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val failure = findNetflixPlaybackFailure(loadErrorInfo.exception)
        if (failure != null && (failure.retryable == false || failure.serviceCode in setOf("1044", "205064"))) {
            AppLog.info(failure.stage.name.lowercase(Locale.ROOT), "retry_stopped", mapOf(
                "serviceCode" to failure.serviceCode, "attempt" to loadErrorInfo.errorCount,
                "reason" to "request_rejected",
            ))
            return C.TIME_UNSET
        }
        // Network failures, HTTP 5xx and an explicit MSL TRANSIENT_FAILURE retain bounded retries.
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
