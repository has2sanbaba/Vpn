package org.greatfire.browser.web

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
import org.greatfire.browser.vpn.LegacySslTrustBridge
import org.greatfire.browser.vpn.TunnelManager
import org.greatfire.browser.vpn.VpnConnectionState
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Locale

class EnvoyWebViewClient(
    private val tunnelManager: TunnelManager,
    private val onUrlChanged: (WebView, String) -> Unit,
    private val onLoadingStateChanged: (WebView, Boolean) -> Unit
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
        return scheme !in ALLOWED_WEB_SCHEMES
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ENGLISH) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val uiState = tunnelManager.uiState.value

        // 1. Strict Kill-Switch enforcement: zero packets escape when VPN is disconnected!
        if (uiState.state == VpnConnectionState.DISCONNECTED && uiState.killSwitchEnabled) {
            return buildKillSwitchResponse()
        }

        // 2. Built-in Ad & Tracker domain blocker
        val host = uri.host?.lowercase(Locale.ENGLISH).orEmpty()
        if (tunnelManager.repository.adBlockEnabled && isBlockedTrackerHost(host)) {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "Blocked",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        }

        // 3. Legacy WebView client-level interception fallback (only when PROXY_OVERRIDE is absent)
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
        if (tunnelManager.repository.webRtcShieldEnabled) {
            view.evaluateJavascript(WEBRTC_SHIELD_JS, null)
        }
        if (url != null) {
            onUrlChanged(view, url)
        }
        onLoadingStateChanged(view, true)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (url != null) {
            onUrlChanged(view, url)
        }
        onLoadingStateChanged(view, false)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val host = runCatching { Uri.parse(error.url).host }.getOrNull()
        // Cryptographically validate ISRG Root X1/X2 on legacy Android 5.0 - 7.0 ONLY
        if (LegacySslTrustBridge.verifyLegacyWebViewSslError(error, host)) {
            handler.proceed()
        } else {
            handler.cancel()
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            onLoadingStateChanged(view, false)
        }
    }

    private fun buildKillSwitchResponse(): WebResourceResponse {
        val html = """
            <!DOCTYPE html>
            <html dir="rtl" lang="fa">
            <head>
            <meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width,initial-scale=1"/>
            <style>
            body{background:#0B1320;color:#F8FAFC;font-family:sans-serif;padding:32px 20px;text-align:center}
            .box{background:#131F33;border:1px solid #EF4444;border-radius:16px;padding:24px;max-width:420px;margin:40px auto}
            h2{color:#EF4444;margin-top:0}
            p{color:#94A3B8;line-height:1.7;font-size:14px}
            </style>
            </head>
            <body>
            <div class="box">
            <h2>🔒 قفل امنیتی Kill-Switch فعال است</h2>
            <p>برای جلوگیری از لو رفتن آی‌پی و اتصال مستقیم بدون فیلترشکن، بارگذاری سایت مسدود شد.<br/>لطفاً از نوار بالا دکمه <b>VPN</b> را بزنید و تونل GreatFire را روشن کنید.</p>
            </div>
            </body>
            </html>
        """.trimIndent()
        return WebResourceResponse(
            "text/html",
            "utf-8",
            503,
            "Kill-Switch Active",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        )
    }

    private fun isBlockedTrackerHost(host: String): Boolean {
        if (host.isEmpty()) return false
        return TRACKER_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    companion object {
        private val ALLOWED_WEB_SCHEMES = setOf("http", "https", "about", "data", "blob")

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

        // Prevents WebRTC STUN ICE gathering from leaking local/ISP IP addresses outside the proxy
        const val WEBRTC_SHIELD_JS = """
            (function() {
                try {
                    const noop = function() { throw new Error('WebRTC disabled by GreatFire Privacy Shield'); };
                    Object.defineProperty(window, 'RTCPeerConnection', { value: undefined, writable: false });
                    Object.defineProperty(window, 'webkitRTCPeerConnection', { value: undefined, writable: false });
                    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                        // Keep media playback intact, only block raw STUN peer leaks
                    }
                } catch (_) {}
            })();
        """
    }
}
