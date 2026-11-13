package pl.kyvaith.velvet.playback

import org.json.JSONArray
import org.json.JSONObject
import pl.kyvaith.velvet.diagnostics.AppLog
import pl.kyvaith.velvet.diagnostics.DiagnosticPrivacy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** Only fixed categories and bounded numeric codes leave the response parser. */
internal object NetflixServiceErrors {
    private const val MAX_ERROR_DEPTH = 12
    private const val MAX_ERROR_NODES = 256
    private const val MAX_ERRORS = 32
    private val numericCodeKeys = listOf("bladeRunnerCode", "internalcode", "errorcode")
    private val numericDetailKeys = listOf("errorActionId", "errorSubCode", "errorReasonCode", "errorNccpCode", "errorExternalCode", "clientAction")
    private val textFields = listOf("errorDisplayMessage", "errormsg", "detail", "bladeRunnerMessage", "message", "display", "usermsg")
    internal data class Failure(
        val code: String?,
        val reason: String,
        val retryable: Boolean?,
        val fields: Map<String, Any?>,
        val mslResponseCode: Int?,
        val authenticationRejected: Boolean,
    )

    fun throwIfError(value: JSONObject, stage: PlaybackStage) {
        val errors = parse(value)
        if (errors.isEmpty()) return
        errors.take(20).forEachIndexed { index, error ->
            AppLog.warn(stage.name.lowercase(Locale.ROOT), "service_rejected", error.fields + mapOf(
                "errorCount" to errors.size, "sequence" to index + 1,
            ))
        }
        val initial = errors.first()
        // A descriptive application wrapper must not hide a recognized inner error in UI.
        // Keep an outer MSL rejection first, including one without a recognized numeric code.
        val first = if (initial.fields["source"] == "msl" || initial.code != null) initial
            else errors.firstOrNull { it.code != null } ?: initial
        val retryable = when {
            errors.any { it.retryable == false } -> false
            errors.all { it.retryable == true } -> true
            else -> null
        }
        val authentication = errors.firstOrNull { it.authenticationRejected }
        throw NetflixPlaybackException(stage, first.code, userMessage(first.code, stage), retryable = retryable,
            mslResponseCode = authentication?.mslResponseCode ?: first.mslResponseCode,
            authenticationRejected = authentication != null)
    }

    internal fun parse(value: JSONObject): List<Failure> = Parser().parse(value)

    /**
     * Native BasePlayErrorStatus also treats innerErrors/deviceCommand as failures.
     * Visit only response envelopes and these explicit error slots: business objects may
     * contain their own code, alert or metadata.error fields without failing the request.
     */
    private class Parser {
        private val failures = mutableListOf<Failure>()
        private val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        private var remainingNodes = MAX_ERROR_NODES
        private var limited = false

        fun parse(value: JSONObject): List<Failure> {
            if (value.has("errordata")) {
                val decoded = try {
                    MslEncoding.json(MslEncoding.decode(value.optString("errordata")))
                } catch (_: Exception) {
                    // Never retain opaque rejected response data, including parser causes.
                    JSONObject()
                }
                add(classify(decoded, isMsl = true, container = "msl"))
            }
            envelope(value, 0)
            when (val result = value.opt("result")) {
                is JSONObject -> envelope(result, 1)
                is JSONArray -> if (visit(result, 1)) {
                    for (index in 0 until result.length()) {
                        if (!canContinue()) break
                        val item = result.opt(index)
                        if (item is JSONObject) envelope(item, 2) else visit(item, 2)
                    }
                }
            }
            if (limited) {
                if (failures.isEmpty()) add(structuralFailure("error_traversal_limited", "limit"))
                // A skipped entry may contain a permanent rejection. Incomplete classification
                // must not make a batch of preceding RETRY entries safe to repeat automatically.
                return failures.map { it.copy(retryable = false, fields = it.fields + ("errorTraversalLimited" to true)) }
            }
            return failures
        }

        private fun envelope(value: JSONObject, depth: Int) {
            if (!visit(value, depth)) return
            val error = value.opt("error")
            if (error != null && error != JSONObject.NULL && error != false) errorValue(error, depth + 1, "error")
            if (value.has("innerErrors")) innerErrors(value.opt("innerErrors"), depth + 1)
            if (value.has("deviceCommand")) deviceCommand(value.opt("deviceCommand"))
        }

        private fun errorValue(value: Any?, depth: Int, container: String) {
            if (value is JSONObject && value.has("error")) {
                val before = failures.size
                envelope(value, depth)
                if (failures.size == before && !limited) add(structuralFailure("service_rejected", container))
                return
            }
            if (!visit(value, depth)) return
            if (value !is JSONObject) {
                add(classify(JSONObject(), isMsl = false, hasText = value is String && value.isNotBlank(), container = container))
                return
            }
            val before = failures.size
            val describesError = (numericCodeKeys + numericDetailKeys + textFields + listOf("code", "bladeRunnerExceptionType"))
                .any(value::has)
            if (describesError) add(classify(value, isMsl = false, container = container))
            if (value.has("innerErrors")) innerErrors(value.opt("innerErrors"), depth + 1)
            if (value.has("deviceCommand")) deviceCommand(value.opt("deviceCommand"))
            if (failures.size == before && !limited) add(classify(value, isMsl = false, container = container))
        }

