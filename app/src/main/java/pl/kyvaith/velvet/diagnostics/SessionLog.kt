// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Only structural metadata crosses this boundary. Response bodies and exception messages do not. */
internal object DiagnosticPrivacy {
    // Closed vocabularies shared with the service parser. An arbitrary uppercase word can
    // still be an account token, so syntax validation alone is not sufficient here.
    internal val nativeServiceCodes = setOf(
        "FAIL", "RETRY", "INCORRECT_PIN", "ACCOUNT_ON_HOLD", "EXTRA_MEMBER_STREAM_HOLD",
        "ACCOUNT_NON_MEMBER", "FREE_PREVIEW_ENDED", "STREAM_QUOTA_EXCEEDED",
        "CONCURRENT_STREAM_QUOTA_EXCEEDED_FAULT", "BLACKLISTED_IP", "INSUFFICICENT_MATURITY",
        "TITLE_OUT_OF_WINDOW", "AGE_VERIFICATION_REQUIRED",
    )
    internal val nativeExceptionCategories = setOf(
        "INCORRECT_PIN_FAULT", "ACCOUNT_ON_HOLD_FAULT", "CONCURRENT_STREAM_QUOTA_EXCEEDED_FAULT",
    )
    private val serviceValueShapes = setOf("absent", "null", "numeric", "known", "unknown_string", "invalid_type")
    private val numbers = setOf(
        "operationId", "httpStatus", "durationMs", "responseBytes", "redirectCount", "rowCount",
        "titleCount", "seasonCount", "episodeCount", "profileCount", "resultCount", "nodeCount",
        "errorCount", "attempt", "mediaCode", "challengeBytes", "licenseBytes", "trackCount",
        "androidApi", "versionCode", "webViewCode", "sslCode", "webErrorCode", "sequence",
        "videoTrackCount", "audioTrackCount", "subtitleTrackCount", "manifestBytes", "payloadBytes",
        "sectionCount", "connectionCount", "unresolvedRefCount", "rootVideoCount",
        "errorIndex", "loggedErrorCount", "modelCount", "locoRootCount", "profileLinkCount", "cookieCount",
        "errorActionId", "errorSubCode", "errorReasonCode", "errorNccpCode", "errorExternalCode",
        "byteCount", "loadTaskId", "rangeLength", "selectedTrackCount", "supportedTrackCount", "drmTrackCount",
        "setCookieCount", "authCookieCount", "manifestCount", "clientAction",
        "activeLoadCount", "loadCount", "completedLoadCount", "failedLoadCount", "canceledLoadCount",
        "suppressedCount", "bufferedDurationMs", "sessionIdBytes", "authCookieDuplicateCount",
    )
    private val booleans = setOf(
        "hasData", "hasSession", "hasProfile", "hasEsn", "hasAuth", "hasFalcor", "hasApollo",
        "isSeries", "hasCurrentEpisode", "matchedParent", "recovered", "didCrash", "isMainFrame",
        "isAdsPlan", "hasSeasons", "hasEpisodes", "hasVideo", "hasAudio", "isPlaying",
        "hasPlayerConfig", "hasServerMessage", "clientHintsApplied", "retryable",
        "success", "hasDrmInitData", "isLoading", "playWhenReady",
        "hasClientIdField", "hasEncryptedClientIdField", "hasNfvdid", "cookieConflict",
        "hasNativeCookiePair", "sourceProfileVerified", "ownerStillCurrent", "hasUserToken",
        "hasOwnerGuid", "accountVerified", "profileVerified", "hasLoginToken",
        "cookieSecure", "cookieHttpOnly", "cookieHostOnly", "cookieRootPath", "cookieMatchesRequest", "cookieExpired",
        "hasStructuredCode", "hasExceptionType", "errorTraversalLimited",
    )
    private val tokens = setOf(
        "source", "operation", "outcome", "keyExchange", "cacheKind", "esnFamily",
        "requestedAlgorithm", "responseAlgorithm", "securityLevel", "cdmVersion", "appVersion",
        "androidVersion", "webViewVersion", "method", "endpoint", "contentType", "playerState",
        "scheme", "reason", "stage", "code", "exceptionType", "authScheme", "fromEndpoint", "toEndpoint",
        "dataType", "trackType", "codec", "sessionIdEncoding",
    )
    private val tokenPattern = Regex("[A-Za-z0-9_.+\\-$]{1,96}")
    private val numericServiceCodePattern = Regex("-?[0-9]{1,10}")
    private val httpServiceCodePattern = Regex("HTTP_[1-9][0-9]{2}")
    private val fixedTokens = mapOf(
        "deviceCategory" to setOf("T", "P"),
        "cookieKind" to setOf("netflix_id", "secure_netflix_id"),
        "cookieDomainScope" to setOf("netflix_root", "request_host", "parent_domain"),
        "httpProfile" to setOf("kodi_cros_arm64", "native_android"),
        "tokenScope" to setOf("MOBILE_DEVICE_UPGRADE"),
        "privacyMode" to setOf("enable"),
        "challengeStructure" to setOf("license_request", "unknown"),
        "challengeReason" to setOf("empty", "limit", "malformed", "invalid_wire", "unsupported_type",
            "missing_envelope", "missing_content", "missing_identity", "ambiguous", "observer_error"),
        "manifestContract" to setOf("native_android_v1"),
        "nqCode" to nativeServiceCodes,
        "exceptionCategory" to nativeExceptionCategories,
        "codeSource" to setOf("bladeRunnerCode", "internalcode", "errorcode", "code", "bladeRunnerExceptionType", "none"),
        "codeShape" to serviceValueShapes,
        "exceptionTypeShape" to serviceValueShapes,
        "errorContainer" to setOf("error", "inner_errors", "device_command", "msl", "limit"),
        "deviceCommandKind" to setOf("reset", "exit", "reload", "unknown", "invalid_type"),
    )

