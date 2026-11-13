/*
 * SPDX-License-Identifier: MIT
 * Manifest/license request shapes and profiles adapted from msl_handler.py and profiles.py,
 * CastagnaIT/plugin.video.netflix commit 7accc24cfbf2bf798c037996119e08e6334d64cb.
 * Copyright (C) 2017 Sebastian Golasch; 2017 Trummerjo; 2018 Caphm. See root THIRD_PARTY_NOTICES.md.
 */
package pl.kyvaith.velvet.playback

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import pl.kyvaith.velvet.diagnostics.AppLog
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.coroutines.coroutineContext

@UnstableApi
class NetflixPlaybackClient(
    context: Context,
    private val http: OkHttpClient,
    private val sessionProvider: NetflixSessionProvider,
) {
    private val defaultLocale = context.resources.configuration.locales[0]?.toLanguageTag() ?: "pl-PL"
    private val nativeIdentity = AndroidPlaybackIdentityStore(context.applicationContext)

    suspend fun prepare(request: PlaybackRequest): PreparedPlayback = ownedPlaybackResult(
        dispatcher = Dispatchers.IO,
        release = { it: PreparedPlayback -> it.close() },
    ) {
        if (!request.videoId.matches(Regex("[0-9]{1,20}")) || (request.videoId.toLongOrNull() ?: 0) <= 0) throw NetflixPlaybackException(
            PlaybackStage.MANIFEST, message = "Materiał nie ma prawidłowego identyfikatora Netflix.",
        )
        val supplied = sessionProvider.playbackContext()
        val sourceContext = if (supplied.locale.isBlank()) supplied.copy(locale = defaultLocale) else supplied
        validateContext(sourceContext)
        AppLog.info("playback", "prepare_start", mapOf("keyExchange" to "WIDEVINE",
            "source" to NativeAndroidProfile.ID, "authScheme" to "NFTOKEN",
            "httpProfile" to "native_android", "deviceCategory" to "P",
            "hasSession" to sourceContext.cookieHeader.isNotEmpty(), "hasProfile" to sourceContext.profileGuid.isNotEmpty(),
            "hasEsn" to sourceContext.esn.isNotEmpty()))
        // Cookies stay bound to the captured account/profile/web identity. Native playback gets
        // a separate device identity; it must never overwrite the catalog's saved browser ESN.
        val sourceSession = BoundPlaybackSession(sourceContext, sessionProvider)
        // The web session proves ownership only to the token-issuing Netflix endpoint. Native
        // authentication uses an isolated cookie jar and the device's own MSL session.
        PlaybackCookies.snapshot(sourceContext.cookieHeader)
        val device = NativeAndroidProfile.Device.current()
        val nativeUserAgent = NativeAndroidProfile.userAgent(device, sourceContext.locale)
        val publicTransport = PlaybackHttp(http, nativeUserAgent)
        var cryptoOwner: AndroidMslCrypto? = null
        var mslOwner: NetflixMslSession? = null
        var nativeProviderOwner: NativeAccountSessionProvider? = null
        var completed = false
        try {
            coroutineContext.ensureActive()
            val crypto = AndroidMslCrypto.create(publicTransport, nativeIdentity::identityForPhone)
            cryptoOwner = crypto
            if (crypto.securityLevel != "L1") throw NetflixPlaybackException(
                PlaybackStage.AUTHENTICATION,
                message = "Eksperymentalna ścieżka natywnego odtwarzania wymaga Widevine L1. Urządzenie zgłosiło inny poziom DRM.",
                retryable = false,
            )
            val nativeProvider = NativeAccountSessionProvider(sourceSession, crypto.identity, nativeUserAgent)
            nativeProviderOwner = nativeProvider
            val context = nativeProvider.playbackContext() // Recheck the owner after provisioning.
            val transport = NativeAccountHttp(http, nativeUserAgent, nativeProvider,
                NativeAndroidProfile.headers(device))
            val msl = NetflixMslSession(BoundPlaybackSession(context, nativeProvider), transport, crypto,
                authenticationMode = MslAuthenticationMode.NATIVE_USER_TOKEN,
                handshakeEndpoint = NativeAndroidProfile.HANDSHAKE_ENDPOINT)
            mslOwner = msl
            AppLog.info("key_exchange", "android_identity_ready", mapOf(
                "keyExchange" to crypto.keyExchangeScheme, "esnFamily" to "ANDROID",
                "securityLevel" to crypto.securityLevel, "source" to NativeAndroidProfile.ID,
                "deviceCategory" to "P", "authScheme" to "NFTOKEN", "httpProfile" to "native_android"))
            val preparationContext = coroutineContext
            transport.createLoginToken(sourceSession.current()).use { loginToken ->
                preparationContext.ensureActive()
                sourceSession.current()
                val config = NativeAndroidProfile.configPayload(device, crypto.widevineSystemId, context.locale)
                try {
                    msl.authenticateWithToken(loginToken, config) {
                        preparationContext.ensureActive()
                        sourceSession.current()
                    }
                } finally { config.fill(0) }
            }
            coroutineContext.ensureActive()
            val profiles = supportedProfiles()
            val xid = (System.currentTimeMillis() * 100000L).toString()
            val params = NativePlaybackRequests.manifest(request.videoId, device, profiles)
            AppLog.info("manifest", "native_manifest_request", mapOf("manifestContract" to "native_android_v1",
                "manifestCount" to params.length(), "profileCount" to profiles.size))
            val result = msl.exchangeNativePlayback(NativePlaybackOperation.MANIFEST,
                NativePlaybackRequests.MANIFEST_PATH, params, PlaybackStage.MANIFEST)
            val manifest = NativePlaybackRequests.selectManifest(result, request.videoId)
            val expiration = manifest.optLong("expiration", Long.MAX_VALUE)
            if (expiration < System.currentTimeMillis()) throw NetflixPlaybackException(PlaybackStage.MANIFEST, message = "Manifest odtwarzania Netflix wygasł.")
            val converted = NetflixManifestConverter.convert(manifest, context.locale)
            AppLog.info("manifest", "converted", mapOf("trackCount" to converted.subtitles.size))
            val licenseOperation = manifest.optJSONObject("links")?.optJSONObject("license")?.optString("href").orEmpty()
            if (licenseOperation.isBlank()) throw NetflixPlaybackException(
                PlaybackStage.LICENSE, message = "Netflix nie udostępnił endpointu licencji tego materiału.",
            )
            val requestSessions = DrmRequestSessions()
            val callback = NetflixDrmCallback(msl, publicTransport, requestSessions, licenseOperation, xid, nativePlayback = true)
            // Parse the real MPD now, so prepare does not report success for an invalid manifest.
            val manifestUri = Uri.parse("https://www.netflix.com/watch/${request.videoId}")
            val dash = try { DashManifestParser().parse(manifestUri, ByteArrayInputStream(converted.mpd.toByteArray(Charsets.UTF_8))) }
            catch (_: Exception) { throw NetflixPlaybackException(PlaybackStage.MANIFEST, message = "Media3 nie może odczytać manifestu Netflix.") }
            coroutineContext.ensureActive()
            val prepared = PreparedPlayback(converted.mpd, callback, request, converted.durationMs, converted.subtitles,
                sourceFactory = {
                    val mediaItem = MediaItem.Builder().setMediaId(request.videoId).setUri(manifestUri)
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
                        .setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(NativeAndroidProfile.PLAYBACK_ENDPOINT).setMultiSession(true).build()).build()
                    val drmManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid ->
                            SessionTrackingMediaDrm(configuredNetflixMediaDrm { FrameworkMediaDrm.newInstance(uuid) }, requestSessions)
                        }.setMultiSession(true).setSessionKeepaliveMs(C.TIME_UNSET)
                        .setLoadErrorHandlingPolicy(NetflixDrmRetryPolicy()).build(callback)
                    // CDN links are already signed. Native account cookies stay in the playback transport.
                    val dataSource = OkHttpDataSource.Factory(publicTransport.publicClient.newBuilder()
                        .followRedirects(true).followSslRedirects(false).build()).setUserAgent(context.userAgent)
                    DashMediaSource.Factory(dataSource).setDrmSessionManagerProvider { drmManager }
                        .createMediaSource(dash, mediaItem)
                },
                release = {
                    try { callback.releaseLicenses() } finally {
                        try { msl.close() } finally { nativeProvider.close() }
                    }
                })
            completed = true
            AppLog.info("playback", "prepare_success")
            prepared
        } catch (error: Exception) {
            AppLog.error("playback", "prepare_failed", error, mapOf("serviceCode" to (error as? NetflixPlaybackException)?.serviceCode))
            throw error
        } finally {
            if (!completed) {
                // A changed account can reject the native context after the CDM was allocated.
                // Until the MSL owner exists, release that CDM directly as well.
                val owner = mslOwner
                if (owner != null) runCatching { owner.close() }
                else runCatching { cryptoOwner?.close() }
                runCatching { nativeProviderOwner?.close() }
            }
        }
    }

    private fun validateContext(context: PlaybackContext) {
        if (context.cookieHeader.isBlank() || context.profileGuid.isBlank()) throw NetflixPlaybackException(
            PlaybackStage.SESSION, message = "Zaloguj się do Netflix i wybierz profil przed odtwarzaniem.",
        )
        if (context.esn.isBlank()) throw NetflixPlaybackException(PlaybackStage.SESSION,
            message = "Brak kontekstu strony zalogowanego konta. Odśwież połączenie z Netflixem.")
        if (context.userAgent.isBlank()) throw NetflixPlaybackException(PlaybackStage.SESSION, message = "Brak kontekstu przeglądarki, w której wykonano logowanie.")
    }

    private fun supportedProfiles(): List<String> {
        val types = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.toList() }.map { it.lowercase(Locale.ROOT) }.toSet()
        } catch (_: Exception) { throw NetflixPlaybackException(PlaybackStage.MEDIA, message = "Nie można odczytać dostępnych dekoderów urządzenia.") }
        val profiles = mutableListOf("heaac-2-dash", "heaac-2hq-dash", "BIF240", "BIF320")
        if ("video/avc" in types) profiles += listOf("playready-h264mpl30-dash", "playready-h264mpl31-dash",
            "playready-h264mpl40-dash", "playready-h264hpl22-dash", "playready-h264hpl30-dash",
            "playready-h264hpl31-dash", "playready-h264hpl40-dash")
        profiles += "imsc1.1"
        // H.264 PRK-QC is deliberately excluded (jankofron c41d488). Other content remains DRM protected.
        if ("video/x-vnd.on2.vp9" in types) profiles += listOf("L21", "L30", "L31", "L40").map { "vp9-profile0-$it-dash-cenc" }
        if ("video/avc" !in types && "video/x-vnd.on2.vp9" !in types) throw NetflixPlaybackException(
            PlaybackStage.MEDIA, message = "Urządzenie nie ma dekodera zgodnego z obsługiwanymi profilami Netflix.",
        )
        return profiles
    }

}
