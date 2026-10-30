package acr.browser.lightning.browser.tab

import acr.browser.lightning.R
import acr.browser.lightning.adblock.AdBlocker
import acr.browser.lightning.adblock.allowlist.AllowListModel
import acr.browser.lightning.databinding.DialogAuthRequestBinding
import acr.browser.lightning.extensions.resizeAndShow
import acr.browser.lightning.js.TextReflow
import acr.browser.lightning.log.Logger
import acr.browser.lightning.preference.UserPreferences
import acr.browser.lightning.ssl.SslState
import acr.browser.lightning.ssl.SslWarningPreferences
import android.app.Application
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.LayoutInflater
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.reactivex.rxjava3.subjects.PublishSubject
import okhttp3.Request
import org.greatfire.browser.media.WebViewMediaInspector
import org.greatfire.browser.vpn.TunnelManager
import org.greatfire.browser.web.WebViewProxyConfigurator
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * A [WebViewClient] that supports tab adaptation, AdBlock, strict Play-Protect-safe TLS validation,
 * and IDM media stream discovery.
 */
class TabWebViewClient @AssistedInject constructor(
    private val application: Application,
    private val adBlocker: AdBlocker,
    private val allowListModel: AllowListModel,
    private val urlHandler: UrlHandler,
    @Assisted private val headers: Map<String, String>,
    private val userPreferences: UserPreferences,
    @Suppress("unused") private val sslWarningPreferences: SslWarningPreferences,
    private val textReflow: TextReflow,
    private val logger: Logger,
    @Assisted("cache") private val cacheStoragePathHandler: InternalStoragePathHandler,
    @Assisted("files") private val filesStoragePathHandler: InternalStoragePathHandler,
) : WebViewClient() {

    private val cache by lazy {
        File(application.cacheDir, "favicon-cache")
    }

    private val files by lazy {
        File(application.filesDir, "generated-html")
    }

    val urlObservable: PublishSubject<String> = PublishSubject.create()
    val sslStateObservable: PublishSubject<SslState> = PublishSubject.create()
    val goBackObservable: PublishSubject<Boolean> = PublishSubject.create()
    val goForwardObservable: PublishSubject<Boolean> = PublishSubject.create()
    val finishedObservable = PublishSubject.create<Unit>()

    var sslState: SslState = SslState.None
        private set

    private var currentUrl: String = ""
    private var isReflowRunning: Boolean = false
    private var zoomScale: Float = 0.0F
    private var urlWithSslError: String? = null

    private fun shouldBlockRequest(pageUrl: String, requestUrl: String) =
        !allowListModel.isUrlAllowedAds(pageUrl) &&
            adBlocker.isAd(requestUrl)

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        currentUrl = url
        WebViewMediaInspector.onPageStarted(view, url)
        urlObservable.onNext(url)
        if (urlWithSslError != url) {
            urlWithSslError = null
            sslState = if (URLUtil.isHttpsUrl(url)) {
                SslState.Valid
            } else {
                SslState.None
            }
        }
        sslStateObservable.onNext(sslState)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        WebViewMediaInspector.onPageFinished(view, url)
        urlObservable.onNext(url)
        goBackObservable.onNext(view.canGoBack())
        goForwardObservable.onNext(view.canGoForward())
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    finishedObservable.onNext(Unit)
                }
            })
        } else {
            finishedObservable.onNext(Unit)
        }
    }

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        if (view.isShown && userPreferences.textReflowEnabled) {
            if (isReflowRunning)
                return
            val changeInPercent = abs(100 - 100 / zoomScale * newScale)
            if (changeInPercent > 2.5f && !isReflowRunning) {
                isReflowRunning = view.postDelayed({
                    zoomScale = newScale
                    view.evaluateJavascript(textReflow.provideJs()) { isReflowRunning = false }
                }, 100)
            }
        }
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            val dialogView = DialogAuthRequestBinding.inflate(LayoutInflater.from(context))

            val realmLabel = dialogView.authRequestRealmTextview
            val name = dialogView.authRequestUsernameEdittext
            val password = dialogView.authRequestPasswordEdittext

            realmLabel.text = context.getString(R.string.label_realm, realm)

            setView(dialogView.root)
            setTitle(R.string.title_sign_in)
            setCancelable(true)
            setPositiveButton(R.string.title_sign_in) { _, _ ->
                val user = name.text.toString()
                val pass = password.text.toString()
                handler.proceed(user.trim(), pass.trim())
                logger.log(TAG, "Attempting HTTP Authentication")
            }
            setNegativeButton(R.string.action_cancel) { _, _ ->
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            setTitle(context.getString(R.string.title_form_resubmission))
            setMessage(context.getString(R.string.message_form_resubmission))
            setCancelable(true)
            setPositiveButton(context.getString(R.string.action_yes)) { _, _ ->
                resend.sendToTarget()
            }
            setNegativeButton(context.getString(R.string.action_no)) { _, _ ->
                dontResend.sendToTarget()
            }
        }.resizeAndShow()
    }

    override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {
        // Strict Google Play Protect & TLS security policy: always reject invalid TLS certificates
        // immediately so MITM attacks can never intercept user sessions.
        handler.cancel()
        urlWithSslError = webView.url
        sslState = SslState.Invalid(error)
        sslStateObservable.onNext(sslState)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return urlHandler.shouldOverrideLoading(view, url, headers) ||
            super.shouldOverrideUrlLoading(view, url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && !request.isForMainFrame) {
            return false
        }
        return urlHandler.shouldOverrideLoading(view, request.url.toString(), headers) ||
            super.shouldOverrideUrlLoading(view, request)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val reqUrl = request.url.toString()
        if (shouldBlockRequest(currentUrl, reqUrl)) {
            val empty = ByteArrayInputStream(emptyResponseByteArray)
            return WebResourceResponse(BLOCKED_RESPONSE_MIME_TYPE, BLOCKED_RESPONSE_ENCODING, empty)
        }

        // Passively record video/HLS/audio stream URLs for the IDM grabber
        WebViewMediaInspector.onInterceptedRequest(application, view, request, currentUrl)

        if (request.url.path?.startsWith(files.path) == true) {
            return filesStoragePathHandler.handle(request.url.path!!.substring(files.path.length))
        }
        if (request.url.path?.startsWith(cache.path) == true) {
            return cacheStoragePathHandler.handle(request.url.path!!.substring(cache.path.length))
        }

        // Legacy Android 5.0–6.0 factory WebView fallback when WebViewFeature.PROXY_OVERRIDE is unavailable
        if (WebViewProxyConfigurator.requiresClientInterception &&
            request.method.equals("GET", ignoreCase = true) &&
            (reqUrl.startsWith("https://", true) || reqUrl.startsWith("http://", true))
        ) {
            proxyGetFallback(request)?.let { return it }
        }

        return super.shouldInterceptRequest(view, request)
    }

    private fun proxyGetFallback(request: WebResourceRequest): WebResourceResponse? {
        return try {
            val tm = TunnelManager.getInstance(application)
            if (tm.gatewayPort <= 0) return null
            val okReq = Request.Builder().url(request.url.toString()).apply {
                for ((k, v) in request.requestHeaders) {
                    if (!k.equals("Accept-Encoding", true)) {
                        header(k, v)
                    }
                }
            }.build()
            val resp = tm.getTunneledOkHttpClient().newCall(okReq).execute()
            val ct = resp.header("Content-Type", "text/html; charset=utf-8").orEmpty()
            val mime = ct.substringBefore(';').trim().ifBlank { "application/octet-stream" }
            val charset = if ("charset=" in ct.lowercase(Locale.US)) {
                ct.substringAfter("charset=", "utf-8").substringBefore(';').trim()
            } else {
                "utf-8"
            }
            val respHeaders = LinkedHashMap<String, String>()
            for (name in resp.headers.names()) {
                resp.header(name)?.let { respHeaders[name] = it }
            }
            WebResourceResponse(
                mime,
                charset,
                resp.code,
                resp.message.ifBlank { "OK" },
                respHeaders,
                resp.body?.byteStream()
            )
        } catch (_: Throwable) {
            null
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            headers: Map<String, String>,
            @Assisted("cache") cacheStoragePathHandler: InternalStoragePathHandler,
            @Assisted("files") filesStoragePathHandler: InternalStoragePathHandler,
        ): TabWebViewClient
    }

    companion object {
        private const val TAG = "TabWebViewClient"
        private val emptyResponseByteArray: ByteArray = byteArrayOf()
        private const val BLOCKED_RESPONSE_MIME_TYPE = "text/plain"
        private const val BLOCKED_RESPONSE_ENCODING = "utf-8"
    }
}
