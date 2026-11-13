// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.diagnostics

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionLogInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun errorsSurviveJournalRecreationOnRealAndroidFilesystem() {
        val root = File(context.cacheDir, "log-test-${UUID.randomUUID()}")
        try {
            val first = SessionLog(root)
            first.append("ERROR", "catalog", "empty_rows", mapOf("rowCount" to 0))
            first.append("ERROR", "key_exchange", "rejected", mapOf("serviceCode" to "107016"))
            val second = SessionLog(root)
            second.append("ERROR", "episodes", "empty", mapOf("episodeCount" to 0))
            val report = second.exportText("Android test")
            assertTrue(report.contains("empty_rows"))
            assertTrue(report.contains("107016"))
            assertTrue(report.contains("\"event\":\"empty\""))
            assertTrue(report.contains(first.sessionId))
            assertTrue(report.contains(second.sessionId))
        } finally { root.deleteRecursively() }
    }

    @Test fun diagnosticFileProviderServesOnlyExportDirectory() {
        val file = File(context.cacheDir, "diagnostic_exports/test-${UUID.randomUUID()}.txt")
        val outside = File(context.filesDir, "outside-diagnostic-test-${UUID.randomUUID()}.txt")
        try {
            file.parentFile!!.mkdirs()
            file.writeText("diagnostic test\n")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
            assertEquals("content", uri.scheme)
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertEquals("diagnostic test\n", text)
            outside.writeText("not for sharing")
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", outside)
                fail("Private app files must never have a share URI")
            } catch (_: IllegalArgumentException) { /* Required */ }
        } finally { file.delete(); outside.delete() }
    }
}
