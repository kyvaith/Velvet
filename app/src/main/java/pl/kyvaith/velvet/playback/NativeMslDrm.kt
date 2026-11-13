// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import android.media.MediaDrm
import android.media.NotProvisionedException
import android.os.Build
import java.io.Closeable
import java.util.UUID

/** Opaque handles only: content keys and the device's private keys never cross this facade. */
internal interface NativeMslDrm : Closeable {
    fun property(name: String): String
    fun openSession(): ByteArray
    fun cryptoSession(scope: ByteArray, cipher: String, mac: String): NativeMslCryptoSession
    fun keyRequest(scope: ByteArray, init: ByteArray, mime: String, keyType: Int,
                   parameters: Map<String, String>): ByteArray
    fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray
    fun provisionRequest(): NativeMslProvisionRequest
    fun provideProvisionResponse(response: ByteArray)
    fun removeKeys(scope: ByteArray)
    fun removeOfflineLicense(keySetId: ByteArray)
    fun closeSession(scope: ByteArray)
}

internal interface NativeMslCryptoSession {
    fun encrypt(keyId: ByteArray, input: ByteArray, iv: ByteArray): ByteArray
    fun decrypt(keyId: ByteArray, input: ByteArray, iv: ByteArray): ByteArray
    fun sign(keyId: ByteArray, input: ByteArray): ByteArray
    fun verify(keyId: ByteArray, input: ByteArray, signature: ByteArray): Boolean
}

/** Deliberately not a data class: toString must never disclose the request or its URL. */
internal class NativeMslProvisionRequest(val defaultUrl: String, val data: ByteArray) {
    override fun toString() = "NativeMslProvisionRequest(redacted)"
}

/** An internal control signal, with no native exception text or cause. */
internal class NativeMslProvisionRequired : Exception()

/** A dedicated MediaDrm instance; the Media3 movie-license session remains independent. */
internal class PlatformNativeMslDrm private constructor(private val drm: MediaDrm) : NativeMslDrm {
    override fun property(name: String): String = native { drm.getPropertyString(name) }
    override fun openSession(): ByteArray = native { drm.openSession() }
    override fun cryptoSession(scope: ByteArray, cipher: String, mac: String): NativeMslCryptoSession = native {
        val session = drm.getCryptoSession(scope, cipher, mac)
        object : NativeMslCryptoSession {
            override fun encrypt(keyId: ByteArray, input: ByteArray, iv: ByteArray): ByteArray =
                native { session.encrypt(keyId, input, iv) }
            override fun decrypt(keyId: ByteArray, input: ByteArray, iv: ByteArray): ByteArray =
                native { session.decrypt(keyId, input, iv) }
            override fun sign(keyId: ByteArray, input: ByteArray): ByteArray = native { session.sign(keyId, input) }
            override fun verify(keyId: ByteArray, input: ByteArray, signature: ByteArray): Boolean =
                native { session.verify(keyId, input, signature) }
        }
    }

    override fun keyRequest(scope: ByteArray, init: ByteArray, mime: String, keyType: Int,
                            parameters: Map<String, String>): ByteArray = native {
        drm.getKeyRequest(scope, init, mime, keyType, HashMap(parameters)).data
    }

    override fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray =
        native { drm.provideKeyResponse(scope, response) ?: ByteArray(0) }

    override fun provisionRequest(): NativeMslProvisionRequest = native {
        val request = drm.provisionRequest
        NativeMslProvisionRequest(request.defaultUrl, request.data)
    }

    override fun provideProvisionResponse(response: ByteArray) = native { drm.provideProvisionResponse(response) }
    override fun removeKeys(scope: ByteArray) = native { drm.removeKeys(scope) }
    override fun closeSession(scope: ByteArray) = native { drm.closeSession(scope) }
    override fun close() = drm.close()

    override fun removeOfflineLicense(keySetId: ByteArray) {
        // The factory rejects older Android before constructing MediaDrm. Keep the explicit
        // guard here as well, so this call cannot persist a key set on an unsupported API.
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException()
        native { drm.removeOfflineLicense(keySetId) }
    }

    companion object {
        private val WIDEVINE = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

        fun create(): NativeMslDrm {
            if (Build.VERSION.SDK_INT < 29) throw NetflixPlaybackException(
                PlaybackStage.KEY_EXCHANGE,
                message = "Natywne odtwarzanie Netflix wymaga Androida 10 lub nowszego.",
                retryable = false,
            )
            // Never set securityLevel, device identity, a movie service certificate or privacyMode.
            return PlatformNativeMslDrm(MediaDrm(WIDEVINE))
        }

        private inline fun <T> native(block: () -> T): T = try { block() }
        catch (_: NotProvisionedException) { throw NativeMslProvisionRequired() }
    }
}
