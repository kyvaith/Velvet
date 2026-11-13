/*
 * SPDX-License-Identifier: MIT
 * MSL wire format adapted from CastagnaIT/plugin.video.netflix (commit 7accc24),
 * Copyright (C) 2017 Sebastian Golasch; 2018 Caphm; 2019-2020 Stefano Gottardo.
 * See root THIRD_PARTY_NOTICES.md. Kotlin implementation and validation: Velvet contributors.
 */
package pl.kyvaith.velvet.playback

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import pl.kyvaith.velvet.diagnostics.AppLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream

internal const val MAX_MSL_BYTES = 16 * 1024 * 1024

internal object MslEncoding {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw NetflixPlaybackException(PlaybackStage.KEY_EXCHANGE, message = "Nieprawidłowe kodowanie odpowiedzi Netflix.")
    }
    fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
    fun json(bytes: ByteArray): JSONObject = try {
        JSONObject(String(bytes, StandardCharsets.UTF_8))
    } catch (e: Exception) {
        throw NetflixPlaybackException(PlaybackStage.KEY_EXCHANGE, message = "Netflix zwrócił nieprawidłowy komunikat MSL.")
    }

    fun pad(bytes: ByteArray): ByteArray {
        val count = 16 - bytes.size % 16
        return bytes.copyOf(bytes.size + count).apply { fill(count.toByte(), bytes.size) }
    }

    fun unpad(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || bytes.size % 16 != 0) invalidPadding()
        val count = bytes.last().toInt() and 0xff
        if (count !in 1..16 || count > bytes.size) invalidPadding()
        for (i in bytes.size - count until bytes.size) if ((bytes[i].toInt() and 0xff) != count) invalidPadding()
        return bytes.copyOf(bytes.size - count)
    }

    private fun invalidPadding(): Nothing = throw NetflixPlaybackException(
        PlaybackStage.KEY_EXCHANGE, message = "Nieprawidłowa integralność zaszyfrowanej odpowiedzi MSL.",
    )
}

internal fun InputStream.readBounded(limit: Int = MAX_MSL_BYTES): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) throw NetflixPlaybackException(
            PlaybackStage.MANIFEST, message = "Odpowiedź Netflix przekroczyła dopuszczalny rozmiar.",
        )
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** MSL transport crypto is independent from Media3's Widevine content DRM. */
internal interface MslCrypto : Closeable {
    val keyExchangeScheme: String
    fun keyRequest(): JSONObject
    fun acceptKeyResponse(keyResponse: JSONObject)
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(envelope: ByteArray): ByteArray
    fun sign(message: ByteArray): ByteArray
    fun verify(message: ByteArray, signature: ByteArray): Boolean
}

internal data class MslOutgoing(val messageId: Long, val wire: String)

internal class MslWire(private val crypto: MslCrypto, private val random: SecureRandom = SecureRandom()) {
    fun handshake(identity: String, locale: String): MslOutgoing {
        val id = nextId()
        val headerData = commonHeader(id, locale).put("keyrequestdata", JSONArray().put(crypto.keyRequest()))
        val header = JSONObject()
            .put("entityauthdata", JSONObject().put("scheme", "NONE").put("authdata", JSONObject().put("identity", identity)))
            .put("headerdata", MslEncoding.encode(MslEncoding.utf8(headerData.toString())))
            .put("signature", "")
        val payload = JSONObject().put("messageid", id).put("data", "").put("sequencenumber", 1).put("endofmsg", true)
        return MslOutgoing(id, header.toString() + JSONObject().put("payload", MslEncoding.encode(MslEncoding.utf8(payload.toString()))).put("signature", ""))
    }

    fun request(context: PlaybackContext, masterToken: JSONObject, data: JSONObject,
                authentication: MslAuthentication = MslAuthentication.NetflixCookies): MslOutgoing {
        val bytes = MslEncoding.utf8(data.toString())
        return try { requestBytes(context, masterToken, bytes, authentication) }
        finally { bytes.fill(0) }
    }

