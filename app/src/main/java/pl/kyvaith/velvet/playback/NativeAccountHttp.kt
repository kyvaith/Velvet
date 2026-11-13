// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pl.kyvaith.velvet.diagnostics.AppLog
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Direct first-party native activation. The issuer uses the captured browser session, while
 * every later request uses only the isolated native session. No token-bearing redirect is followed.
 */
internal class NativeAccountHttp(
    http: OkHttpClient,
    private val userAgent: String,
    private val nativeCookies: NativeCookieSession,
    nativeHeaders: Map<String, String> = emptyMap(),
) : NativeMslTransport {
    private val nativeHeaders = nativeHeaders.toMap().also { headers ->
        if (headers.any { (name, value) ->
                !HEADER_NAME.matches(name) || name.lowercase(Locale.ROOT) in RESERVED_HEADERS ||
                    value.any { it.code !in 0x20..0x7e }
            }) throw failure("Nieprawidłowe nagłówki natywnego klienta odtwarzania.")
    }
    private val nativeClient = http.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).cache(null).build()

    fun createLoginToken(web: PlaybackContext): NativeLoginToken {
        val cookieSnapshot = PlaybackCookies.snapshot(web.cookieHeader)
        val requestBody = JSONObject().put("operationName", "CreateAutoLoginToken")
            .put("variables", JSONObject().put("scope", "MOBILE_DEVICE_UPGRADE"))
            .put("extensions", JSONObject().put("persistedQuery", JSONObject()
                .put("version", 102).put("id", "76e97129-f4b5-41a0-a73c-12e674896849")))
            .toString().toByteArray(Charsets.UTF_8)
        val request = Request.Builder().url(NativeAndroidProfile.TOKEN_ENDPOINT)
            .header("User-Agent", checkedHeaderValue(web.userAgent))
            .header("Cookie", cookieSnapshot.header)
            .header("Accept", "application/json")
            .header("X-Netflix.request.name", "CreateAutoLoginToken")
            .post(requestBody.toRequestBody("application/json".toMediaType())).build()
        val operationId = AppLog.nextOperationId()
        val response = try {
            execute(request, PlaybackStage.AUTHENTICATION, operationId, "create_login_token", MAX_TOKEN_RESPONSE_BYTES)
        } finally { requestBody.fill(0) }
        try {
            val json = try { JSONObject(String(response, Charsets.UTF_8)) }
            catch (_: Exception) { throw failure("Netflix nie zwrócił prawidłowej odpowiedzi tworzenia tokenu aktywacji.") }
            val errors = json.opt("errors")
            if (errors != null && errors != JSONObject.NULL && (errors !is JSONArray || errors.length() != 0)) {
                AppLog.warn("authentication", "native_login_token_graphql_rejected", mapOf(
                    "operationId" to operationId, "errorCount" to ((errors as? JSONArray)?.length() ?: 1),
                    "hasData" to (json.optJSONObject("data") != null)))
                throw failure("Netflix odmówił utworzenia tokenu natywnej aktywacji odtwarzania.")
            }
            val token = json.optJSONObject("data")?.opt("createAutoLoginToken") as? String
                ?: throw failure("Netflix nie zwrócił tokenu natywnej aktywacji odtwarzania.")
            return NativeLoginToken(token).also {
                AppLog.info("authentication", "native_login_token_created", mapOf("operationId" to operationId, "success" to true))
            }
        } catch (error: NetflixPlaybackException) {
            AppLog.error("authentication", "native_login_token_rejected", error, mapOf("operationId" to operationId))
            throw error
        } finally { response.fill(0) }
    }

    override fun nativePost(
        operation: NativeMslOperation,
        wire: String,
        context: PlaybackContext,
        operationId: Long,
        loginToken: String?,
    ): String {
        if ((operation == NativeMslOperation.CONSUME_TOKEN) != (loginToken != null)) {
            throw failure("Nieprawidłowy etap przekazania tokenu natywnej aktywacji.")
        }
        val url = when (operation) {
            NativeMslOperation.CONSUME_TOKEN, NativeMslOperation.FETCH_ACCOUNT -> NativeAndroidProfile.GRAPHQL_ENDPOINT
            NativeMslOperation.FETCH_CONFIG -> NativeAndroidProfile.CONFIG_ENDPOINT
        }
        val request = nativeRequest(url, wire, context,
            requireAuthentication = operation != NativeMslOperation.CONSUME_TOKEN,
            contentType = if (operation == NativeMslOperation.FETCH_CONFIG) "application/x-www-form-urlencoded" else "application/json")
        when (operation) {
            NativeMslOperation.CONSUME_TOKEN -> request
                .header("X-Netflix.request.name", "ConsumeAutoLoginToken")
                .header("X-Netflix.nftoken", checkedToken(loginToken!!))
            NativeMslOperation.FETCH_ACCOUNT -> request.header("X-Netflix.request.name", "AccountQuery")
            NativeMslOperation.FETCH_CONFIG -> request.header("X-Netflix.Request.NqTracking", "FetchConfigDataMslRequest")
        }
        return String(execute(request.build(), PlaybackStage.AUTHENTICATION, operationId,
            operation.name.lowercase(Locale.ROOT), MAX_MSL_BYTES, importNativeCookies = true), Charsets.UTF_8)
    }

    override fun playbackPost(
        operation: NativePlaybackOperation,
        wire: String,
        context: PlaybackContext,
        stage: PlaybackStage,
        operationId: Long,
    ): String {
        // SWITCH_PROFILE / USER_ID_TOKEN authenticate the signed MSL header. HTTP cookie
        // scope is independent, including when no account cookie matches this fixed route.
        val request = nativeRequest(NativeAndroidProfile.PLAYBACK_ENDPOINT, wire, context,
            requireAuthentication = false, contentType = "application/json")
            .header("X-Netflix.client.request.name", operation.requestName)
            .header("X-Netflix.Request.Routing", NativeAndroidProfile.PLAYBACK_ROUTING)
            .build()
        return String(execute(request, stage, operationId, "native_${operation.name.lowercase(Locale.ROOT)}",
            MAX_MSL_BYTES, importNativeCookies = true), Charsets.UTF_8)
    }

    override fun netflixPost(url: String, wire: String, context: PlaybackContext, stage: PlaybackStage, operationId: Long): String {
        val target = checkedUrl(url, stage)
        if (target.toString() == NativeAndroidProfile.HANDSHAKE_ENDPOINT) {
            val request = nativeRequest(target.toString(), wire, context, requireAuthentication = false,
                contentType = MSL_CONTENT_TYPE).build()
            return String(execute(request, stage, operationId, "native_handshake", MAX_MSL_BYTES,
                importNativeCookies = true), Charsets.UTF_8)
        }
        if (target.host == "www.netflix.com" && target.encodedPath in PLAYBACK_PATHS) {
            // UIT / SWITCH_PROFILE live in the signed MSL header. HTTP cookies have their
            // own URL scope, so a native host-only pair must not be copied to www.
            val cookies = nativeCookies.cookieHeaderFor(target)
            val request = Request.Builder().url(target)
                .header("User-Agent", checkedHeaderValue(context.userAgent))
                .header("Accept", "*/*").apply {
                    if (cookies.isNotEmpty()) header("Cookie", cookies)
                }.post(wireBytes(wire).toRequestBody("text/plain".toMediaType())).build()
            return String(execute(request, stage, operationId, "playapi", MAX_MSL_BYTES,
                importNativeCookies = true), Charsets.UTF_8)
        }
        throw failure("Nieprawidłowy adres natywnej usługi odtwarzania.", stage)
    }

    private fun nativeRequest(url: String, wire: String, context: PlaybackContext, requireAuthentication: Boolean,
        contentType: String): Request.Builder {
        val target = checkedUrl(url, PlaybackStage.AUTHENTICATION)
        if (target.toString() !in NATIVE_ENDPOINTS) throw failure("Nieprawidłowy adres natywnej usługi odtwarzania.")
        val esn = context.esn.takeIf { it.startsWith("NFANDROID1-") && it.length in 32..512 && it.all { c -> c.code in 0x21..0x7e } }
            ?: throw failure("Nieprawidłowa tożsamość natywnego klienta odtwarzania.")
        // This snapshot is only the MSL credential pair, not an HTTP cookie header.
        if (requireAuthentication) PlaybackCookies.snapshot(context.cookieHeader)
        val cookieHeader = nativeCookies.cookieHeaderFor(target)
        return Request.Builder().url(target).apply {
            nativeHeaders.forEach { (name, value) -> header(name, value) }
            header("User-Agent", checkedHeaderValue(userAgent))
            header("Accept", "*/*")
            header("Content-Encoding", "msl_v1")
            header("X-Netflix.esn", esn)
            header("X-Netflix.esnPrefix", esn.substringBeforeLast('-') + "-")
            if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
            post(wireBytes(wire).toRequestBody(contentType.toMediaType()))
        }
    }

    private fun execute(request: Request, stage: PlaybackStage, operationId: Long, operation: String, maxBytes: Int,
        importNativeCookies: Boolean = false): ByteArray {
        val started = System.nanoTime()
        val logStage = stage.name.lowercase(Locale.ROOT)
        AppLog.info(logStage, "native_http_start", mapOf("operationId" to operationId, "operation" to operation))
        try {
            nativeClient.newCall(request).execute().use { response ->
                AppLog.info(logStage, "native_http_headers", mapOf("operationId" to operationId,
                    "httpStatus" to response.code, "durationMs" to (System.nanoTime() - started) / 1_000_000))
                if (!response.isSuccessful) throw NetflixPlaybackException(stage, "HTTP_${response.code}",
                    message = when (response.code) {
                        401, 403 -> "Netflix odmówił natywnej autoryzacji tej sesji."
                        in 300..399 -> "Netflix przekierował natywne żądanie. Token nie został przekazany dalej."
                        429 -> "Netflix ograniczył liczbę żądań. Spróbuj ponownie później."
                        else -> "Błąd natywnej usługi Netflix (HTTP ${response.code})."
                    }, retryable = response.code == 408 || response.code in 500..599)
                val body = response.body ?: throw failure("Netflix zwrócił pustą odpowiedź natywnej autoryzacji.", stage)
                if (body.contentLength() > maxBytes) throw failure("Odpowiedź natywnej usługi Netflix jest zbyt duża.", stage)
                val bytes = try { body.byteStream().use { it.readBounded(maxBytes) } }
                catch (_: NetflixPlaybackException) { throw failure("Odpowiedź natywnej usługi Netflix jest zbyt duża.", stage) }
                AppLog.info(logStage, "native_http_body_received", mapOf("operationId" to operationId,
                    "responseBytes" to bytes.size))
                if (importNativeCookies) {
                    try {
                        collectNativeCookies(request.url, response, operationId).takeIf { it.isNotEmpty() }?.let { updates ->
                            nativeCookies.updateCookiesFrom(request.url, updates)
                            AppLog.info("session", "native_cookies_updated", mapOf("operationId" to operationId,
                                "cookieCount" to updates.size))
                        }
                    }
                    catch (error: Exception) { bytes.fill(0); throw error }
                }
                AppLog.info(logStage, "native_http_complete", mapOf("operationId" to operationId, "responseBytes" to bytes.size,
                    "durationMs" to (System.nanoTime() - started) / 1_000_000))
                return bytes
            }
        } catch (error: NetflixPlaybackException) {
            AppLog.error(logStage, "native_http_rejected", error,
                mapOf("operationId" to operationId, "serviceCode" to error.serviceCode))
            throw error
        } catch (error: IOException) {
            AppLog.error(logStage, "native_http_failed", error, mapOf("operationId" to operationId,
                "durationMs" to (System.nanoTime() - started) / 1_000_000))
            // Do not retain an OkHttp exception: its message may contain request material.
            throw NetflixPlaybackException(stage, message = "Nie udało się połączyć z natywną usługą Netflix. Spróbuj ponownie.", retryable = true)
        }
    }

    private fun collectNativeCookies(url: HttpUrl, response: Response, operationId: Long): List<Cookie> {
        val parsed = Cookie.parseAll(url, response.headers)
        val authNames = response.headers.values("Set-Cookie").map { it.substringBefore('=').trim() }
            .filter { it in AUTHENTICATION_NAMES }.toSet()
        AppLog.info("session", "native_cookie_batch_received", mapOf("operationId" to operationId,
            "setCookieCount" to response.headers.values("Set-Cookie").size,
            "cookieCount" to parsed.size, "authCookieCount" to parsed.count { it.name in AUTHENTICATION_NAMES }))
        parsed.filter { it.name in AUTHENTICATION_NAMES }.take(16).forEach { cookie ->
            AppLog.info("session", "native_cookie_attributes", mapOf("operationId" to operationId,
                "cookieKind" to if (cookie.name == "NetflixId") "netflix_id" else "secure_netflix_id",
                "cookieSecure" to cookie.secure, "cookieHttpOnly" to cookie.httpOnly,
                "cookieHostOnly" to cookie.hostOnly, "cookieRootPath" to (cookie.path == "/"),
                "cookieMatchesRequest" to cookie.matches(url),
                "cookieExpired" to (cookie.expiresAt <= System.currentTimeMillis()),
                "cookieDomainScope" to when (cookie.domain) {
                    "netflix.com" -> "netflix_root"
                    url.host -> "request_host"
                    else -> "parent_domain"
                }))
        }
        if (authNames.any { name -> parsed.none { it.name == name } }) {
            AppLog.warn("session", "native_cookie_parse_rejected", mapOf("operationId" to operationId,
                "cookieCount" to parsed.size, "reason" to "auth_cookie_not_parsed"))
            throw failure("Netflix zwrócił nieprawidłowe cookies natywnej sesji.", PlaybackStage.SESSION)
        }
        return parsed
    }

    private fun wireBytes(wire: String): ByteArray = wire.toByteArray(Charsets.UTF_8).also {
        if (it.size > MAX_MSL_BYTES) throw failure("Zbyt duże żądanie natywnego odtwarzania.")
    }

    private fun checkedToken(value: String): String = NativeLoginToken(value).use { it.value() }

    private fun checkedHeaderValue(value: String): String = value.takeIf {
        it.isNotBlank() && it.length <= 1024 && it.all { c -> c.code in 0x20..0x7e }
    } ?: throw failure("Nieprawidłowy nagłówek klienta natywnej usługi Netflix.")

    private fun checkedUrl(url: String, stage: PlaybackStage): HttpUrl {
        val target = try { url.toHttpUrl() }
        catch (_: IllegalArgumentException) { throw failure("Nieprawidłowy adres natywnej usługi odtwarzania.", stage) }
        if (target.scheme != "https" || target.port != 443 || target.username.isNotEmpty() ||
            target.password.isNotEmpty() || target.fragment != null) {
            throw failure("Nieprawidłowy adres natywnej usługi odtwarzania.", stage)
        }
        return target
    }

    private fun failure(message: String, stage: PlaybackStage = PlaybackStage.AUTHENTICATION) =
        NetflixPlaybackException(stage, message = message, retryable = false)

    private companion object {
        const val MAX_TOKEN_RESPONSE_BYTES = 1024 * 1024
        const val MSL_CONTENT_TYPE = "application/msl; charset=UTF-8"
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val AUTHENTICATION_NAMES = setOf("NetflixId", "SecureNetflixId")
        val NATIVE_ENDPOINTS = setOf(NativeAndroidProfile.GRAPHQL_ENDPOINT, NativeAndroidProfile.CONFIG_ENDPOINT,
            NativeAndroidProfile.HANDSHAKE_ENDPOINT, NativeAndroidProfile.PLAYBACK_ENDPOINT)
        val PLAYBACK_PATHS = setOf(NetflixMslSession.MANIFEST_ENDPOINT, NetflixMslSession.LICENSE_ENDPOINT,
            NetflixMslSession.LOGBLOB_ENDPOINT).map { it.toHttpUrl().encodedPath }.toSet()
        val RESERVED_HEADERS = setOf("host", "cookie", "authorization", "proxy-authorization", "content-type",
            "content-encoding", "content-length", "transfer-encoding", "user-agent", "origin", "referer",
            "x-netflix.nftoken", "x-netflix.esn", "x-netflix.esnprefix", "x-netflix.request.name",
            "x-netflix.request.nqtracking", "x-netflix.client.request.name", "x-netflix.request.routing")
    }
}
