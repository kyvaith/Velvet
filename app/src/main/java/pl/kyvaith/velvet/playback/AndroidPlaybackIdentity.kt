/*
 * SPDX-License-Identifier: MIT
 * Android ESN field layout adapted from resources/lib/utils/esn.py,
 * atrHusK/plugin.video.netflix commit bf91b55089b06bd892eb6cd1457b3a87341fb5bf.
 * See THIRD_PARTY_NOTICES.md. This is Velvet's own interoperability profile,
 * not an identifier read from the official Netflix application or its credentials.
 */
package pl.kyvaith.velvet.playback

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import pl.kyvaith.velvet.diagnostics.AppLog
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale

/** Kodi's mobile compatibility category, actual CDM properties and Velvet's own installation suffix. */
internal object AndroidPlaybackIdentity {
    private val suffixPattern = Regex("[0-9A-F]{64}")
    private val systemIdPattern = Regex("[1-9][0-9]{0,9}")
    private val productCharacters = Regex("[^A-Za-z0-9=-]")

    fun create(manufacturer: String, model: String, systemId: String, securityLevel: String, suffix: String): String =
        prefix(manufacturer, model, systemId, securityLevel) + checkedSuffix(suffix)

    fun prefix(manufacturer: String, model: String, systemId: String, securityLevel: String, category: String = "T"): String {
        require(category == "T" || category == "P") { "Unsupported Android client category." }
        require(manufacturer.isNotBlank() && !manufacturer.equals("unknown", ignoreCase = true)) { "Missing Android manufacturer." }
        require(model.isNotBlank() && !model.equals("unknown", ignoreCase = true)) { "Missing Android model." }
        require(systemIdPattern.matches(systemId) && (systemId.toLongOrNull() ?: 0) in 1..4_294_967_295L) {
            "Invalid Widevine system identifier."
        }
        require(securityLevel == "L1" || securityLevel == "L3") { "Unsupported Widevine security level." }
        val manufacturerPart = manufacturer.uppercase(Locale.ROOT).padEnd(5, ' ').take(5)
        val modelPart = model.uppercase(Locale.ROOT).take(45).trim()
        require(modelPart.isNotBlank()) { "Missing Android model." }
        val product = productCharacters.replace(manufacturerPart + modelPart, "=")
        val levelPart = if (securityLevel == "L1") "" else "L3-"
        // Kodi deliberately selects T for its mobile interoperability profile, including phones.
        // This is a protocol category, not a measured tablet size or a borrowed device identity.
        // Manufacturer, model, Widevine system ID and level still come from this device.
        return "NFANDROID1-PRV-$category-$levelPart$product-$systemId-"
    }

    fun checkedSuffix(value: String): String {
        require(suffixPattern.matches(value)) { "Invalid Velvet installation identifier." }
        return value
    }

    fun randomSuffix(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val digits = "0123456789ABCDEF"
        return buildString(64) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4]); append(digits[value and 15])
            }
        }.also { bytes.fill(0) }
    }
}

/** Implementations must either persist a value or throw; a failed write is not an identity. */
internal interface AndroidIdentityStorage {
    fun read(): String?
    fun write(value: String)
}

/**
 * Kept independently of website SessionData.esn and account cookies. The suffix is stable for
 * this app installation, including logout/profile changes. No hardware deviceUniqueId is read.
 */
internal class AndroidPlaybackIdentityStore internal constructor(
    private val storage: AndroidIdentityStorage,
    private val manufacturer: String,
    private val model: String,
    private val newSuffix: () -> String = AndroidPlaybackIdentity::randomSuffix,
) {
    constructor(context: Context) : this(
        AtomicIdentityStorage(AtomicFile(File(context.applicationContext.noBackupFilesDir, "velvet_android_identity_v1"))),
        Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(),
    )

    fun identityFor(systemId: String, securityLevel: String): String = identityForCategory(systemId, securityLevel, "T")

    /** Native phone login uses P; it reuses this installation's own persisted suffix. */
    fun identityForPhone(systemId: String, securityLevel: String): String = identityForCategory(systemId, securityLevel, "P")

    private fun identityForCategory(systemId: String, securityLevel: String, category: String): String {
        val prefix = try { AndroidPlaybackIdentity.prefix(manufacturer, model, systemId, securityLevel, category) }
        catch (error: IllegalArgumentException) {
            AppLog.error("key_exchange", "android_identity_invalid", error)
            throw failure("Android nie udostępnił prawidłowych danych urządzenia do profilu odtwarzania Velvet.")
        }
        // Multiple client instances must not create different suffixes at the same time.
        return synchronized(identityLock) {
            try {
                val existing = storage.read()
                val suffix = if (existing != null) AndroidPlaybackIdentity.checkedSuffix(existing) else {
                    val created = AndroidPlaybackIdentity.checkedSuffix(newSuffix())
                    storage.write(created)
                    if (storage.read() != created) throw IOException("Installation identity was not persisted.")
                    created
                }
                prefix + suffix
            } catch (error: Exception) {
                AppLog.error("key_exchange", "android_identity_storage_failed", error)
                // Never silently regenerate an invalid saved identity or retain native/storage messages.
                throw failure("Nie można trwale odczytać lub zapisać identyfikatora tej instalacji Velvet. Sprawdź wolne miejsce i uruchom aplikację ponownie.")
            }
        }
    }

    companion object {
        private val identityLock = Any()
        private fun failure(message: String) = NetflixPlaybackException(PlaybackStage.KEY_EXCHANGE,
            message = message, retryable = false)
    }
}

/** Atomic private-file storage avoids a failed SharedPreferences commit exposing a cached value. */
private class AtomicIdentityStorage(private val file: AtomicFile) : AndroidIdentityStorage {
    override fun read(): String? = try {
        file.openRead().use { input ->
            val bytes = ByteArray(65)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) throw IOException("Incomplete installation identity read.")
                count += read
            }
            if (count != 64) throw IOException("Invalid installation identity length.")
            String(bytes, 0, count, Charsets.US_ASCII)
        }
    } catch (error: FileNotFoundException) {
        if (file.baseFile.exists()) throw error
        null
    }

    override fun write(value: String) {
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(value.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
            file.finishWrite(output)
            output = null
        } catch (error: Exception) {
            output?.let { file.failWrite(it) }
            throw error
        }
    }
}
