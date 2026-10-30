package org.greatfire.browser.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class SniffedMediaKind {
    VIDEO,
    HLS_STREAM,
    AUDIO
}

data class DetectedMediaItem(
    val id: String,
    val tabId: String,
    val url: String,
    val kind: SniffedMediaKind,
    val title: String,
    val pageUrl: String,
    val mimeType: String,
    val formatBadge: String,
    val qualityLabel: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Int = 0,
    val contentLengthBytes: Long = -1L,
    val detectedAt: Long = System.currentTimeMillis()
) {
    val isHls: Boolean get() = kind == SniffedMediaKind.HLS_STREAM

    fun formattedDuration(): String {
        if (durationSec <= 0) return ""
        val hrs = durationSec / 3600
        val mins = (durationSec % 3600) / 60
        val secs = durationSec % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun suggestedFileName(): String {
        val cleanTitle = title
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(56)
            .ifBlank { "video_${System.currentTimeMillis() % 100000}" }
        val qSuffix = if (height > 0) "_${height}p" else ""
        val ext = when {
            isHls -> "mp4"
            formatBadge.equals("WEBM", true) -> "webm"
            formatBadge.equals("MKV", true) -> "mkv"
            formatBadge.equals("MOV", true) -> "mov"
            formatBadge.equals("MP3", true) -> "mp3"
            formatBadge.equals("M4A", true) -> "m4a"
            formatBadge.equals("OGG", true) -> "ogg"
            formatBadge.equals("WAV", true) -> "wav"
            kind == SniffedMediaKind.AUDIO -> "mp3"
            else -> "mp4"
        }
        return "${cleanTitle.replace(' ', '_')}${qSuffix}.$ext"
    }
}

/**
 * IDM-Style (Internet Download Manager) Real-Time Web Video & Stream Detector.
 * Combines WebView network request inspection (`shouldInterceptRequest`) with an audited
 * DOM/HTMLMediaElement/XHR/Fetch inspector (`IDM_PAGE_SNIFFER_JS`) to capture progressive
 * videos (`.mp4`, `.webm`, `.mkv`), audio (`.mp3`, `.m4a`), and adaptive HLS playlists (`.m3u8`).
 */
object VideoStreamSniffer {

    private val VIDEO_EXTS = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "3gp", "avi", "ogv")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val HLS_EXTS = setOf("m3u8")

    // Never report HLS/DASH micro-segments or tracker beacons as standalone downloads
    private val IGNORED_SEGMENT_EXTS = setOf("ts", "m4s", "cmfv", "cmfa", "vtt", "srt", "jpg", "jpeg", "png", "webp", "gif", "svg", "css", "js", "woff", "woff2")
    private val IGNORED_HOST_SUBSTRINGS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google",
        "facebook.com/tr",
        "mc.yandex.ru"
    )

    private val tabMediaMap = ConcurrentHashMap<String, LinkedHashMap<String, DetectedMediaItem>>()
    private val dismissedPageMap = ConcurrentHashMap<String, String>()

    private val _updatesFlow = MutableStateFlow(0L)
    val updatesFlow: StateFlow<Long> = _updatesFlow.asStateFlow()

    fun getMediaForTab(tabId: String): List<DetectedMediaItem> {
        val map = tabMediaMap[tabId] ?: return emptyList()
        return synchronized(map) {
            map.values.sortedWith(
                compareByDescending<DetectedMediaItem> { it.isHls }
                    .thenByDescending { it.height }
                    .thenByDescending { it.detectedAt }
            )
        }
    }

    fun getCountForTab(tabId: String): Int {
        val map = tabMediaMap[tabId] ?: return 0
        return synchronized(map) { map.size }
    }

    fun isFloatingPillDismissed(tabId: String, currentPageUrl: String): Boolean {
        return dismissedPageMap[tabId] == normalizeDedupeUrl(currentPageUrl)
    }

    fun dismissFloatingPillForPage(tabId: String, currentPageUrl: String) {
        dismissedPageMap[tabId] = normalizeDedupeUrl(currentPageUrl)
        bumpUpdate()
    }

    fun clearTab(tabId: String) {
        val removed = tabMediaMap.remove(tabId)
        dismissedPageMap.remove(tabId)
        if (removed != null) {
            bumpUpdate()
        }
    }

    fun onMainFramePageStarted(tabId: String, newPageUrl: String) {
        val map = tabMediaMap[tabId] ?: return
        val newNorm = normalizeDedupeUrl(newPageUrl)
        var changed = false
        synchronized(map) {
            val firstExistingPage = map.values.firstOrNull()?.pageUrl?.let { normalizeDedupeUrl(it) }
            if (firstExistingPage != null && firstExistingPage != newNorm) {
                map.clear()
                changed = true
            }
        }
        if (changed) {
            dismissedPageMap.remove(tabId)
            bumpUpdate()
        }
    }

    fun updateProbedSize(tabId: String, itemId: String, contentLength: Long) {
        if (contentLength <= 0L) return
        val map = tabMediaMap[tabId] ?: return
        var updated = false
        synchronized(map) {
            val existing = map[itemId]
            if (existing != null && existing.contentLengthBytes != contentLength) {
                map[itemId] = existing.copy(contentLengthBytes = contentLength)
                updated = true
            }
        }
        if (updated) bumpUpdate()
    }

    fun recordCandidate(
        tabId: String,
        rawUrl: String,
        pageUrl: String = "",
        pageTitle: String = "",
        mimeHint: String = "",
        width: Int = 0,
        height: Int = 0,
        durationSec: Int = 0
    ): DetectedMediaItem? {
        val classified = classifyCandidate(
            tabId = tabId,
            rawUrl = rawUrl,
            pageUrl = pageUrl,
            pageTitle = pageTitle,
            mimeHint = mimeHint,
            width = width,
            height = height,
            durationSec = durationSec
        ) ?: return null

        val map = tabMediaMap.getOrPut(tabId) { LinkedHashMap() }
        synchronized(map) {
            val existing = map[classified.id]
            if (existing != null) {
                val merged = existing.copy(
                    title = if (classified.title.isNotBlank() &&
                        (existing.title.isBlank() || existing.title.startsWith("ویدیو ") || existing.title.contains('.'))
                    ) classified.title else existing.title,
                    pageUrl = existing.pageUrl.ifBlank { classified.pageUrl },
                    width = maxOf(existing.width, classified.width),
                    height = maxOf(existing.height, classified.height),
                    durationSec = maxOf(existing.durationSec, classified.durationSec),
                    qualityLabel = classified.qualityLabel.ifBlank { existing.qualityLabel }
                )
                if (merged != existing) {
                    map[classified.id] = merged
                    bumpUpdate()
                }
                return merged
            }
            if (map.size >= 40) {
                val oldestKey = map.keys.firstOrNull()
                if (oldestKey != null) map.remove(oldestKey)
            }
            map[classified.id] = classified
        }
        bumpUpdate()
        return classified
    }

    /**
     * Pure classification logic (zero Android framework dependency — 100% JVM unit-testable!).
     */
    fun classifyCandidate(
        tabId: String,
        rawUrl: String,
        pageUrl: String = "",
        pageTitle: String = "",
        mimeHint: String = "",
        width: Int = 0,
        height: Int = 0,
        durationSec: Int = 0
    ): DetectedMediaItem? {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (host.isEmpty() || host == "127.0.0.1" || host == "localhost") return null
        if (IGNORED_HOST_SUBSTRINGS.any { host.contains(it) }) return null

        val rawPath = uri.rawPath.orEmpty()
        val lastSeg = rawPath.substringAfterLast('/')
        val ext = lastSeg.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext in IGNORED_SEGMENT_EXTS) return null

        // Skip HLS segment chunks disguised with query strings like seg-14-v1-a1.ts?token=...
        if (rawPath.lowercase(Locale.US).let { it.endsWith(".ts") || it.endsWith(".m4s") || it.contains("/seg-") && it.contains(".ts") }) {
            return null
        }

        val queryMap = parseQueryParams(uri.rawQuery.orEmpty())

        // 1. Progressive googlevideo streams (only keep muxed video+audio itag 18/22 or clean audio)
        if (host.endsWith("googlevideo.com") && rawPath.contains("videoplayback")) {
            val itag = queryMap["itag"]?.toIntOrNull()
            val qMime = URLDecoder.decode(queryMap["mime"].orEmpty(), "UTF-8").lowercase(Locale.US)
            val isMuxedMp4 = itag == 18 || itag == 22
            val isCleanAudio = itag == 140 || qMime.startsWith("audio/mp4")
            if (!isMuxedMp4 && !isCleanAudio) return null

            val cleanUrl = stripQueryKeys(trimmed, setOf("range", "rn", "rbuf"))
            val resHeight = when (itag) {
                22 -> 720
                18 -> 360
                else -> 0
            }
            val qLabel = when (itag) {
                22 -> "720p HD"
                18 -> "360p MP4"
                else -> "M4A Audio"
            }
            val kind = if (isCleanAudio) SniffedMediaKind.AUDIO else SniffedMediaKind.VIDEO
            val badge = if (isCleanAudio) "M4A" else "MP4"
            val id = normalizeDedupeUrl(cleanUrl)
            return DetectedMediaItem(
                id = id,
                tabId = tabId,
                url = cleanUrl,
                kind = kind,
                title = bestTitle(pageTitle, cleanUrl, kind),
                pageUrl = pageUrl,
                mimeType = if (isCleanAudio) "audio/mp4" else "video/mp4",
                formatBadge = badge,
                qualityLabel = qLabel,
                width = 0,
                height = resHeight,
                durationSec = durationSec
            )
        }

        // 2. Instagram / Facebook byte-range progressive tracks -> strip bytestart/byteend for full file!
        var normalizedDownloadUrl = trimmed
        if ((host.contains("cdninstagram") || host.contains("fbcdn")) &&
            (queryMap.containsKey("bytestart") || queryMap.containsKey("byteend"))
        ) {
            normalizedDownloadUrl = stripQueryKeys(trimmed, setOf("bytestart", "byteend"))
        }

        val cleanMime = mimeHint.substringBefore(';').trim().lowercase(Locale.US)
        val queryMime = (queryMap["mime"] ?: queryMap["type"] ?: queryMap["content_type"]).orEmpty()
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .lowercase(Locale.US)

        val isHls = ext in HLS_EXTS ||
            rawPath.lowercase(Locale.US).contains(".m3u8") ||
            cleanMime.contains("mpegurl") ||
            queryMime.contains("mpegurl")

        val isAudio = !isHls && (
            ext in AUDIO_EXTS ||
                cleanMime.startsWith("audio/") ||
                queryMime.startsWith("audio/")
            )

        val isVideo = !isHls && !isAudio && (
            ext in VIDEO_EXTS ||
                cleanMime.startsWith("video/") ||
                queryMime.startsWith("video/")
            )

        if (!isHls && !isVideo && !isAudio) {
            return null
        }

        val kind = when {
            isHls -> SniffedMediaKind.HLS_STREAM
            isAudio -> SniffedMediaKind.AUDIO
            else -> SniffedMediaKind.VIDEO
        }

        val badge = when {
            isHls -> "HLS"
            ext in VIDEO_EXTS || ext in AUDIO_EXTS -> ext.uppercase(Locale.US)
            cleanMime == "video/webm" -> "WEBM"
            cleanMime == "video/x-matroska" -> "MKV"
            cleanMime.startsWith("audio/mp4") -> "M4A"
            cleanMime.startsWith("audio/") -> "MP3"
            else -> "MP4"
        }

        val resolvedMime = when {
            isHls -> "application/vnd.apple.mpegurl"
            cleanMime.startsWith("video/") || cleanMime.startsWith("audio/") -> cleanMime
            ext == "webm" -> "video/webm"
            ext == "mkv" -> "video/x-matroska"
            ext == "mp3" -> "audio/mpeg"
            ext == "m4a" -> "audio/mp4"
            isAudio -> "audio/mpeg"
            else -> "video/mp4"
        }

        val inferredHeight = if (height > 0) height else inferResolutionFromUrl(normalizedDownloadUrl)
        val qLabel = when {
            isHls && inferredHeight > 0 -> "${inferredHeight}p استریم"
            isHls -> "چند کیفیتی (HLS)"
            inferredHeight >= 1080 -> "${inferredHeight}p Full HD"
            inferredHeight >= 720 -> "${inferredHeight}p HD"
            inferredHeight > 0 -> "${inferredHeight}p"
            isAudio -> "فایل صوتی"
            else -> ""
        }

        val dedupeKey = normalizeDedupeUrl(normalizedDownloadUrl)
        return DetectedMediaItem(
            id = dedupeKey,
            tabId = tabId,
            url = normalizedDownloadUrl,
            kind = kind,
            title = bestTitle(pageTitle, normalizedDownloadUrl, kind),
            pageUrl = pageUrl,
            mimeType = resolvedMime,
            formatBadge = badge,
            qualityLabel = qLabel,
            width = width,
            height = inferredHeight,
            durationSec = durationSec
        )
    }

    private fun inferResolutionFromUrl(url: String): Int {
        val m = Regex("""(?:^|[/_.-])(2160|1440|1080|720|480|360|240)p?(?:[/_?.&-]|$)""", RegexOption.IGNORE_CASE)
            .find(url)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun bestTitle(pageTitle: String, url: String, kind: SniffedMediaKind): String {
        val cleanPageTitle = pageTitle.trim()
        if (cleanPageTitle.isNotEmpty() &&
            !cleanPageTitle.startsWith("http", ignoreCase = true) &&
            cleanPageTitle != "تب جدید"
        ) {
            return cleanPageTitle
        }
        val pathName = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .substringBeforeLast('.')
            .trim()
        if (pathName.length in 3..70 && !pathName.equals("master", true) && !pathName.equals("index", true) && !pathName.equals("playlist", true)) {
            return pathName
        }
        return when (kind) {
            SniffedMediaKind.HLS_STREAM -> "استریم ویدیو آنلاین"
            SniffedMediaKind.AUDIO -> "فایل صوتی صفحه"
            SniffedMediaKind.VIDEO -> "ویدیوی صفحه"
        }
    }

    private fun normalizeDedupeUrl(url: String): String {
        return url.trim().substringBefore('#')
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    private fun stripQueryKeys(fullUrl: String, keysToDrop: Set<String>): String {
        val qIdx = fullUrl.indexOf('?')
        if (qIdx < 0) return fullUrl
        val base = fullUrl.substring(0, qIdx)
        val rawQuery = fullUrl.substring(qIdx + 1).substringBefore('#')
        val kept = rawQuery.split('&').filter { part ->
            val key = part.substringBefore('=').lowercase(Locale.US)
            key.isNotEmpty() && key !in keysToDrop
        }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    private fun bumpUpdate() {
        _updatesFlow.value = System.nanoTime()
    }

    /**
     * Audited, lightweight IDM DOM + Fetch/XHR Media Sniffer injected into WebViews.
     * Detects `<video>`, `<audio>`, `<source>`, OpenGraph video tags, direct download links,
     * and SPA JSON stream manifests without modifying page layout or breaking site players.
     */
    const val IDM_PAGE_SNIFFER_JS = """
        (function() {
            try {
                var seen = window.__drIdmSeen || (window.__drIdmSeen = Object.create(null));
                function report(kind, rawUrl, w, h, dur) {
                    try {
                        if (!rawUrl || typeof rawUrl !== 'string') return;
                        if (rawUrl.indexOf('blob:') === 0 || rawUrl.indexOf('data:') === 0 || rawUrl.indexOf('javascript:') === 0) return;
                        var abs = new URL(rawUrl, document.baseURI).href;
                        var key = abs.split('#')[0];
                        var metaKey = key + '|' + (h || 0) + '|' + Math.round(dur || 0);
                        if (seen[metaKey]) return;
                        seen[metaKey] = 1;
                        if (window.DrIdmBridge && window.DrIdmBridge.onMediaDetected) {
                            window.DrIdmBridge.onMediaDetected(JSON.stringify({
                                url: abs,
                                kind: kind || 'video',
                                pageUrl: location.href,
                                title: document.title || location.hostname,
                                width: w || 0,
                                height: h || 0,
                                duration: (isFinite(dur) && dur > 0) ? Math.round(dur) : 0
                            }));
                        }
                    } catch (e) {}
                }

                function inspectMediaEl(el) {
                    if (!el) return;
                    var isAudio = el.tagName === 'AUDIO';
                    var k = isAudio ? 'audio' : 'video';
                    var w = el.videoWidth || 0;
                    var h = el.videoHeight || 0;
                    var d = (isFinite(el.duration) && el.duration > 0) ? el.duration : 0;
                    if (el.currentSrc) report(k, el.currentSrc, w, h, d);
                    if (el.src) report(k, el.src, w, h, d);
                    var sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
                    for (var i = 0; i < sources.length; i++) {
                        if (sources[i].src) report(k, sources[i].src, w, h, d);
                    }
                    if (!el.__drIdmHooked) {
                        el.__drIdmHooked = true;
                        ['play', 'playing', 'loadedmetadata', 'durationchange'].forEach(function(ev) {
                            el.addEventListener(ev, function() { inspectMediaEl(el); }, true);
                        });
                    }
                }

                function scanRoot(root) {
                    root = root || document;
                    if (!root.querySelectorAll) return;
                    var mediaList = root.querySelectorAll('video, audio');
                    for (var i = 0; i < mediaList.length; i++) inspectMediaEl(mediaList[i]);

                    var metas = document.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]');
                    for (var m = 0; m < metas.length; m++) {
                        var c = metas[m].getAttribute('content');
                        if (c && c.indexOf('http') === 0) report('video', c, 0, 0, 0);
                    }

                    var links = root.querySelectorAll('a[href]');
                    for (var k = 0; k < links.length && k < 250; k++) {
                        var href = links[k].getAttribute('href') || '';
                        var clean = href.split('?')[0].split('#')[0].toLowerCase();
                        if (/\.(mp4|m4v|webm|mkv|mov|3gp|m3u8|mp3|m4a|aac|ogg|opus|wav|flac)$/.test(clean)) {
                            var lk = /\.(mp3|m4a|aac|ogg|opus|wav|flac)$/.test(clean) ? 'audio' : 'video';
                            report(lk, href, 0, 0, 0);
                        }
                    }
                }

                function scanJsonText(txt) {
                    try {
                        if (!txt || typeof txt !== 'string' || txt.length > 4000000) return;
                        if (txt.indexOf('.mp4') < 0 && txt.indexOf('.m3u8') < 0 && txt.indexOf('.webm') < 0 && txt.indexOf('.mp3') < 0 && txt.indexOf('.m4a') < 0) return;
                        var unescaped = txt.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/\\u003d/g, '=').replace(/\\u0025/g, '%').replace(/&amp;/g, '&');
                        var re = /https?:\/\/[^"'\s<>\\]+?\.(mp4|m3u8|webm|mkv|mp3|m4a)(\?[^"'\s<>\\]*)?/gi;
                        var match, count = 0;
                        while ((match = re.exec(unescaped)) && count < 40) {
                            count++;
                            var u = match[0];
                            if (/\.(jpg|jpeg|png|webp|gif|ts)(\?|$)/i.test(u)) continue;
                            report(/\.(mp3|m4a)(\?|$)/i.test(u) ? 'audio' : 'video', u, 0, 0, 0);
                        }
                    } catch (e) {}
                }

                window.__drIdmRescan = function() {
                    scanRoot(document);
                    var scripts = document.querySelectorAll('script[type="application/json"], script[type="application/ld+json"]');
                    for (var s = 0; s < scripts.length; s++) scanJsonText(scripts[s].textContent);
                };

                if (!window.__drIdmInstalled) {
                    window.__drIdmInstalled = true;
                    if (window.fetch && !window.fetch.__drIdm) {
                        var origFetch = window.fetch;
                        window.fetch = function() {
                            var p = origFetch.apply(this, arguments);
                            try {
                                p.then(function(r) {
                                    var ct = (r.headers && r.headers.get('content-type')) || '';
                                    if (ct.indexOf('json') >= 0 || ct.indexOf('mpegurl') >= 0 || ct.indexOf('text') >= 0) {
                                        r.clone().text().then(scanJsonText).catch(function(){});
                                    }
                                }).catch(function(){});
                            } catch (e) {}
                            return p;
                        };
                        window.fetch.__drIdm = 1;
                    }
                    if (window.XMLHttpRequest && !XMLHttpRequest.prototype.send.__drIdm) {
                        var origSend = XMLHttpRequest.prototype.send;
                        XMLHttpRequest.prototype.send = function() {
                            try {
                                this.addEventListener('load', function() {
                                    try {
                                        if (this.responseType === '' || this.responseType === 'text') {
                                            scanJsonText(this.responseText);
                                        }
                                    } catch (e) {}
                                });
                            } catch (e) {}
                            return origSend.apply(this, arguments);
                        };
                        XMLHttpRequest.prototype.send.__drIdm = 1;
                    }
                    if (window.MutationObserver && document.documentElement) {
                        new MutationObserver(function(muts) {
                            for (var i = 0; i < muts.length; i++) {
                                var nodes = muts[i].addedNodes;
                                for (var j = 0; j < nodes.length; j++) {
                                    var n = nodes[j];
                                    if (n && n.nodeType === 1) {
                                        if (n.tagName === 'VIDEO' || n.tagName === 'AUDIO') inspectMediaEl(n);
                                        else if (n.querySelectorAll) scanRoot(n);
                                    }
                                }
                            }
                        }).observe(document.documentElement, { childList: true, subtree: true });
                    }
                }

                window.__drIdmRescan();
            } catch (err) {}
        })();
    """
}
