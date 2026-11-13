package pl.kyvaith.velvet.playback

import android.net.Uri
import androidx.media3.common.DrmInitData
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import org.json.JSONArray
import org.json.JSONObject
import pl.kyvaith.velvet.diagnostics.AppLog
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.IdentityHashMap
import java.util.UUID

/** Each license challenge is paired with the actual Media3 Widevine session that created it. */
@UnstableApi
internal class DrmRequestSessions {
    private val sessions = IdentityHashMap<ExoMediaDrm.KeyRequest, String>()

    @Synchronized
    fun remember(request: ExoMediaDrm.KeyRequest, scope: ByteArray) {
        if (sessions.size >= 128) throw NetflixPlaybackException(PlaybackStage.LICENSE, message = "Zbyt wiele aktywnych żądań licencji DRM.")
        sessions[request] = DeviceDrmSessionId.encode(scope)
    }

    @Synchronized
    fun sessionFor(request: ExoMediaDrm.KeyRequest): String = sessions[request]
        ?: throw NetflixPlaybackException(PlaybackStage.LICENSE, message = "Nie można powiązać żądania licencji z sesją odtwarzacza.")

    @Synchronized
    fun complete(request: ExoMediaDrm.KeyRequest) { sessions.remove(request) }

    @Synchronized
    fun forget(scope: ByteArray) {
        val id = DeviceDrmSessionId.encode(scope)
        sessions.entries.removeAll { it.value == id }
    }

    @Synchronized fun clear() = sessions.clear()
}

internal object DeviceDrmSessionId {
    fun encode(scope: ByteArray): String {
        if (scope.isEmpty()) throw NetflixPlaybackException(PlaybackStage.LICENSE, message = "Widevine zwrócił pusty identyfikator sesji odtwarzacza.")
        // Widevine normally supplies a textual session ID (the same bytes used by Kodi).
        // Binary IDs remain lossless and stable through Base64; no fabricated fixed ID is used.
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(scope)).toString()
        } catch (_: Exception) { null }
        return if (text != null && text.all { it.code in 0x20..0x7e }) text else MslEncoding.encode(scope)
    }
}

/** Preserve the same playback xid and the real player's challenge/session pair. */
internal fun licenseRequestParams(
    challenge: ByteArray,
    sessionId: String,
    xid: String,
    context: PlaybackContext,
    clientTime: Long = System.currentTimeMillis() / 1000,
): JSONArray = JSONArray().put(JSONObject().put("drmSessionId", sessionId).put("clientTime", clientTime)
    .put("challengeBase64", MslEncoding.encode(challenge)).put("xid", xid)
    .put("clientVersion", context.clientVersion).put("platform", context.browserVersion)
    .put("osVersion", context.osVersion).put("osName", context.osName))

@UnstableApi
internal class SessionTrackingMediaDrm(
    private val delegate: ExoMediaDrm,
    private val requests: DrmRequestSessions,
) : ExoMediaDrm by delegate {
    override fun getKeyRequest(
        scope: ByteArray,
        schemeDatas: List<DrmInitData.SchemeData>?,
        keyType: Int,
        optionalParameters: HashMap<String, String>?,
    ): ExoMediaDrm.KeyRequest {
        val operationId = AppLog.nextOperationId()
        val started = System.nanoTime()
        AppLog.info("drm", "key_request_start", mapOf("operationId" to operationId))
        try {
            val request = delegate.getKeyRequest(scope, schemeDatas, keyType, optionalParameters)
            requests.remember(request, scope)
            AppLog.info("drm", "key_request_complete", mapOf("operationId" to operationId,
                "durationMs" to (System.nanoTime() - started) / 1_000_000,
                "challengeBytes" to request.data.size, "sessionIdBytes" to scope.size,
                "sessionIdEncoding" to if (scope.all { it.toInt() in 0x20..0x7e }) "text" else "base64"))
            AppLog.info("drm", "challenge_structure",
                WidevineChallengeDiagnostics.fields(request.data) + ("operationId" to operationId))
            return request
        } catch (error: Exception) {
            AppLog.error("drm", "key_request_failed", error, mapOf("operationId" to operationId,
                "durationMs" to (System.nanoTime() - started) / 1_000_000))
            // In particular preserve NotProvisionedException so Media3 can provision the real CDM.
            throw error
        }
    }

    override fun closeSession(sessionId: ByteArray) {
        requests.forget(sessionId)
        delegate.closeSession(sessionId)
    }
}