    fun token(value: String): String = value.takeIf { tokenPattern.matches(it) } ?: "redacted"

    private fun serviceCode(value: String): String = value.takeIf {
        it in nativeServiceCodes || it in nativeExceptionCategories || httpServiceCodePattern.matches(it) ||
            (numericServiceCodePattern.matches(it) && it.toIntOrNull() != null)
    } ?: "redacted"

    fun fields(input: Map<String, Any?>): JSONObject = JSONObject().apply {
        input.entries.take(40).forEach { (key, value) ->
            when {
                key in numbers && value is Number -> {
                    val n = value.toDouble()
                    if (n.isFinite() && n >= -1_000_000 && n <= 9_007_199_254_740_991.0) put(key, value)
                }
                key in booleans && value is Boolean -> put(key, value)
                key == "serviceCode" && value is String -> put(key, serviceCode(value))
                key in fixedTokens && value is String ->
                    put(key, value.takeIf { it in fixedTokens.getValue(key) } ?: "redacted")
                key in tokens && value is String -> put(key, token(value))
            }
        }
    }

    fun exception(error: Throwable): JSONArray {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val result = JSONArray()
        var current: Throwable? = error
        while (current != null && result.length() < 6 && seen.add(current)) {
            val item = current
            val frames = item.stackTrace.filter { it.className.startsWith("pl.kyvaith.velvet.") }
                .take(14).map { frame ->
                    "${token(frame.className)}.${token(frame.methodName)}(${token(frame.fileName ?: "unknown")}:${frame.lineNumber})"
                }
            result.put(JSONObject().put("type", token(item.javaClass.name)).put("appFrames", JSONArray(frames)))
            current = item.cause
        }
        return result
    }
}

/**
 * One journal per app process. Appends are synchronous and locked so a crash or overlapping
 * catalog/player request cannot replace earlier errors. Low-volume metadata only; no logcat.
 * Keep five runs, bound normal events to 2 MiB, with 512 KiB reserved for errors after that.
 */
