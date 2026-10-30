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
            rawPath.lowercase(Locale.US).contains("/hls/") && rawPath.lowercase(Locale.US).contains("playlist") ||
            cleanMime.contains("mpegurl") ||
            queryMime.contains("mpegurl") ||
            queryMap["format"]?.equals("m3u8", ignoreCase = true) == true

        val isAudio = !isHls && (
            ext in AUDIO_EXTS ||
                cleanMime.startsWith("audio/") ||
                queryMime.startsWith("audio/")
            )

        val isVideo = !isHls && !isAudio && (
            ext in VIDEO_EXTS ||
                cleanMime.startsWith("video/") ||
                queryMime.startsWith("video/") ||
                queryMap["format"]?.lowercase(Locale.US) in VIDEO_EXTS
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
     * Audited, lightweight IDM DOM + Performance Resource + Fetch/XHR + ShadowDOM/Iframe Media Sniffer.
     * Calling `window.__drIdmRescan(true)` clears stale suppression, inspects newly loaded SPA videos,
     * `performance.getEntriesByType('resource')`, hooked `fetch`/`XHR.open` request URLs, open shadowRoots,
     * and same-origin iframes, and BOTH notifies `DrIdmBridge` AND returns a JSON array synchronously.
     */
    const val IDM_PAGE_SNIFFER_JS = """
        (function(forceClear) {
            try {
                if (forceClear) {
                    window.__drIdmSeen = Object.create(null);
                }
                var seen = window.__drIdmSeen || (window.__drIdmSeen = Object.create(null));
                var netRing = window.__drIdmNetUrls || (window.__drIdmNetUrls = []);
                var batchOut = [];

                function pushNetUrl(u) {
                    try {
                        if (!u || typeof u !== 'string') return;
                        if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
                        var abs = new URL(u, document.baseURI).href;
                        if (netRing.length >= 160) netRing.shift();
                        netRing.push(abs);
                    } catch (e) {}
                }

                function looksLikeMediaUrl(u) {
                    if (!u || typeof u !== 'string') return false;
                    var low = u.toLowerCase();
                    if (/\.(ts|m4s|cmfv|cmfa|vtt|srt|jpg|jpeg|png|webp|gif|svg|css|js|woff|woff2)(\?|#|$)/.test(low)) return false;
                    if (/\.(mp4|m4v|webm|mkv|mov|3gp|m3u8|mp3|m4a|aac|ogg|opus|wav|flac)(\?|#|$)/.test(low)) return true;
                    if (low.indexOf('.m3u8') >= 0 || low.indexOf('mime=video') >= 0 || low.indexOf('mime=audio') >= 0 || low.indexOf('mime%3dvideo') >= 0 || low.indexOf('format=m3u8') >= 0 || low.indexOf('videoplayback') >= 0) return true;
                    return false;
                }

                function report(kind, rawUrl, w, h, dur, mimeHint) {
                    try {
                        if (!rawUrl || typeof rawUrl !== 'string') return;
                        if (rawUrl.indexOf('blob:') === 0 || rawUrl.indexOf('data:') === 0 || rawUrl.indexOf('javascript:') === 0) return;
                        var abs = new URL(rawUrl, document.baseURI).href;
                        var key = abs.split('#')[0];
                        var metaKey = key + '|' + (h || 0) + '|' + Math.round(dur || 0);
                        var item = {
                            url: abs,
                            kind: kind || 'video',
                            mime: mimeHint || '',
                            pageUrl: location.href,
                            title: document.title || location.hostname,
                            width: w || 0,
                            height: h || 0,
                            duration: (isFinite(dur) && dur > 0) ? Math.round(dur) : 0
                        };
                        batchOut.push(item);
                        if (seen[metaKey]) return;
                        seen[metaKey] = 1;
                        if (window.DrIdmBridge && window.DrIdmBridge.onMediaDetected) {
                            window.DrIdmBridge.onMediaDetected(JSON.stringify(item));
                        }
                    } catch (e) {}
                }

                function inspectMediaEl(el, docRef) {
                    if (!el) return;
                    var isAudio = el.tagName === 'AUDIO';
                    var k = isAudio ? 'audio' : 'video';
                    var w = el.videoWidth || 0;
                    var h = el.videoHeight || 0;
                    var d = (isFinite(el.duration) && el.duration > 0) ? el.duration : 0;
                    var defaultMime = isAudio ? 'audio/mp4' : 'video/mp4';
                    if (el.currentSrc && el.currentSrc.indexOf('blob:') !== 0) report(k, el.currentSrc, w, h, d, defaultMime);
                    if (el.src && el.src.indexOf('blob:') !== 0) report(k, el.src, w, h, d, defaultMime);
                    var attrs = ['data-src', 'data-video-src', 'data-mp4', 'data-hls', 'data-stream', 'data-url'];
                    for (var a = 0; a < attrs.length; a++) {
                        var av = el.getAttribute ? el.getAttribute(attrs[a]) : null;
                        if (av && av.indexOf('blob:') !== 0) report(k, av, w, h, d, defaultMime);
                    }
                    var sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
                    for (var i = 0; i < sources.length; i++) {
                        var sUrl = sources[i].src || (sources[i].getAttribute && sources[i].getAttribute('src'));
                        var sType = (sources[i].type || (sources[i].getAttribute && sources[i].getAttribute('type'))) || defaultMime;
                        if (sUrl && sUrl.indexOf('blob:') !== 0) report(k, sUrl, w, h, d, sType);
                    }
                    if (!el.__drIdmHooked) {
                        el.__drIdmHooked = true;
                        ['play', 'playing', 'loadedmetadata', 'loadeddata', 'durationchange'].forEach(function(ev) {
                            el.addEventListener(ev, function() { inspectMediaEl(el, docRef); }, true);
                        });
                    }
                }

                function scanRoot(root, depth) {
                    if (!root || (depth || 0) > 4 || !root.querySelectorAll) return;
                    var mediaList = root.querySelectorAll('video, audio');
                    for (var i = 0; i < mediaList.length; i++) inspectMediaEl(mediaList[i], root);

                    var standaloneSources = root.querySelectorAll('source[src]');
                    for (var si = 0; si < standaloneSources.length; si++) {
                        var su = standaloneSources[si].src || standaloneSources[si].getAttribute('src');
                        var st = standaloneSources[si].type || standaloneSources[si].getAttribute('type') || 'video/mp4';
                        if (su) report('video', su, 0, 0, 0, st);
                    }

                    var links = root.querySelectorAll('a[href], [data-video-url], [data-mp4], [data-hls]');
                    for (var k = 0; k < links.length && k < 300; k++) {
                        var cand = links[k].getAttribute('href') || links[k].getAttribute('data-video-url') || links[k].getAttribute('data-mp4') || links[k].getAttribute('data-hls') || '';
                        if (looksLikeMediaUrl(cand)) {
                            var lk = /\.(mp3|m4a|aac|ogg|opus|wav|flac)(\?|#|$)/i.test(cand) ? 'audio' : 'video';
                            report(lk, cand, 0, 0, 0, '');
                        }
                    }

                    // Pierce open Shadow DOM trees
                    var allEls = root.querySelectorAll('*');
                    for (var e = 0; e < allEls.length && e < 800; e++) {
                        if (allEls[e].shadowRoot) {
                            scanRoot(allEls[e].shadowRoot, (depth || 0) + 1);
                        }
                    }

                    // Pierce accessible same-origin iframes (video embeds!)
                    var iframes = root.querySelectorAll('iframe');
                    for (var f = 0; f < iframes.length && f < 15; f++) {
                        try {
                            var idoc = iframes[f].contentDocument || (iframes[f].contentWindow && iframes[f].contentWindow.document);
                            if (idoc) scanRoot(idoc, (depth || 0) + 1);
                        } catch (ie) {}
                    }
                }

                function scanPerformanceAndNetRing() {
                    try {
                        if (window.performance && window.performance.getEntriesByType) {
                            var entries = window.performance.getEntriesByType('resource') || [];
                            for (var i = 0; i < entries.length; i++) {
                                var en = entries[i];
                                var u = en && en.name;
                                if (!u) continue;
                                if (looksLikeMediaUrl(u) || en.initiatorType === 'video' || en.initiatorType === 'audio') {
                                    var isAud = en.initiatorType === 'audio' || /\.(mp3|m4a|aac|ogg|opus|wav|flac)(\?|#|$)/i.test(u);
                                    var mh = (en.initiatorType === 'video') ? 'video/mp4' : (en.initiatorType === 'audio' ? 'audio/mp4' : '');
                                    report(isAud ? 'audio' : 'video', u, 0, 0, 0, mh);
                                }
                            }
                        }
                    } catch (pe) {}
                    for (var r = 0; r < netRing.length; r++) {
                        var nu = netRing[r];
                        if (looksLikeMediaUrl(nu)) {
                            var na = /\.(mp3|m4a|aac|ogg|opus|wav|flac)(\?|#|$)/i.test(nu);
                            report(na ? 'audio' : 'video', nu, 0, 0, 0, '');
                        }
                    }
                }

                function scanJsonText(txt) {
                    try {
                        if (!txt || typeof txt !== 'string' || txt.length > 4000000) return;
                        if (txt.indexOf('.mp4') < 0 && txt.indexOf('.m3u8') < 0 && txt.indexOf('.webm') < 0 && txt.indexOf('.mp3') < 0 && txt.indexOf('.m4a') < 0 && txt.indexOf('contentUrl') < 0) return;
                        var unescaped = txt.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/\\u003d/g, '=').replace(/\\u0025/g, '%').replace(/&amp;/g, '&');
                        var re = /https?:\/\/[^"'\s<>\\]+?\.(mp4|m3u8|webm|mkv|mp3|m4a)(\?[^"'\s<>\\]*)?/gi;
                        var match, count = 0;
                        while ((match = re.exec(unescaped)) && count < 60) {
                            count++;
                            var u = match[0];
                            if (/\.(jpg|jpeg|png|webp|gif|ts)(\?|$)/i.test(u)) continue;
                            report(/\.(mp3|m4a)(\?|$)/i.test(u) ? 'audio' : 'video', u, 0, 0, 0, '');
                        }
                    } catch (e) {}
                }

                window.__drIdmRescan = function(clearSeenFlag) {
                    if (clearSeenFlag) {
                        window.__drIdmSeen = Object.create(null);
                        seen = window.__drIdmSeen;
                    }
                    batchOut = [];
                    scanRoot(document, 0);
                    var metas = document.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]');
                    for (var m = 0; m < metas.length; m++) {
                        var c = metas[m].getAttribute('content');
                        if (c && c.indexOf('http') === 0) report('video', c, 0, 0, 0, 'video/mp4');
                    }
                    var scripts = document.querySelectorAll('script[type="application/json"], script[type="application/ld+json"], script#__NEXT_DATA__');
                    for (var s = 0; s < scripts.length; s++) scanJsonText(scripts[s].textContent);
                    scanPerformanceAndNetRing();
                    return JSON.stringify(batchOut);
                };

                if (!window.__drIdmInstalled) {
                    window.__drIdmInstalled = true;
                    if (window.fetch && !window.fetch.__drIdm) {
                        var origFetch = window.fetch;
                        window.fetch = function(input) {
                            try {
                                var reqUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                                if (reqUrl) {
                                    pushNetUrl(reqUrl);
                                    if (looksLikeMediaUrl(reqUrl)) report('video', reqUrl, 0, 0, 0, '');
                                }
                            } catch (e) {}
                            var p = origFetch.apply(this, arguments);
                            try {
                                p.then(function(r) {
                                    if (r && r.url) {
                                        pushNetUrl(r.url);
                                        var ct = (r.headers && r.headers.get('content-type')) || '';
                                        if (ct.indexOf('video/') === 0 || ct.indexOf('audio/') === 0 || ct.indexOf('mpegurl') >= 0) {
                                            report(ct.indexOf('audio/') === 0 ? 'audio' : 'video', r.url, 0, 0, 0, ct);
                                        } else if (ct.indexOf('json') >= 0 || ct.indexOf('text') >= 0) {
                                            r.clone().text().then(scanJsonText).catch(function(){});
                                        }
                                    }
                                }).catch(function(){});
                            } catch (e) {}
                            return p;
                        };
                        window.fetch.__drIdm = 1;
                    }
                    if (window.XMLHttpRequest && !XMLHttpRequest.prototype.open.__drIdm) {
                        var origOpen = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, url) {
                            try {
                                if (url) {
                                    this.__drUrl = String(url);
                                    pushNetUrl(this.__drUrl);
                                    if (looksLikeMediaUrl(this.__drUrl)) report('video', this.__drUrl, 0, 0, 0, '');
                                }
                            } catch (e) {}
                            return origOpen.apply(this, arguments);
                        };
                        XMLHttpRequest.prototype.open.__drIdm = 1;

                        var origSend = XMLHttpRequest.prototype.send;
                        XMLHttpRequest.prototype.send = function() {
                            try {
                                this.addEventListener('load', function() {
                                    try {
                                        var rUrl = this.responseURL || this.__drUrl || '';
                                        var rCt = (this.getResponseHeader && this.getResponseHeader('content-type')) || '';
                                        if (rUrl && (rCt.indexOf('video/') === 0 || rCt.indexOf('audio/') === 0 || rCt.indexOf('mpegurl') >= 0)) {
                                            report(rCt.indexOf('audio/') === 0 ? 'audio' : 'video', rUrl, 0, 0, 0, rCt);
                                        } else if (this.responseType === '' || this.responseType === 'text') {
                                            scanJsonText(this.responseText);
                                        }
                                    } catch (e) {}
                                });
                            } catch (e) {}
                            return origSend.apply(this, arguments);
                        };
                    }
                    if (window.MutationObserver && document.documentElement) {
                        new MutationObserver(function(muts) {
                            for (var i = 0; i < muts.length; i++) {
                                var nodes = muts[i].addedNodes;
                                for (var j = 0; j < nodes.length; j++) {
                                    var n = nodes[j];
                                    if (n && n.nodeType === 1) {
                                        if (n.tagName === 'VIDEO' || n.tagName === 'AUDIO') inspectMediaEl(n, document);
                                        else if (n.querySelectorAll) scanRoot(n, 1);
                                    }
                                }
                            }
                        }).observe(document.documentElement, { childList: true, subtree: true });
                    }
                }

                return window.__drIdmRescan(Boolean(forceClear));
            } catch (err) {
                return "[]";
            }
        })(false);
    """

    const val IDM_FORCE_RESCAN_JS = """
        (function() {
            if (typeof window.__drIdmRescan === 'function') {
                return window.__drIdmRescan(true);
            }
            return "[]";
        })();
    """
}
