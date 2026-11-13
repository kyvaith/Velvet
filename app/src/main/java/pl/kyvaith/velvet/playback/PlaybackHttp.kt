package pl.kyvaith.velvet.playback

import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import pl.kyvaith.velvet.diagnostics.AppLog
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun interface MslTransport {
    fun netflixPost(url: String, wire: String, context: PlaybackContext, stage: PlaybackStage, operationId: Long): String
}

internal class PlaybackHttp(http: OkHttpClient, private val userAgent: String,
    private val updateCookies: (List<Cookie>) -> Unit = {},
) : MslTransport {
    // Media/provisioning requests must never inherit an authenticated CookieJar or logging interceptor.
    val publicClient: OkHttpClient = http.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).cache(null).build()

    override fun netflixPost(url: String, wire: String, context: PlaybackContext, stage: PlaybackStage, operationId: Long): String {
        val target = url.toHttpUrl()
        if (target.scheme != "https" || target.host != "www.netflix.com" || target.port != 443 ||
            target.username.isNotEmpty() || target.password.isNotEmpty()) throw NetflixPlaybackException(
            stage, message = "Nieprawidłowy adres usługi MSL.", retryable = false,
        )
        val cookies = PlaybackCookies.snapshot(context.cookieHeader)
        AppLog.info("session", "playback_cookie_snapshot", mapOf("operationId" to operationId,
            "cookieCount" to cookies.cookieCount, "authCookieDuplicateCount" to cookies.authCookieDuplicateCount,
            "hasNfvdid" to cookies.hasNfvdid, "cookieConflict" to false))
        val request = Request.Builder().url(target)
            .header("User-Agent", context.userAgent)
            .header("Cookie", cookies.header)
            .header("Accept", "*/*")
            // ByteArray avoids OkHttp adding charset=utf-8 to Kodi's text/plain content type.
            .post(wire.toByteArray(Charsets.UTF_8).toRequestBody("text/plain".toMediaType())).build()
        return String(execute(request, stage, operationId) { response ->
            val updates = Cookie.parseAll(target, response.headers).filter {
                it.domain in setOf("netflix.com", "www.netflix.com") && it.path == "/"
            }
            if (updates.isNotEmpty()) {
                // The caller's captured account provider rechecks generation/profile/web ESN
                // atomically with this write. Public/CDN/provisioning responses never get here.
                updateCookies(updates)
                AppLog.info("session", "playback_cookies_updated", mapOf(
                    "operationId" to operationId, "cookieCount" to updates.size))
            }
        }, Charsets.UTF_8)
    }

    fun provision(defaultUrl: String, data: ByteArray): ByteArray {
        val target = try { defaultUrl.toHttpUrl() } catch (e: IllegalArgumentException) {
            throw NetflixPlaybackException(PlaybackStage.PROVISIONING, message = "Widevine nie udostępnił adresu aktywacji urządzenia.")
        }
        if (target.scheme != "https") throw NetflixPlaybackException(
            PlaybackStage.PROVISIONING, message = "Widevine zwrócił niezabezpieczony adres aktywacji urządzenia.",
        )
        // URL is supplied by the device DRM implementation. No Netflix session data is attached.
        val url = target.newBuilder().addQueryParameter("signedRequest", String(data, Charsets.UTF_8)).build()
        return execute(Request.Builder().url(url).header("User-Agent", userAgent)
            .post(ByteArray(0).toRequestBody()).build(), PlaybackStage.PROVISIONING)
    }

    private fun execute(request: Request, stage: PlaybackStage, operationId: Long = AppLog.nextOperationId(),
        responseObserver: (Response) -> Unit = {},
    ): ByteArray {
        val started = System.nanoTime()
        val logStage = stage.name.lowercase(java.util.Locale.ROOT)
        AppLog.info(logStage, "http_start", mapOf("operationId" to operationId))
        try {
            publicClient.newCall(request).execute().use { response ->
                AppLog.info(logStage, "http_headers", mapOf("operationId" to operationId, "httpStatus" to response.code,
                    "durationMs" to (System.nanoTime() - started) / 1_000_000))
                responseObserver(response)
                if (!response.isSuccessful) throw NetflixPlaybackException(
                    stage, "HTTP_${response.code}", when (response.code) {
                        401, 403 -> "Usługa odmówiła dostępu. Sprawdź sesję Netflix oraz uprawnienia urządzenia."
                        429 -> "Usługa ograniczyła liczbę żądań. Spróbuj ponownie później."
                        else -> "Błąd usługi odtwarzania (HTTP ${response.code})."
                    }, retryable = response.code == 408 || response.code in 500..599,
                )
                val body = response.body ?: throw NetflixPlaybackException(stage, message = "Usługa zwróciła pustą odpowiedź.")
                if (body.contentLength() > MAX_MSL_BYTES) throw NetflixPlaybackException(stage, message = "Odpowiedź usługi jest zbyt duża.")
                val bytes = body.byteStream().use { it.readBounded() }
                AppLog.info(logStage, "http_complete", mapOf("operationId" to operationId, "responseBytes" to bytes.size,
                    "durationMs" to (System.nanoTime() - started) / 1_000_000))
                return bytes
            }
        } catch (e: NetflixPlaybackException) {
            AppLog.error(logStage, "http_rejected", e, mapOf("operationId" to operationId, "serviceCode" to e.serviceCode))
            throw e
        }
        catch (e: IOException) {
            AppLog.error(logStage, "http_failed", e, mapOf("operationId" to operationId,
                "durationMs" to (System.nanoTime() - started) / 1_000_000))
            // OkHttp exceptions can contain signed URL query parameters; don't retain them as causes.
            throw NetflixPlaybackException(stage, message = "Nie udało się połączyć z usługą odtwarzania. Sprawdź połączenie i spróbuj ponownie.", retryable = true)
        }
    }
}
