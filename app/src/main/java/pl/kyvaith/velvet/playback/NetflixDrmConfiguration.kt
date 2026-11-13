/*
 * SPDX-License-Identifier: MIT
 * Public Netflix service certificate from CastagnaIT/plugin.video.netflix player.py,
 * commit de0db546d9b8ba8d79dbc08fe088b91fc3bcacb7 (also used by Breezyslasher/wip).
 * Copyright (C) 2017 Sebastian Golasch; 2018 Caphm. See THIRD_PARTY_NOTICES.md.
 * https://github.com/CastagnaIT/plugin.video.netflix/blob/de0db546d9b8ba8d79dbc08fe088b91fc3bcacb7/resources/lib/navigation/player.py
 */
package pl.kyvaith.velvet.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import pl.kyvaith.velvet.diagnostics.AppLog
import java.util.Base64

/**
 * A published SERVICE certificate containing a public key, not a device certificate or credential.
 * Snapshot: 707 bytes; SHA-256 f17a427fcff8e3c263d9dde8a6fb227b6a22bc4227c7fb82f05b6f639b104a76.
 * The published object was created in 2013 and has no explicit expiry field. Continued publication
 * in upstream does not prove current server acceptance. Validation remains with the native CDM.
 */
internal object NetflixServerCertificate {
    fun bytes(): ByteArray = Base64.getDecoder().decode(ENCODED)

    private const val ENCODED =
        "Cr0CCAMSEOVEukALwQ8307Y2+LVP+0MYh/HPkwUijgIwggEKAoIBAQDm875btoWUbGqQD8eAGuBl" +
        "GY+Pxo8YF1LQR+Ex0pDONMet8EHslcZRBKNQ/09RZFTP0vrYimyYiBmk9GG+S0wB3CRITgweNE15" +
        "cD33MQYyS3zpBd4z+sCJam2+jj1ZA4uijE2dxGC+gRBRnw9WoPyw7D8RuhGSJ95OEtzg3Ho+mEsx" +
        "uE5xg9LM4+Zuro/9msz2bFgJUjQUVHo5j+k4qLWu4ObugFmc9DLIAohL58UR5k0XnvizulOHbMMx" +
        "dzna9lwTw/4SALadEV/CZXBmswUtBgATDKNqjXwokohncpdsWSauH6vfS6FXwizQoZJ9TdjSGC60" +
        "rUB2t+aYDm74cIuxAgMBAAE6EHRlc3QubmV0ZmxpeC5jb20SgAOE0y8yWw2Win6M2/bw7+aqVuQP" +
        "wzS/YG5ySYvwCGQd0Dltr3hpik98WijUODUr6PxMn1ZYXOLo3eED6xYGM7Riza8XskRdCfF8xjj7" +
        "L7/THPbixyn4mULsttSmWFhexzXnSeKqQHuoKmerqu0nu39iW3pcxDV/K7E6aaSr5ID0SCi7KRcL" +
        "9BCUCz1g9c43sNj46BhMCWJSm0mx1XFDcoKZWhpj5FAgU4Q4e6f+S8eX39nf6D6SJRb4ap7Znzn7" +
        "preIvmS93xWjm75I6UBVQGo6pn4qWNCgLYlGGCQCUm5tg566j+/g5jvYZkTJvbiZFwtjMW5njbSR" +
        "wB3W4CrKoyxw4qsJNSaZRTKAvSjTKdqVDXV/U5HK7SaBA6iJ981/aforXbd2vZlRXO/2S+Maa2mH" +
        "ULzsD+S5l4/YGpSt7PnkCe25F+nAovtl/ogZgjMeEdFyd/9YMYjOS4krYmwp3yJ7m9ZzYCQ6I8RQ" +
        "N4x/yLlHG5RH/+WNLNUs6JAZ0fFdCmw="
}

/**
 * Configure exactly the native Media3 instance that will open the playback session. Ownership
 * passes to the caller only after configuration succeeds; failed setup releases its acquisition.
 * Chromium explicitly enables Widevine privacy mode separately from setting its certificate:
 * https://chromium.googlesource.com/chromium/src/media/+/fc69f2181c67940d3d962a2ea0de14611020ad54/base/android/java/src/org/chromium/media/MediaDrmBridge.java
 * This is a configuration experiment, not proof of Netflix license-policy compatibility.
 * Neither the native security level nor the client identity is changed here.
 */
@UnstableApi
internal fun configuredNetflixMediaDrm(create: () -> ExoMediaDrm): ExoMediaDrm {
    val drm = create()
    var transferred = false
    var configuringCertificate = false
    var certificateSize = 0
    try {
        drm.setPropertyString("privacyMode", "enable")
        AppLog.info("drm", "privacy_mode_configured", mapOf("success" to true, "privacyMode" to "enable"))
        configuringCertificate = true
        val certificate = NetflixServerCertificate.bytes()
        certificateSize = certificate.size
        drm.setPropertyByteArray("serviceCertificate", certificate)
        AppLog.info("drm", "service_certificate_configured", mapOf("success" to true, "byteCount" to certificateSize))
        transferred = true
        return drm
    } catch (error: Exception) {
        if (configuringCertificate) {
            AppLog.error("drm", "service_certificate_configured", error,
                mapOf("success" to false, "byteCount" to certificateSize))
        } else {
            AppLog.error("drm", "privacy_mode_configured", error,
                mapOf("success" to false, "privacyMode" to "enable"))
        }
        val safe = NetflixPlaybackException(PlaybackStage.LICENSE,
            message = if (configuringCertificate)
                "Nie udało się skonfigurować certyfikatu usługi Netflix w systemowym Widevine. Wyślij raport diagnostyczny Velvet."
            else "Nie udało się włączyć trybu prywatności w systemowym Widevine. Wyślij raport diagnostyczny Velvet.",
            retryable = false)
        // Provider.acquireExoMediaDrm has no checked-exception contract. Media3 1.6.1 catches this
        // RuntimeException in ExoPlayerImplInternal.handleMessage and publishes a playback error.
        // Keep only our fixed exception as its cause; native messages can contain opaque data.
        throw IllegalStateException(safe.message, safe)
    } finally {
        if (!transferred) {
            try {
                drm.release()
            } catch (releaseError: Exception) {
                AppLog.error("drm", "configuration_release_failed", releaseError)
            }
        }
    }
}
