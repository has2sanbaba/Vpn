package org.greatfire.browser.media

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import org.greatfire.browser.vpn.TunnelManager
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight, zero-leak bridge connecting Android [WebView] tabs to [VideoStreamSniffer]
 * and async HLS master-playlist variant discovery (`1080p`, `720p`, `480p`).
 */
object WebViewMediaInspector {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attachedWebViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    )
    private val expandedMasters = ConcurrentHashMap.newKeySet<String>()

    fun tabKeyFor(webView: WebView?): String {
        if (webView == null) return "global"
        return "wv_${System.identityHashCode(webView)}"
    }

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun ensureAttached(webView: WebView) {
        if (!attachedWebViews.add(webView)) return
        val tabKey = tabKeyFor(webView)
        val appCtx = webView.context.applicationContext
        webView.addJavascriptInterface(JsMediaBridge(appCtx, tabKey), "DrIdmBridge")
    }

    fun onPageStarted(webView: WebView, url: String) {
        ensureAttached(webView)
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            VideoStreamSniffer.onMainFramePageStarted(tabKeyFor(webView), url)
        }
    }

    fun onPageFinished(webView: WebView, url: String) {
        ensureAttached(webView)
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        val tabKey = tabKeyFor(webView)
        val appCtx = webView.context.applicationContext
        val pageTitle = webView.title.orEmpty()
        webView.evaluateJavascript(VideoStreamSniffer.IDM_PAGE_SNIFFER_JS) { rawEval ->
            ingestEvaluatedBatch(appCtx, tabKey, rawEval, url, pageTitle, null)
        }
    }

    fun onInterceptedRequest(
        appContext: Context,
        webView: WebView,
        request: WebResourceRequest,
        currentPageUrl: String
    ) {
        val reqUrl = request.url?.toString().orEmpty()
        if (!reqUrl.startsWith("http://", true) && !reqUrl.startsWith("https://", true)) return
        val acceptHdr = request.requestHeaders["Accept"].orEmpty()
        val mimeHint = when {
            acceptHdr.contains("video/", true) -> "video/*"
            acceptHdr.contains("audio/", true) -> "audio/*"
            else -> ""
        }
        val tabKey = tabKeyFor(webView)
        val item = VideoStreamSniffer.recordCandidate(
            tabId = tabKey,
            rawUrl = reqUrl,
            pageUrl = currentPageUrl,
            mimeHint = mimeHint
        )
        if (item != null && item.isHls) {
            expandHlsMasterVariantsAsync(appContext, tabKey, item, null)
        }
    }

    /**
     * Actively rescans the current page DOM, open Shadow DOMs, accessible same-origin iframes,
     * `performance.getEntriesByType('resource')`, and hooked `fetch`/`XHR` streams.
     * Clears stale JS suppression so newly loaded SPA videos are ALWAYS discovered!
     */
    fun rescanWebViewNow(
        webView: WebView?,
        onCompleted: (totalDetected: Int) -> Unit
    ) {
        if (webView == null) {
            onCompleted(0)
            return
        }
        ensureAttached(webView)
        val tabKey = tabKeyFor(webView)
        val appCtx = webView.context.applicationContext
        val currentUrl = webView.url.orEmpty()
        val currentTitle = webView.title.orEmpty()

        // Run full installer + forceClear=true so even pages where SPA replaced window state get rescanned
        val forceInstallAndScanJs =
            VideoStreamSniffer.IDM_PAGE_SNIFFER_JS.replace("})(false);", "})(true);")

        webView.evaluateJavascript(forceInstallAndScanJs) { rawEval ->
            ingestEvaluatedBatch(appCtx, tabKey, rawEval, currentUrl, currentTitle) {
                mainHandler.post {
                    onCompleted(VideoStreamSniffer.getCountForTab(tabKey))
                }
            }
        }
    }

    private fun ingestEvaluatedBatch(
        appContext: Context,
        tabKey: String,
        rawEvalResult: String?,
        fallbackPageUrl: String,
        fallbackTitle: String,
        onDone: (() -> Unit)?
    ) {
        ioScope.launch {
            try {
                val jsonText = unwrapEvaluateJsString(rawEvalResult)
                if (jsonText.startsWith("[")) {
                    val arr = JSONArray(jsonText)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        recordJsonCandidate(appContext, tabKey, obj, fallbackPageUrl, fallbackTitle, onDone)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                onDone?.invoke()
            }
        }
    }

    private fun recordJsonCandidate(
        appContext: Context,
        tabKey: String,
        obj: JSONObject,
        fallbackPageUrl: String,
        fallbackTitle: String,
        onVariantExpanded: (() -> Unit)?
    ) {
        val mediaUrl = obj.optString("url", "").trim()
        if (mediaUrl.isEmpty()) return
        val kind = obj.optString("kind", "video")
        val explicitMime = obj.optString("mime", "").trim()
        val mimeHint = explicitMime.ifEmpty {
            if (kind.equals("audio", true)) "audio/*" else ""
        }
        val detected = VideoStreamSniffer.recordCandidate(
            tabId = tabKey,
            rawUrl = mediaUrl,
            pageUrl = obj.optString("pageUrl", fallbackPageUrl),
            pageTitle = obj.optString("title", fallbackTitle),
            mimeHint = mimeHint,
            width = obj.optInt("width", 0),
            height = obj.optInt("height", 0),
            durationSec = obj.optInt("duration", 0)
        )
        if (detected != null && detected.isHls) {
            expandHlsMasterVariantsAsync(appContext, tabKey, detected, onVariantExpanded)
        }
    }

    private fun expandHlsMasterVariantsAsync(
        appContext: Context,
        tabKey: String,
        item: DetectedMediaItem,
        onExpanded: (() -> Unit)?
    ) {
        val masterKey = "$tabKey|${item.url}"
        if (!expandedMasters.add(masterKey)) return
        if (expandedMasters.size > 256) {
            expandedMasters.clear()
            expandedMasters.add(masterKey)
        }
        ioScope.launch {
            try {
                val client = TunnelManager.getInstance(appContext).getTunneledOkHttpClient()
                val reqBuilder = Request.Builder()
                    .url(item.url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
                if (item.pageUrl.startsWith("http")) {
                    reqBuilder.header("Referer", item.pageUrl)
                }
                runCatching { CookieManager.getInstance().getCookie(item.url) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { reqBuilder.header("Cookie", it) }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    if (!HlsPlaylistParser.isMasterPlaylist(body)) return@use
                    val variants = HlsPlaylistParser.parseMasterPlaylist(body, item.url)
                    var addedAny = false
                    for (v in variants.take(6)) {
                        val rec = VideoStreamSniffer.recordCandidate(
                            tabId = tabKey,
                            rawUrl = v.url,
                            pageUrl = item.pageUrl,
                            pageTitle = item.title,
                            mimeHint = "application/vnd.apple.mpegurl",
                            width = v.width,
                            height = v.height,
                            durationSec = item.durationSec
                        )
                        if (rec != null) addedAny = true
                    }
                    if (addedAny && onExpanded != null) {
                        mainHandler.post { onExpanded.invoke() }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun unwrapEvaluateJsString(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return "[]"
        if (trimmed.startsWith("[")) return trimmed
        return try {
            (JSONTokener(trimmed).nextValue() as? String)?.trim().orEmpty()
        } catch (_: Throwable) {
            "[]"
        }
    }

    private class JsMediaBridge(
        private val appContext: Context,
        private val tabKey: String
    ) {
        @JavascriptInterface
        fun onMediaDetected(payloadJson: String?) {
            if (payloadJson.isNullOrBlank()) return
            ioScope.launch {
                runCatching {
                    val obj = JSONObject(payloadJson)
                    recordJsonCandidate(appContext, tabKey, obj, "", "", null)
                }
            }
        }
    }
}