        private fun innerErrors(value: Any?, depth: Int) {
            val before = failures.size
            if (value is JSONArray) {
                if (visit(value, depth)) for (index in 0 until value.length()) {
                    if (!canContinue()) break
                    errorValue(value.opt(index), depth + 1, "inner_errors")
                }
            } else if (value is JSONObject) {
                errorValue(value, depth, "inner_errors")
            }
            // Presence is a native error marker even when the collection is empty or malformed.
            if (failures.size == before && !limited) add(structuralFailure("inner_errors_received", "inner_errors"))
        }

        private fun deviceCommand(value: Any?) {
            val kind = if (value is String) when (if (value.length <= 6) value.lowercase(Locale.ROOT) else null) {
                "reset" -> "reset"
                "exit" -> "exit"
                "reload" -> "reload"
                else -> "unknown"
            } else "invalid_type"
            // Diagnose a command; do not execute remote reset/logout instructions.
            add(structuralFailure("device_command_received", "device_command", mapOf("deviceCommandKind" to kind)))
        }

        private fun visit(value: Any?, depth: Int): Boolean {
            if (depth > MAX_ERROR_DEPTH || remainingNodes-- <= 0 || failures.size >= MAX_ERRORS) {
                limited = true
                return false
            }
            if ((value is JSONObject || value is JSONArray) && !seen.add(value)) {
                limited = true
                return false
            }
            return true
        }

        private fun canContinue(): Boolean {
            if (remainingNodes <= 0 || failures.size >= MAX_ERRORS) {
                limited = true
                return false
            }
            return true
        }

        private fun add(failure: Failure) {
            if (failures.size < MAX_ERRORS) failures += failure else limited = true
        }
    }

    private fun structuralFailure(reason: String, container: String, fields: Map<String, Any?> = emptyMap()) = Failure(
        code = null, reason = reason, retryable = false,
        fields = mapOf("source" to "node_quark", "reason" to reason, "hasServerMessage" to false,
            "errorContainer" to container) + fields,
        mslResponseCode = null, authenticationRejected = false,
    )

    private fun classify(error: JSONObject, isMsl: Boolean, hasText: Boolean = false, container: String = "error"): Failure {
        val numericCode = (numericCodeKeys + if (isMsl) emptyList() else listOf("code")).firstNotNullOfOrNull { key ->
            numeric(error.opt(key))?.let { key to it.toString() }
        }
        // Native NqErrorStatus uses symbolic error.code as well as numeric Blade Runner codes.
        // Exact known literals only; an arbitrary all-uppercase string can still be a secret.
        val nqCode = if (isMsl) null else nativeCode(error.opt("code"))
        val exceptionCategory = if (isMsl) null else (error.opt("bladeRunnerExceptionType") as? String)
            ?.takeIf { it in DiagnosticPrivacy.nativeExceptionCategories }
        val code = numericCode?.second ?: nqCode ?: exceptionCategory
        val mslCode = if (isMsl) numeric(error.opt("errorcode")) else null
        // Looking for this one published category does not copy the adjacent opaque E2/E3 data.
        val policyFailure = textFields.any { (error.opt(it) as? String)?.contains("IssueLicensePolicyError") == true }
        val reason = when {
            policyFailure -> "license_policy_rejected"
            code == "1044" -> "license_rejected_1044"
            code == "205064" -> "playback_session_rejected"
            // Netflix's public Cadmium player: EMAILPASSWORD_INCORRECT, 100000 + 100000 + 5007.
            // This identifies the rejected method, not whether the user's account password is wrong.
            code == "205007" -> "email_password_authentication_rejected"
            // Confirmed in Netflix's public Cadmium player: 100000 + 100000 + 5032.
            code == "205032" -> "user_entity_mismatch"
            mslCode == 7L -> "user_data_reauthentication_required"
            mslCode == 2L -> "transient_service_failure"
            mslCode in listOf(3L, 4L, 6L, 7L, 10L) -> "authentication_rejected"
            mslCode in listOf(5L, 8L) -> "msl_session_rejected"
            mslCode == 1L -> "request_rejected"
            nqCode != null || exceptionCategory != null -> nativeReason(nqCode ?: exceptionCategory!!)
            else -> "service_rejected"
        }
        val retryable = when {
            code in setOf("1044", "205064") || policyFailure -> false
            mslCode == 2L -> true
            mslCode != null && mslCode in 1L..10L -> false
            nqCode == "RETRY" -> true
            nqCode != null || exceptionCategory != null -> false
            else -> null
        }
        val fields = linkedMapOf<String, Any?>(
            "serviceCode" to code,
            "source" to if (isMsl) "msl" else if (error.has("code")) "node_quark" else "blade_runner",
            "reason" to reason,
            "hasServerMessage" to (hasText || textFields.any { (error.opt(it) as? String)?.isNotBlank() == true }),
            "errorContainer" to container,
            "codeSource" to (numericCode?.first ?: if (nqCode != null) "code" else
                if (exceptionCategory != null) "bladeRunnerExceptionType" else "none"),
        )
        if (mslCode != null) fields["code"] = mslCode.toString()
        if (!isMsl) {
            fields["hasStructuredCode"] = error.has("code")
            fields["codeShape"] = shape(error, "code", nqCode)
            fields["hasExceptionType"] = error.has("bladeRunnerExceptionType")
            fields["exceptionTypeShape"] = shape(error, "bladeRunnerExceptionType", exceptionCategory)
            if (nqCode != null) fields["nqCode"] = nqCode
            if (exceptionCategory != null) fields["exceptionCategory"] = exceptionCategory
        }
        numericDetailKeys.forEach { key ->
            numeric(error.opt(key))?.let { fields[key] = it }
        }
        return Failure(code, reason, retryable, fields, mslCode?.toInt(),
            mslCode in listOf(4L, 7L) || code in setOf("205007", "205032"))
    }