    /** The native API accepts GraphQL JSON and form bytes without the playapi result envelope. */
    fun requestBytes(context: PlaybackContext, masterToken: JSONObject, data: ByteArray,
                     authentication: MslAuthentication = MslAuthentication.NetflixCookies): MslOutgoing {
        if (data.isEmpty() || data.size > MAX_MSL_BYTES) invalid("Nieprawidłowy rozmiar żądania MSL.")
        val id = nextId()
        val headerData = commonHeader(id, context.locale).put("sender", context.esn)
        authentication.apply(headerData, context)
        val headerBytes = MslEncoding.utf8(headerData.toString())
        val encryptedHeader = try { crypto.encrypt(headerBytes) } finally {
            headerBytes.fill(0)
            headerData.remove("userauthdata")
        }
        val header = JSONObject().put("mastertoken", masterToken)
            .put("headerdata", MslEncoding.encode(encryptedHeader))
            .put("signature", MslEncoding.encode(crypto.sign(encryptedHeader)))
        val payloadData = JSONObject().put("messageid", id).put("sequencenumber", 1).put("endofmsg", true)
            .put("data", MslEncoding.encode(data))
        val payloadBytes = MslEncoding.utf8(payloadData.toString())
        val encryptedPayload = try { crypto.encrypt(payloadBytes) } finally {
            payloadBytes.fill(0)
            payloadData.remove("data")
        }
        val payload = JSONObject().put("payload", MslEncoding.encode(encryptedPayload))
            .put("signature", MslEncoding.encode(crypto.sign(encryptedPayload)))
        return MslOutgoing(id, header.toString() + payload)
    }

    fun readHandshake(response: String, expectedMessageId: Long): JSONObject {
        val messages = parseObjects(response)
        val header = messages.firstOrNull() ?: invalid("Pusta odpowiedź na wymianę kluczy.")
        throwServiceError(header, PlaybackStage.KEY_EXCHANGE)
        val data = MslEncoding.json(MslEncoding.decode(header.optString("headerdata")))
        checkMessageId(data, responseMessageId(expectedMessageId))
        val keyResponse = data.optJSONObject("keyresponsedata") ?: invalid("Netflix nie udostępnił kluczy sesji MSL.")
        val responseScheme = keyResponse.optString("scheme")
        AppLog.info("key_exchange", "response_received", mapOf("requestedAlgorithm" to crypto.keyExchangeScheme,
            "responseAlgorithm" to responseScheme.takeIf { it in setOf("WIDEVINE", "ASYMMETRIC_WRAPPED", "SYMMETRIC_WRAPPED", "JWE_LADDER", "JWK_LADDER") }.orEmpty().ifBlank { "UNKNOWN" }))
        if (responseScheme != crypto.keyExchangeScheme) invalid("Netflix zwrócił inny typ wymiany kluczy niż żądany.")
        return keyResponse
    }

    class Response(val header: JSONObject, val result: Any, val masterToken: JSONObject? = null) {
        override fun toString(): String = "MslResponse(redacted)"
    }

    class RawResponse(val header: JSONObject, val body: JSONObject, val masterToken: JSONObject? = null) {
        override fun toString(): String = "MslRawResponse(redacted)"
    }

    fun readResponse(response: String, expectedMessageId: Long, stage: PlaybackStage, allowNullResult: Boolean = false): Response {
        val decoded = readRawJsonResponse(response, expectedMessageId, stage)
        val result = decoded.body.opt("result")?.takeUnless { it == JSONObject.NULL && !allowNullResult }
            ?: invalid("Brak wyniku w odpowiedzi Netflix.")
        return Response(decoded.header, result, decoded.masterToken)
    }

