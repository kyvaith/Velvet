// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.diagnostics

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.kyvaith.velvet.playback.NetflixServiceErrors
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun keepsAllThreeReportedFailuresAndInterveningSuccessInOrder() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        log.append("ERROR", "catalog", "empty_rows", mapOf("rowCount" to 0))
        log.append("INFO", "session", "verified", mapOf("hasSession" to true))
        log.append("ERROR", "key_exchange", "rejected", mapOf("serviceCode" to "107016"))
        log.append("ERROR", "episodes", "empty", mapOf("episodeCount" to 0))
        val records = records(root)
        assertEquals(listOf("session_started", "empty_rows", "verified", "rejected", "empty"), records.map { it.getString("event") })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), records.map { it.getLong("sequence") })
        assertEquals(3, records.count { it.getString("level") == "ERROR" })
        val export = log.exportText("test device")
        assertTrue(export.contains("107016"))
        assertTrue(export.contains("\"errors\":3"))
    }

    @Test fun rejectsSecretsUrlsArbitraryFieldsAndExceptionMessages() {
        val root = temporary.newFolder()
        val secret = "secret-cookie-do-not-log"
        val error = IllegalStateException("NetflixId=$secret https://video.example/secret", java.io.IOException(secret))
        error.stackTrace = arrayOf(StackTraceElement("pl.kyvaith.velvet.data.NetflixHttp", "execute", "NetflixHttp.kt", 78))
        val log = SessionLog(root)
        log.append("ERROR", "http", "failed", mapOf(
            "cookieHeader" to secret, "authUrl" to secret, "esn" to secret, "profileGuid" to secret,
            "query" to "private search", "videoId" to "987654321", "response" to secret,
            "endpoint" to "https://netflix.com/?cookie=$secret", "serviceCode" to "107016", "httpStatus" to 403,
            "httpProfile" to secret, "deviceCategory" to secret,
            "cookieConflict" to secret, "hasNfvdid" to secret, "authCookieDuplicateCount" to secret,
        ), error)
        val export = log.exportText("safe header")
        for (forbidden in listOf(secret, "https://", "private search", "987654321", "cookieHeader", "authUrl", "profileGuid")) {
            assertFalse(forbidden, export.contains(forbidden))
        }
        assertTrue(export.contains("java.lang.IllegalStateException"))
        assertTrue(export.contains("java.io.IOException"))
        assertTrue(export.contains("NetflixHttp.kt:78"))
        assertTrue(export.contains("107016"))
        assertTrue(export.contains("\"httpStatus\":403"))
    }

    @Test fun recordsCookieShapeAndFixedCompatibilityProfileWithoutCookieValues() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        log.append("INFO", "playback", "cookie_snapshot", mapOf(
            "httpProfile" to "kodi_cros_arm64", "deviceCategory" to "T",
            "cookieCount" to 6, "authCookieDuplicateCount" to 1,
            "hasNfvdid" to true, "cookieConflict" to false,
            "NetflixId" to "private-cookie-value", "cookieHash" to "private-cookie-hash",
        ))
        val data = records(root).last().getJSONObject("data")
        assertEquals("kodi_cros_arm64", data.getString("httpProfile"))
        assertEquals("T", data.getString("deviceCategory"))
        assertEquals(1, data.getInt("authCookieDuplicateCount"))
        assertTrue(data.getBoolean("hasNfvdid"))
        assertFalse(data.getBoolean("cookieConflict"))
        val export = log.exportText("safe header")
        assertFalse(export.contains("private-cookie"))
        assertFalse(export.contains("cookieHash"))
    }

    @Test fun nativeActivationLabelsNeverRecordTokensOrOwnerIdentifiers() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        val privateValue = "private-nftoken-owner-cookie"
        log.append("INFO", "authentication", "native_account_verified", mapOf(
            "httpProfile" to "native_android", "deviceCategory" to "P",
            "tokenScope" to "MOBILE_DEVICE_UPGRADE", "success" to true,
            "hasUserToken" to true, "hasOwnerGuid" to true,
            "nfToken" to privateValue, "loginToken" to privateValue,
            "ownerGuid" to privateValue, "useridtoken" to privateValue,
            "mastertoken" to privateValue, "nativeCookies" to privateValue,
        ))
        val data = records(root).last().getJSONObject("data")
        assertEquals("native_android", data.getString("httpProfile"))
        assertEquals("P", data.getString("deviceCategory"))
        assertEquals("MOBILE_DEVICE_UPGRADE", data.getString("tokenScope"))
        assertTrue(data.getBoolean("hasUserToken"))
        assertTrue(data.getBoolean("hasOwnerGuid"))
        log.append("ERROR", "authentication", "invalid_values", mapOf(
            "httpProfile" to privateValue, "deviceCategory" to privateValue,
            "tokenScope" to privateValue, "hasUserToken" to privateValue,
            "hasOwnerGuid" to privateValue,
        ), IllegalStateException(privateValue))
        val invalid = records(root).last().getJSONObject("data")
        assertEquals("redacted", invalid.getString("tokenScope"))
        assertFalse(invalid.has("hasUserToken"))
        assertFalse(invalid.has("hasOwnerGuid"))
        assertFalse(log.exportText("safe header").contains(privateValue))
    }

    @Test fun cookieAttributeDiagnosticsKeepFlagsButNeverValuesOrRawScope() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        val secret = "private-cookie-domain-path-value"
        log.append("INFO", "session", "native_cookie_attributes", mapOf(
            "cookieKind" to "netflix_id", "cookieDomainScope" to "request_host",
            "cookieSecure" to false, "cookieHttpOnly" to true, "cookieHostOnly" to true,
            "cookieRootPath" to false, "cookieMatchesRequest" to true, "cookieExpired" to false,
            "cookieName" to secret, "cookieValue" to secret, "cookieDomain" to secret,
            "cookiePath" to secret, "setCookie" to secret,
        ))
        val flags = records(root).last().getJSONObject("data")
        assertEquals("netflix_id", flags.getString("cookieKind"))
        assertEquals("request_host", flags.getString("cookieDomainScope"))
        assertFalse(flags.getBoolean("cookieSecure"))
        assertTrue(flags.getBoolean("cookieHostOnly"))
        assertFalse(flags.getBoolean("cookieRootPath"))
        assertEquals(8, flags.length())
        log.append("WARN", "session", "invalid_cookie_metadata", mapOf(
            "cookieKind" to secret, "cookieDomainScope" to secret,
            "cookieSecure" to secret, "cookieHttpOnly" to secret, "cookieHostOnly" to secret,
            "cookieRootPath" to secret, "cookieMatchesRequest" to secret, "cookieExpired" to secret,
            "setCookieCount" to secret, "authCookieCount" to secret,
        ))
        val invalid = records(root).last().getJSONObject("data")
        assertEquals("redacted", invalid.getString("cookieKind"))
        assertEquals("redacted", invalid.getString("cookieDomainScope"))
        assertEquals(2, invalid.length())
        assertFalse(log.exportText("safe header").contains(secret))
    }

    @Test fun concurrentFailuresProduceWholeDistinctRecords() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        val pool = Executors.newFixedThreadPool(4)
        val gate = CountDownLatch(1)
        val complete = CountDownLatch(4)
        repeat(4) { worker ->
            pool.execute {
                try {
                    gate.await()
                    repeat(40) { i -> log.append("ERROR", "network", "failed", mapOf("operationId" to worker * 40 + i)) }
                } finally { complete.countDown() }
            }
        }
        gate.countDown()
        assertTrue(complete.await(15, TimeUnit.SECONDS))
        pool.shutdownNow()
        val events = records(root)
        assertEquals(161, events.size)
        assertEquals(161, events.map { it.getLong("sequence") }.distinct().size)
        assertEquals(160, events.drop(1).map { it.getJSONObject("data").getInt("operationId") }.distinct().size)
    }

    @Test fun newProcessKeepsPreviousRunAndPrunesOnlyOldRuns() {
        val root = temporary.newFolder()
        var now = 100_000L
        var latest: SessionLog? = null
        repeat(7) { index ->
            latest = SessionLog(root, wallClock={ now }, retainedRuns=5)
            latest!!.append("ERROR", "test", "error_$index")
            now += 1000
        }
        val export = latest!!.exportText("device")
        assertEquals(5, root.listFiles()!!.count { it.isDirectory })
        assertFalse(export.contains("error_0"))
        assertFalse(export.contains("error_1"))
        (2..6).forEach { assertTrue(export.contains("error_$it")) }
    }

    @Test fun errorsHaveReservedCapacityAndLimitsAreReported() {
        val root = temporary.newFolder()
        val log = SessionLog(root, maxBytes=420, errorReserveBytes=4000)
        repeat(30) { log.append("INFO", "http", "response", mapOf("httpStatus" to 200)) }
        log.append("ERROR", "key_exchange", "important_failure", mapOf("serviceCode" to "107016"))
        val export = log.exportText("device")
        assertTrue(export.contains("important_failure"))
        assertTrue(export.contains("\"suppressedErrors\":0"))
        assertTrue(log.overview().contains("pominięto"))
        assertTrue(root.walkTopDown().filter { it.name == "events.jsonl" }.sumOf { it.length() } <= 4420)
    }

    @Test fun storageFailureNeverThrowsFromDiagnosticPath() {
        val badDirectory = temporary.newFile()
        val log = SessionLog(badDirectory)
        log.append("ERROR", "app", "failure", error=IllegalStateException("private data"))
        assertFalse(log.storageAvailable)
        assertTrue(log.overview().contains("Nie można"))
        assertTrue(log.exportText("header").contains("UWAGA"))
    }

    @Test fun elapsedTimeUsesMonotonicClockDespiteWallClockAdjustment() {
        var time = 50_000L
        var nanos = 900_000_000L
        val root = temporary.newFolder()
        val log = SessionLog(root, wallClock={ time }, monotonicNanos={ nanos })
        time -= 10_000
        nanos += 10_000_000
        log.append("INFO", "app", "after_clock_change")
        assertEquals(10L, records(root).last().getLong("elapsedMs"))
    }

    @Test fun invalidFieldsAreDroppedAndExceptionCauseCyclesAreBounded() {
        val root = temporary.newFolder()
        val error = object : RuntimeException() { override val cause: Throwable get() = this }
        val log = SessionLog(root)
        log.append("ERROR", "app", "failed", mapOf("responseBytes" to Double.NaN,
            "httpStatus" to "cookie=value", "hasData" to "secret", "operationId" to 1), error)
        val last = records(root).last()
        assertEquals(1, last.getJSONArray("exception").length())
        assertEquals(setOf("operationId"), last.getJSONObject("data").keys().asSequence().toSet())
    }

    @Test fun richerServiceAndRedirectDiagnosticsKeepOnlySafeMetadata() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        log.append("ERROR", "license", "service_rejected", mapOf(
            "serviceCode" to "1044", "errorSubCode" to 3, "hasServerMessage" to true,
            "reason" to "license_policy_denied", "authScheme" to "NETFLIXID", "retryable" to false,
            "serverMessage" to "private-account-value", "errorExternalCode" to "secret-token",
            "fromEndpoint" to "browse", "toEndpoint" to "https://netflix.com/m?cookie=private-value",
            "modelCount" to 12, "hasPlayerConfig" to false, "cookieCount" to 2,
        ))
        val data = records(root).last().getJSONObject("data")
        assertEquals(3, data.getInt("errorSubCode"))
        assertTrue(data.getBoolean("hasServerMessage"))
        assertFalse(data.getBoolean("retryable"))
        assertEquals("NETFLIXID", data.getString("authScheme"))
        assertEquals("redacted", data.getString("toEndpoint"))
        assertEquals(12, data.getInt("modelCount"))
        val exported = log.exportText("safe device")
        listOf("private-account-value", "secret-token", "private-value", "serverMessage", "errorExternalCode")
            .forEach { assertFalse(it, exported.contains(it)) }
    }

    @Test fun nativeServiceCategoriesAndManifestShapeRemainVisibleWithoutResponseDetails() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        for (code in DiagnosticPrivacy.nativeServiceCodes) {
            val failure = NetflixServiceErrors.parse(JSONObject().put("error", JSONObject()
                .put("code", code).put("clientAction", 3).put("detail", "PRIVATE_DETAIL"))).single()
            log.append("WARN", "manifest", "service_rejected", failure.fields)
            val data = records(root).last().getJSONObject("data")
            assertEquals(code, data.getString("serviceCode"))
            assertEquals(code, data.getString("nqCode"))
            assertEquals("code", data.getString("codeSource"))
            assertEquals("known", data.getString("codeShape"))
            assertTrue(data.getBoolean("hasStructuredCode"))
            assertEquals(3, data.getInt("clientAction"))
        }
        for (category in DiagnosticPrivacy.nativeExceptionCategories) {
            val failure = NetflixServiceErrors.parse(JSONObject().put("error", JSONObject()
                .put("bladeRunnerExceptionType", category))).single()
            log.append("WARN", "manifest", "service_rejected", failure.fields)
            val data = records(root).last().getJSONObject("data")
            assertEquals(category, data.getString("serviceCode"))
            assertEquals(category, data.getString("exceptionCategory"))
            assertEquals("known", data.getString("exceptionTypeShape"))
        }
        log.append("INFO", "manifest", "manifest_shape", mapOf("manifestContract" to "native_android_v1",
            "manifestCount" to 1, "profileCount" to 4))
        val shape = records(root).last().getJSONObject("data")
        assertEquals("native_android_v1", shape.getString("manifestContract"))
        assertEquals(1, shape.getInt("manifestCount"))
        assertEquals(4, shape.getInt("profileCount"))
        assertFalse(log.exportText("safe device").contains("PRIVATE_DETAIL"))
    }

    @Test fun unknownNativeCodesAndSensitiveBodiesNeverEnterTheJournalOrExport() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        val secrets = listOf("ALLCAPS_SECRET", "PRIVATE_EXCEPTION", "PRIVATE_DETAIL", "PRIVATE_EXTRA_INFO", "PRIVATE_ALERT",
            "https://cdn.example/?token=PRIVATE_TOKEN")
        val failure = NetflixServiceErrors.parse(JSONObject().put("error", JSONObject()
            .put("code", secrets[0]).put("bladeRunnerExceptionType", secrets[1])
            .put("detail", secrets[2]).put("extraInfo", JSONObject().put("value", secrets[3]))
            .put("alert", JSONArray().put(secrets[4])).put("bladeRunnerMessage", secrets[5]))).single()
        log.append("WARN", "manifest", "service_rejected", failure.fields)
        val parsed = records(root).last().getJSONObject("data")
        assertFalse(parsed.has("serviceCode"))
        assertFalse(parsed.has("nqCode"))
        assertTrue(parsed.getBoolean("hasStructuredCode"))
        assertTrue(parsed.getBoolean("hasExceptionType"))
        assertEquals("unknown_string", parsed.getString("codeShape"))
        assertEquals("unknown_string", parsed.getString("exceptionTypeShape"))
        assertEquals("none", parsed.getString("codeSource"))
        log.append("WARN", "manifest", "invalid_metadata", mapOf(
            "serviceCode" to secrets[0], "nqCode" to secrets[0], "exceptionCategory" to secrets[1],
            "codeSource" to secrets[0], "codeShape" to secrets[0], "exceptionTypeShape" to secrets[0],
            "errorContainer" to secrets[0], "deviceCommandKind" to secrets[0], "manifestContract" to secrets[0],
            "hasStructuredCode" to secrets[0], "hasExceptionType" to secrets[0], "errorTraversalLimited" to secrets[0],
            "clientAction" to secrets[0], "manifestCount" to secrets[0], "detail" to secrets[2],
            "extraInfo" to secrets[3], "alert" to secrets[4], "playbackContextId" to secrets[5],
        ))
        val invalid = records(root).last().getJSONObject("data")
        for (key in listOf("serviceCode", "nqCode", "exceptionCategory", "codeSource", "codeShape", "exceptionTypeShape",
            "errorContainer", "deviceCommandKind", "manifestContract")) assertEquals("redacted", invalid.getString(key))
        for (key in listOf("hasStructuredCode", "hasExceptionType", "errorTraversalLimited", "clientAction", "manifestCount")) {
            assertFalse(invalid.has(key))
        }
        val exported = log.exportText("safe device")
        secrets.forEach { assertFalse(it, exported.contains(it)) }
        assertFalse(exported.contains("https://"))
    }

    @Test fun numericAndHttpServiceCodesRemainBoundedAndNativeTraversalMetadataIsFixed() {
        for (code in listOf("1044", "205032", "-2147483648", "2147483647", "HTTP_403", "HTTP_503")) {
            assertEquals(code, DiagnosticPrivacy.fields(mapOf("serviceCode" to code)).getString("serviceCode"))
        }
        for (code in listOf("2147483648", "-2147483649", "HTTP_503_PRIVATE_TOKEN", "ALLCAPS_SECRET", "UNKNOWN_FAULT")) {
            assertEquals("redacted", DiagnosticPrivacy.fields(mapOf("serviceCode" to code)).getString("serviceCode"))
        }
        val data = DiagnosticPrivacy.fields(mapOf("deviceCommandKind" to "unknown", "errorContainer" to "device_command",
            "errorTraversalLimited" to true, "codeShape" to "invalid_type", "exceptionTypeShape" to "absent"))
        assertEquals("unknown", data.getString("deviceCommandKind"))
        assertEquals("device_command", data.getString("errorContainer"))
        assertTrue(data.getBoolean("errorTraversalLimited"))
        assertEquals("invalid_type", data.getString("codeShape"))
        assertEquals("absent", data.getString("exceptionTypeShape"))
    }

    @Test fun playbackLoadDiagnosticsKeepCountsAndRejectMediaCredentials() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        val privateUrl = "https://cdn.example/movie?token=private-cdn-secret"
        log.append("INFO", "drm", "service_certificate_configured", mapOf("success" to true, "byteCount" to 707,
            "sessionIdBytes" to 16, "sessionIdEncoding" to "text",
            "serviceCertificate" to "private-binary-data"))
        log.append("ERROR", "media_load", "load_failed", mapOf(
            "loadTaskId" to 42L, "rangeLength" to 4096L, "dataType" to "initialization",
            "trackType" to "video", "codec" to "h264", "httpStatus" to 403,
            "activeLoadCount" to 1, "loadCount" to 2, "completedLoadCount" to 0,
            "failedLoadCount" to 1, "canceledLoadCount" to 0, "suppressedCount" to 0,
            "bufferedDurationMs" to 0L, "selectedTrackCount" to 1, "supportedTrackCount" to 4,
            "drmTrackCount" to 1, "hasDrmInitData" to true, "isLoading" to true, "playWhenReady" to true,
            "uri" to privateUrl, "headers" to "cookie=private-cookie", "formatId" to "private-title-id",
        ), java.io.IOException(privateUrl))
        val events = records(root)
        assertTrue(events[1].getJSONObject("data").getBoolean("success"))
        assertEquals(707, events[1].getJSONObject("data").getInt("byteCount"))
        assertEquals(16, events[1].getJSONObject("data").getInt("sessionIdBytes"))
        assertEquals("text", events[1].getJSONObject("data").getString("sessionIdEncoding"))
        val data = events.last().getJSONObject("data")
        assertEquals(42L, data.getLong("loadTaskId"))
        assertEquals(4096L, data.getLong("rangeLength"))
        assertEquals("initialization", data.getString("dataType"))
        assertEquals(1, data.getInt("activeLoadCount"))
        assertEquals(1, data.getInt("selectedTrackCount"))
        assertTrue(data.getBoolean("hasDrmInitData"))
        assertTrue(data.getBoolean("playWhenReady"))
        val exported = log.exportText("safe device")
        listOf("https://", "private-cdn-secret", "private-cookie", "private-title-id", "private-binary-data")
            .forEach { assertFalse(it, exported.contains(it)) }
    }

    @Test fun drmStructureDiagnosticsKeepOnlyPresenceAndClosedTokens() {
        val root = temporary.newFolder()
        val log = SessionLog(root)
        log.append("INFO", "drm", "privacy_mode_configured", mapOf("privacyMode" to "enable", "success" to true))
        log.append("INFO", "drm", "challenge_structure", mapOf(
            "challengeStructure" to "license_request", "hasClientIdField" to false,
            "hasEncryptedClientIdField" to true, "clientId" to "private-client-value",
            "encryptedClientId" to "private-encrypted-value", "challenge" to "private-challenge-value",
            "deviceCertificate" to "private-certificate-value",
        ))
        log.append("INFO", "drm", "challenge_structure", mapOf(
            "challengeStructure" to "unknown", "challengeReason" to "missing_content"))
        log.append("INFO", "drm", "invalid_fixture", mapOf(
            "privacyMode" to "short-secret", "challengeStructure" to "other-secret",
            "challengeReason" to "short-reason-secret",
            "hasClientIdField" to "private-presence-value", "hasEncryptedClientIdField" to 1,
        ))
        val events = records(root)
        assertEquals("enable", events[1].getJSONObject("data").getString("privacyMode"))
        val structure = events[2].getJSONObject("data")
        assertEquals("license_request", structure.getString("challengeStructure"))
        assertFalse(structure.getBoolean("hasClientIdField"))
        assertTrue(structure.getBoolean("hasEncryptedClientIdField"))
        assertEquals(3, structure.length())
        val unknown = events[3].getJSONObject("data")
        assertEquals("unknown", unknown.getString("challengeStructure"))
        assertEquals("missing_content", unknown.getString("challengeReason"))
        assertEquals(2, unknown.length())
        val invalid = events[4].getJSONObject("data")
        assertEquals("redacted", invalid.getString("privacyMode"))
        assertEquals("redacted", invalid.getString("challengeStructure"))
        assertEquals("redacted", invalid.getString("challengeReason"))
        assertEquals(3, invalid.length())
        val exported = log.exportText("safe device")
        listOf("private-client-value", "private-encrypted-value", "private-challenge-value",
            "private-certificate-value", "short-secret", "other-secret", "short-reason-secret", "private-presence-value")
            .forEach { assertFalse(it, exported.contains(it)) }
    }

    private fun records(root: File): List<JSONObject> = root.walkTopDown().filter { it.name == "events.jsonl" }
        .flatMap { it.readLines().asSequence() }.filter(String::isNotBlank).map(::JSONObject).toList()
}
