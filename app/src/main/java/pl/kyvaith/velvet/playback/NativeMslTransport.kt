// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

/** Fixed native routes; callers cannot use an NFToken with arbitrary endpoints. */
internal enum class NativeMslOperation { CONSUME_TOKEN, FETCH_CONFIG, FETCH_ACCOUNT }

/** The operation chooses the native HTTP route and public request name, never the MSL body. */
internal enum class NativePlaybackOperation(val requestName: String) {
    MANIFEST("licensedManifest"),
    LICENSE("license"),
}

internal interface NativeMslTransport : MslTransport {
    fun nativePost(
        operation: NativeMslOperation,
        wire: String,
        context: PlaybackContext,
        operationId: Long,
        loginToken: String? = null,
    ): String

    fun playbackPost(
        operation: NativePlaybackOperation,
        wire: String,
        context: PlaybackContext,
        stage: PlaybackStage,
        operationId: Long,
    ): String = throw NetflixPlaybackException(stage,
        message = "Brak obsługi natywnego transportu manifestu i licencji Netflix.", retryable = false)
}