    /** Same authenticated framing as playapi; only the application JSON contract differs. */
    fun readRawJsonResponse(response: String, expectedMessageId: Long, stage: PlaybackStage): RawResponse {
        val objects = parseObjects(response)
        val outerHeader = objects.firstOrNull() ?: invalid("Pusta odpowiedź Netflix.")
        throwServiceError(outerHeader, stage)
        val header = verifiedDecrypt(outerHeader, "headerdata")
        val responseId = responseMessageId(expectedMessageId)
        checkMessageId(header, responseId)
        val output = ByteArrayOutputStream()
        var sequence = 1L
        var ended = false
        for (index in 1 until objects.size) {
            if (ended) invalid("Dane po końcu odpowiedzi MSL.")
            val payload = verifiedDecrypt(objects[index], "payload")
            checkMessageId(payload, responseId)
            if (payload.optLong("sequencenumber", -1L) != sequence++) invalid("Nieprawidłowa kolejność fragmentów MSL.")
            val bytes = MslEncoding.decode(payload.optString("data"))
            val decoded = when (payload.optString("compressionalgo")) {
                "", "null" -> bytes
                "GZIP" -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBounded() }
                else -> invalid("Netflix użył nieobsługiwanej kompresji MSL.")
            }
            if (output.size().toLong() + decoded.size > MAX_MSL_BYTES) invalid("Zbyt duża odpowiedź MSL.")
            output.write(decoded)
            ended = payload.optBoolean("endofmsg", false)
        }
        if (!ended) invalid("Niekompletna odpowiedź MSL.")
        val bodyBytes = output.toByteArray()
        val decoded = try { rawJson(bodyBytes) } finally { bodyBytes.fill(0) }
        throwServiceError(decoded, stage)
        return RawResponse(header, decoded, outerHeader.optJSONObject("mastertoken"))
    }

    private fun rawJson(bytes: ByteArray): JSONObject {
        if (bytes.isEmpty() || bytes.size > MAX_MSL_BYTES) invalid("Nieprawidłowy rozmiar odpowiedzi MSL.")
        val text = String(bytes, StandardCharsets.UTF_8)
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 128) invalid("Zbyt głęboko zagnieżdżona odpowiedź MSL.")
                '}', ']' -> if (--depth < 0) invalid("Nieprawidłowy JSON odpowiedzi MSL.")
            }
        }
        if (depth != 0 || quoted) invalid("Niekompletny JSON odpowiedzi MSL.")
        return try {
            val parser = JSONTokener(text)
            val value = parser.nextValue() as? JSONObject ?: invalid("Nieprawidłowy JSON odpowiedzi MSL.")
            if (parser.nextClean() != '\u0000') invalid("Dane po końcu JSON odpowiedzi MSL.")
            value
        } catch (error: NetflixPlaybackException) { throw error }
        catch (_: Exception) { invalid("Nie można odczytać JSON odpowiedzi MSL.") }
    }

    private fun verifiedDecrypt(objectData: JSONObject, key: String): JSONObject {
        val envelope = MslEncoding.decode(objectData.optString(key))
        val signature = MslEncoding.decode(objectData.optString("signature"))
        if (signature.isEmpty() || !crypto.verify(envelope, signature)) invalid("Podpis odpowiedzi MSL jest nieprawidłowy.")
        return MslEncoding.json(crypto.decrypt(envelope))
    }

    private fun commonHeader(id: Long, locale: String) = JSONObject().put("messageid", id).put("renewable", true)
        .put("capabilities", JSONObject().put("languages", JSONArray().put(locale)).put("compressionalgos", JSONArray()))
    private fun nextId(): Long = random.nextLong().ushr(12)

    companion object {
        fun parseCookieHeader(header: String): Map<String, String> = header.split(';').mapNotNull {
            val separator = it.indexOf('=')
            if (separator <= 0) null else it.substring(0, separator).trim() to it.substring(separator + 1).trim()
        }.toMap()

        // Netflix MSL ResponseMessageBuilder: a response increments the request ID, wrapping at 2^53-1.
        internal fun responseMessageId(requestId: Long): Long = if (requestId == 9007199254740991L) 0 else requestId + 1

        /** Concatenated JSON objects are not an array; braces inside strings must remain untouched. */
        fun parseObjects(value: String): List<JSONObject> {
            if (value.length > MAX_MSL_BYTES) invalid("Zbyt duży komunikat MSL.")
            return try {
                val parser = JSONTokener(value)
                val result = mutableListOf<JSONObject>()
                while (parser.nextClean() != '\u0000') {
                    parser.back()
                    val next = parser.nextValue()
                    if (next !is JSONObject) invalid("Nieprawidłowy format komunikatu MSL.")
                    result.add(next)
                    if (result.size > 4096) invalid("Zbyt wiele fragmentów komunikatu MSL.")
                }
                result
            } catch (e: NetflixPlaybackException) { throw e }
            catch (e: Exception) { invalid("Nie można odczytać odpowiedzi MSL Netflix.") }
        }

        fun throwServiceError(value: JSONObject, stage: PlaybackStage) {
            NetflixServiceErrors.throwIfError(value, stage)
        }

        private fun checkMessageId(value: JSONObject, expected: Long) {
            if (value.optLong("messageid", -1L) != expected) invalid("Odpowiedź MSL nie pasuje do wysłanego żądania.")
        }
        private fun invalid(message: String): Nothing = throw NetflixPlaybackException(PlaybackStage.KEY_EXCHANGE, message = message)
    }
}
