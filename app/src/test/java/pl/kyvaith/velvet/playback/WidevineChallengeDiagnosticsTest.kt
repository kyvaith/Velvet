package pl.kyvaith.velvet.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class WidevineChallengeDiagnosticsTest {
    @Test fun `reports literal client identification field presence without opening its opaque contents`() {
        val opaque = byteArrayOf(0xff.toByte(), 0x80.toByte(), 0, 0x7f)
        val result = WidevineChallengeDiagnostics.fields(envelope(request(1, opaque)))
        assertEquals(mapOf("challengeStructure" to "license_request",
            "hasClientIdField" to true, "hasEncryptedClientIdField" to false), result)
    }

    @Test fun `reports encrypted identification field presence without validating encryption or signature`() {
        val result = WidevineChallengeDiagnostics.fields(envelope(request(8, byteArrayOf(0x7f))))
        assertEquals(mapOf("challengeStructure" to "license_request",
            "hasClientIdField" to false, "hasEncryptedClientIdField" to true), result)
        // The opaque fixture bytes are deliberately not real cryptographic objects. Only field
        // presence is reported; a true flag must never be described as validated encryption.
    }

    @Test fun `never mutates input or retains any opaque content in diagnostic fields`() {
        val marker = "synthetic-device-data-must-not-be-logged".toByteArray()
        val challenge = envelope(request(1, marker), signature = marker)
        val before = challenge.copyOf()
        val result = WidevineChallengeDiagnostics.fields(challenge)
        assertArrayEquals(before, challenge)
        assertEquals(setOf("challengeStructure", "hasClientIdField", "hasEncryptedClientIdField"), result.keys)
        assertFalse(result.toString().contains("synthetic-device-data"))
        assertTrue(result.values.all { it is Boolean || it == "license_request" })
    }

    @Test fun `truncated envelope and truncated request are unknown without partial presence claims`() {
        val valid = envelope(request(1))
        for (length in 0 until valid.size) assertUnknown(valid.copyOf(length))
        val truncatedField = bytesField(1, byteArrayOf(1, 2, 3)).dropLast(1).toByteArray()
        assertUnknown(envelope(bytesField(2, byteArrayOf(1)) + scalarField(3, 1) + truncatedField))
    }

    @Test fun `omitted optional proto2 types use defaults while retaining all structural anchors`() {
        val body = bytesField(1, byteArrayOf(1)) + bytesField(2, byteArrayOf(1))
        val withoutEitherType = bytesField(2, body) + bytesField(3, byteArrayOf(1))
        for (challenge in listOf(envelope(body), withoutEitherType,
            bytesField(2, request(1)) + bytesField(3, byteArrayOf(1)))) {
            assertEquals(mapOf("challengeStructure" to "license_request",
                "hasClientIdField" to true, "hasEncryptedClientIdField" to false),
                WidevineChallengeDiagnostics.fields(challenge))
        }
        assertUnknown(bytesField(2, body), "missing_envelope")
        assertUnknown(bytesField(3, byteArrayOf(1)), "missing_envelope")
        assertUnknown(envelope(bytesField(1, byteArrayOf(1))), "missing_content")
        assertUnknown(envelope(bytesField(2, byteArrayOf(1))), "missing_identity")
    }

    @Test fun `explicit unsupported types and incorrect known wire types stay unknown`() {
        for (type in listOf(0L, 2L, 3L, 4L, 5L, 7L, 99L)) {
            assertUnknown(envelope(request(1), type = type), "unsupported_type")
        }
        for (type in listOf(0L, 4L, 99L)) {
            assertUnknown(envelope(request(1, requestType = type)), "unsupported_type")
        }
        assertUnknown(envelope(scalarField(1, 1) + bytesField(2, byteArrayOf(1)) + scalarField(3, 1)), "invalid_wire")
        assertUnknown(byteArrayOf(0), "malformed") // Illegal field zero.
        for (wire in listOf(3L, 4L, 6L, 7L)) {
            assertUnknown(envelope(request(1)) + varint((10L shl 3) or wire), "invalid_wire")
            assertUnknown(envelope(request(1) + varint((9L shl 3) or wire)), "invalid_wire")
        }
        assertUnknown(bytesField(1, byteArrayOf(1)) + bytesField(2, request(1)) + bytesField(3, byteArrayOf(1)), "invalid_wire")
    }

    @Test fun `unknown varint fixed64 bytes and fixed32 fields are skipped without exposing content`() {
        val marker = "synthetic-unknown-device-value".toByteArray()
        val extension = scalarField(10, -1L) + fixedField(11, 1, ByteArray(8) { 0xff.toByte() }) +
            bytesField(12, marker) + fixedField(13, 5, ByteArray(4) { 0xff.toByte() })
        val challenge = extension + envelope(request(8) + extension) + scalarField(0x1fff_ffff, 1)
        val before = challenge.copyOf()
        val result = WidevineChallengeDiagnostics.fields(challenge)
        assertEquals(mapOf("challengeStructure" to "license_request",
            "hasClientIdField" to false, "hasEncryptedClientIdField" to true), result)
        assertArrayEquals(before, challenge)
        assertFalse(result.toString().contains("synthetic-unknown-device-value"))
    }

    @Test fun `malformed unknown fields retain length and varint bounds`() {
        val malformed = listOf(
            fixedField(10, 1, ByteArray(7)),
            fixedField(10, 5, ByteArray(3)),
            varint((10L shl 3) or 2) + varint(12) + byteArrayOf(1),
            varint((10L shl 3) or 2) + varint(-1L),
            varint(10L shl 3) + ByteArray(11) { 0x80.toByte() },
            varint(1L shl 32) + byteArrayOf(1),
        )
        for (field in malformed) {
            assertUnknown(envelope(request(1)) + field, "malformed")
            assertUnknown(envelope(request(1) + field), "malformed")
        }
    }

    @Test fun `missing or ambiguous identity and missing envelope anchors stay unknown`() {
        val contentAndType = bytesField(2, byteArrayOf(1)) + scalarField(3, 1)
        assertUnknown(envelope(contentAndType), "missing_identity")
        assertUnknown(envelope(request(1) + bytesField(8, byteArrayOf(1))), "ambiguous")
        assertUnknown(envelope(request(1) + bytesField(8, byteArrayOf())), "ambiguous")
        assertUnknown(envelope(request(1, byteArrayOf()) + bytesField(8, byteArrayOf(1))), "ambiguous")
        assertUnknown(envelope(request(1, byteArrayOf())), "missing_identity")
        assertUnknown(envelope(bytesField(1, byteArrayOf(1)) + scalarField(3, 1)), "missing_content")
        assertUnknown(envelope(request(1), signature = byteArrayOf()), "missing_envelope")
        assertUnknown(envelope(byteArrayOf()), "missing_envelope")
        assertUnknown(scalarField(1, 1) + bytesField(2, request(1)), "missing_envelope")
        assertUnknown(scalarField(1, 1) + bytesField(3, byteArrayOf(1)), "missing_envelope")
    }

    @Test fun `duplicate singular fields do not inherit protobuf merge or last-value semantics`() {
        val valid = envelope(request(1))
        assertUnknown(valid + scalarField(1, 1), "ambiguous")
        assertUnknown(valid + bytesField(2, request(8)), "ambiguous")
        assertUnknown(valid + bytesField(3, byteArrayOf(1)), "ambiguous")
        assertUnknown(envelope(request(1) + bytesField(1, byteArrayOf(1))), "ambiguous")
        assertUnknown(envelope(request(1) + scalarField(3, 2)), "ambiguous")
    }

    @Test fun `oversize challenges excessive fields and unbounded lengths stay unknown`() {
        val atLimit = envelope(request(1, ByteArray(64 * 1024 - 18)))
        assertEquals(64 * 1024, atLimit.size)
        assertEquals("license_request", WidevineChallengeDiagnostics.fields(atLimit)["challengeStructure"])
        assertUnknown(ByteArray(64 * 1024 + 1), "limit")
        val metric = bytesField(6, byteArrayOf())
        val allowed = envelope(request(1)) + repeated(metric, 58) // Three outer + three inner + 58.
        assertEquals("license_request", WidevineChallengeDiagnostics.fields(allowed)["challengeStructure"])
        assertUnknown(envelope(request(1)) + repeated(metric, 59), "limit")
        val unknownField = scalarField(10, 0)
        assertEquals("license_request", WidevineChallengeDiagnostics.fields(
            envelope(request(1) + repeated(unknownField, 29)) + repeated(unknownField, 29))["challengeStructure"])
        assertUnknown(envelope(request(1) + repeated(unknownField, 30)) + repeated(unknownField, 29), "limit")
        assertUnknown(scalarField(1, 1) + byteArrayOf(0x12) + varint(Int.MAX_VALUE.toLong()), "malformed")
        assertUnknown(scalarField(1, 1) + byteArrayOf(0x12) + varint(-1L), "malformed")
        assertUnknown(envelope(bytesField(2, byteArrayOf(1)) + scalarField(3, 1) +
            byteArrayOf(0x0a) + varint(Int.MAX_VALUE.toLong())), "malformed")
    }

    @Test fun `varints reject truncation continuation beyond ten bytes and 64-bit overflow`() {
        assertUnknown(byteArrayOf(0x80.toByte()))
        assertUnknown(ByteArray(11) { 0x80.toByte() })
        val overflow = ByteArray(10) { if (it == 9) 2 else 0x80.toByte() }
        assertUnknown(overflow)
        assertUnknown(envelope(request(1) + byteArrayOf(0x20) + overflow))
        assertUnknown(scalarField(1, 1) + byteArrayOf(0x12) + overflow)
    }

    @Test fun `field order optional known fields and known request types do not change presence`() {
        for (requestType in 1L..3L) {
            val body = scalarField(7, 12) + scalarField(6, 21) + bytesField(5, byteArrayOf()) +
                scalarField(4, 1_700_000_000) + scalarField(3, requestType) +
                bytesField(2, byteArrayOf(1)) + bytesField(8, byteArrayOf(1))
            val challenge = bytesField(3, byteArrayOf(1)) + bytesField(9, byteArrayOf(1)) +
                scalarField(8, 1) + bytesField(7, byteArrayOf()) + bytesField(6, byteArrayOf()) +
                bytesField(5, byteArrayOf()) + bytesField(4, byteArrayOf()) +
                bytesField(2, body) + scalarField(1, 1)
            assertEquals(false, WidevineChallengeDiagnostics.fields(challenge)["hasClientIdField"])
            assertEquals(true, WidevineChallengeDiagnostics.fields(challenge)["hasEncryptedClientIdField"])
        }
    }

    private fun assertUnknown(bytes: ByteArray, expectedReason: String? = null) {
        val before = bytes.copyOf()
        val fields = WidevineChallengeDiagnostics.fields(bytes)
        assertEquals(setOf("challengeStructure", "challengeReason"), fields.keys)
        assertEquals("unknown", fields["challengeStructure"])
        assertTrue(fields["challengeReason"] in setOf("empty", "limit", "malformed", "invalid_wire",
            "unsupported_type", "missing_envelope", "missing_content", "missing_identity", "ambiguous", "observer_error"))
        if (expectedReason != null) assertEquals(expectedReason, fields["challengeReason"])
        assertArrayEquals(before, bytes)
    }

    // Handwritten synthetic protobuf fixtures contain no captured/device/license data.
    private fun request(identityField: Int, opaque: ByteArray = byteArrayOf(1), requestType: Long = 1): ByteArray =
        bytesField(identityField, opaque) + bytesField(2, byteArrayOf(1)) + scalarField(3, requestType)

    private fun envelope(body: ByteArray, type: Long = 1, signature: ByteArray = byteArrayOf(1)): ByteArray =
        scalarField(1, type) + bytesField(2, body) + bytesField(3, signature)

    private fun scalarField(field: Int, value: Long): ByteArray = varint(field.toLong() shl 3) + varint(value)
    private fun bytesField(field: Int, value: ByteArray): ByteArray =
        varint((field.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun fixedField(field: Int, wire: Int, value: ByteArray): ByteArray =
        varint((field.toLong() shl 3) or wire.toLong()) + value

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream()
        do {
            val next = (remaining and 127).toInt()
            remaining = remaining ushr 7
            output.write(if (remaining == 0L) next else next or 128)
        } while (remaining != 0L)
        return output.toByteArray()
    }

    private fun repeated(bytes: ByteArray, count: Int): ByteArray =
        ByteArrayOutputStream().apply { repeat(count) { write(bytes) } }.toByteArray()
}