    private fun nativeCode(value: Any?): String? = when (value) {
        "fail" -> "FAIL"
        "retry" -> "RETRY"
        is String -> value.takeIf { it in DiagnosticPrivacy.nativeServiceCodes }
        else -> null
    }

    private fun shape(error: JSONObject, key: String, known: String?): String {
        if (!error.has(key)) return "absent"
        val value = error.opt(key)
        return when {
            value == null || value == JSONObject.NULL -> "null"
            known != null -> "known"
            numeric(value) != null -> "numeric"
            value is String -> "unknown_string"
            else -> "invalid_type"
        }
    }

    private fun nativeReason(code: String): String = when (code) {
        "RETRY" -> "transient_service_failure"
        "INCORRECT_PIN", "INCORRECT_PIN_FAULT" -> "profile_pin_rejected"
        "ACCOUNT_ON_HOLD", "ACCOUNT_ON_HOLD_FAULT" -> "account_on_hold"
        "EXTRA_MEMBER_STREAM_HOLD" -> "extra_member_stream_hold"
        "ACCOUNT_NON_MEMBER" -> "account_non_member"
        "FREE_PREVIEW_ENDED" -> "free_preview_ended"
        "STREAM_QUOTA_EXCEEDED", "CONCURRENT_STREAM_QUOTA_EXCEEDED_FAULT" -> "stream_quota_exceeded"
        "BLACKLISTED_IP" -> "ip_rejected"
        "INSUFFICICENT_MATURITY" -> "maturity_restricted"
        "TITLE_OUT_OF_WINDOW" -> "title_unavailable"
        "AGE_VERIFICATION_REQUIRED" -> "age_verification_required"
        else -> "service_rejected"
    }

    private fun numeric(value: Any?): Long? {
        val text = when (value) {
            is Number -> value.toString()
            is String -> value
            else -> return null
        }
        if (!text.matches(Regex("-?[0-9]{1,10}"))) return null
        return text.toLongOrNull()?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
    }

    private fun userMessage(code: String?, stage: PlaybackStage): String = when (code) {
        "1044" -> if (stage == PlaybackStage.LICENSE) {
            "Netflix odmówił wydania licencji (1044). Sam kod nie wskazuje dokładnej przyczyny. Wyślij raport diagnostyczny Velvet."
        } else {
            "Netflix odrzucił odtwarzanie (1044). Sam kod nie wskazuje dokładnej przyczyny. Wyślij raport diagnostyczny Velvet."
        }
        "205064" -> "Netflix odrzucił sesję odtwarzania (205064). Sprawdź materiał w oficjalnej aplikacji i wyślij raport diagnostyczny Velvet."
        "205007" -> "Netflix odrzucił natywne uwierzytelnienie odtwarzania (205007). Sam ten błąd nie rozstrzyga, czy hasło do konta jest nieprawidłowe."
        "205032" -> "Netflix nie powiązał danych zalogowanego konta z tą sesją odtwarzania (205032). Odtwarzanie w Velvet pozostaje niedostępne."
        "107016" -> "Netflix odrzucił typ wymiany kluczy (107016). Wyślij raport diagnostyczny Velvet."
        else -> "Netflix odrzucił żądanie" + (code?.let { " ($it)" } ?: " bez rozpoznanego kodu błędu") + ". Wyślij raport diagnostyczny Velvet."
    }
}

/** Unwrap Media3's 6004 while retaining the actual, safely constructed Netflix stage and code. */
fun findNetflixPlaybackFailure(error: Throwable): NetflixPlaybackException? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    repeat(24) {
        val next = current ?: return null
        if (!seen.add(next)) return null
        if (next is NetflixPlaybackException) return next
        current = next.cause
    }
    return null
}
