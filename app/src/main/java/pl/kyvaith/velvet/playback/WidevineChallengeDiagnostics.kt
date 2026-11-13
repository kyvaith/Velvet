// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

/**
 * A bounded, read-only observation of field presence, NOT a Widevine implementation or signature
 * validator. Field numbers come from this pinned, community-published schema (not a Google API
 * contract); no implementation or device data is copied from that project:
 * https://github.com/devine-dl/pywidevine/blob/e58510279825bd3422b2d9c123a034e633742e3e/pywidevine/license_protocol.proto
 *
 * SignedMessage: type=1 (LICENSE_REQUEST=1), msg=2, signature=3.
 * LicenseRequest: client_id=1, content_id=2, type=3, encrypted_client_id=8.
 * Only tags, lengths and message-type enums are inspected. Identity fields, content identifiers,
 * signatures and other opaque values are skipped without reading their contents or copying bytes.
 * Unknown/malformed formats yield "unknown" plus a closed reason enum, never device values.
 * This observer never changes the request sent to Netflix or controls whether it is sent.
 */
internal object WidevineChallengeDiagnostics {
    private const val MAX_CHALLENGE_BYTES = 64 * 1024
    private const val MAX_FIELDS = 64

    private enum class Reason(val label: String) {
        EMPTY("empty"), LIMIT("limit"), MALFORMED("malformed"), INVALID_WIRE("invalid_wire"),
        UNSUPPORTED_TYPE("unsupported_type"), MISSING_ENVELOPE("missing_envelope"),
        MISSING_CONTENT("missing_content"), MISSING_IDENTITY("missing_identity"),
        AMBIGUOUS("ambiguous"), OBSERVER_ERROR("observer_error"),
    }

    private fun unknown(reason: Reason): Map<String, Any> = mapOf(
        "challengeStructure" to "unknown", "challengeReason" to reason.label,
    )

    private class Unobservable(val reason: Reason) : RuntimeException(null, null, false, false)
    private fun stop(reason: Reason): Nothing = throw Unobservable(reason)

    fun fields(challenge: ByteArray): Map<String, Any> {
        if (challenge.isEmpty()) return unknown(Reason.EMPTY)
        if (challenge.size > MAX_CHALLENGE_BYTES) return unknown(Reason.LIMIT)
        // Diagnostics must never replace a native request with a parser failure or a new payload.
        val presence = try { inspect(challenge) }
        catch (error: Unobservable) { return unknown(error.reason) }
        catch (_: RuntimeException) { return unknown(Reason.OBSERVER_ERROR) }
        return mapOf(
            "challengeStructure" to "license_request",
            "hasClientIdField" to presence.clientId,
            "hasEncryptedClientIdField" to presence.encryptedClientId,
        )
    }

    private data class Presence(val clientId: Boolean, val encryptedClientId: Boolean)
    private data class Span(val start: Int, val end: Int) {
        val nonEmpty: Boolean get() = end > start
    }
    private class Budget {
        private var fields = 0
        fun next() { if (++fields > MAX_FIELDS) stop(Reason.LIMIT) }
    }

    private fun inspect(data: ByteArray): Presence {
        val budget = Budget()
        val outer = Cursor(data, 0, data.size)
        var seen = 0
        var hasSignature = false
        var message: Span? = null
        while (!outer.finished) {
            budget.next()
            val (field, wire) = outer.tag()
            if (field !in 1..9) { outer.skip(wire); continue }
            val bit = 1 shl field
            // Only metric_data (6) is repeated in the known envelope.
            if (field != 6 && seen and bit != 0) stop(Reason.AMBIGUOUS)
            seen = seen or bit
            when (field) {
                1 -> {
                    expectWire(wire, 0)
                    if (outer.varint() != 1L) stop(Reason.UNSUPPORTED_TYPE)
                }
                2 -> {
                    expectWire(wire, 2)
                    message = outer.bytes()
                }
                3 -> {
                    expectWire(wire, 2)
                    hasSignature = outer.bytes().nonEmpty
                }
                8 -> {
                    expectWire(wire, 0)
                    outer.varint()
                }
                else -> {
                    expectWire(wire, 2)
                    outer.bytes()
                }
            }
        }
        val body = message ?: stop(Reason.MISSING_ENVELOPE)
        if (!body.nonEmpty || !hasSignature) stop(Reason.MISSING_ENVELOPE)
        // Both type fields are optional proto2 enums. An omitted outer type defaults to the
        // first enum value, LICENSE_REQUEST=1; an explicit different value is not reinterpreted.
        return inspectRequest(Cursor(data, body.start, body.end), budget)
    }