class SessionLog(
    private val directory: File,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val errorReserveBytes: Long = 512L * 1024,
    private val retainedRuns: Int = 5,
) {
    private val lock = Any()
    private val startedAt = wallClock()
    private val startedNanos = monotonicNanos()
    val sessionId: String = UUID.randomUUID().toString().substring(0, 8)
    private val runDirectory = File(directory, "${startedAt}_$sessionId")
    private val journal = File(runDirectory, "events.jsonl")
    private val counters = File(runDirectory, "summary.json")
    private var sequence = 0L
    private var errors = 0L
    private var suppressed = 0L
    private var suppressedErrors = 0L
    @Volatile var storageAvailable = true
        private set

    init {
        try {
            check(runDirectory.mkdirs() || runDirectory.isDirectory)
            directory.listFiles()?.filter { it.isDirectory && it != runDirectory }
                ?.sortedByDescending { it.name }?.drop((retainedRuns - 1).coerceAtLeast(0))
                ?.forEach { it.deleteRecursively() }
            append("INFO", "app", "session_started")
        } catch (_: Exception) { storageAvailable = false }
    }

    fun append(level: String, stage: String, event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) {
        synchronized(lock) {
            try {
                val severity = level.takeIf { it in setOf("INFO", "WARN", "ERROR") } ?: "INFO"
                val isError = severity == "ERROR"
                sequence++
                if (isError) errors++
                val item = JSONObject()
                    .put("time", Instant.ofEpochMilli(wallClock()).toString())
                    .put("elapsedMs", ((monotonicNanos() - startedNanos) / 1_000_000).coerceAtLeast(0))
                    .put("session", sessionId).put("sequence", sequence).put("level", severity)
                    .put("stage", DiagnosticPrivacy.token(stage)).put("event", DiagnosticPrivacy.token(event))
                    .put("data", DiagnosticPrivacy.fields(fields))
                if (error != null) item.put("exception", DiagnosticPrivacy.exception(error))
                val bytes = (item.toString() + "\n").toByteArray(Charsets.UTF_8)
                val limit = maxBytes + if (isError) errorReserveBytes else 0
                if (journal.length() + bytes.size <= limit) journal.appendBytes(bytes)
                else {
                    suppressed++
                    if (isError) suppressedErrors++
                }
                // Also records limits after a crash; a truncated final summary can be ignored.
                if (isError || suppressed > 0) persistCounters()
                storageAvailable = true
            } catch (_: Exception) { storageAvailable = false } // Diagnostics must not crash the app.
        }
    }

    private fun persistCounters() {
        counters.writeText(JSONObject().put("events", sequence).put("errors", errors)
            .put("suppressed", suppressed).put("suppressedErrors", suppressedErrors).toString())
    }

    fun overview(): String = synchronized(lock) {
        "Zapis sesji: $sessionId\nZdarzenia: $sequence; błędy: $errors" +
            if (!storageAvailable) "\nNie można zapisać dziennika w pamięci aplikacji." else
                if (suppressed > 0) "\nLimit dziennika: pominięto $suppressed wpisów, w tym $suppressedErrors błędów." else ""
    }

    /** Includes previous runs so a process crash/restart does not hide the failure. */
    fun exportText(header: String): String = synchronized(lock) {
        runCatching { persistCounters() }.onFailure { storageAvailable = false }
        buildString {
            appendLine("VELVET — DZIENNIK DIAGNOSTYCZNY v1")
            appendLine(header)
            appendLine()
            appendLine("Bieżący zapis: $sessionId")
            appendLine("Zdarzenia bieżącego i do ${retainedRuns - 1} poprzednich uruchomień aplikacji, od najstarszego.")
            appendLine("UTC; elapsedMs liczone od uruchomienia. operationId łączy żądanie z wynikiem.")
            appendLine("Zapis obejmuje etapy, liczniki, kody i typy wyjątków. Bez treści odpowiedzi i wiadomości wyjątków,")
            appendLine("ciasteczek, tokenów, ESN, identyfikatorów profili/filmów, wyszukiwanych fraz i adresów strumieni.")
            directory.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.takeLast(retainedRuns)?.forEach { run ->
                appendLine()
                appendLine("=== ${run.name} ===")
                val events = File(run, "events.jsonl")
                if (events.isFile) {
                    // The export is a fixed snapshot under the same lock as writers.
                    append(events.readText())
                }
                val summary = File(run, "summary.json")
                if (summary.isFile) appendLine("SUMMARY ${summary.readText()}")
            }
            if (!storageAvailable) appendLine("UWAGA: zapis części zdarzeń mógł się nie udać.")
        }
    }
}