@UnstableApi
internal class NetflixDrmCallback(
    private val msl: NetflixMslSession,
    private val transport: PlaybackHttp,
    private val requests: DrmRequestSessions,
    private val licenseOperation: String,
    private val xid: String,
    private val nativePlayback: Boolean = false,
) : MediaDrmCallback {
    private data class LicenseRecord(val sessionId: String, val releaseOperation: String)
    private val licenses = linkedMapOf<String, LicenseRecord>()
    private var closed = false

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray = callback(PlaybackStage.PROVISIONING) {
        ensureWidevine(uuid)
        transport.provision(request.defaultUrl, request.data)
    }

    @Synchronized
    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray = callback(PlaybackStage.LICENSE) {
        ensureWidevine(uuid)
        if (closed) throw NetflixPlaybackException(PlaybackStage.LICENSE, message = "Sesja licencji została zamknięta.")
        val sessionId = requests.sessionFor(request)
        if (request.data.isEmpty()) throw NetflixPlaybackException(PlaybackStage.LICENSE, message = "Widevine nie wygenerował żądania licencji.")
        AppLog.info("license", "challenge_ready", mapOf("challengeBytes" to request.data.size))
        val result = if (nativePlayback) {
            msl.exchangeNativePlayback(NativePlaybackOperation.LICENSE, licenseOperation,
                NativePlaybackRequests.license(request.data, xid), PlaybackStage.LICENSE)
        } else {
            msl.exchange(msl.endpoint(NetflixMslSession.LICENSE_ENDPOINT, "license"), licenseOperation,
                licenseRequestParams(request.data, sessionId, xid, msl.context), PlaybackStage.LICENSE, "drmSessionId")
        }
        val response = (if (nativePlayback) result as? JSONObject else when (result) {
            is JSONArray -> result.optJSONObject(0)
            is JSONObject -> result
            else -> null
        }) ?: throw NetflixPlaybackException(PlaybackStage.LICENSE, message = "Netflix nie zwrócił odpowiedzi licencyjnej.")
        MslWire.throwServiceError(response, PlaybackStage.LICENSE)
        val returnedSession = response.optString("drmSessionId")
        if (!nativePlayback && returnedSession.isNotBlank() && returnedSession != sessionId) throw NetflixPlaybackException(
            PlaybackStage.LICENSE, message = "Licencja Netflix jest przypisana do innej sesji odtwarzacza.",
        )
        val encoded = response.optString("licenseResponseBase64")
        if (encoded.isBlank()) throw NetflixPlaybackException(PlaybackStage.LICENSE, message = "Netflix nie udostępnił licencji dla tego materiału i urządzenia.")
        val license = if (nativePlayback) NativePlaybackRequests.licenseResponse(response) else MslEncoding.decode(encoded)
        if (license.isEmpty()) throw NetflixPlaybackException(PlaybackStage.LICENSE, message = "Netflix zwrócił pustą licencję Widevine.")
        val releaseOperation = response.optJSONObject("links")?.optJSONObject("releaseLicense")?.optString("href").orEmpty()
        if (!nativePlayback && releaseOperation.isNotBlank()) licenses[sessionId] = LicenseRecord(sessionId, releaseOperation)
        requests.complete(request)
        AppLog.info("license", "license_received", mapOf("licenseBytes" to license.size))
        license
    }

    // Native PDS playback events are a different contract. Do not send the legacy WWW bundle
    // through the native router; Media3 closes its actual CDM sessions before this cleanup.
    @Synchronized
    fun releaseLicenses() {
        if (closed) return
        closed = true
        try {
            if (licenses.isNotEmpty()) {
                val params = JSONArray()
                licenses.values.forEach { record ->
                    params.put(JSONObject().put("url", record.releaseOperation)
                        .put("params", JSONObject().put("drmSessionId", record.sessionId).put("xid", xid))
                        .put("echo", "drmSessionId"))
                }
                msl.exchange(msl.endpoint(NetflixMslSession.LICENSE_ENDPOINT, "release/license"), "/bundle",
                    params, PlaybackStage.RELEASE)
            }
        } finally { licenses.clear(); requests.clear() }
    }

    private fun ensureWidevine(uuid: UUID) {
        if (uuid != C.WIDEVINE_UUID) throw NetflixPlaybackException(
            PlaybackStage.LICENSE, message = "Ta usługa odtwarzania wymaga Widevine DRM.",
        )
    }

    private inline fun callback(stage: PlaybackStage, block: () -> ByteArray): ByteArray = try { block() }
    catch (error: Exception) {
        AppLog.error(stage.name.lowercase(java.util.Locale.ROOT), "drm_callback_failed", error,
            mapOf("serviceCode" to (error as? NetflixPlaybackException)?.serviceCode))
        val safe = error as? NetflixPlaybackException ?: NetflixPlaybackException(stage, message = "Nie udało się przygotować licencji odtwarzania Netflix.", retryable = false)
        val uri = Uri.parse("https://www.netflix.com/msl-license")
        throw MediaDrmCallbackException(DataSpec(uri), uri, emptyMap(), 0, safe)
    }
}
