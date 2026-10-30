package org.greatfire.browser.media

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.greatfire.browser.vpn.TunnelManager
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Rebuilt v8.0 zero-leak bridge connecting Android [WebView] tabs to [VideoStreamSniffer]:
 * 1. Injects [VideoStreamSniffer.IDM_PAGE_SNIFFER_JS] at `DOCUMENT_START` across ALL frames
 *    (including cross-origin embedded player `<iframe>`s) via [WebViewCompat.addDocumentStartJavaScript]
 *    when supported, with automatic multi-stage fallback injection on older system WebViews.
 * 2. Exposes `window.DrIdmBridge` (`@JavascriptInterface`) across all frames to capture streams
 *    from `JSON.parse`, `fetch`, `XMLHttpRequest`, `<video>`, `JWPlayer`, `Video.js`, and Shadow DOM.
 * 3. Expands HLS Master Playlists via [VideoStreamSniffer.replaceMasterWithResolvedVariants],
 *    pairing every video variant with its `#EXT-X-MEDIA:TYPE=AUDIO` separate audio rendition and
 *    suppressing silent raw child sub-playlists.
 * 4. Runs lightweight 64-byte `Range: bytes=0-63` probes through the anti-censorship tunnel with
 *    browser `Referer`/`Origin`/`Cookie` headers to populate exact file sizes, upgrade extensionless
 *    `#EXTM3U` streams to HLS, and remove `text/html` or HTTP 404/410 false positives.
 */
object WebViewMediaInspector {