    private fun inspectRequest(request: Cursor, budget: Budget): Presence {
        var seen = 0
        var clientId = false
        var encryptedClientId = false
        var hasContent = false
        while (!request.finished) {
            budget.next()
            val (field, wire) = request.tag()
            if (field !in 1..8) { request.skip(wire); continue }
            val bit = 1 shl field
            if (seen and bit != 0) stop(Reason.AMBIGUOUS)
            seen = seen or bit
            when (field) {
                1, 2, 5, 8 -> {
                    expectWire(wire, 2)
                    val value = request.bytes()
                    when (field) {
                        1 -> clientId = value.nonEmpty
                        2 -> hasContent = value.nonEmpty
                        8 -> encryptedClientId = value.nonEmpty
                    }
                }
                3 -> {
                    expectWire(wire, 0)
                    if (request.varint() !in 1L..3L) stop(Reason.UNSUPPORTED_TYPE)
                }
                else -> {
                    expectWire(wire, 0)
                    request.varint()
                }
            }
        }
        // The pinned schema requires one identity form and forbids both simultaneously.
        // Even an empty identity field alongside the other form is ambiguous. An omitted
        // request type defaults to NEW=1, but never replaces the content/identity anchors.
        if (seen and (1 shl 1) != 0 && seen and (1 shl 8) != 0) stop(Reason.AMBIGUOUS)
        if (!hasContent) stop(Reason.MISSING_CONTENT)
        if (!clientId && !encryptedClientId) stop(Reason.MISSING_IDENTITY)
        return Presence(clientId, encryptedClientId)
    }

    private fun expectWire(actual: Int, expected: Int) {
        if (actual != expected) stop(Reason.INVALID_WIRE)
    }

    /** Cursors retain only a temporary view into the original array; no slice is materialized. */
    private class Cursor(private val data: ByteArray, private var position: Int, private val end: Int) {
        val finished: Boolean get() = position == end

        fun tag(): Pair<Int, Int> {
            val tag = varint()
            if (tag <= 0 || tag > 0xffff_ffffL) stop(Reason.MALFORMED)
            val field = (tag ushr 3).toInt()
            if (field == 0) stop(Reason.MALFORMED)
            return field to (tag and 7).toInt()
        }

        fun varint(): Long {
            var value = 0L
            for (index in 0 until 10) {
                if (position >= end) stop(Reason.MALFORMED)
                val next = data[position++].toInt() and 0xff
                // The tenth byte may only carry bit 63; reject overflow and continuation.
                if (index == 9 && next > 1) stop(Reason.MALFORMED)
                value = value or ((next and 0x7f).toLong() shl (index * 7))
                if (next and 0x80 == 0) return value
            }
            stop(Reason.MALFORMED)
        }

        fun bytes(): Span {
            val length = varint()
            if (length < 0 || length > (end - position).toLong()) stop(Reason.MALFORMED)
            val start = position
            position += length.toInt()
            return Span(start, position)
        }

        /** Unknown fields are forward-compatible, but remain bounded and entirely opaque. */
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> skipFixed(8)
                2 -> bytes()
                5 -> skipFixed(4)
                else -> stop(Reason.INVALID_WIRE) // Groups and invalid wire types stay unknown.
            }
        }

        private fun skipFixed(length: Int) {
            if (length > end - position) stop(Reason.MALFORMED)
            position += length
        }
    }
}
