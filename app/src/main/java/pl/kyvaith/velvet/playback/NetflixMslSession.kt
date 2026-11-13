/*
 * SPDX-License-Identifier: MIT
 * Protocol adapted from msl_requests.py, msl_request_builder.py, base_crypto.py and msl_utils.py,
 * CastagnaIT/plugin.video.netflix commit 7accc24cfbf2bf798c037996119e08e6334d64cb.
 * Copyright (C) 2017 Sebastian Golasch; 2018 Caphm; 2019-2020 Stefano Gottardo.
 * See root THIRD_PARTY_NOTICES.md.
 */
package pl.kyvaith.velvet.playback

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import pl.kyvaith.velvet.diagnostics.AppLog
import java.io.Closeable

/** Account authentication is selected independently of the transport key exchange. */
internal enum class MslAuthenticationMode { COOKIE_SESSION, NATIVE_USER_TOKEN }

internal class NetflixMslSession(
    private val session: BoundPlaybackSession,
    private val transport: MslTransport,
    val crypto: MslCrypto,
    private val authenticationMode: MslAuthenticationMode = MslAuthenticationMode.COOKIE_SESSION,
    private val handshakeEndpoint: String = MANIFEST_ENDPOINT,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : Closeable {
    val context: PlaybackContext get() = session.current()
    private val wire = MslWire(crypto)
    private var masterToken: JSONObject? = null
    private var masterExpiration = 0L
    private val nativeKeyExchange = crypto.keyExchangeScheme == "WIDEVINE"
    private val usesNativeTokens = authenticationMode == MslAuthenticationMode.NATIVE_USER_TOKEN
    private var ownerToken: JSONObject? = null
    private var profileToken: JSONObject? = null
    private var pendingOwner: NativeMslTokenPair? = null
    private var renewalProfile: NativeMslTokenPair? = null
    private var tokenAuthenticationAttempted = false
    private var tokenActivationCompleted = false
    private var closed = false

    /**
     * Native token activation is separate from playapi. The short-lived NFToken is consumed
     * only by the first HTTP operation; later operations use the provider's private cookie jar.
     * An owner UIT is not required until config and account verification have both completed.
     */
    @Synchronized
    fun authenticateWithToken(token: NativeLoginToken, configPayload: ByteArray, checkActive: () -> Unit = {}) {
        try {
            checkActive()
            checkOpen(PlaybackStage.AUTHENTICATION)
            if (!nativeKeyExchange || !usesNativeTokens || transport !is NativeMslTransport) {
                throw nativeActivationFailure("Brak natywnego transportu autoryzacji Netflix.")
            }
            if (configPayload.isEmpty() || configPayload.size > MAX_MSL_BYTES) {
                throw nativeActivationFailure("Nieprawidłowe żądanie konfiguracji natywnej.")
            }
            tokenAuthenticationAttempted = true
            tokenActivationCompleted = false
            ownerToken = null
            profileToken = null
            pendingOwner = null
            renewalProfile = null
            ensureHandshake()
            checkActive()

            val candidates = mutableListOf<JSONObject>()
            val consumePayload = nativeGraphQlPayload("ConsumeAutoLoginToken", CONSUME_TOKEN_ID,
                JSONObject().put("logOnly", false))
            val consume = try {
                sendNativeBytes(NativeMslOperation.CONSUME_TOKEN, consumePayload, MslAuthentication.None, token.value(), checkActive)
            } finally { consumePayload.fill(0); token.close() }
            validateGraphQl(consume.body)
            val consumeOwner = optionalConsumeOwner(consume.body)
            consume.header.optJSONObject("useridtoken")?.let(candidates::add)

            // A successful GraphQL body alone is not a native session. Require the complete
            // newly redeemed cookie pair before serializing NETFLIXID into a later header.
            PlaybackCookies.snapshot(context.cookieHeader)
            val config = sendNativeBytes(NativeMslOperation.FETCH_CONFIG, configPayload, MslAuthentication.NetflixCookies,
                checkActive = checkActive)
            config.header.optJSONObject("useridtoken")?.let(candidates::add)

            val accountPayload = nativeGraphQlPayload("AccountQuery", ACCOUNT_QUERY_ID, JSONObject())
            val account = try {
                sendNativeBytes(NativeMslOperation.FETCH_ACCOUNT, accountPayload, MslAuthentication.NetflixCookies,
                    checkActive = checkActive)
            } finally { accountPayload.fill(0) }
            validateGraphQl(account.body)
            verifyNativeAccount(account.body, consumeOwner)
            account.header.optJSONObject("useridtoken")?.let(candidates::add)
            val currentMaster = requireMasterToken()
            val owner = candidates.asReversed().firstOrNull {
                runCatching { MslTokenValidation.user(it, currentMaster, nowSeconds()) }.isSuccess
            } ?: throw nativeActivationFailure("Netflix nie zwrócił prawidłowego tokena natywnej autoryzacji konta.")
            ownerToken = owner
            pendingOwner = NativeMslTokenPair(context.esn, currentMaster, owner).also { it.validate(nowSeconds()) }
            AppLog.info("authentication", "native_token_activation_success", mapOf("success" to true))
            // Keep the pair pending until the existing SWITCH_PROFILE gate confirms the
            // selected profile with its own signed UIT. Nothing is persisted here.
        } catch (error: Exception) {
            ownerToken = null
            profileToken = null
            pendingOwner = null
            renewalProfile = null
            tokenActivationCompleted = false
            if (error is CancellationException) throw error
            AppLog.error("authentication", "native_token_activation_failed", error,
                mapOf("serviceCode" to (error as? NetflixPlaybackException)?.serviceCode))
            throw authenticationFailure(error, invalidateCachedPair = false)
        } finally { token.close() }
    }

    private fun nativeGraphQlPayload(name: String, id: String, variables: JSONObject): ByteArray = MslEncoding.utf8(
        JSONObject().put("operationName", name).put("variables", variables)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 102).put("id", id)))
            .toString(),
    )

    private fun validateGraphQl(body: JSONObject) {
        val errors = body.opt("errors")
        if (errors != null && errors != JSONObject.NULL && (errors !is JSONArray || errors.length() != 0)) {
            throw nativeActivationFailure("Netflix odrzucił natywne uwierzytelnienie konta.")
        }
        if (body.optJSONObject("data") == null) throw nativeActivationFailure("Brak danych natywnej autoryzacji konta.")
    }

    private fun optionalConsumeOwner(body: JSONObject): String? {
        val upgrade = body.getJSONObject("data").opt("deviceUpgrade")
        if (upgrade == null || upgrade == JSONObject.NULL) return null
        if (upgrade !is JSONObject) throw nativeActivationFailure("Nieprawidłowa odpowiedź natywnego logowania.")
        val account = upgrade.opt("account")
        if (account == null || account == JSONObject.NULL) return null
        if (account !is JSONObject) throw nativeActivationFailure("Nieprawidłowa odpowiedź natywnego konta.")
        if (!account.has("ownerGuid")) return null
        return nativeGuid(account.opt("ownerGuid"))
    }

    private fun verifyNativeAccount(body: JSONObject, consumeOwner: String?) {
        val account = body.getJSONObject("data").optJSONObject("account")
            ?: throw nativeActivationFailure("Netflix nie potwierdził natywnego konta.")
        val owner = nativeGuid(account.opt("ownerGuid"))
        if (consumeOwner != null && consumeOwner != owner) {
            throw nativeActivationFailure("Konto natywnego odtwarzania zmieniło się podczas autoryzacji.")
        }
        val nonMember = account.opt("isNonMember")
        if (nonMember != null && nonMember != JSONObject.NULL && (nonMember !is Boolean || nonMember)) {
            throw nativeActivationFailure("Netflix nie potwierdził aktywnego konta odtwarzania.")
        }
        val profiles = account.optJSONArray("profiles")
            ?: throw nativeActivationFailure("Netflix nie potwierdził profili natywnego konta.")
        if (profiles.length() !in 1..128) throw nativeActivationFailure("Nieprawidłowa lista profili natywnego konta.")
        val guids = HashSet<String>()
        for (index in 0 until profiles.length()) {
            val profile = profiles.optJSONObject(index)
                ?: throw nativeActivationFailure("Nieprawidłowa odpowiedź profili natywnego konta.")
            if (!guids.add(nativeGuid(profile.opt("guid")))) {
                throw nativeActivationFailure("Niejednoznaczna lista profili natywnego konta.")
            }
        }
        if (context.profileGuid !in guids) {
            throw nativeActivationFailure("Wybrany profil nie należy do konta potwierdzonego przez natywną sesję.")
        }
        AppLog.info("authentication", "native_account_verified", mapOf("success" to true))
    }

    private fun nativeGuid(value: Any?): String = (value as? String)?.takeIf {
        it.isNotBlank() && it.length <= 512 && it != "invalid_guid" && it.none { character -> character.isISOControl() }
    } ?: throw nativeActivationFailure("Netflix zwrócił nieprawidłową tożsamość natywnego konta lub profilu.")

    private fun nativeActivationFailure(message: String) = NetflixPlaybackException(
        PlaybackStage.AUTHENTICATION, message = message, retryable = false,
    )

    /** Consumes explicit credentials; no password is retained for renewal or later playback. */
    @Synchronized
    fun authenticate(credentials: NetflixPlaybackCredentials?) {
        try {
            checkOpen(PlaybackStage.AUTHENTICATION)
            if (!usesNativeTokens) return
            ensureHandshake()
            if (credentials == null && profileToken != null) return
            ownerToken = null
            profileToken = null
            pendingOwner = null
            bootstrapOwner(credentials)
        } catch (error: Exception) {
            throw authenticationFailure(error, invalidateCachedPair = credentials == null)
        } finally { credentials?.close() }
    }

    /** Single-flight: Media3 can request video and audio keys concurrently. */
    @Synchronized
    fun exchange(endpoint: String, operation: String, params: Any, stage: PlaybackStage, echo: String = ""): Any =
        exchangeAuthenticated(stage) { authentication -> send(endpoint, operation, params, stage, echo, authentication) }

    /** Native Android has a distinct HTTP route and application envelope; MSL ownership is shared. */
    @Synchronized
    fun exchangeNativePlayback(operation: NativePlaybackOperation, operationPath: String, params: Any,
                               stage: PlaybackStage): Any {
        val expected = if (operation == NativePlaybackOperation.MANIFEST) PlaybackStage.MANIFEST else PlaybackStage.LICENSE
        if (!usesNativeTokens || !nativeKeyExchange || stage != expected) throw NetflixPlaybackException(stage,
            message = "Nieprawidłowy kontekst natywnego odtwarzania Netflix.", retryable = false)
        return exchangeAuthenticated(stage, nativeRoute = true) { authentication ->
            sendNativePlayback(operation, operationPath, params, stage, authentication)
        }
    }

    private fun exchangeAuthenticated(stage: PlaybackStage, nativeRoute: Boolean = false, sendRequest: (MslAuthentication) -> MslWire.Response): Any {
        checkOpen(stage)
        try {
            // Re-establishing a native account requires a fresh NFToken flow. Never renew
            // a native playback through the legacy WWW logblob/token-reauthentication route.
            if (nativeRoute && (ownerToken == null || masterToken == null || renewalProfile != null ||
                    masterExpiration <= nowSeconds() + 30)) {
                throw playbackAuthorizationUnavailable("Natywna sesja odtwarzania wygasła. Otwórz materiał ponownie.")
            }
            // Native activation already installed this master; a second clock check must
            // not turn a near-expiry request into legacy WWW reauthentication.
            if (!nativeRoute) ensureHandshake()
            if (!usesNativeTokens) return sendRequest(MslAuthentication.NetflixCookies).result
            prepareNativeAuthentication(stage)
            val selectingProfile = profileToken == null
            val auth = if (selectingProfile) MslAuthentication.SwitchProfile(ownerToken ?: throw playbackAuthorizationUnavailable())
                else MslAuthentication.UserToken(profileToken!!)
            val response = sendRequest(auth)
            val returnedToken = response.header.optJSONObject("useridtoken")
            if (selectingProfile && returnedToken == null) throw NetflixPlaybackException(PlaybackStage.AUTHENTICATION,
                message = "Netflix nie potwierdził wybranego profilu dla natywnego odtwarzania.", retryable = false)
            if (returnedToken != null) {
                MslTokenValidation.user(returnedToken, requireMasterToken(), nowSeconds())
                profileToken = returnedToken
            }
            commitPendingOwner()
            return response.result
        } catch (error: Exception) {
            if (usesNativeTokens && isAuthenticationRejection(error)) throw authenticationFailure(error, true)
            throw error
        }
    }

    private fun sendNativePlayback(operation: NativePlaybackOperation, operationPath: String, params: Any,
                                   stage: PlaybackStage, authentication: MslAuthentication): MslWire.Response {
        val nativeTransport = transport as? NativeMslTransport ?: throw NetflixPlaybackException(stage,
            message = "Brak natywnego transportu odtwarzania Netflix.", retryable = false)
        val operationId = AppLog.nextOperationId()
        val started = System.nanoTime()
        val logStage = stage.name.lowercase(java.util.Locale.ROOT)
        AppLog.info(logStage, "native_playback_exchange_start", mapOf("operationId" to operationId,
            "operation" to operation.requestName, "manifestContract" to "native_android_v1"))
        try {
            val requestContext = context
            val payload = NativePlaybackRequests.envelope(operation, operationPath, params, requestContext.locale)
            val outgoing = wire.request(requestContext, requireMasterToken(), payload, authentication)
            AppLog.info(logStage, "msl_auth", mapOf("operationId" to operationId,
                "authScheme" to authentication.scheme, "operation" to authentication.mode))
            val response = nativeTransport.playbackPost(operation, outgoing.wire, requestContext, stage, operationId)
            val decoded = wire.readResponse(response, outgoing.messageId, stage)
            context // A response from a previous account/profile cannot update this session.
            applyTokenRenewal(decoded)
            AppLog.info(logStage, "native_playback_exchange_success", mapOf("operationId" to operationId,
                "operation" to operation.requestName, "durationMs" to (System.nanoTime() - started) / 1_000_000))
            return decoded
        } catch (error: Exception) {
            AppLog.error(logStage, "native_playback_exchange_failed", error, mapOf("operationId" to operationId,
                "operation" to operation.requestName, "durationMs" to (System.nanoTime() - started) / 1_000_000,
                "serviceCode" to (error as? NetflixPlaybackException)?.serviceCode))
            throw error
        }
    }

    private fun prepareNativeAuthentication(stage: PlaybackStage) {
        val active = profileToken
        if (active != null) {
            try { MslTokenValidation.user(active, requireMasterToken(), nowSeconds()); return }
            catch (_: NetflixPlaybackException) { profileToken = null }
        }
        if (ownerToken == null) bootstrapOwner(null)
        val previousProfile = renewalProfile
        if (previousProfile != null) {
            // The old profile pair exists only in RAM and belongs to this captured profile.
            // Do not send SWITCH_PROFILE to the license endpoint during a mid-film renewal.
            renewalProfile = null // One attempt, including failures.
            previousProfile.validate(nowSeconds())
            val response = send(endpoint(LOGBLOB_ENDPOINT, "bind"), "/logblob", nativeLogblobParams(context, nowSeconds() * 1000),
                PlaybackStage.AUTHENTICATION, "", MslAuthentication.TokenReauthentication(previousProfile, owner = false), true)
            profileToken = requiredUserToken(response)
            commitPendingOwner()
        } else if (stage != PlaybackStage.MANIFEST) {
            throw NetflixPlaybackException(PlaybackStage.AUTHENTICATION,
                message = "Sesja profilu odtwarzania wygasła. Otwórz materiał ponownie.", retryable = false)
        }
    }

    private fun bootstrapOwner(credentials: NetflixPlaybackCredentials?) {
        val auth = if (credentials != null) MslAuthentication.EmailPassword(credentials) else {
            if (tokenAuthenticationAttempted && !tokenActivationCompleted) throw playbackAuthorizationUnavailable(
                "Natywna sesja odtwarzania wygasła. Otwórz materiał ponownie.")
            val record = NativeMslTokenPair.readOwnerRecord(session.loadNativeAuthentication(), context.esn, nowSeconds())
            MslAuthentication.TokenReauthentication(record, owner = true)
        }
        AppLog.info("authentication", "native_auth_start", mapOf("authScheme" to auth.scheme, "operation" to auth.mode))
        val response = send(endpoint(LOGBLOB_ENDPOINT, "bind"), "/logblob", nativeLogblobParams(context, nowSeconds() * 1000),
            PlaybackStage.AUTHENTICATION, "", auth, true)
        val token = requiredUserToken(response)
        ownerToken = token
        pendingOwner = NativeMslTokenPair(context.esn, requireMasterToken(), token)
        // Do not persist yet: valid credentials for a different account must not replace the
        // catalog account's record. A verified SWITCH_PROFILE response is the next gate.
        AppLog.info("authentication", "native_owner_authenticated", mapOf("authScheme" to auth.scheme, "success" to true))
    }

    private fun requiredUserToken(response: MslWire.Response): JSONObject {
        val token = response.header.optJSONObject("useridtoken") ?: throw NetflixPlaybackException(PlaybackStage.AUTHENTICATION,
            message = "Netflix nie zwrócił tokena autoryzacji natywnego odtwarzania.", retryable = false)
        MslTokenValidation.user(token, requireMasterToken(), nowSeconds())
        return token
    }

    private fun commitPendingOwner() {
        val pair = pendingOwner ?: return
        val selected = profileToken ?: return
        MslTokenValidation.user(selected, requireMasterToken(), nowSeconds())
        pair.validate(nowSeconds())
        session.saveNativeAuthentication(pair.ownerRecord()) // Atomic account/profile ownership guard.
        if (tokenAuthenticationAttempted) tokenActivationCompleted = true
        pendingOwner = null
        AppLog.info("authentication", "native_profile_authenticated", mapOf("success" to true))
    }

    private fun send(endpoint: String, operation: String, params: Any, stage: PlaybackStage, echo: String,
                     authentication: MslAuthentication, allowNullResult: Boolean = false): MslWire.Response {
        val operationId = AppLog.nextOperationId()
        val started = System.nanoTime()
        val logStage = stage.name.lowercase(java.util.Locale.ROOT)
        AppLog.info(logStage, "msl_exchange_start", mapOf("operationId" to operationId, "keyExchange" to crypto.keyExchangeScheme))
        try {
            val requestContext = context
            val request = JSONObject().put("version", 2).put("url", operation)
                .put("id", System.currentTimeMillis() * 100000L)
                .put("languages", JSONArray().put(requestContext.locale)).put("params", params).put("echo", echo)
            val outgoing = wire.request(requestContext, requireMasterToken(), request, authentication)
            AppLog.info(logStage, "msl_auth", mapOf("operationId" to operationId,
                "authScheme" to authentication.scheme, "operation" to authentication.mode))
            val response = transport.netflixPost(endpoint, outgoing.wire, requestContext, stage, operationId)
            val decoded = wire.readResponse(response, outgoing.messageId, stage, allowNullResult)
            context // Reject an account/profile change that happened while the response was in flight.
            applyTokenRenewal(decoded)
            AppLog.info(logStage, "msl_exchange_success", mapOf("operationId" to operationId,
                "durationMs" to (System.nanoTime() - started) / 1_000_000))
            return decoded
        } catch (error: Exception) {
            // A deployment may disable the formal USER_ID_TOKEN factory. A permanent
            // rejection of this one attempted reauthentication must not trap the caller
            // behind an unusable cache. Entity/key recovery and transient/HTTP errors do
            // not imply that fresh user credentials are needed.
            val failure = if (authentication is MslAuthentication.TokenReauthentication &&
                error is NetflixPlaybackException && error.mslResponseCode in setOf(1, 4, 7, 10)) {
                playbackAuthorizationUnavailable(serviceCode = error.serviceCode, mslResponseCode = error.mslResponseCode)
            } else error
            AppLog.error(logStage, "msl_exchange_failed", failure, mapOf("operationId" to operationId,
                "durationMs" to (System.nanoTime() - started) / 1_000_000,
                "serviceCode" to (failure as? NetflixPlaybackException)?.serviceCode))
            throw failure
        }
    }

    private fun sendNativeBytes(operation: NativeMslOperation, payload: ByteArray, authentication: MslAuthentication,
                                loginToken: String? = null, checkActive: () -> Unit = {}): MslWire.RawResponse {
        checkActive()
        val nativeTransport = transport as? NativeMslTransport
            ?: throw nativeActivationFailure("Brak natywnego transportu autoryzacji Netflix.")
        val operationId = AppLog.nextOperationId()
        val started = System.nanoTime()
        val operationName = operation.name.lowercase(java.util.Locale.ROOT)
        AppLog.info("authentication", "native_msl_exchange_start", mapOf("operationId" to operationId,
            "operation" to operationName, "authScheme" to authentication.scheme))
        try {
            val requestContext = context
            val request = wire.requestBytes(requestContext, requireMasterToken(), payload, authentication)
            val response = nativeTransport.nativePost(operation, request.wire, requestContext, operationId, loginToken)
            checkActive()
            val decoded = wire.readRawJsonResponse(response, request.messageId, PlaybackStage.AUTHENTICATION)
            context // The provider rechecks the captured account/profile after every network operation.
            applyTokenRenewal(decoded.header, decoded.masterToken)
            AppLog.info("authentication", "native_msl_exchange_success", mapOf("operationId" to operationId,
                "operation" to operationName, "durationMs" to (System.nanoTime() - started) / 1_000_000))
            return decoded
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            AppLog.error("authentication", "native_msl_exchange_failed", error, mapOf("operationId" to operationId,
                "operation" to operationName, "durationMs" to (System.nanoTime() - started) / 1_000_000,
                "serviceCode" to (error as? NetflixPlaybackException)?.serviceCode))
            throw error
        }
    }

    private fun ensureHandshake() {
        if (masterToken != null && masterExpiration > nowSeconds() + 30) return
        val previousMaster = masterToken
        val previousProfile = profileToken
        renewalProfile = if (usesNativeTokens && previousMaster != null && previousProfile != null) {
            NativeMslTokenPair(context.esn, previousMaster, previousProfile).takeIf {
                runCatching { it.validate(nowSeconds()) }.isSuccess
            }
        } else null
        ownerToken = null
        profileToken = null
        pendingOwner = null
        masterToken = null
        masterExpiration = 0
        val operationId = AppLog.nextOperationId()
        AppLog.info("key_exchange", "handshake_start", mapOf("operationId" to operationId,
            "keyExchange" to crypto.keyExchangeScheme,
            "requestedAlgorithm" to when (crypto.keyExchangeScheme) {
                "ASYMMETRIC_WRAPPED" -> "JWK_RSA"
                "WIDEVINE" -> "CDM_GENERIC"
                else -> "UNKNOWN"
            },
            "esnFamily" to identityFamily(context.esn)))
        try {
            val requestContext = context
            val request = wire.handshake(requestContext.esn, requestContext.locale)
            val response = transport.netflixPost(handshakeEndpoint, request.wire, requestContext, PlaybackStage.KEY_EXCHANGE, operationId)
            val keyResponse = wire.readHandshake(response, request.messageId)
            crypto.acceptKeyResponse(keyResponse)
            setMasterToken(keyResponse.getJSONObject("mastertoken"))
            AppLog.info("key_exchange", "handshake_success", mapOf("operationId" to operationId, "keyExchange" to crypto.keyExchangeScheme))
        } catch (error: Exception) {
            AppLog.error("key_exchange", "handshake_failed", error, mapOf("operationId" to operationId,
                "serviceCode" to (error as? NetflixPlaybackException)?.serviceCode, "keyExchange" to crypto.keyExchangeScheme))
            throw error
        }
    }

    private fun setMasterToken(token: JSONObject) {
        if (nativeKeyExchange) {
            val metadata = MslTokenValidation.master(token, nowSeconds())
            masterToken = token
            masterExpiration = metadata.expiration
            return
        }
        val data = MslEncoding.json(MslEncoding.decode(token.getString("tokendata")))
        val expiration = data.optLong("expiration", 0)
        if (expiration <= nowSeconds()) throw NetflixPlaybackException(
            PlaybackStage.KEY_EXCHANGE, message = "Netflix zwrócił wygasły token sesji MSL.",
        )
        masterToken = token
        masterExpiration = expiration
    }

    private fun applyTokenRenewal(response: MslWire.Response) = applyTokenRenewal(response.header, response.masterToken)

    private fun applyTokenRenewal(header: JSONObject, master: JSONObject?) {
        // Ordinary requests send no keyrequestdata. A key response here cannot correspond to
        // a pending CDM nonce. Reject it after verification; do not replace keys or cached UITs.
        if (header.has("keyresponsedata")) throw NetflixPlaybackException(PlaybackStage.KEY_EXCHANGE,
            message = "Netflix zwrócił nieoczekiwaną wymianę kluczy. Otwórz materiał ponownie.", retryable = false)
        val renewed = master ?: return
        if (renewed.toString() == requireMasterToken().toString()) return
        if (nativeKeyExchange) {
            val before = MslTokenValidation.master(requireMasterToken(), nowSeconds())
            val after = MslTokenValidation.master(renewed, nowSeconds())
            val newerSequence = when {
                after.sequence == before.sequence -> true
                after.sequence > before.sequence -> before.sequence >= after.sequence - 9007199254740991L + 127
                else -> after.sequence < before.sequence - 9007199254740991L + 127
            }
            if (before.serial != after.serial || after.expiration < before.expiration || !newerSequence) throw NetflixPlaybackException(
                PlaybackStage.KEY_EXCHANGE, message = "Netflix zwrócił niezgodne odnowienie sesji MSL.", retryable = false)
        }
        setMasterToken(renewed)
        ownerToken?.let { owner ->
            MslTokenValidation.user(owner, renewed, nowSeconds())
            pendingOwner = NativeMslTokenPair(context.esn, renewed, owner)
        }
    }

    private fun requireMasterToken(): JSONObject = masterToken ?: throw NetflixPlaybackException(
        PlaybackStage.KEY_EXCHANGE, message = "Brak aktywnej sesji wymiany kluczy MSL.")

    private fun checkOpen(stage: PlaybackStage) {
        if (closed) throw NetflixPlaybackException(stage, message = "Sesja odtwarzania została zamknięta.")
    }

    private fun isAuthenticationRejection(error: Exception): Boolean = error is NetflixPlaybackException &&
        (error.authenticationRejected || error.mslResponseCode in setOf(4, 7))

    private fun authenticationFailure(error: Exception, invalidateCachedPair: Boolean): Exception {
        if (error is CancellationException) return error
        if (isAuthenticationRejection(error)) {
            ownerToken = null
            profileToken = null
            pendingOwner = null
            renewalProfile = null
            tokenActivationCompleted = false
            if (invalidateCachedPair) session.saveNativeAuthentication("")
            val rejected = error as NetflixPlaybackException
            AppLog.warn("authentication", "native_auth_rejected", mapOf("serviceCode" to rejected.serviceCode))
            return playbackAuthorizationUnavailable(serviceCode = rejected.serviceCode, mslResponseCode = rejected.mslResponseCode)
        }
        if (error is NetflixPlaybackException) return error
        AppLog.error("authentication", "native_auth_failed", error)
        return NetflixPlaybackException(PlaybackStage.AUTHENTICATION,
            message = "Nie udało się autoryzować natywnego odtwarzania Netflix.", retryable = false)
    }

    fun endpoint(base: String, requestName: String): String = base.toHttpUrl().newBuilder()
        .addQueryParameter("reqAttempt", "1")
        .addQueryParameter("reqName", requestName)
        .addQueryParameter("clienttype", "akira")
        .addQueryParameter("uiversion", context.buildIdentifier)
        .addQueryParameter("browsername", "chrome")
        .addQueryParameter("browserversion", context.browserVersion.lowercase(java.util.Locale.ROOT))
        .addQueryParameter("osname", context.osName.lowercase(java.util.Locale.ROOT))
        .addQueryParameter("osversion", context.osVersion)
        .build().toString()

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        masterToken = null
        ownerToken = null
        profileToken = null
        pendingOwner = null
        renewalProfile = null
        tokenActivationCompleted = false
        crypto.close()
        AppLog.info("release", "msl_closed")
    }

    companion object {
        const val MANIFEST_ENDPOINT = "https://www.netflix.com/msl/playapi/cadmium/licensedmanifest/1"
        const val LICENSE_ENDPOINT = "https://www.netflix.com/nq/msl_v1/cadmium/pbo_licenses/%5E1.0.0/router"
        const val LOGBLOB_ENDPOINT = "https://www.netflix.com/msl/playapi/cadmium/logblob/1"
        private const val CONSUME_TOKEN_ID = "dc3bac4c-760d-42c3-b41c-2cdb81e5fa5b"
        private const val ACCOUNT_QUERY_ID = "81b80497-2447-4ede-9e32-124ec928e107"

        private fun identityFamily(esn: String): String = when {
            esn.startsWith("NFCD") -> "BROWSER"
            esn.startsWith("NFANDROID") -> "ANDROID"
            else -> "UNKNOWN"
        }
    }
}
