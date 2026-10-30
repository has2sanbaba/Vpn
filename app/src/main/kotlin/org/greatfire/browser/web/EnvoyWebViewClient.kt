package org.greatfire.browser.web

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Request
import org.greatfire.browser.media.VideoStreamSniffer
import org.greatfire.browser.vpn.TunnelManager
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Locale

class EnvoyWebViewClient(
    private val tabIdProvider: () -> String,
    private val pageMetaProvider: () -> Pair<String, String>,
    private val tunnelManager: TunnelManager,
    private val onUrlChanged: (WebView, String) -> Unit,
    private val onLoadingStateChanged: (WebView, Boolean) -> Unit,
    private val onHistoryVisited: ((title: String, url: String) -> Unit)? = null,
    private val onMainFrameNetworkError: ((WebView, String) -> Unit)? = null
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return false
        return handleSpecialSchemes(view, url)
    }

    @Deprecated("Required for API 21-23 compatibility")
    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
        if (url == null) return false
        return handleSpecialSchemes(view, url)
    }

    private fun handleSpecialSchemes(view: WebView, url: String): Boolean {
        if (url == BrowserTabManager.HOME_URL) {
            onUrlChanged(view, url)
            return true
        }
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.ENGLISH) ?: return false
        if (scheme in ALLOWED_WEB_SCHEMES) {
            return false
        }

        if (scheme == "intent") {
            return runCatching {
                val parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                val pm = view.context.packageManager
                if (parsed.resolveActivity(pm) != null) {
                    view.context.startActivity(parsed)
                } else {
                    val fallback = parsed.getStringExtra("browser_fallback_url")
                    if (!fallback.isNullOrBlank() &&
                        (fallback.startsWith("https://", true) || fallback.startsWith("http://", true))
                    ) {
                        view.loadUrl(fallback)
                    }
                }
                true
            }.getOrDefault(true)
        }

        if (scheme in EXTERNAL_SAFE_SCHEMES) {
            runCatching {
                val extIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                view.context.startActivity(extIntent)
            }
            return true
        }

        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ENGLISH) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase(Locale.ENGLISH).orEmpty()
        if (tunnelManager.adBlockEnabled && isBlockedTrackerHost(host)) {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "Blocked",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        }

        // IDM Network Layer Sniffer: inspect request URL on WebView IO thread (non-blocking & zero latency!)
        if (!request.isForMainFrame) {
            val (pageUrl, pageTitle) = pageMetaProvider()
            VideoStreamSniffer.recordCandidate(
                tabId = tabIdProvider(),
                rawUrl = uri.toString(),
                pageUrl = pageUrl,
                pageTitle = pageTitle
            )
        }

        if (WebViewProxyConfigurator.requiresClientInterception &&
            request.method.equals("GET", ignoreCase = true) &&
            !request.requestHeaders.containsKey("Range") &&
            !request.requestHeaders.containsKey("range")
        ) {
            return executeViaGreatFireClient(request)
        }

        return null
    }

    private fun executeViaGreatFireClient(request: WebResourceRequest): WebResourceResponse? {
        return try {
            val reqBuilder = Request.Builder().url(request.url.toString())
            for ((k, v) in request.requestHeaders) {
                if (!k.equals("Accept-Encoding", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            val resp = tunnelManager.getTunneledOkHttpClient().newCall(reqBuilder.build()).execute()
            val body = resp.body ?: return null
            val mediaType = body.contentType()
            val mime = if (mediaType != null) "${mediaType.type}/${mediaType.subtype}" else "text/html"
            val charset = mediaType?.charset(Charset.forName("UTF-8"))?.name() ?: "utf-8"

            if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.contains("mpegurl")) {
                val (pageUrl, pageTitle) = pageMetaProvider()
                VideoStreamSniffer.recordCandidate(
                    tabId = tabIdProvider(),
                    rawUrl = request.url.toString(),
                    pageUrl = pageUrl,
                    pageTitle = pageTitle,
                    mimeHint = mime
                )
            }

            val headersMap = LinkedHashMap<String, String>()
            for (i in 0 until resp.headers.size) {
                headersMap[resp.headers.name(i)] = resp.headers.value(i)
            }

            WebResourceResponse(
                mime,
                charset,
                resp.code,
                resp.message.ifBlank { "OK" },
                headersMap,
                body.byteStream()
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!url.isNullOrBlank() && url != "about:blank") {
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                VideoStreamSniffer.onMainFramePageStarted(tabIdProvider(), url)
            }
            onUrlChanged(view, url)
        }
        if (url != "about:blank") {
            onLoadingStateChanged(view, true)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (!url.isNullOrBlank() && url != BrowserTabManager.HOME_URL && url != "about:blank") {
            onUrlChanged(view, url)
            if (!isReload) {
                onHistoryVisited?.invoke(view.title.orEmpty(), url)
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (!url.isNullOrBlank() && url != "about:blank") {
            onUrlChanged(view, url)
            if (url != BrowserTabManager.HOME_URL) {
                onHistoryVisited?.invoke(view.title.orEmpty(), url)
                view.evaluateJavascript(VideoStreamSniffer.IDM_PAGE_SNIFFER_JS, null)
            }
        }
        onLoadingStateChanged(view, false)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Strict Google Play / Play Protect security compliance: never proceed past invalid TLS certificates
        handler.cancel()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            onLoadingStateChanged(view, false)
            val failingUrl = request.url?.toString().orEmpty()
            if (failingUrl.startsWith("http", ignoreCase = true)) {
                onMainFrameNetworkError?.invoke(view, failingUrl)
            }
        }
    }

    private fun isBlockedTrackerHost(host: String): Boolean {
        if (host.isEmpty()) return false
        return TRACKER_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    companion object {
        private val ALLOWED_WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")
        private val EXTERNAL_SAFE_SCHEMES = setOf("tel", "mailto", "sms", "geo", "market")

        private val TRACKER_SUFFIXES = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adnxs.com",
            "taboola.com",
            "outbrain.com",
            "scorecardresearch.com",
            "criteo.com"
        )
    }
}
