// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.diagnostics

import android.content.Context
import android.os.Build
import android.webkit.WebView
import pl.kyvaith.velvet.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicLong

object AppLog {
    @Volatile private var journal: SessionLog? = null
    private val operations = AtomicLong()

    fun install(context: Context) {
        if (journal != null) return
        synchronized(this) {
            if (journal != null) return
            journal = SessionLog(File(context.filesDir, "diagnostics"))
            info("app", "environment", mapOf(
                "appVersion" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE,
                "androidApi" to Build.VERSION.SDK_INT, "androidVersion" to Build.VERSION.RELEASE,
                "webViewVersion" to runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull(),
            ))
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                // Preserve Android's crash handling even if logging itself cannot allocate memory.
                try { error("app", "uncaught_exception", failure) } catch (_: Throwable) { }
                if (previous != null) previous.uncaughtException(thread, failure)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    fun nextOperationId(): Long = operations.incrementAndGet()
    fun info(stage: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        journal?.append("INFO", stage, event, fields)
    }
    fun warn(stage: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        journal?.append("WARN", stage, event, fields)
    }
    fun error(stage: String, event: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap()) {
        journal?.append("ERROR", stage, event, fields, error)
    }
    fun overview(): String = journal?.overview() ?: "Dziennik nie jest dostępny."
    fun exportText(header: String): String = journal?.exportText(header) ?: header + "\nDziennik nie jest dostępny."
}
