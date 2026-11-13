// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.data

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import pl.kyvaith.velvet.diagnostics.AppLog
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetflixApiException(val stage: String, val statusCode: Int? = null, message: String) : IOException(message)

/** Credentials are never sent to image CDNs, external redirects or look-alike domains. */
object NetflixOrigins {
    private val apiHosts = setOf("netflix.com", "www.netflix.com", "web.prod.cloud.netflix.com")
    fun isApi(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 && url.host in apiHosts &&
        url.username.isEmpty() && url.password.isEmpty()
    fun isLogin(url: String?): Boolean = url?.toHttpUrlOrNull()?.let {
        it.scheme == "https" && it.port == 443 && it.host in setOf("netflix.com", "www.netflix.com") &&
            it.username.isEmpty() && it.password.isEmpty()
    } == true

    /** A closed set of route labels; URL paths, queries, title IDs and profile IDs never leave here. */
    fun pageTag(address: String?): String = address?.toHttpUrlOrNull()?.let(::pageTag) ?: "untrusted"
    fun pageTag(url: HttpUrl): String {
        if (!isApi(url)) return "untrusted"
        val raw = url.pathSegments.filter { it.isNotEmpty() }.map { it.lowercase(java.util.Locale.ROOT) }
        val parts = if (raw.firstOrNull()?.matches(Regex("[a-z]{2}(?:-[a-z]{2,4})?")) == true) raw.drop(1) else raw
        return when {
            parts.any { it == "graphql" } -> "graphql"
            parts.any { it == "pathevaluator" } -> "falcor"
            parts.any { it == "metadata" } -> "metadata"
            parts.any { it.startsWith("playlist") } -> "my_list"
            parts.isEmpty() -> "landing"
            parts.first() == "m" -> "mobile_landing"
            parts.first() == "browse" -> "browse"
            parts.first() == "switchprofile" -> "profile_switch"
            parts.first() in setOf("profiles", "manageprofiles", "profileslist") -> "profiles"
            parts.first() in setOf("youraccount", "account") -> "account"
            parts.first() == "login" -> "login"
            parts.first() == "signup" -> "signup"
            parts.first() == "watch" -> "watch"
            parts.first() == "title" -> "title"
            else -> "netflix_page_other"
        }
    }

    fun mergeCookies(header: String, updates: List<Cookie>, now: Long = System.currentTimeMillis()): String {
        val values = LinkedHashMap<String, String>()
        header.split(';').forEach { pair ->
            val parts = pair.trim().split('=', limit=2)
            if (parts.size == 2 && parts[0].isNotBlank()) values[parts[0]] = parts[1]
        }
        updates.filter { (it.domain == "netflix.com" || it.domain == "www.netflix.com") && it.path == "/" }.forEach {
            if (it.expiresAt <= now) values.remove(it.name) else values[it.name] = it.value
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
    fun hasAuthCookies(header: String): Boolean {
        val names = header.split(';').mapNotNull {
            val pair = it.trim().split('=',limit=2)
            if(pair.size==2 && pair[1].isNotBlank()) pair[0] else null
        }.toSet()
        return names.containsAll(listOf("NetflixId", "SecureNetflixId"))
    }
}

class NetflixHttp(private val sessions: SessionStore) {
    // This client is private to authenticated catalog traffic. Playback uses its own scoped transport.
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun get(url: String, expectedGeneration: Long = sessions.generation): String = execute(url, null, emptyMap(),expectedGeneration)
    fun post(url: String, body: RequestBody, headers: Map<String, String> = emptyMap(),
        expectedGeneration: Long = sessions.generation): String = execute(url, body, headers,expectedGeneration)

    private fun execute(address: String, body: RequestBody?, headers: Map<String, String>, sessionGeneration: Long): String {
        val operationId = AppLog.nextOperationId()
        val started = System.nanoTime()
        return try {
            val result = executeRequest(address, body, headers, sessionGeneration, operationId)
            AppLog.info("http", "completed", mapOf("operationId" to operationId,
                "durationMs" to (System.nanoTime() - started) / 1_000_000))
            result
        } catch (ex: Exception) {
            AppLog.error("http", "failed", ex, mapOf("operationId" to operationId,
                "durationMs" to (System.nanoTime() - started) / 1_000_000,
                "httpStatus" to (ex as? NetflixApiException)?.statusCode))
            throw ex
        }
    }

    private fun executeRequest(address: String, body: RequestBody?, headers: Map<String, String>, sessionGeneration: Long, operationId: Long): String {
        var url = address.toHttpUrlOrNull() ?: throw NetflixApiException("sesja", message="Niepoprawny adres usługi.")
        var currentBody = body
        repeat(6) {
            sessions.requireGeneration(sessionGeneration)
            if (!NetflixOrigins.isApi(url)) throw NetflixApiException("sesja", message="Netflix przekierował poza obsługiwany adres usługi. Zaloguj się ponownie.")
            val session = sessions.read(sessionGeneration) ?: throw NetflixApiException("sesja", message="Zaloguj się na konto Netflix.")
            AppLog.info("http", "request", mapOf("operationId" to operationId,
                "method" to if (currentBody == null) "GET" else "POST", "endpoint" to NetflixOrigins.pageTag(url), "redirectCount" to it))
            val request = Request.Builder().url(url)
                .header("User-Agent", session.userAgent)
                .header("Cookie", session.cookieHeader)
                .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.7")
                .header("Origin", "https://www.netflix.com")
                .header("Referer", "https://www.netflix.com/browse")
                .apply {
                    NetflixBrowserContext.lowEntropyClientHints(session.userAgent).forEach { (key, value) -> header(key, value) }
                    headers.forEach { (key, value) -> header(key, value) }
                    currentBody?.let(::post)
                }
                .build()
            client.newCall(request).execute().use { response ->
                AppLog.info("http", "response", mapOf("operationId" to operationId,
                    "httpStatus" to response.code, "endpoint" to NetflixOrigins.pageTag(url)))
                val cookies = Cookie.parseAll(url, response.headers)
                sessions.requireGeneration(sessionGeneration)
                if (cookies.isNotEmpty()) sessions.update(sessionGeneration) { it.copy(cookieHeader=NetflixOrigins.mergeCookies(it.cookieHeader, cookies)) }
                if (response.code in setOf(301,302,303,307,308)) {
                    val next = response.header("Location")?.let(url::resolve)
                        ?: throw NetflixApiException("sesja", response.code, "Niepoprawne przekierowanie Netflixa.")
                    AppLog.info("http", "redirect", mapOf("operationId" to operationId, "httpStatus" to response.code,
                        "fromEndpoint" to NetflixOrigins.pageTag(url), "toEndpoint" to NetflixOrigins.pageTag(next),
                        "outcome" to if (NetflixOrigins.isApi(next)) "allowed" else "blocked"))
                    if (NetflixOrigins.pageTag(next) == "login") {
                        throw NetflixApiException("sesja", 401, "Sesja Netflixa wygasła. Zaloguj się ponownie.")
                    }
                    url = next
                    if (response.code == 303 || response.code in setOf(301,302)) currentBody = null
                    return@repeat
                }
                if (!response.isSuccessful) throw NetflixApiException("katalog", response.code, when(response.code) {
                    401 -> "Netflix odrzucił sesję. Zaloguj się ponownie."
                    403 -> "Netflix odmówił dostępu do tej funkcji (HTTP 403)."
                    404, 412 -> "Netflix zmienił lub wyłączył ten adres API (HTTP ${response.code})."
                    429 -> "Netflix ograniczył liczbę zapytań. Spróbuj ponownie za chwilę."
                    else -> "Usługa Netflix zwróciła błąd HTTP ${response.code}."
                })
                val responseBody = response.body ?: throw NetflixApiException("katalog", message="Pusta odpowiedź Netflixa.")
                if (responseBody.contentLength() > MAX_RESPONSE) throw NetflixApiException("katalog", message="Odpowiedź Netflixa przekroczyła limit danych.")
                val source = responseBody.source()
                source.request(MAX_RESPONSE + 1)
                val bytes = source.buffer.readByteArray()
                AppLog.info("http", "body_received", mapOf("operationId" to operationId, "responseBytes" to bytes.size))
                if (bytes.size > MAX_RESPONSE) throw NetflixApiException("katalog", message="Odpowiedź Netflixa przekroczyła limit danych.")
                sessions.requireGeneration(sessionGeneration)
                return bytes.toString(Charsets.UTF_8)
            }
        }
        throw NetflixApiException("sesja", message="Zbyt wiele przekierowań. Otwórz ponownie logowanie.")
    }

    companion object {
        private const val MAX_RESPONSE = 24L * 1024 * 1024
        fun requireGraphQlData(payload: JSONObject): JSONObject {
            // HTTP 200 is not proof of success: GraphQL errors must be surfaced.
            if (payload.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
                val errors = payload.optJSONArray("errors")
                val count = errors?.length() ?: 0
                val operationId = AppLog.nextOperationId()
                for (index in 0 until minOf(count, 20)) {
                    val code = errors?.optJSONObject(index)?.optJSONObject("extensions")?.opt("code")
                    AppLog.error("graphql", "response_error", fields=mapOf("operationId" to operationId,
                        "errorIndex" to index, "errorCount" to count,
                        "hasData" to (payload.optJSONObject("data") != null), "serviceCode" to code?.toString()))
                }
                if (count > 20) AppLog.warn("graphql", "error_limit", mapOf("errorCount" to count, "loggedErrorCount" to 20))
                throw NetflixApiException("GraphQL", message="Netflix odrzucił zapytanie katalogu. Jego API mogło się zmienić.")
            }
            return payload.optJSONObject("data") ?: throw NetflixApiException("GraphQL", message="Netflix nie zwrócił danych katalogu.")
        }
    }
}
