package pl.kyvaith.velvet.playback

import android.media.NotProvisionedException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.MessageDigest

@UnstableApi
class NetflixDrmConfigurationTest {
    @Test fun `public service certificate matches the pinned Kodi snapshot`() {
        // Independently computed from player.py at de0db546d9b8ba8d79dbc08fe088b91fc3bcacb7.
        // This checks the source snapshot, not Netflix's current license policy or revocation state.
        val bytes = NetflixServerCertificate.bytes()
        assertEquals(707, bytes.size)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("f17a427fcff8e3c263d9dde8a6fb227b6a22bc4227c7fb82f05b6f639b104a76", digest)
        bytes.fill(0)
        assertFalse("One consumer cannot mutate the certificate for a later DRM instance",
            NetflixServerCertificate.bytes().all { it == 0.toByte() })
    }

    @Test fun `privacy mode then certificate configure the same DRM instance before session and challenge`() {
        val native = RecordingDrm()
        val configured = configuredNetflixMediaDrm(native::create)
        assertSame(native.drm, configured)
        val session = configured.openSession()
        configured.getKeyRequest(session, emptyList(), ExoMediaDrm.KEY_TYPE_STREAMING, hashMapOf())
        assertEquals(listOf("create", "set:privacyMode", "set:serviceCertificate", "openSession", "getKeyRequest"), native.calls)
        assertEquals("enable", native.privacyMode)
        assertArrayEquals(NetflixServerCertificate.bytes(), native.certificate)
        assertEquals(0, native.releaseCount)
        // A configured instance is now owned by Media3/the caller, which releases it once.
        configured.release()
        assertEquals(1, native.releaseCount)
    }

    @Test fun `certificate setter failure releases acquisition and exposes only a fixed playback cause`() {
        val unsafe = IllegalArgumentException("opaque-native-detail token=short-secret")
        val native = RecordingDrm(setterFailure = unsafe)
        val failure = expectConfigurationFailure { configuredNetflixMediaDrm(native::create) }
        assertEquals(listOf("create", "set:privacyMode", "set:serviceCertificate", "release"), native.calls)
        assertEquals(1, native.releaseCount)
        val netflix = findNetflixPlaybackFailure(failure)
        assertNotNull(netflix)
        assertEquals(PlaybackStage.LICENSE, netflix!!.stage)
        assertEquals(false, netflix.retryable)
        assertSame(netflix, failure.cause)
        assertNull("Raw native causes must not be retained", netflix.cause)
        assertFalse(failure.toString().contains("short-secret"))
        assertFalse(netflix.toString().contains("opaque-native-detail"))
    }

    @Test fun `privacy setter failure releases once without configuring certificate or opening a session`() {
        val unsafe = IllegalStateException("opaque-privacy-detail token=short-secret")
        val native = RecordingDrm(privacyFailure = unsafe)
        val failure = expectConfigurationFailure { configuredNetflixMediaDrm(native::create) }
        assertEquals(listOf("create", "set:privacyMode", "release"), native.calls)
        assertEquals(1, native.releaseCount)
        assertNull(native.certificate)
        val netflix = findNetflixPlaybackFailure(failure)
        assertNotNull(netflix)
        assertEquals(PlaybackStage.LICENSE, netflix!!.stage)
        assertEquals(false, netflix.retryable)
        assertSame(netflix, failure.cause)
        assertNull("Raw native causes must not be retained", netflix.cause)
        assertFalse(failure.toString().contains("short-secret"))
        assertFalse(netflix.toString().contains("opaque-privacy-detail"))
    }

    @Test fun `cleanup failure does not replace the safe configuration failure`() {
        val native = RecordingDrm(privacyFailure = IllegalStateException("native-setter-data"),
            releaseFailure = IllegalStateException("native-release-data"))
        val failure = expectConfigurationFailure { configuredNetflixMediaDrm(native::create) }
        assertEquals(1, native.releaseCount)
        assertNotNull(findNetflixPlaybackFailure(failure))
        assertFalse(failure.toString().contains("native-release-data"))
        assertNull(failure.cause!!.cause)
    }

    @Test fun `each new playback instance receives its own certificate configuration`() {
        val first = RecordingDrm()
        val second = RecordingDrm()
        val one = configuredNetflixMediaDrm(first::create)
        val two = configuredNetflixMediaDrm(second::create)
        assertNotSame(one, two)
        assertEquals(listOf("create", "set:privacyMode", "set:serviceCertificate"), first.calls)
        assertEquals(listOf("create", "set:privacyMode", "set:serviceCertificate"), second.calls)
        one.release()
        two.release()
        assertEquals(1, first.releaseCount)
        assertEquals(1, second.releaseCount)
    }

