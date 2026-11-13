// SPDX-License-Identifier: MIT
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package pl.kyvaith.velvet

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.kyvaith.velvet.diagnostics.AppLog
import pl.kyvaith.velvet.model.AppAction
import pl.kyvaith.velvet.ui.VelvetApp
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[VelvetViewModel::class.java] }
    private val login=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(it.resultCode==RESULT_OK) model.restoreAccount(openHome=true)
        else it.data?.getStringExtra(NetflixLoginActivity.ERROR_MESSAGE)?.let(model::loginFailed)
    }
    private val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val report = model.exportDiagnostics()
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                }
                AppLog.info("diagnostics", "file_saved")
                Toast.makeText(this@MainActivity, "Dziennik zapisany.", Toast.LENGTH_LONG).show()
            } catch (ex: CancellationException) { throw ex }
            catch (ex: Exception) { diagnosticExportFailed(ex) }
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val playing=state.playback!=null
            DisposableEffect(playing) {
                configurePlayerWindow(playing)
                onDispose { }
            }
            VelvetApp(state) { action ->
                when (action) {
                    AppAction.Login -> {
                        AppLog.info("login", "opened")
                        login.launch(Intent(this,NetflixLoginActivity::class.java))
                    }
                    AppAction.ExportDiagnostics -> shareDiagnostics()
                    AppAction.SaveDiagnostics -> saveLog.launch(diagnosticFilename())
                    else -> model.onAction(action)
                }
            }
        }
    }

    private fun diagnosticFilename(): String = "Velvet-${BuildConfig.VERSION_NAME}-log-" +
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt"

    private fun shareDiagnostics() = lifecycleScope.launch {
        try {
            val report = model.exportDiagnostics()
            val file = withContext(Dispatchers.IO) {
                val directory = File(cacheDir, "diagnostic_exports").apply { mkdirs() }
                directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                    ?.drop(4)?.forEach { it.delete() }
                File(directory, diagnosticFilename()).apply { writeText(report, Charsets.UTF_8) }
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.diagnostics", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "Dziennik Velvet", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Udostępnij dziennik Velvet"))
            AppLog.info("diagnostics", "share_sheet_opened")
        } catch (ex: CancellationException) { throw ex }
        catch (ex: Exception) { diagnosticExportFailed(ex) }
    }

    private fun diagnosticExportFailed(error: Exception) {
        AppLog.error("diagnostics", "export_failed", error)
        Toast.makeText(this, "Nie udało się wyeksportować dziennika. Spróbuj opcji Zapisz plik.", Toast.LENGTH_LONG).show()
    }

    private fun configurePlayerWindow(playing:Boolean) {
        requestedOrientation=if(playing) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller=WindowCompat.getInsetsController(window,window.decorView)
        if(playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
            controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if(Build.VERSION.SDK_INT>=31) setPictureInPictureParams(PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16,9)).setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true).build())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if(Build.VERSION.SDK_INT<31 && model.state.value.playback!=null) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())
        }
    }
}
