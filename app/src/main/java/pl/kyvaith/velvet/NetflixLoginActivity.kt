// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import pl.kyvaith.velvet.data.NetflixApiException
import pl.kyvaith.velvet.data.NetflixBrowserContext
import pl.kyvaith.velvet.data.NetflixOrigins
import pl.kyvaith.velvet.diagnostics.AppLog
import pl.kyvaith.velvet.ui.VelvetTheme

/** Passwords, OTPs and profile PINs are entered only into the real HTTPS Netflix site. */
class NetflixLoginActivity : ComponentActivity() {
    private var web: WebView? = null
    private var status by mutableStateOf("Zaloguj się i wybierz swój profil na stronie Netflixa.")
    private var loading by mutableStateOf(false)
    private var checking by mutableStateOf(false)
    private var trustedPage by mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WebView.setWebContentsDebuggingEnabled(false)
        val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val initialSession = (application as VelvetApplication).sessions.read()
        // Native requests may renew the owned session. Copy it back only to the same HTTPS service.
        initialSession?.cookieHeader?.split(';')?.forEach { pair ->
            if (pair.contains('=')) {
                val privateCookie=pair.trim().substringBefore('=') in setOf("NetflixId","SecureNetflixId")
                cookieManager.setCookie("https://www.netflix.com", pair.trim()+"; Domain=.netflix.com; Path=/; Secure"+if(privateCookie) "; HttpOnly" else "")
            }
        }
        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.rgb(9,9,11))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            configureDesktopSite(settings)
            cookieManager.setAcceptThirdPartyCookies(this,false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (NetflixOrigins.isLogin(request.url.toString())) return false
                    status = "Zostań na stronie netflix.com, aby dokończyć logowanie."
                    AppLog.warn("login", "external_navigation_blocked")
                    return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loading = true
                    trustedPage = NetflixOrigins.isLogin(url)
                    AppLog.info("login", "page_started", mapOf("source" to pageTag(url)))
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    trustedPage = NetflixOrigins.isLogin(url)
                    if (trustedPage) status = when (pageTag(url)) {
                        "browse", "profiles", "profile_switch" -> "Wybierz profil na stronie, a następnie dotknij przycisku poniżej."
                        "mobile_landing" -> "Netflix otworzył stronę mobilną bez katalogu. Użyj przycisku poniżej, aby sprawdzić sesję."
                        else -> "Dokończ logowanie i wybierz swój profil na stronie Netflixa."
                    }
                    cookieManager.flush()
                    AppLog.info("login", "page_finished", mapOf("source" to pageTag(url)))
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    trustedPage = false
                    loading = false
                    status = "Nie udało się potwierdzić bezpiecznego połączenia z Netflixem."
                    AppLog.error("login", "ssl_error", fields=mapOf("sslCode" to error.primaryError))
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    AppLog.error("login", "page_error", fields=mapOf("webErrorCode" to error.errorCode, "isMainFrame" to request.isForMainFrame))
                    if (request.isForMainFrame) {
                        loading = false
                        status = "Nie można otworzyć Netflixa. Sprawdź połączenie i spróbuj ponownie."
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    AppLog.error("login", "http_error",
                        fields=mapOf("httpStatus" to errorResponse.statusCode, "isMainFrame" to request.isForMainFrame))
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    AppLog.error("login", "renderer_gone", fields=mapOf("didCrash" to detail.didCrash()))
                    // A dead renderer cannot be reused. Handle only this WebView and let the
                    // caller offer another login attempt, rather than allowing Chromium to kill
                    // the whole application. API 26+, covered by our API 28 minimum.
                    checking=false
                    loading=false
                    trustedPage=false
                    (view.parent as? ViewGroup)?.removeView(view)
                    if(web===view) web=null
                    view.destroy()
                    setResult(RESULT_CANCELED,Intent().putExtra(ERROR_MESSAGE,
                        "Silnik strony Netflixa został zamknięty. Otwórz logowanie ponownie. Jeśli problem się powtarza, sprawdź Android System WebView na telefonie."))
                    finish()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            loadUrl(if (initialSession != null) "https://www.netflix.com/browse" else "https://www.netflix.com/login")
        }
        setContent {
            VelvetTheme {
                BackHandler { if (web?.canGoBack() == true) web?.goBack() else finish() }
                Column(Modifier.fillMaxSize().background(Color(0xFF09090B)).systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=5.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick={ finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Zamknij") }
                        Icon(Icons.Rounded.Lock,null,Modifier.size(16.dp),tint=Color(0xFF76D6A7))
                        Text("www.netflix.com",Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.titleSmall)
                        TextButton(onClick={ web?.reload() },enabled=!checking) { Text("Odśwież") }
                    }
                    if (loading || checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    web?.let { current -> AndroidView(factory={ current },modifier=Modifier.weight(1f).fillMaxWidth()) }
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(status,style=MaterialTheme.typography.bodySmall,color=Color(0xFFB8B8C3))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick=::captureSession,enabled=trustedPage && !checking,modifier=Modifier.fillMaxWidth().height(50.dp)) {
                            if (checking) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=Color.White)
                            else Text("Użyj wybranego profilu")
                        }
                    }
                }
            }
        }
    }

    private fun captureSession() {
        val view = web ?: return
        if (!NetflixOrigins.isLogin(view.url) || checking) return
        checking = true
        status = "Sprawdzam sesję konta…"
        AppLog.info("login", "capture_started")
        val userAgent = view.settings.userAgentString
        // No JavaScript interface is installed and no input fields/passwords are inspected.
        view.evaluateJavascript("""(function(){try { if(location.protocol!=='https:' || !['www.netflix.com','netflix.com'].includes(location.hostname)) return null; var n=window.netflix; if(!n || !n.reactContext) return null; return JSON.stringify({reactContext:n.reactContext,falcorCache:n.falcorCache||{},url:location.href}); } catch(e) {return null;} })();""") { encoded ->
            if (isFinishing || !NetflixOrigins.isLogin(view.url)) { checking=false; return@evaluateJavascript }
            val raw = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
            if (raw == null || raw.length > 16*1024*1024) {
                AppLog.warn("login", "capture_unavailable", mapOf("hasData" to (raw != null)))
                checking=false
                status="Netflix nie udostępnił sesji. Dokończ logowanie i wybierz profil na stronie."
                return@evaluateJavascript
            }
            val data = runCatching { JSONObject(raw) }.getOrNull()
            if (data == null) {
                AppLog.error("login", "capture_json_invalid")
                checking=false; status="Nie można odczytać sesji ze strony Netflixa."; return@evaluateJavascript
            }
            AppLog.info("login", "capture_received", mapOf("hasData" to data.has("reactContext"),
                "hasFalcor" to (data.optJSONObject("falcorCache")?.length()?.let { it > 0 } == true)))
            val cookies = CookieManager.getInstance().getCookie("https://www.netflix.com/").orEmpty()
            lifecycleScope.launch {
                try {
                    (application as VelvetApplication).netflix.importWebSession(cookies,userAgent,data)
                    AppLog.info("login", "session_verified")
                    setResult(RESULT_OK)
                    finish()
                } catch (ex: CancellationException) { throw ex }
                catch (ex: Exception) {
                    AppLog.error("login", "session_verification_failed", ex,
                        mapOf("httpStatus" to (ex as? NetflixApiException)?.statusCode))
                    status=if (ex is NetflixApiException) ex.message.orEmpty() else "Nie udało się potwierdzić sesji. Sprawdź połączenie lub ponów logowanie."
                } finally { checking=false }
            }
        }
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.webViewClient=WebViewClient(); it.destroy() }
        web=null
        super.onDestroy()
    }

    /** Same website mode as Chrome's Desktop site. The installed Chromium version is retained. */
    private fun configureDesktopSite(settings: WebSettings) {
        val userAgent = NetflixBrowserContext.desktopUserAgent(settings.userAgentString)
        val desktop = NetflixBrowserContext.contextTag(userAgent) == "desktop_site"
        var clientHintsApplied = false
        if (desktop && WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            try {
                // Read real engine brands/versions before changing presentation metadata.
                val currentMetadata = WebSettingsCompat.getUserAgentMetadata(settings)
                val chromiumBrands = currentMetadata.brandVersionList.filter { it.brand == "Chromium" }
                val metadata = UserAgentMetadata.Builder(currentMetadata)
                    .apply { if (chromiumBrands.isNotEmpty()) setBrandVersionList(chromiumBrands) }
                    .setPlatform("Linux").setPlatformVersion("").setMobile(false).setModel("")
                    .setArchitecture("x86").setBitness(64).setWow64(false).build()
                settings.userAgentString = userAgent
                WebSettingsCompat.setUserAgentMetadata(settings, metadata)
                clientHintsApplied = true
            } catch (error: RuntimeException) {
                AppLog.warn("login", "client_hints_unavailable", mapOf("exceptionType" to error.javaClass.simpleName))
            }
        }
        if (!clientHintsApplied) settings.userAgentString = userAgent
        settings.useWideViewPort = desktop
        settings.loadWithOverviewMode = desktop
        AppLog.info("login", "browser_context", mapOf("source" to NetflixBrowserContext.contextTag(userAgent),
            "clientHintsApplied" to clientHintsApplied))
    }

    private fun pageTag(url: String?): String =
        if (NetflixOrigins.isLogin(url)) NetflixOrigins.pageTag(url) else "untrusted"

    companion object { const val ERROR_MESSAGE="velvet.login.error" }
}