    @Test fun `request diagnostics preserve native provisioning exceptions for Media3`() {
        val provisioning = NotProvisionedException("fixture provisioning required")
        val native = RecordingDrm(keyRequestFailure = provisioning)
        val configured = SessionTrackingMediaDrm(configuredNetflixMediaDrm(native::create), DrmRequestSessions())
        try {
            configured.getKeyRequest(configured.openSession(), emptyList(), ExoMediaDrm.KEY_TYPE_STREAMING, hashMapOf())
            fail("Expected the native provisioning exception")
        } catch (expected: NotProvisionedException) {
            assertSame(provisioning, expected)
        } finally { configured.release() }
        assertEquals(1, native.releaseCount)
    }

    @Test fun `diagnostics preserve original native request and its bytes for recognized and unknown structures`() {
        val recognized = byteArrayOf(8, 1, 18, 8, 10, 1, 127, 18, 1, 127, 24, 1, 26, 1, 127)
        assertEquals("license_request", WidevineChallengeDiagnostics.fields(recognized)["challengeStructure"])
        for (payload in listOf(recognized, byteArrayOf(8, 1, 2))) {
            val before = payload.copyOf()
            val request = ExoMediaDrm.KeyRequest(payload, "")
            val originalData = request.data
            val native = RecordingDrm(keyRequest = request)
            val requests = DrmRequestSessions()
            val configured = SessionTrackingMediaDrm(configuredNetflixMediaDrm(native::create), requests)
            try {
                val returned = configured.getKeyRequest(configured.openSession(), emptyList(),
                    ExoMediaDrm.KEY_TYPE_STREAMING, hashMapOf())
                assertSame(request, returned)
                assertSame(originalData, returned.data)
                assertArrayEquals(before, returned.data)
                assertArrayEquals(before, payload)
                assertEquals("fixture-session", requests.sessionFor(returned))
            } finally { configured.release() }
            assertEquals(1, native.releaseCount)
        }
    }

    private fun expectConfigurationFailure(block: () -> Unit): IllegalStateException {
        try { block(); fail("Expected a safe configuration failure") }
        catch (expected: IllegalStateException) { return expected }
        throw AssertionError()
    }

    /** The native interface is a recording peer; no native DRM credentials or keys are simulated. */
    private class RecordingDrm(
        private val privacyFailure: Exception? = null,
        private val setterFailure: Exception? = null,
        private val releaseFailure: Exception? = null,
        private val keyRequestFailure: Exception? = null,
        private val keyRequest: ExoMediaDrm.KeyRequest = ExoMediaDrm.KeyRequest(byteArrayOf(8, 1, 2), ""),
    ) {
        val calls = mutableListOf<String>()
        var privacyMode: String? = null
        var certificate: ByteArray? = null
        var releaseCount = 0
        val drm: ExoMediaDrm = Proxy.newProxyInstance(ExoMediaDrm::class.java.classLoader,
            arrayOf(ExoMediaDrm::class.java)) { _, method, args ->
            when (method.name) {
                "setPropertyString" -> {
                    calls += "set:${args!![0]}"
                    check(args[0] == "privacyMode") { "Unexpected native property" }
                    privacyFailure?.let { throw it }
                    privacyMode = args[1] as String
                    null
                }
                "setPropertyByteArray" -> {
                    check(privacyMode == "enable") { "Certificate configured before privacy mode" }
                    calls += "set:${args!![0]}"
                    certificate = (args[1] as ByteArray).copyOf()
                    setterFailure?.let { throw it }
                    null
                }
                "openSession" -> {
                    check(privacyMode == "enable") { "Session opened before privacy mode" }
                    check(certificate != null) { "Session opened before service certificate" }
                    calls += "openSession"
                    "fixture-session".toByteArray()
                }
                "getKeyRequest" -> {
                    check(privacyMode == "enable") { "Challenge requested before privacy mode" }
                    check(certificate != null) { "Challenge requested before service certificate" }
                    calls += "getKeyRequest"
                    keyRequestFailure?.let { throw it }
                    keyRequest
                }
                "release" -> {
                    calls += "release"
                    releaseCount++
                    releaseFailure?.let { throw it }
                    null
                }
                else -> throw AssertionError("Unexpected DRM call: ${method.name}")
            }
        } as ExoMediaDrm

        fun create(): ExoMediaDrm { calls += "create"; return drm }
    }
}