    private const val BRIDGE_NAME = "DrIdmBridge"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attachedWebViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    )
    private val docStartInstalledViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    )
    private val inspectedItems = ConcurrentHashMap.newKeySet<String>()

    fun tabKeyFor(webView: WebView?): String {
        if (webView == null) return "global"
        return "wv_${System.identityHashCode(webView)}"
    }

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface", "RequiresFeature")
    fun ensureAttached(webView: WebView) {
        if (attachedWebViews.add(webView)) {
            val tabKey = tabKeyFor(webView)
            val appCtx = webView.context.applicationContext
            try {
                webView.removeJavascriptInterface(BRIDGE_NAME)
            } catch (_: Throwable) {
            }
            webView.addJavascriptInterface(JsMediaBridge(appCtx, webView, tabKey), BRIDGE_NAME)
        }
        if (!docStartInstalledViews.contains(webView)) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        VideoStreamSniffer.IDM_PAGE_SNIFFER_JS,
                        setOf("*")
                    )
                    docStartInstalledViews.add(webView)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun onPageStarted(webView: WebView, url: String) {
        ensureAttached(webView)
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            VideoStreamSniffer.onMainFramePageStarted(tabKeyFor(webView), url)
            if (!docStartInstalledViews.contains(webView)) {
                mainHandler.postDelayed({ evaluateSnifferBatch(webView, forceRescan = false, onDone = null) }, 180L)
            }
        }
    }

    fun onProgressChanged(webView: WebView, newProgress: Int) {
        ensureAttached(webView)
        if (!docStartInstalledViews.contains(webView) && (newProgress in 30..40 || newProgress in 72..82)) {
            evaluateSnifferBatch(webView, forceRescan = false, onDone = null)
        } else if (newProgress == 100 && docStartInstalledViews.contains(webView)) {
            triggerLightweightDomScan(webView)
        }
    }

    fun onReceivedTitle(webView: WebView, title: String) {
        if (title.isNotBlank()) {
            VideoStreamSniffer.updatePageTitleForTab(tabKeyFor(webView), title)
        }
    }

    fun onPageFinished(webView: WebView, url: String) {
        ensureAttached(webView)
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        val title = runCatching { webView.title.orEmpty() }.getOrDefault("")
        if (title.isNotBlank()) {
            VideoStreamSniffer.updatePageTitleForTab(tabKeyFor(webView), title)
        }
        if (docStartInstalledViews.contains(webView)) {
            triggerLightweightDomScan(webView)
        } else {
            evaluateSnifferBatch(webView, forceRescan = false, onDone = null)
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

        val headers = request.requestHeaders ?: emptyMap()

        // Filter out MSE/DASH partial byte-range chunk fetches (e.g., Range: bytes=524288-1048575),
        // while keeping whole-file open ranges (Range: bytes=0-)!
        val rangeHdr = headers["Range"] ?: headers["range"]
        if (!rangeHdr.isNullOrBlank()) {
            val r = rangeHdr.trim().lowercase(Locale.US)
            if (r.startsWith("bytes=") && !r.startsWith("bytes=0-")) {
                return
            }
        }

        val acceptHdr = headers["Accept"] ?: headers["accept"] ?: ""
        val referer = headers["Referer"] ?: headers["referer"] ?: currentPageUrl
        val mimeHint = when {
            acceptHdr.contains("application/vnd.apple.mpegurl", true) ||
                acceptHdr.contains("application/x-mpegurl", true) -> "application/vnd.apple.mpegurl"
            acceptHdr.contains("video/", true) -> "video/*"
            acceptHdr.contains("audio/", true) -> "audio/*"
            else -> ""
        }

        val tabKey = tabKeyFor(webView)
        val item = VideoStreamSniffer.recordCandidate(
            tabId = tabKey,
            rawUrl = reqUrl,
            pageUrl = referer,
            mimeHint = mimeHint
        )
        if (item != null) {
            scheduleStreamInspection(appContext.applicationContext, tabKey, item, null)
        }
    }

    /**
     * Actively rescans the current page DOM, Shadow DOMs, same-origin + cross-origin `<iframe>`s
     * (`postMessage`), `performance.getEntriesByType('resource')`, and player APIs (`jwplayer`/`videojs`).
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
        val currentTitle = runCatching { webView.title.orEmpty() }.getOrDefault("")
        if (currentTitle.isNotBlank()) {
            VideoStreamSniffer.updatePageTitleForTab(tabKey, currentTitle)
        }
        evaluateSnifferBatch(webView, forceRescan = true) {
            mainHandler.post {
                onCompleted(VideoStreamSniffer.getCountForTab(tabKey))
            }
        }
    }

    private fun triggerLightweightDomScan(webView: WebView) {
        val currentUrl = runCatching { webView.url.orEmpty() }.getOrDefault("")
        if (!currentUrl.startsWith("http://", true) && !currentUrl.startsWith("https://", true)) return
        try {
            webView.evaluateJavascript(
                "(function(){ if (typeof window.__drIdmRescan === 'function') window.__drIdmRescan(false); })();",
                null
            )
        } catch (_: Throwable) {
        }
    }

    private fun evaluateSnifferBatch(
        webView: WebView,
        forceRescan: Boolean,
        onDone: (() -> Unit)?
    ) {
        val currentUrl = runCatching { webView.url.orEmpty() }.getOrDefault("")
        if (!currentUrl.startsWith("http://", true) && !currentUrl.startsWith("https://", true)) {
            onDone?.invoke()
            return
        }
        val tabKey = tabKeyFor(webView)
        val appCtx = webView.context.applicationContext
        val currentTitle = runCatching { webView.title.orEmpty() }.getOrDefault("")

        val script = if (forceRescan) {
            VideoStreamSniffer.IDM_PAGE_SNIFFER_JS.replace("})(false);", "})(true);")
        } else {
            VideoStreamSniffer.IDM_PAGE_SNIFFER_JS
        }

        try {
            webView.evaluateJavascript(script) { rawEval ->
                ingestEvaluatedBatch(appCtx, tabKey, rawEval, currentUrl, currentTitle, onDone)
            }
        } catch (_: Throwable) {
            onDone?.invoke()
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
        val contentDisposition = obj.optString("cd", "").trim()
        val qualityHint = obj.optString("quality", "").trim()
        val mimeHint = explicitMime.ifEmpty {
            if (kind.equals("audio", true)) "audio/*" else ""
        }
        val detected = VideoStreamSniffer.recordCandidate(
            tabId = tabKey,
            rawUrl = mediaUrl,
            pageUrl = obj.optString("pageUrl", fallbackPageUrl).ifBlank { fallbackPageUrl },
            pageTitle = obj.optString("title", fallbackTitle).ifBlank { fallbackTitle },
            mimeHint = mimeHint,
            width = obj.optInt("width", 0),
            height = obj.optInt("height", 0),
            durationSec = obj.optInt("duration", 0),
            contentDisposition = contentDisposition,
            qualityHint = qualityHint
        )
        if (detected != null) {
            scheduleStreamInspection(appContext, tabKey, detected, onVariantExpanded)
        }
    }

    private fun scheduleStreamInspection(
        appContext: Context,
        tabKey: String,
        item: DetectedMediaItem,
        onUpdated: (() -> Unit)?
    ) {
        if (item.isMasterVariant && item.contentLengthBytes > 0L) return
        val key = "$tabKey|${item.kind}|${item.id}"
        if (!inspectedItems.add(key)) return
        if (inspectedItems.size > 512) {
            inspectedItems.clear()
            inspectedItems.add(key)
        }
        ioScope.launch {
            try {
                val client = TunnelManager.getInstance(appContext).getTunneledOkHttpClient()
                if (item.isHls) {
                    inspectHlsPlaylist(client, tabKey, item, onUpdated)
                } else {
                    inspectDirectMedia(client, tabKey, item, onUpdated)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun inspectHlsPlaylist(
        client: OkHttpClient,
        tabKey: String,
        item: DetectedMediaItem,
        onUpdated: (() -> Unit)?
    ) {
        val reqBuilder = Request.Builder()
            .url(item.url)
            .get()
            .header("User-Agent", DEFAULT_UA)
            .header("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, text/plain, */*")
        attachBrowserContextHeaders(reqBuilder, item.url, item.pageUrl)

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 404 || resp.code == 410) {
                    VideoStreamSniffer.removeInvalidCandidate(tabKey, item.id)
                }
                return
            }
            val finalUrl = resp.request.url.toString()
            val body = resp.body?.string().orEmpty()
            if (!HlsPlaylistParser.looksLikeHlsManifest(body)) return

            if (HlsPlaylistParser.isMasterPlaylist(body)) {
                val variants = HlsPlaylistParser.parseMasterPlaylist(body, finalUrl)
                val audioRenditions = HlsPlaylistParser.parseAudioRenditions(body, finalUrl)
                if (variants.isNotEmpty()) {
                    var durationSec = item.durationSec
                    if (durationSec <= 0) {
                        val firstVideoVariant = variants.firstOrNull { !it.isAudioOnly } ?: variants.first()
                        durationSec = probeVariantMediaDurationSec(client, firstVideoVariant.url, item.pageUrl)
                    }
                    val replaced = VideoStreamSniffer.replaceMasterWithResolvedVariants(
                        tabId = tabKey,
                        masterItem = item,
                        variants = variants,
                        audioRenditions = audioRenditions,
                        probedDurationSec = durationSec
                    )
                    if (replaced && onUpdated != null) {
                        mainHandler.post { onUpdated.invoke() }
                    }
                }
            } else {
                val mediaPl = HlsPlaylistParser.parseMediaPlaylist(body, finalUrl)
                if (mediaPl.isDrmOrEncrypted) {
                    VideoStreamSniffer.removeInvalidCandidate(tabKey, item.id)
                    return
                }
                val dur = mediaPl.totalDurationSec.toInt()
                if (dur > 0) {
                    VideoStreamSniffer.updateProbedMetadata(
                        tabId = tabKey,
                        itemId = item.id,
                        durationSec = dur
                    )
                    if (onUpdated != null) {
                        mainHandler.post { onUpdated.invoke() }
                    }
                }
            }
        }
    }

    private fun probeVariantMediaDurationSec(
        client: OkHttpClient,
        variantUrl: String,
        pageUrl: String
    ): Int {
        return try {
            val reqBuilder = Request.Builder()
                .url(variantUrl)
                .get()
                .header("User-Agent", DEFAULT_UA)
            attachBrowserContextHeaders(reqBuilder, variantUrl, pageUrl)
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return 0
                val finalUrl = resp.request.url.toString()
                val text = resp.body?.string().orEmpty()
                if (!HlsPlaylistParser.looksLikeHlsManifest(text) || HlsPlaylistParser.isMasterPlaylist(text)) {
                    return 0
                }
                HlsPlaylistParser.parseMediaPlaylist(text, finalUrl).totalDurationSec.toInt()
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun inspectDirectMedia(
        client: OkHttpClient,
        tabKey: String,
        item: DetectedMediaItem,
        onUpdated: (() -> Unit)?
    ) {
        val reqBuilder = Request.Builder()
            .url(item.url)
            .get()
            .header("Range", "bytes=0-63")
            .header("User-Agent", DEFAULT_UA)
        attachBrowserContextHeaders(reqBuilder, item.url, item.pageUrl)

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 404 || resp.code == 410) {
                VideoStreamSniffer.removeInvalidCandidate(tabKey, item.id)
                return
            }
            if (!resp.isSuccessful && resp.code != 206) return

            val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
            val cd = resp.header("Content-Disposition").orEmpty()
            if (ct.startsWith("text/html") || ct == "application/json" || ct.startsWith("image/")) {
                VideoStreamSniffer.removeInvalidCandidate(tabKey, item.id)
                return
            }

            // Peek up to 64 bytes to detect extensionless #EXTM3U manifests disguised as octet-stream/text!
            val peekBytes = try {
                resp.body?.byteStream()?.use { ins ->
                    val buf = ByteArray(64)
                    val n = ins.read(buf)
                    if (n > 0) buf.copyOf(n) else ByteArray(0)
                } ?: ByteArray(0)
            } catch (_: Throwable) {
                ByteArray(0)
            }
            val peekAscii = String(peekBytes, Charsets.ISO_8859_1).trimStart().removePrefix("\uFEFF")
            if (peekAscii.startsWith("#EXTM3U", ignoreCase = true) ||
                ct.contains("mpegurl")
            ) {
                VideoStreamSniffer.updateProbedMetadata(
                    tabId = tabKey,
                    itemId = item.id,
                    probedMime = "application/vnd.apple.mpegurl"
                )
                val upgraded = item.copy(
                    kind = SniffedMediaKind.HLS_STREAM,
                    mimeType = "application/vnd.apple.mpegurl",
                    formatBadge = "HLS"
                )
                inspectHlsPlaylist(client, tabKey, upgraded, onUpdated)
                return
            }

            val cr = resp.header("Content-Range")
            val total = if (cr != null && cr.contains('/')) {
                cr.substringAfterLast('/').trim().toLongOrNull() ?: -1L
            } else {
                resp.header("Content-Length")?.toLongOrNull() ?: -1L
            }
            val cdName = extractFileNameHint(cd)

            VideoStreamSniffer.updateProbedMetadata(
                tabId = tabKey,
                itemId = item.id,
                contentLength = total,
                probedMime = ct,
                fileNameHint = cdName
            )
            if (onUpdated != null) {
                mainHandler.post { onUpdated.invoke() }
            }
        }
    }

    private fun extractFileNameHint(cd: String): String {
        if (cd.isBlank()) return ""
        val starMatch = Regex("""filename\*\s*=\s*(?:UTF-8''|utf-8'')?([^;\r\n]+)""").find(cd)
        if (starMatch != null) {
            return runCatching {
                java.net.URLDecoder.decode(starMatch.groupValues[1].trim().trim('"', '\''), "UTF-8")
            }.getOrDefault("")
        }
        val plainMatch = Regex("""filename\s*=\s*(?:"([^"]+)"|'([^']+)'|([^;\r\n]+))""", RegexOption.IGNORE_CASE).find(cd)
        if (plainMatch != null) {
            return (plainMatch.groupValues[1].ifBlank { plainMatch.groupValues[2] }.ifBlank { plainMatch.groupValues[3] }).trim()
        }
        return ""
    }

    private fun attachBrowserContextHeaders(reqBuilder: Request.Builder, targetUrl: String, pageUrl: String) {
        if (pageUrl.startsWith("http://", true) || pageUrl.startsWith("https://", true)) {
            reqBuilder.header("Referer", pageUrl)
            extractOrigin(pageUrl)?.let { pageOrigin ->
                val targetOrigin = extractOrigin(targetUrl)
                if (targetOrigin != null && !targetOrigin.equals(pageOrigin, ignoreCase = true)) {
                    reqBuilder.header("Origin", pageOrigin)
                }
            }
        }
        runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { reqBuilder.header("Cookie", it) }
    }

    private fun extractOrigin(url: String): String? {
        return try {
            val u = Uri.parse(url)
            val scheme = u.scheme?.lowercase(Locale.US) ?: return null
            val host = u.host?.lowercase(Locale.US) ?: return null
            val port = u.port
            if (port > 0 && port != 80 && port != 443) "$scheme://$host:$port" else "$scheme://$host"
        } catch (_: Throwable) {
            null
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
        private val webView: WebView,
        private val tabKey: String
    ) {
        @JavascriptInterface
        fun onMediaDetected(payloadJson: String?) {
            if (payloadJson.isNullOrBlank()) return
            mainHandler.post {
                val fallbackUrl = runCatching { webView.url.orEmpty() }.getOrDefault("")
                val fallbackTitle = runCatching { webView.title.orEmpty() }.getOrDefault("")
                ioScope.launch {
                    runCatching {
                        val obj = JSONObject(payloadJson)
                        recordJsonCandidate(appContext, tabKey, obj, fallbackUrl, fallbackTitle, null)
                    }
                }
            }
        }
    }
}
