// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import org.json.JSONArray
import org.json.JSONObject
import pl.kyvaith.velvet.diagnostics.AppLog

/** Original Kotlin implementation of the Android playback wire contract; see NATIVE_PLAYBACK_0_1_11.md. */
internal object NativePlaybackRequests {
    const val MANIFEST_PATH = "/licensedManifest"

    /** Before the player opens its DRM session, request metadata without a fabricated challenge. */
    fun manifest(videoId: String, device: NativeAndroidProfile.Device, profiles: List<String>): JSONArray {
        if (!videoId.matches(Regex("[0-9]{1,20}")) || (videoId.toLongOrNull() ?: 0L) <= 0L ||
            device.api < 28 || profiles.isEmpty() || profiles.size > 128 ||
            profiles.any { !it.matches(Regex("[A-Za-z0-9._-]{1,100}")) }) {
            fail(PlaybackStage.MANIFEST, "Nieprawidłowe parametry natywnego manifestu Netflix.")
        }
        val item = JSONObject()
            .put("viewableId", videoId)
            .put("manifestVersion", "v2")
            .put("method", "licensedManifest")
            .put("flavor", "STANDARD")
            .put("cellularCap", "auto")
            .put("profiles", JSONArray(profiles.distinct()))
            .put("drmType", "widevine")
            .put("useHttpsStreams", true)
            .put("supportsWatermark", true)
            .put("supportsPreReleasePin", true)
            .put("requestEligibleABTests", true)
            .put("supportsUnequalizedDownloadables", true)
            .put("isBranching", false)
            .put("maxSupportedLanguages", 2)
            .put("osName", "android")
            .put("osVersion", device.api.toString())
            .put("application", "samurai")
            .put("clientVersion", NativeAndroidProfile.APP_VERSION)
            .put("uiVersion", NativeAndroidProfile.APP_VERSION)
            .put("uiPlatform", "android")
            .put("player", "streaming")
        // ro.board.platform is not Build.MODEL or ro.hardware. If unavailable, omit it.
        device.boardPlatform.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
            ?.let { item.put("hardware", it) }
        return JSONArray().put(item)
    }

    /** Native license params are an object. The challenge already identifies its real CDM session. */
    fun license(challenge: ByteArray, xid: String, clientTimeSeconds: Long = System.currentTimeMillis() / 1000): JSONObject {
        if (challenge.isEmpty() || challenge.size > MAX_MSL_BYTES / 2 ||
            !xid.matches(Regex("[0-9]{1,24}")) || clientTimeSeconds <= 0L) {
            fail(PlaybackStage.LICENSE, "Nieprawidłowe parametry natywnej licencji Netflix.")
        }
        return JSONObject().put("clientTime", clientTimeSeconds)
            .put("challengeBase64", MslEncoding.encode(challenge)).put("xid", xid)
    }

    /** Native metadata (including providerSessionToken) is separate from the CDM's response bytes. */
    fun licenseResponse(result: Any): ByteArray {
        val response = result as? JSONObject
            ?: fail(PlaybackStage.LICENSE, "Netflix nie zwrócił obiektu natywnej licencji.")
        MslWire.throwServiceError(response, PlaybackStage.LICENSE)
        val encoded = response.opt("licenseResponseBase64") as? String
            ?: fail(PlaybackStage.LICENSE, "Netflix nie udostępnił natywnej licencji Widevine.")
        if (encoded.isBlank() || encoded.length > MAX_MSL_BYTES) fail(PlaybackStage.LICENSE, "Nieprawidłowy rozmiar natywnej licencji Widevine.")
        val bytes = try { MslEncoding.decode(encoded) }
        catch (_: Exception) { fail(PlaybackStage.LICENSE, "Nieprawidłowy format natywnej licencji Widevine.") }
        if (bytes.isEmpty()) fail(PlaybackStage.LICENSE, "Netflix zwrócił pustą licencję Widevine.")
        return bytes
    }

    fun envelope(operation: NativePlaybackOperation, operationPath: String, params: Any, locale: String): JSONObject {
        val stage = if (operation == NativePlaybackOperation.MANIFEST) PlaybackStage.MANIFEST else PlaybackStage.LICENSE
        if (operationPath.length !in 2..8192 || !operationPath.startsWith('/') || operationPath.startsWith("//") ||
            operationPath.contains("://") || operationPath.any { it.code !in 0x21..0x7e } ||
            locale.isBlank() || locale.length > 128 || locale.any(Char::isISOControl)) {
            fail(stage, "Nieprawidłowa operacja natywnego odtwarzania Netflix.")
        }
        when (operation) {
            NativePlaybackOperation.MANIFEST -> if (operationPath != MANIFEST_PATH || params !is JSONArray || params.length() != 1) {
                fail(stage, "Nieprawidłowy format żądania natywnego manifestu Netflix.")
            }
            NativePlaybackOperation.LICENSE -> if (params !is JSONObject) {
                fail(stage, "Nieprawidłowy format żądania natywnej licencji Netflix.")
            }
        }
        return JSONObject().put("version", 2).put("url", operationPath)
            .put("languages", JSONArray().put(locale)).put("params", params)
    }

    /** A batched response must contain exactly one entry for the requested movie. */
    fun selectManifest(result: Any, videoId: String): JSONObject {
        val entries = result as? JSONArray
            ?: fail(PlaybackStage.MANIFEST, "Netflix nie zwrócił listy natywnych manifestów.")
        if (entries.length() !in 1..64) fail(PlaybackStage.MANIFEST, "Nieprawidłowa liczba manifestów Netflix.")
        var selected: JSONObject? = null
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index)
                ?: fail(PlaybackStage.MANIFEST, "Nieprawidłowy element natywnego manifestu Netflix.")
            if (item.optString("movieId") == videoId) {
                if (selected != null) fail(PlaybackStage.MANIFEST, "Netflix zwrócił niejednoznaczny manifest materiału.")
                selected = item
            }
        }
        AppLog.info("manifest", "native_manifest_selected", mapOf("manifestCount" to entries.length(),
            "matchedParent" to (selected != null), "manifestContract" to "native_android_v1"))
        return selected ?: fail(PlaybackStage.MANIFEST, "Netflix nie zwrócił manifestu wybranego materiału.")
    }

    private fun fail(stage: PlaybackStage, message: String): Nothing =
        throw NetflixPlaybackException(stage, message = message, retryable = false)
}
