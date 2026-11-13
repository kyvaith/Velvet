package pl.kyvaith.velvet.playback

import android.net.PlaybackTestUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

@UnstableApi
class NetflixServiceErrorsTest {
    @Test fun `1044 retains numeric diagnostics but never raw server details`() {
        val secret = "short-cookie@example.test E2-private-token"
        val response = JSONObject().put("error", JSONObject().put("bladeRunnerCode", "1044")
            .put("detail", "IssueLicensePolicyError ($secret)")
            .put("errorDisplayMessage", secret).put("errorActionId", 3)
            .put("errorSubCode", 0).put("errorNccpCode", 12).put("errorReasonCode", "not-a-code"))
        val failure = NetflixServiceErrors.parse(response).single()
        assertEquals("1044", failure.code)
        assertEquals("license_policy_rejected", failure.reason)
        assertEquals(false, failure.retryable)
        assertEquals(3L, failure.fields["errorActionId"])
        assertEquals(12L, failure.fields["errorNccpCode"])
        assertEquals(true, failure.fields["hasServerMessage"])
        assertFalse(failure.fields.containsKey("errorReasonCode"))
        assertFalse(failure.toString().contains(secret))
        val thrown = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.LICENSE) }
        assertEquals(PlaybackStage.LICENSE, thrown.stage)
        assertEquals("1044", thrown.serviceCode)
        assertFalse(thrown.message.orEmpty().contains(secret))
        assertNull(thrown.cause)
    }

    @Test fun `205064 does not claim account cookies have expired`() {
        val response = JSONObject().put("error", JSONObject().put("bladeRunnerCode", "205064")
            .put("errorDisplayMessage", "Your session has expired secret-account-name"))
        val parsed = NetflixServiceErrors.parse(response).single()
        assertEquals("playback_session_rejected", parsed.reason)
        val error = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.MANIFEST) }
        assertEquals(false, error.retryable)
        assertFalse(error.message.orEmpty().contains("wygas"))
        assertFalse(error.message.orEmpty().contains("secret-account"))
    }

    @Test fun `all failing entries in license result are recognized including later entries`() {
        val result = JSONArray().put(JSONObject().put("licenseResponseBase64", "fixture"))
            .put(JSONObject().put("error", JSONObject().put("bladeRunnerCode", "1044")))
            .put(JSONObject().put("error", JSONObject().put("bladeRunnerCode", 205064)))
        val response = JSONObject().put("result", result)
        assertEquals(listOf("1044", "205064"), NetflixServiceErrors.parse(response).map { it.code })
        assertEquals("1044", failureOf { MslWire.throwServiceError(response, PlaybackStage.LICENSE) }.serviceCode)
    }

    @Test fun `null error fields do not reject a successful response`() {
        val response = JSONObject().put("error", JSONObject.NULL)
            .put("result", JSONArray().put(JSONObject().put("error", false)))
        assertTrue(NetflixServiceErrors.parse(response).isEmpty())
        NetflixServiceErrors.throwIfError(response, PlaybackStage.MANIFEST)
    }

    @Test fun `malicious nonnumeric codes and messages do not enter diagnostics`() {
        val response = JSONObject().put("error", JSONObject().put("bladeRunnerCode", "NetflixId=private")
            .put("errorReasonCode", "https://private.example/?token=small")
            .put("errorDisplayMessage", "user@example.test"))
        val parsed = NetflixServiceErrors.parse(response).single()
        assertNull(parsed.code)
        assertFalse(parsed.toString().contains("private"))
        val thrown = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.LICENSE) }
        assertFalse(thrown.message.orEmpty().contains("example.test"))
        assertNull(thrown.serviceCode)
    }

    @Test fun `MSL error code distinguishes transient failure from identical request rejection`() {
        fun error(mslCode: Int) = JSONObject().put("errordata", MslEncoding.encode(JSONObject()
            .put("errorcode", mslCode).put("internalcode", 9000).put("errormsg", "private-value")
            .toString().toByteArray()))
        assertEquals(true, NetflixServiceErrors.parse(error(2)).single().retryable)
        assertEquals(false, NetflixServiceErrors.parse(error(1)).single().retryable)
        assertEquals("9000", NetflixServiceErrors.parse(error(2)).single().code)
        assertEquals("2", NetflixServiceErrors.parse(error(2)).single().fields["code"])
        assertEquals("authentication_rejected", NetflixServiceErrors.parse(error(4)).single().reason)
    }

    @Test fun `205032 retains user entity mismatch without directing the user to a second login`() {
        val response = JSONObject().put("errordata", MslEncoding.encode(JSONObject()
            .put("errorcode", 7).put("internalcode", 205032)
            .put("errormsg", "User authentication data does not match entity identity. secret@example.test")
            .toString().toByteArray()))
        val failure = NetflixServiceErrors.parse(response).single()
        assertEquals("user_entity_mismatch", failure.reason)
        assertEquals(7, failure.mslResponseCode)
        assertTrue(failure.authenticationRejected)
        val thrown = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.AUTHENTICATION) }
        assertEquals("205032", thrown.serviceCode)
        assertEquals(7, thrown.mslResponseCode)
        assertTrue(thrown.authenticationRejected)
        assertEquals(false, thrown.retryable)
        assertFalse(thrown.message.orEmpty().contains("secret@example.test"))
        assertNull(thrown.cause)
    }

    @Test fun `only user reauthentication responses mark authentication rejected`() {
        for (code in 1..10) {
            val response = JSONObject().put("errordata", MslEncoding.encode(JSONObject()
                .put("errorcode", code).put("internalcode", 999999).toString().toByteArray()))
            val failure = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.AUTHENTICATION) }
            assertEquals(code, failure.mslResponseCode)
            assertEquals("code $code", code == 4 || code == 7, failure.authenticationRejected)
        }
    }

    @Test fun `205007 classifies the rejected method without blaming the password or requesting another login`() {
        val response = JSONObject().put("errordata", MslEncoding.encode(JSONObject()
            .put("errorcode", 7).put("internalcode", 205007)
            .put("errormsg", "Email or password is incorrect. secret-account@example.test")
            .toString().toByteArray()))
        val parsed = NetflixServiceErrors.parse(response).single()
        assertEquals("email_password_authentication_rejected", parsed.reason)
        assertTrue(parsed.authenticationRejected)
        val error = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.AUTHENTICATION) }
        assertEquals("205007", error.serviceCode)
        assertEquals(7, error.mslResponseCode)
        assertEquals(false, error.retryable)
        assertTrue(error.message.orEmpty().contains("Sam ten błąd nie rozstrzyga"))
        assertFalse(error.message.orEmpty().contains("secret-account"))
        assertFalse(error.message.orEmpty().contains("Wpisz"))
        assertFalse(error.message.orEmpty().contains("Potwierdź konto"))
        assertNull(error.cause)
    }

    @Test fun `native symbolic codes survive without a numeric code or raw server text`() {
        val reasons = mapOf(
            "FAIL" to "service_rejected", "RETRY" to "transient_service_failure",
            "INCORRECT_PIN" to "profile_pin_rejected", "ACCOUNT_ON_HOLD" to "account_on_hold",
            "EXTRA_MEMBER_STREAM_HOLD" to "extra_member_stream_hold", "ACCOUNT_NON_MEMBER" to "account_non_member",
            "FREE_PREVIEW_ENDED" to "free_preview_ended", "STREAM_QUOTA_EXCEEDED" to "stream_quota_exceeded",
            "CONCURRENT_STREAM_QUOTA_EXCEEDED_FAULT" to "stream_quota_exceeded", "BLACKLISTED_IP" to "ip_rejected",
            "INSUFFICICENT_MATURITY" to "maturity_restricted", "TITLE_OUT_OF_WINDOW" to "title_unavailable",
            "AGE_VERIFICATION_REQUIRED" to "age_verification_required",
        )
        for ((code, reason) in reasons) {
            val response = JSONObject().put("error", JSONObject().put("code", code)
                .put("detail", "private-token@example.test").put("clientAction", 1))
            val parsed = NetflixServiceErrors.parse(response).single()
            assertEquals(code, parsed.code)
            assertEquals(code, parsed.fields["nqCode"])
            assertEquals(reason, parsed.reason)
            assertEquals("node_quark", parsed.fields["source"])
            assertEquals("code", parsed.fields["codeSource"])
            assertEquals("known", parsed.fields["codeShape"])
            assertEquals(true, parsed.fields["hasStructuredCode"])
            assertEquals(1L, parsed.fields["clientAction"])
            assertEquals(code == "RETRY", parsed.retryable)
            assertFalse(parsed.authenticationRejected)
            val failure = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.MANIFEST) }
            assertEquals(code, failure.serviceCode)
            assertTrue(failure.message.orEmpty().contains("($code)"))
            assertFalse(failure.message.orEmpty().contains("private-token"))
            assertNull(failure.cause)
        }
    }

    @Test fun `numeric codes keep precedence while native categories remain available`() {
        val response = JSONObject().put("error", JSONObject().put("bladeRunnerCode", "1044")
            .put("internalcode", 205032).put("errorcode", 7).put("code", "RETRY")
            .put("bladeRunnerExceptionType", "INCORRECT_PIN_FAULT"))
        val parsed = NetflixServiceErrors.parse(response).single()
        assertEquals("1044", parsed.code)
        assertEquals("bladeRunnerCode", parsed.fields["codeSource"])
        assertEquals("RETRY", parsed.fields["nqCode"])
        assertEquals("INCORRECT_PIN_FAULT", parsed.fields["exceptionCategory"])
        assertEquals("license_rejected_1044", parsed.reason)
        assertEquals(false, parsed.retryable)
        assertFalse(parsed.authenticationRejected)
        val numericNq = NetflixServiceErrors.parse(JSONObject().put("error", JSONObject().put("code", 445))).single()
        assertEquals("445", numericNq.code)
        assertEquals("numeric", numericNq.fields["codeShape"])
        assertEquals("code", numericNq.fields["codeSource"])
        for ((input, expected) in listOf("fail" to "FAIL", "retry" to "RETRY")) {
            val alias = NetflixServiceErrors.parse(JSONObject().put("error", JSONObject().put("code", input))).single()
            assertEquals(expected, alias.code)
            assertEquals(expected == "RETRY", alias.retryable)
        }
    }

    @Test fun `known Blade Runner exception categories are retained without arbitrary exception names`() {
        for ((category, reason) in mapOf("INCORRECT_PIN_FAULT" to "profile_pin_rejected",
            "ACCOUNT_ON_HOLD_FAULT" to "account_on_hold", "CONCURRENT_STREAM_QUOTA_EXCEEDED_FAULT" to "stream_quota_exceeded")) {
            val response = JSONObject().put("error", JSONObject().put("bladeRunnerExceptionType", category))
            val parsed = NetflixServiceErrors.parse(response).single()
            assertEquals(category, parsed.code)
            assertEquals(category, parsed.fields["exceptionCategory"])
            assertEquals("bladeRunnerExceptionType", parsed.fields["codeSource"])
            assertEquals("known", parsed.fields["exceptionTypeShape"])
            assertEquals(reason, parsed.reason)
            assertFalse(parsed.authenticationRejected)
        }
    }

    @Test fun `NQ data cannot override MSL numeric retry and authentication semantics`() {
        for (mslCode in 1..10) {
            val response = JSONObject().put("errordata", MslEncoding.encode(JSONObject()
                .put("errorcode", mslCode).put("internalcode", 999999)
                .put("code", "RETRY").put("bladeRunnerExceptionType", "ACCOUNT_ON_HOLD_FAULT")
                .toString().toByteArray()))
            val parsed = NetflixServiceErrors.parse(response).single()
            assertEquals("999999", parsed.code)
            assertEquals(mslCode, parsed.mslResponseCode)
            assertEquals(mslCode == 2, parsed.retryable)
            assertEquals(mslCode == 4 || mslCode == 7, parsed.authenticationRejected)
            assertFalse(parsed.fields.containsKey("nqCode"))
            assertFalse(parsed.fields.containsKey("exceptionCategory"))
        }
    }

    @Test fun `unknown codes expose presence and shape without copying strings objects or message contents`() {
        val secrets = listOf("ALLCAPS_SECRET", "INCORRECT_PIN_PRIVATE_TOKEN", "user@example.test", "https://private.example/?token=123")
        for (secret in secrets) {
            val response = JSONObject().put("error", JSONObject().put("code", secret)
                .put("bladeRunnerExceptionType", secret).put("detail", secret).put("bladeRunnerMessage", secret)
                .put("extraInfo", JSONObject().put("code", secret)).put("alert", JSONArray().put(secret)))
            val parsed = NetflixServiceErrors.parse(response).single()
            assertNull(parsed.code)
            assertEquals("none", parsed.fields["codeSource"])
            assertEquals(true, parsed.fields["hasStructuredCode"])
            assertEquals(true, parsed.fields["hasExceptionType"])
            assertEquals("unknown_string", parsed.fields["codeShape"])
            assertEquals("unknown_string", parsed.fields["exceptionTypeShape"])
            assertFalse(parsed.fields.containsKey("nqCode"))
            assertFalse(parsed.fields.containsKey("exceptionCategory"))
            assertFalse(parsed.toString().contains(secret))
            assertFalse(failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.MANIFEST) }.message.orEmpty().contains(secret))
        }
        for ((value, shape) in listOf(JSONObject.NULL to "null", JSONObject().put("code", "ALLCAPS_SECRET") to "invalid_type",
            JSONArray().put("ALLCAPS_SECRET") to "invalid_type", true to "invalid_type")) {
            val parsed = NetflixServiceErrors.parse(JSONObject().put("error", JSONObject().put("code", value))).single()
            assertNull(parsed.code)
            assertEquals(shape, parsed.fields["codeShape"])
            assertEquals(true, parsed.fields["hasStructuredCode"])
        }
        val absent = NetflixServiceErrors.parse(JSONObject().put("error", JSONObject())).single()
        assertEquals(false, absent.fields["hasStructuredCode"])
        assertEquals("absent", absent.fields["codeShape"])
    }

    @Test fun `inner error slots retain nested service errors and later authentication rejection`() {
        val inner = JSONArray().put(JSONObject().put("code", "TITLE_OUT_OF_WINDOW"))
            .put(JSONObject().put("error", JSONObject().put("bladeRunnerCode", 1044)))
            .put(JSONObject().put("innerErrors", JSONArray().put(JSONObject().put("bladeRunnerCode", 205032))))
        val response = JSONObject().put("result", JSONArray().put(JSONObject().put("links", JSONObject()))
            .put(JSONObject().put("innerErrors", inner)))
        val parsed = NetflixServiceErrors.parse(response)
        assertEquals(listOf("TITLE_OUT_OF_WINDOW", "1044", "205032"), parsed.map { it.code })
        assertEquals("inner_errors", parsed.first().fields["errorContainer"])
        val failure = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.LICENSE) }
        assertTrue(failure.authenticationRejected)
        assertEquals(false, failure.retryable)
    }

    @Test fun `descriptive application wrappers do not hide inner codes but outer MSL keeps precedence`() {
        val wrapper = JSONObject().put("detail", "PRIVATE_WRAPPER")
            .put("innerErrors", JSONArray().put(JSONObject().put("code", "INCORRECT_PIN")))
        val response = JSONObject().put("error", wrapper)
        assertEquals(listOf(null, "INCORRECT_PIN"), NetflixServiceErrors.parse(response).map { it.code })
        val failure = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.MANIFEST) }
        assertEquals("INCORRECT_PIN", failure.serviceCode)
        assertTrue(failure.message.orEmpty().contains("INCORRECT_PIN"))
        assertFalse(failure.message.orEmpty().contains("PRIVATE_WRAPPER"))

        response.put("errordata", MslEncoding.encode(JSONObject().toString().toByteArray()))
        val unknownMsl = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.MANIFEST) }
        assertNull(unknownMsl.serviceCode)
        response.put("errordata", MslEncoding.encode(JSONObject().put("errorcode", 7).put("internalcode", 205032)
            .toString().toByteArray()))
        val authentication = failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.MANIFEST) }
        assertEquals("205032", authentication.serviceCode)
        assertEquals(7, authentication.mslResponseCode)
        assertTrue(authentication.authenticationRejected)
    }

    @Test fun `empty or malformed inner error markers are failures without treating business data as errors`() {
        for (inner in listOf(JSONObject.NULL, false, "ALLCAPS_SECRET", JSONArray(), JSONObject())) {
            val parsed = NetflixServiceErrors.parse(JSONObject().put("innerErrors", inner))
            assertEquals(1, parsed.size)
            assertNull(parsed.single().code)
            assertFalse(parsed.toString().contains("ALLCAPS_SECRET"))
        }
        val business = JSONObject().put("code", "RETRY").put("clientAction", 1)
            .put("metadata", JSONObject().put("error", JSONObject().put("code", "FAIL")))
            .put("data", JSONObject().put("innerErrors", JSONArray().put("private")))
            .put("extraInfo", JSONObject().put("deviceCommand", "reset"))
        assertTrue(NetflixServiceErrors.parse(business).isEmpty())
        assertTrue(NetflixServiceErrors.parse(JSONObject().put("result", business)).isEmpty())
        assertTrue(NetflixServiceErrors.parse(JSONObject().put("result", JSONArray().put(business))).isEmpty())
    }

    @Test fun `device commands fail safely using only known command categories`() {
        for ((command, kind) in listOf("RESET" to "reset", "exit" to "exit", "reload" to "reload",
            "ALLCAPS_SECRET" to "unknown", JSONObject.NULL to "invalid_type", JSONObject().put("token", "private") to "invalid_type")) {
            val response = JSONObject().put("deviceCommand", command)
            val parsed = NetflixServiceErrors.parse(response).single()
            assertEquals("device_command_received", parsed.reason)
            assertEquals(kind, parsed.fields["deviceCommandKind"])
            assertEquals(false, parsed.retryable)
            assertFalse(parsed.authenticationRejected)
            assertFalse(parsed.toString().contains("ALLCAPS_SECRET"))
            assertFalse(parsed.toString().contains("private"))
            failureOf { NetflixServiceErrors.throwIfError(response, PlaybackStage.MANIFEST) }
        }
    }

    @Test fun `error traversal bounds breadth depth and cycles without retaining raw input`() {
        val many = JSONArray()
        repeat(1000) { many.put(JSONObject().put("code", "FAIL")) }
        val broad = NetflixServiceErrors.parse(JSONObject().put("innerErrors", many))
        assertEquals(32, broad.size)
        assertTrue(broad.all { it.fields["errorTraversalLimited"] == true })

        val incompleteRetryBatch = JSONArray()
        repeat(32) { incompleteRetryBatch.put(JSONObject().put("code", "RETRY")) }
        incompleteRetryBatch.put(JSONObject().put("bladeRunnerCode", 1044))
        val retryFailure = failureOf { NetflixServiceErrors.throwIfError(
            JSONObject().put("innerErrors", incompleteRetryBatch), PlaybackStage.LICENSE) }
        assertEquals(false, retryFailure.retryable)

        var deep = JSONObject().put("code", "ALLCAPS_SECRET")
        repeat(1000) { deep = JSONObject().put("innerErrors", deep) }
        val deepFailure = NetflixServiceErrors.parse(deep).single()
        assertEquals("error_traversal_limited", deepFailure.reason)
        assertEquals(true, deepFailure.fields["errorTraversalLimited"])

        val cycle = JSONObject()
        cycle.put("error", cycle)
        val cycleFailure = NetflixServiceErrors.parse(cycle).single()
        assertEquals("error_traversal_limited", cycleFailure.reason)

        val excessiveResults = JSONArray()
        repeat(1000) { excessiveResults.put(JSONObject().put("business", "ALLCAPS_SECRET")) }
        val limited = NetflixServiceErrors.parse(JSONObject().put("result", excessiveResults)).single()
        assertEquals("error_traversal_limited", limited.reason)
        assertFalse(limited.toString().contains("ALLCAPS_SECRET"))
    }

    @Test fun `Media3 retry policy stops permanent license rejection at first attempt`() {
        val policy = NetflixDrmRetryPolicy()
        for (code in listOf("1044", "205064")) {
            val failure = NetflixPlaybackException(PlaybackStage.LICENSE, code, "safe", retryable = false)
            val info = loadErrorInfo(IOException("wrapper", failure), 1)
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info))
        }
        val closed = NetflixPlaybackException(PlaybackStage.SESSION, message = "changed", retryable = false)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadErrorInfo(closed, 1)))
    }

    @Test fun `Media3 retry policy retains delayed retries for transient network and server failures`() {
        val policy = NetflixDrmRetryPolicy()
        val network = SocketTimeoutException("not retained in exported logs")
        val transient = NetflixPlaybackException(PlaybackStage.LICENSE, "HTTP_503", "safe", retryable = true)
        for (failure in listOf(network, transient)) {
            assertEquals(0L, policy.getRetryDelayMsFor(loadErrorInfo(failure, 1)))
            assertEquals(1000L, policy.getRetryDelayMsFor(loadErrorInfo(failure, 2)))
        }
    }

    @Test fun `failure unwrapping finds service code and terminates on cyclic causes`() {
        val actual = NetflixPlaybackException(PlaybackStage.LICENSE, "1044", "safe")
        assertSame(actual, findNetflixPlaybackFailure(IOException("media", IOException("drm", actual))))
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertNull(findNetflixPlaybackFailure(first))
    }

    private fun failureOf(block: () -> Unit): NetflixPlaybackException {
        try { block(); fail("Expected service failure") }
        catch (expected: NetflixPlaybackException) { return expected }
        throw AssertionError()
    }

    private fun loadErrorInfo(error: IOException, attempt: Int): LoadErrorInfo {
        val data = DataSpec.Builder().setUri(PlaybackTestUri()).setHttpMethod(DataSpec.HTTP_METHOD_POST).build()
        val event = LoadEventInfo(1L, data, 0L)
        return LoadErrorInfo(event, MediaLoadData(C.DATA_TYPE_DRM), error, attempt)
    }
}
