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
    val audioUrl: String = "",
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
    val isMasterVariant: Boolean = false,
    val detectedAt: Long = System.currentTimeMillis()
) {
    val isHls: Boolean get() = kind == SniffedMediaKind.HLS_STREAM
    val hasPairedAudio: Boolean get() = audioUrl.isNotBlank()

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
            kind == SniffedMediaKind.AUDIO -> "m4a"
            else -> "mp4"
        }
        return "${cleanTitle.replace(' ', '_')}${qSuffix}.$ext"
    }
}

/**
 * Rebuilt v8.0 Multi-Layer Primary Video & Stream Discovery Engine.
 * Combines:
 * 1. `DOCUMENT_START` main-frame + cross-origin `<iframe>` JS API hooks (`JSON.parse`, `fetch`,
 *    `XMLHttpRequest`, `#EXTM3U` magic-byte inspection, `HTMLMediaElement.src`, `JWPlayer`/`Video.js`),
 * 2. Passive `WebViewClient.shouldInterceptRequest` URL & header admission with strict MSE/HLS
 *    chunk suppression and CDN byte-range stripping,
 * 3. Master HLS playlist expansion with automatic `#EXT-X-MEDIA:TYPE=AUDIO` pairing and child
 *    sub-playlist deduplication so users ALWAYS get primary playable video links with sound!
 */
object VideoStreamSniffer {

    private val VIDEO_EXTS = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "3gp", "avi", "ogv")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val HLS_EXTS = setOf("m3u8", "m3u")

    // Strictly reject HLS/DASH micro-segments, subtitles, images, scripts, and tracker pixels
    private val IGNORED_SEGMENT_EXTS = setOf(
        "ts", "m2ts", "m4s", "m4f", "cmfv", "cmfa", "cmft", "mpd",
        "vtt", "srt", "ass", "ttml", "dfxp",
        "jpg", "jpeg", "png", "webp", "gif", "svg", "ico", "avif",
        "css", "js", "mjs", "map", "json", "xml", "txt", "html", "htm", "php",
        "woff", "woff2", "ttf", "otf", "wasm"
    )

    private val SEGMENT_PATH_REGEX = Regex(
        """(?:/(?:seg|segment|segments|frag|fragment|fragments|chunk|chunks)[/_-]\d+|[_/-](?:seg|chunk|frag|part)-?\d+\.(?:ts|m4s|mp4|aac)|/range/\d+-\d+|/sq/\d+)""",
        RegexOption.IGNORE_CASE
    )

    private val IGNORED_HOST_SUBSTRINGS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google",
        "imasdk.googleapis.com",
        "facebook.com/tr",
        "connect.facebook.net",
        "mc.yandex.ru",
        "clarity.ms",
        "hotjar.com",
        "sentry.io"
    )

    private val GENERIC_PLACEHOLDER_TITLES = setOf(
        "استریم ویدیو آنلاین",
        "ویدیوی صفحه",
        "فایل صوتی صفحه",
        "تب جدید"
    )

    private val tabMediaMap = ConcurrentHashMap<String, LinkedHashMap<String, DetectedMediaItem>>()
    private val tabChildSubPlaylists = ConcurrentHashMap<String, MutableSet<String>>()
    private val dismissedPageMap = ConcurrentHashMap<String, String>()

    private val _updatesFlow = MutableStateFlow(0L)
    val updatesFlow: StateFlow<Long> = _updatesFlow.asStateFlow()

    fun getMediaForTab(tabId: String): List<DetectedMediaItem> {
        val map = tabMediaMap[tabId] ?: return emptyList()
        return synchronized(map) {
            val all = map.values.toList()
            val hasSubstantialVideo = all.any {
                it.kind != SniffedMediaKind.AUDIO &&
                    (it.durationSec >= 8 || it.contentLengthBytes >= 600_000L || it.height >= 360 || it.isHls)
            }
            all.sortedWith(
                compareByDescending<DetectedMediaItem> { it.kind != SniffedMediaKind.AUDIO }
                    .thenByDescending { item ->
                        // Deprioritize tiny UI micro-animations (< 250 KB or < 4s) when a real video exists
                        if (!hasSubstantialVideo) true
                        else {
                            val tinySize = item.contentLengthBytes in 1L..250_000L
                            val tinyDur = item.durationSec in 1..3
                            !tinySize && !tinyDur
                        }
                    }
                    .thenByDescending { it.isMasterVariant }
                    .thenByDescending { it.isHls }
                    .thenByDescending { it.height }
                    .thenByDescending { it.contentLengthBytes }
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
        tabChildSubPlaylists.remove(tabId)
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
                tabChildSubPlaylists[tabId]?.clear()
                changed = true
            }
        }
        if (changed) {
            dismissedPageMap.remove(tabId)
            bumpUpdate()
        }
    }

    fun updatePageTitleForTab(tabId: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty() || clean.startsWith("http", ignoreCase = true) || clean in GENERIC_PLACEHOLDER_TITLES) {
            return
        }
        val map = tabMediaMap[tabId] ?: return
        var updated = false
        synchronized(map) {
            for (entry in map.entries) {
                val cur = entry.value
                if (cur.title in GENERIC_PLACEHOLDER_TITLES || cur.title.startsWith("video_")) {
                    entry.setValue(cur.copy(title = clean))
                    updated = true
                }
            }
        }
        if (updated) bumpUpdate()
    }

    fun updateProbedSize(tabId: String, itemId: String, contentLength: Long) {
        updateProbedMetadata(tabId = tabId, itemId = itemId, contentLength = contentLength)
    }

    fun updateProbedMetadata(
        tabId: String,
        itemId: String,
        contentLength: Long = -1L,
        durationSec: Int = 0,
        probedMime: String = "",
        fileNameHint: String = ""
    ) {
        val map = tabMediaMap[tabId] ?: return
        var updated = false
        synchronized(map) {
            val existing = map[itemId] ?: return
            val nextLen = if (contentLength > 0L) contentLength else existing.contentLengthBytes
            val nextDur = if (durationSec > 0) maxOf(existing.durationSec, durationSec) else existing.durationSec
            val nextTitle = if (fileNameHint.isNotBlank() && existing.title in GENERIC_PLACEHOLDER_TITLES) {
                fileNameHint.substringBeforeLast('.').take(64).ifBlank { existing.title }
            } else {
                existing.title
            }
            val cleanProbedMime = probedMime.substringBefore(';').trim().lowercase(Locale.US)
            val upgradedHls = cleanProbedMime.contains("mpegurl") && !existing.isHls
            val next = existing.copy(
                title = nextTitle,
                kind = if (upgradedHls) SniffedMediaKind.HLS_STREAM else existing.kind,
                mimeType = if (upgradedHls) "application/vnd.apple.mpegurl" else existing.mimeType,
                formatBadge = if (upgradedHls) "HLS" else existing.formatBadge,
                contentLengthBytes = nextLen,
                durationSec = nextDur
            )
            if (next != existing) {
                map[itemId] = next
                updated = true
            }
        }
        if (updated) bumpUpdate()
    }

    fun removeInvalidCandidate(tabId: String, itemId: String) {
        val map = tabMediaMap[tabId] ?: return
        val removed = synchronized(map) { map.remove(itemId) != null }
        if (removed) bumpUpdate()
    }

    /**
     * Replaces a raw HLS Master Playlist wrapper with its concrete quality variants (`1080p`, `720p`, ...),
     * pairs each video variant with its `#EXT-X-MEDIA:TYPE=AUDIO` separate audio rendition URL, and
     * suppresses raw child sub-playlists (`hls.js` variant/audio requests) so silent duplicates never appear!
     */
    fun replaceMasterWithResolvedVariants(
        tabId: String,
        masterItem: DetectedMediaItem,
        variants: List<HlsPlaylistParser.VariantStream>,
        audioRenditions: List<HlsPlaylistParser.AudioRendition>,
        probedDurationSec: Int = 0
    ): Boolean {
        if (variants.isEmpty()) return false
        val childSet = tabChildSubPlaylists.getOrPut(tabId) { ConcurrentHashMap.newKeySet() }
        for (v in variants) {
            childSet.add(normalizeDedupeUrl(v.url))
            if (!v.audioUrl.isNullOrBlank()) {
                childSet.add(normalizeDedupeUrl(v.audioUrl))
            }
        }
        for (a in audioRenditions) {
            if (!a.url.isNullOrBlank()) {
                childSet.add(normalizeDedupeUrl(a.url))
            }
        }

        // Deduplicate variants by resolution height (keeping highest-bandwidth stream per resolution)
        val selectedVariants = ArrayList<HlsPlaylistParser.VariantStream>()
        val seenHeights = HashSet<Int>()
        for (v in variants) {
            if (v.isAudioOnly) continue
            if (v.height > 0) {
                if (seenHeights.add(v.height)) selectedVariants.add(v)
            } else if (selectedVariants.size < 5) {
                selectedVariants.add(v)
            }
            if (selectedVariants.size >= 6) break
        }
        if (selectedVariants.isEmpty()) {
            selectedVariants.addAll(variants.take(4))
        }

        val effectiveDuration = maxOf(masterItem.durationSec, probedDurationSec)
        val map = tabMediaMap.getOrPut(tabId) { LinkedHashMap() }
        synchronized(map) {
            // Remove the generic master wrapper now that concrete playable variants are available
            map.remove(masterItem.id)

            // Remove any raw un-paired child audio/video sub-playlists caught earlier from hls.js
            val iter = map.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key in childSet && !entry.value.isMasterVariant) {
                    iter.remove()
                }
            }

            for (v in selectedVariants) {
                val id = normalizeDedupeUrl(v.url)
                val pairedAudio = v.audioUrl.orEmpty()
                val estBytes = if (v.bandwidth > 0L && effectiveDuration > 0) {
                    (v.bandwidth * effectiveDuration) / 8L
                } else {
                    -1L
                }
                val qBadge = v.qualityBadge()
                val qLabel = when {
                    v.isAudioOnly -> "فقط صدا (HLS)"
                    pairedAudio.isNotBlank() -> "$qBadge • با صدا"
                    else -> "$qBadge استریم"
                }
                val item = DetectedMediaItem(
                    id = id,
                    tabId = tabId,
                    url = v.url,
                    audioUrl = pairedAudio,
                    kind = if (v.isAudioOnly) SniffedMediaKind.AUDIO else SniffedMediaKind.HLS_STREAM,
                    title = masterItem.title,
                    pageUrl = masterItem.pageUrl,
                    mimeType = "application/vnd.apple.mpegurl",
                    formatBadge = if (v.isAudioOnly) "M4A" else "HLS",
                    qualityLabel = qLabel,
                    width = v.width,
                    height = v.height,
                    durationSec = effectiveDuration,
                    contentLengthBytes = estBytes,
                    isMasterVariant = true
                )
                map[id] = item
            }
        }
        bumpUpdate()
        return true
    }

    fun recordCandidate(
        tabId: String,
        rawUrl: String,
        pageUrl: String = "",
        pageTitle: String = "",
        mimeHint: String = "",
        width: Int = 0,
        height: Int = 0,
        durationSec: Int = 0,
        audioUrl: String = "",
        contentDisposition: String = "",
        qualityHint: String = ""
    ): DetectedMediaItem? {
        val classified = classifyCandidate(
            tabId = tabId,
            rawUrl = rawUrl,
            pageUrl = pageUrl,
            pageTitle = pageTitle,
            mimeHint = mimeHint,
            width = width,
            height = height,
            durationSec = durationSec,
            audioUrl = audioUrl,
            contentDisposition = contentDisposition,
            qualityHint = qualityHint
        ) ?: return null

        // Suppress raw hls.js child sub-playlists when their parent Master Playlist was already expanded
        val knownChildren = tabChildSubPlaylists[tabId]
        if (knownChildren != null && classified.id in knownChildren && classified.audioUrl.isBlank()) {
            val existingInMap = tabMediaMap[tabId]?.let { synchronized(it) { it[classified.id] } }
            if (existingInMap == null || existingInMap.isMasterVariant) {
                return existingInMap
            }
        }

        val map = tabMediaMap.getOrPut(tabId) { LinkedHashMap() }
        synchronized(map) {
            val existing = map[classified.id]
            if (existing != null) {
                val merged = existing.copy(
                    audioUrl = existing.audioUrl.ifBlank { classified.audioUrl },
                    title = if (classified.title.isNotBlank() &&
                        classified.title !in GENERIC_PLACEHOLDER_TITLES &&
                        (existing.title.isBlank() || existing.title in GENERIC_PLACEHOLDER_TITLES || existing.title.contains('.'))
                    ) classified.title else existing.title,
                    pageUrl = existing.pageUrl.ifBlank { classified.pageUrl },
                    width = maxOf(existing.width, classified.width),
                    height = maxOf(existing.height, classified.height),
                    durationSec = maxOf(existing.durationSec, classified.durationSec),
                    qualityLabel = if (existing.isMasterVariant) existing.qualityLabel else classified.qualityLabel.ifBlank { existing.qualityLabel }
                )
                if (merged != existing) {
                    map[classified.id] = merged
                    bumpUpdate()
                }
                return merged
            }
            if (map.size >= 45) {
                val oldestKey = map.keys.firstOrNull { map[it]?.isMasterVariant == false }
                    ?: map.keys.firstOrNull()
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
        durationSec: Int = 0,
        audioUrl: String = "",
        contentDisposition: String = "",
        qualityHint: String = ""
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
        val fullLower = trimmed.lowercase(Locale.US)
        if (IGNORED_HOST_SUBSTRINGS.any { fullLower.contains(it) }) return null

        val rawPath = uri.rawPath.orEmpty()
        val lowerPath = rawPath.lowercase(Locale.US)
        val lastSeg = rawPath.substringAfterLast('/')
        val ext = if ('.' in lastSeg) lastSeg.substringAfterLast('.').lowercase(Locale.US) else ""
        if (ext in IGNORED_SEGMENT_EXTS) return null

        // Reject HLS/DASH media segment chunks disguised with query strings or path patterns
        if (lowerPath.endsWith(".ts") ||
            lowerPath.endsWith(".m4s") ||
            lowerPath.endsWith(".cmfv") ||
            lowerPath.endsWith(".cmfa") ||
            SEGMENT_PATH_REGEX.containsMatchIn(lowerPath)
        ) {
            return null
        }

        val cleanMime = mimeHint.substringBefore(';').trim().lowercase(Locale.US)
        if (cleanMime == "video/mp2t" || cleanMime == "video/iso.segment" || cleanMime == "audio/mp4a-latm") {
            return null
        }

        val queryMap = parseQueryParams(uri.rawQuery.orEmpty())

        // 1. Progressive googlevideo streams (only keep muxed video+audio itag 18/22 or clean audio 140)
        if (host.endsWith("googlevideo.com") && lowerPath.contains("videoplayback")) {
            val itag = queryMap["itag"]?.toIntOrNull()
            val qMime = runCatching { URLDecoder.decode(queryMap["mime"].orEmpty(), "UTF-8") }
                .getOrDefault(queryMap["mime"].orEmpty())
                .lowercase(Locale.US)
            val isMuxedMp4 = itag == 18 || itag == 22 || itag == 37
            val isCleanAudio = itag == 140 || (itag == null && qMime.startsWith("audio/mp4"))
            if (!isMuxedMp4 && !isCleanAudio) return null

            val cleanUrl = stripQueryKeys(trimmed, setOf("range", "rn", "rbuf", "ump", "srfvp"))
            val resHeight = when (itag) {
                37 -> 1080
                22 -> 720
                18 -> 360
                else -> 0
            }
            val qLabel = when (itag) {
                37 -> "1080p Full HD"
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
                title = bestTitle(pageTitle, cleanUrl, kind, contentDisposition),
                pageUrl = pageUrl,
                mimeType = if (isCleanAudio) "audio/mp4" else "video/mp4",
                formatBadge = badge,
                qualityLabel = qLabel,
                width = 0,
                height = resHeight,
                durationSec = durationSec
            )
        }

        // 2. Strip byte-slice range query parameters from progressive CDN URLs so full video downloads!
        var normalizedDownloadUrl = trimmed
        if (queryMap.containsKey("bytestart") || queryMap.containsKey("byteend")) {
            normalizedDownloadUrl = stripQueryKeys(trimmed, setOf("bytestart", "byteend"))
        }

        val cdExt = extractExtensionFromContentDisposition(contentDisposition)
        val effectiveExt = ext.ifEmpty { cdExt }

        val queryMime = (queryMap["mime"] ?: queryMap["type"] ?: queryMap["content_type"]).orEmpty()
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .lowercase(Locale.US)
        val queryFormat = queryMap["format"]?.lowercase(Locale.US).orEmpty()

        val isHls = effectiveExt in HLS_EXTS ||
            lowerPath.contains(".m3u8") ||
            (lowerPath.contains("/hls/") && (lowerPath.contains("playlist") || lowerPath.contains("master") || lowerPath.contains("manifest"))) ||
            cleanMime.contains("mpegurl") ||
            queryMime.contains("mpegurl") ||
            queryFormat == "m3u8" ||
            queryFormat == "hls"

        val isAudio = !isHls && (
            effectiveExt in AUDIO_EXTS ||
                cleanMime.startsWith("audio/") ||
                queryMime.startsWith("audio/") ||
                queryFormat in AUDIO_EXTS
            )

        val isVideo = !isHls && !isAudio && (
            effectiveExt in VIDEO_EXTS ||
                cleanMime.startsWith("video/") ||
                queryMime.startsWith("video/") ||
                queryFormat in VIDEO_EXTS
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
            effectiveExt in VIDEO_EXTS || effectiveExt in AUDIO_EXTS -> effectiveExt.uppercase(Locale.US)
            cleanMime == "video/webm" -> "WEBM"
            cleanMime == "video/x-matroska" -> "MKV"
            cleanMime.startsWith("audio/mp4") -> "M4A"
            cleanMime.startsWith("audio/") -> "MP3"
            else -> "MP4"
        }

        val resolvedMime = when {
            isHls -> "application/vnd.apple.mpegurl"
            cleanMime.startsWith("video/") && cleanMime != "video/*" -> cleanMime
            cleanMime.startsWith("audio/") && cleanMime != "audio/*" -> cleanMime
            effectiveExt == "webm" -> "video/webm"
            effectiveExt == "mkv" -> "video/x-matroska"
            effectiveExt == "mp3" -> "audio/mpeg"
            effectiveExt == "m4a" || effectiveExt == "aac" -> "audio/mp4"
            isAudio -> "audio/mpeg"
            else -> "video/mp4"
        }

        val hintHeight = parseHeightFromQualityHint(qualityHint)
        val inferredHeight = when {
            height > 0 -> height
            hintHeight > 0 -> hintHeight
            else -> inferResolutionFromUrl(normalizedDownloadUrl)
        }

        val qLabel = when {
            isHls && inferredHeight > 0 && audioUrl.isNotBlank() -> "${inferredHeight}p • با صدا"
            isHls && inferredHeight > 0 -> "${inferredHeight}p استریم"
            isHls -> "چند کیفیتی (HLS)"
            inferredHeight >= 2160 -> "${inferredHeight}p 4K"
            inferredHeight >= 1080 -> "${inferredHeight}p Full HD"
            inferredHeight >= 720 -> "${inferredHeight}p HD"
            inferredHeight > 0 -> "${inferredHeight}p"
            qualityHint.isNotBlank() -> qualityHint.take(18)
            isAudio -> "فایل صوتی"
            else -> ""
        }

        val dedupeKey = normalizeDedupeUrl(normalizedDownloadUrl)
        return DetectedMediaItem(
            id = dedupeKey,
            tabId = tabId,
            url = normalizedDownloadUrl,
            audioUrl = audioUrl.trim(),
            kind = kind,
            title = bestTitle(pageTitle, normalizedDownloadUrl, kind, contentDisposition),
            pageUrl = pageUrl,
            mimeType = resolvedMime,
            formatBadge = badge,
            qualityLabel = qLabel,
            width = width,
            height = inferredHeight,
            durationSec = durationSec
        )
    }

    private fun extractExtensionFromContentDisposition(cd: String): String {
        if (cd.isBlank()) return ""
        val m = Regex("""filename\*?\s*=\s*(?:UTF-8''|utf-8'')?["']?([^"';\r\n]+)["']?""", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (m.isEmpty() || !m.contains('.')) return ""
        return m.substringAfterLast('.').lowercase(Locale.US)
    }

    private fun parseHeightFromQualityHint(hint: String): Int {
        if (hint.isBlank()) return 0
        if (hint.equals("4k", true) || hint.equals("uhd", true)) return 2160
        if (hint.equals("2k", true) || hint.equals("qhd", true)) return 1440
        if (hint.equals("fhd", true) || hint.equals("fullhd", true)) return 1080
        val m = Regex("""(2160|1440|1080|720|576|540|480|360|240|144)""").find(hint)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun inferResolutionFromUrl(url: String): Int {
        val m = Regex("""(?:^|[/_.,=-])(2160|1440|1080|720|576|480|360|240)p?(?:[/_?.,&-]|$)""", RegexOption.IGNORE_CASE)
            .find(url)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun bestTitle(
        pageTitle: String,
        url: String,
        kind: SniffedMediaKind,
        contentDisposition: String = ""
    ): String {
        if (contentDisposition.isNotBlank()) {
            val cdName = Regex("""filename\*?\s*=\s*(?:UTF-8''|utf-8'')?["']?([^"';\r\n]+)["']?""", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.getOrNull(1)
                ?.let { runCatching { URLDecoder.decode(it.trim(), "UTF-8") }.getOrDefault(it.trim()) }
                ?.substringBeforeLast('.')
                ?.trim()
                .orEmpty()
            if (cdName.length in 3..80) return cdName
        }

        val cleanPageTitle = pageTitle.trim()
        if (cleanPageTitle.isNotEmpty() &&
            !cleanPageTitle.startsWith("http", ignoreCase = true) &&
            cleanPageTitle !in GENERIC_PLACEHOLDER_TITLES
        ) {
            return cleanPageTitle
        }
        val pathName = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .substringBeforeLast('.')
            .trim()
        val genericNames = setOf("master", "index", "playlist", "manifest", "prog", "stream", "video", "play", "videoplayback")
        if (pathName.length in 3..70 && pathName.lowercase(Locale.US) !in genericNames) {
            return pathName
        }
        return when (kind) {
            SniffedMediaKind.HLS_STREAM -> "استریم ویدیو آنلاین"
            SniffedMediaKind.AUDIO -> "فایل صوتی صفحه"
            SniffedMediaKind.VIDEO -> "ویدیوی صفحه"
        }
    }

    internal fun normalizeDedupeUrl(url: String): String {
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
     * Rebuilt v8.0 Deep Video Sniffer Script (Main Frame + Cross-Origin Iframe `DOCUMENT_START` Ready!):
     * - Hooks `JSON.parse` (cat-catch style) to extract primary video/HLS URLs + quality labels from
     *   decrypted player configs (`sources`, `file`, `hls`, `video_versions`, `playbackUrl`, `manifest`)
     * - Hooks `fetch` & `XMLHttpRequest` to inspect response `Content-Type`, `Content-Disposition`,
     *   AND `#EXTM3U` magic header on extensionless manifest URLs while filtering out partial `Range` chunks
     * - Hooks `HTMLMediaElement.prototype.src` setter, `play()`, and `setAttribute`
     * - Inspects `JWPlayer`, `Video.js`, `<video>`, `<audio>`, `<source>`, `og:video`, `ld+json`,
     *   `__NEXT_DATA__`, Shadow DOMs, same-origin iframes, and cross-origin iframe `postMessage` rescan!
     */
    const val IDM_PAGE_SNIFFER_JS = """
        (function(forceClear) {
            try {
                if (forceClear) {
                    window.__drIdmSeen = Object.create(null);
                }
                var seen = window.__drIdmSeen || (window.__drIdmSeen = Object.create(null));
                var netRing = window.__drIdmNetItems || (window.__drIdmNetItems = []);
                var batchOut = [];

                var MEDIA_EXT_RE = /\.(mp4|m4v|webm|mkv|mov|3gp|flv|m3u8|m3u|mp3|m4a|aac|ogg|opus|wav|flac)(\?|#|$)/i;
                var IGNORE_EXT_RE = /\.(ts|m2ts|m4s|m4f|cmfv|cmfa|mpd|vtt|srt|jpg|jpeg|png|webp|gif|svg|ico|css|js|mjs|json|xml|html|htm|php|woff|woff2|ttf|wasm)(\?|#|$)/i;
                var SEG_PATH_RE = /\/(?:seg|segment|segments|frag|fragments|chunk|chunks)[\/_-]\d+|[_\/-](?:seg|chunk|frag)-?\d+\.(?:ts|m4s|mp4|aac)|\/range\/\d+-\d+/i;
                var STRICT_VIDEO_KEYS = /^(hls|hlsurl|hls_url|m3u8|m3u8_url|mp4|mp4_url|video_url|videourl|playbackurl|playback_url|stream_url|streamurl|progressive_url|contenturl)$/i;
                var GENERIC_SRC_KEYS = /^(file|src|url)$/i;
                var QUALITY_LABEL_RE = /^(2160|1440|1080|720|576|540|480|360|240)p?$/i;
                var internalBusy = false;

                function isPartialRangeHeader(val) {
                    if (!val) return false;
                    var s = String(val).toLowerCase().replace(/\s+/g, '');
                    if (s.indexOf('bytes=') !== 0) return false;
                    return s !== 'bytes=0-' && s !== 'bytes=0-0';
                }

                function isServerMediaMime(ct) {
                    if (!ct) return false;
                    var low = String(ct).split(';')[0].trim().toLowerCase();
                    if (low === 'video/mp2t' || low === 'video/iso.segment') return false;
                    return low.indexOf('video/') === 0 ||
                        low.indexOf('audio/') === 0 ||
                        low.indexOf('mpegurl') >= 0;
                }

                function hasMediaFilenameInCd(cd) {
                    if (!cd || typeof cd !== 'string') return false;
                    return /filename\*?\s*=.*?\.(mp4|m4v|webm|mkv|mov|m3u8|mp3|m4a|aac|ogg|opus|wav|flac)/i.test(cd);
                }

                function looksLikeMediaUrl(u) {
                    if (!u || typeof u !== 'string' || u.length < 10 || u.length > 8192) return false;
                    if (IGNORE_EXT_RE.test(u) || SEG_PATH_RE.test(u)) return false;
                    if (MEDIA_EXT_RE.test(u)) return true;
                    var low = u.toLowerCase();
                    if (low.indexOf('.m3u8') >= 0 ||
                        low.indexOf('mime=video') >= 0 ||
                        low.indexOf('mime=audio') >= 0 ||
                        low.indexOf('mime%3dvideo') >= 0 ||
                        low.indexOf('format=m3u8') >= 0 ||
                        low.indexOf('videoplayback') >= 0) {
                        return true;
                    }
                    return false;
                }

                function rememberNetItem(obj) {
                    if (netRing.length >= 120) netRing.shift();
                    netRing.push(obj);
                }

                function bestDocTitle() {
                    try {
                        var ogT = document.querySelector && document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
                        if (ogT) {
                            var tc = (ogT.getAttribute('content') || '').trim();
                            if (tc.length >= 3) return tc;
                        }
                    } catch (e) {}
                    return (document && document.title) || (location && location.hostname) || '';
                }

                function report(kind, rawUrl, w, h, dur, mimeHint, cdHint, qualityHint, titleHint) {
                    try {
                        if (!rawUrl || typeof rawUrl !== 'string') return;
                        var trimmed = rawUrl.trim();
                        if (!trimmed || trimmed.indexOf('blob:') === 0 || trimmed.indexOf('data:') === 0 || trimmed.indexOf('javascript:') === 0) return;
                        if ( trimmed.indexOf('//') === 0 ) trimmed = (location.protocol || 'https:') + trimmed;
                        var abs = new URL(trimmed, (document && document.baseURI) || location.href).href;
                        if (abs.indexOf('http') !== 0) return;
                        if (IGNORE_EXT_RE.test(abs) || SEG_PATH_RE.test(abs)) return;

                        var cleanMime = (mimeHint || '').split(';')[0].trim();
                        if (!isServerMediaMime(cleanMime) && !hasMediaFilenameInCd(cdHint) && !looksLikeMediaUrl(abs)) {
                            return;
                        }

                        var key = abs.split('#')[0];
                        var metaKey = key + '|' + (h || 0) + '|' + Math.round(dur || 0) + '|' + cleanMime;
                        var item = {
                            url: abs,
                            kind: kind || (cleanMime.indexOf('audio/') === 0 ? 'audio' : 'video'),
                            mime: cleanMime,
                            cd: cdHint || '',
                            quality: qualityHint || '',
                            pageUrl: (function() { try { return window.top.location.href; } catch(e) { return document.referrer || location.href; } })(),
                            title: titleHint || bestDocTitle(),
                            width: w || 0,
                            height: h || 0,
                            duration: (isFinite(dur) && dur > 0) ? Math.round(dur) : 0
                        };
                        batchOut.push(item);
                        rememberNetItem(item);
                        if (seen[metaKey]) return;
                        seen[metaKey] = 1;
                        if (window.DrIdmBridge && window.DrIdmBridge.onMediaDetected) {
                            internalBusy = true;
                            try {
                                window.DrIdmBridge.onMediaDetected(JSON.stringify(item));
                            } finally {
                                internalBusy = false;
                            }
                        }
                    } catch (e) { internalBusy = false; }
                }

                function walkParsedJson(node, depth, budget, inheritedTitle, inheritedDur) {
                    if (!node || depth > 8 || budget.count > 350) return;
                    budget.count++;
                    if (typeof node === 'string') {
                        if (node.length >= 12 && node.length <= 4096) {
                            if (node.trim().indexOf('#EXTM3U') === 0) return;
                            if (looksLikeMediaUrl(node)) {
                                report('video', node, 0, 0, inheritedDur || 0, '', '', '', inheritedTitle || '');
                            }
                        }
                        return;
                    }
                    if (typeof node !== 'object') return;
                    if (Array.isArray(node)) {
                        for (var i = 0; i < node.length && i < 50; i++) {
                            walkParsedJson(node[i], depth + 1, budget, inheritedTitle, inheritedDur);
                        }
                        return;
                    }

                    var localTitle = (typeof node.title === 'string' && node.title.length >= 2) ? node.title : (inheritedTitle || '');
                    var w = (typeof node.width === 'number') ? node.width : 0;
                    var h = (typeof node.height === 'number') ? node.height : 0;
                    var dur = (typeof node.duration === 'number' && node.duration > 0) ? node.duration : (inheritedDur || 0);
                    var qLabel = '';
                    if (typeof node.label === 'string') qLabel = node.label;
                    else if (typeof node.quality === 'string' || typeof node.quality === 'number') qLabel = String(node.quality);
                    var mType = (typeof node.type === 'string' && (node.type.indexOf('/') > 0 || node.type === 'hls')) ? node.type : ((typeof node.mime_type === 'string') ? node.mime_type : '');
                    if (mType === 'hls') mType = 'application/vnd.apple.mpegurl';
                    var hasStrongPlayerContext = isServerMediaMime(mType) || QUALITY_LABEL_RE.test(qLabel) || (w >= 160 && h >= 120 && (typeof node.bitrate === 'number' || typeof node.fps === 'number'));

                    var keys = Object.keys(node);
                    for (var k = 0; k < keys.length && k < 60; k++) {
                        var prop = keys[k];
                        var val = node[prop];
                        if (typeof val === 'string' && val.length >= 10 && val.length <= 4096) {
                            var unesc = val.replace(/\\\//g, '/');
                            var isHttpOrProto = unesc.indexOf('http://') === 0 || unesc.indexOf('https://') === 0 || unesc.indexOf('//') === 0;
                            if (looksLikeMediaUrl(unesc)) {
                                report('video', unesc, w, h, dur, mType, '', qLabel, localTitle);
                            } else if (isHttpOrProto && STRICT_VIDEO_KEYS.test(prop)) {
                                var inferredMime = mType || (/hls|m3u8/i.test(prop) ? 'application/vnd.apple.mpegurl' : 'video/mp4');
                                report('video', unesc, w, h, dur, inferredMime, '', qLabel, localTitle);
                            } else if (isHttpOrProto && GENERIC_SRC_KEYS.test(prop) && hasStrongPlayerContext) {
                                report('video', unesc, w, h, dur, mType || 'video/mp4', '', qLabel, localTitle);
                            }
                        } else if (val && typeof val === 'object') {
                            walkParsedJson(val, depth + 1, budget, localTitle, dur);
                        }
                    }
                }

                function inspectResponseBodyText(sourceUrl, text) {
                    try {
                        if (!text || typeof text !== 'string' || text.length > 3500000) return;
                        var lead = text.slice(0, 64).trim();
                        if (lead.indexOf('#EXTM3U') === 0 && sourceUrl) {
                            report('video', sourceUrl, 0, 0, 0, 'application/vnd.apple.mpegurl', '', '', '');
                            return;
                        }
                        if (text.indexOf('.mp4') < 0 &&
                            text.indexOf('.m3u8') < 0 &&
                            text.indexOf('.webm') < 0 &&
                            text.indexOf('.mkv') < 0 &&
                            text.indexOf('.mp3') < 0 &&
                            text.indexOf('.m4a') < 0 &&
                            text.indexOf('video_versions') < 0 &&
                            text.indexOf('contentUrl') < 0 &&
                            text.indexOf('playbackUrl') < 0) {
                            return;
                        }
                        var unescaped = text
                            .replace(/\\\//g, '/')
                            .replace(/\\u([0-9a-fA-F]{4})/g, function(_, hex) { return String.fromCharCode(parseInt(hex, 16)); })
                            .replace(/&amp;/g, '&');
                        var re = /https?:\/\/[^"'\s<>\\]+?\.(mp4|m4v|m3u8|webm|mkv|mov|mp3|m4a)(\?[^"'\s<>\\]*)?/gi;
                        var match, count = 0;
                        while ((match = re.exec(unescaped)) && count < 65) {
                            count++;
                            var u = match[0];
                            if (IGNORE_EXT_RE.test(u) || SEG_PATH_RE.test(u)) continue;
                            report(/\.(mp3|m4a)(\?|$)/i.test(u) ? 'audio' : 'video', u, 0, 0, 0, '', '', '', '');
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
                    var defaultMime = isAudio ? 'audio/mp4' : 'video/mp4';
                    if (el.currentSrc && el.currentSrc.indexOf('blob:') !== 0) report(k, el.currentSrc, w, h, d, defaultMime, '', '', '');
                    if (el.src && el.src.indexOf('blob:') !== 0) report(k, el.src, w, h, d, defaultMime, '', '', '');
                    var attrs = ['data-src', 'data-video-src', 'data-mp4', 'data-hls', 'data-stream', 'data-url', 'data-setup'];
                    for (var a = 0; a < attrs.length; a++) {
                        var av = el.getAttribute ? el.getAttribute(attrs[a]) : null;
                        if (!av) continue;
                        if (av.indexOf('{') === 0) {
                            inspectResponseBodyText('', av);
                        } else if (av.indexOf('blob:') !== 0) {
                            report(k, av, w, h, d, defaultMime, '', '', '');
                        }
                    }
                    var sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
                    for (var i = 0; i < sources.length; i++) {
                        var sUrl = sources[i].src || (sources[i].getAttribute && sources[i].getAttribute('src'));
                        var sType = (sources[i].type || (sources[i].getAttribute && sources[i].getAttribute('type'))) || defaultMime;
                        var sLabel = (sources[i].getAttribute && (sources[i].getAttribute('label') || sources[i].getAttribute('size') || sources[i].getAttribute('res'))) || '';
                        if (sUrl && sUrl.indexOf('blob:') !== 0) report(k, sUrl, w, h, d, sType, '', sLabel, '');
                    }
                    if (!el.__drIdmHooked) {
                        el.__drIdmHooked = true;
                        ['play', 'playing', 'loadedmetadata', 'loadeddata', 'durationchange'].forEach(function(ev) {
                            el.addEventListener(ev, function() { inspectMediaEl(el); }, true);
                        });
                    }
                }

                function scanKnownPlayers() {
                    try {
                        if (typeof window.jwplayer === 'function') {
                            for (var p = 0; p < 4; p++) {
                                var jw = window.jwplayer(p);
                                if (!jw || typeof jw.getPlaylist !== 'function') break;
                                var pl = jw.getPlaylist();
                                if (pl) walkParsedJson(pl, 0, { count: 0 }, '');
                            }
                        }
                    } catch (e) {}
                    try {
                        if (window.videojs && window.videojs.players) {
                            var vPlayers = window.videojs.players;
                            var pKeys = Object.keys(vPlayers);
                            for (var vi = 0; vi < pKeys.length; vi++) {
                                var vp = vPlayers[pKeys[vi]];
                                if (vp && typeof vp.currentSources === 'function') {
                                    walkParsedJson(vp.currentSources(), 0, { count: 0 }, '');
                                } else if (vp && typeof vp.currentSrc === 'function') {
                                    var vs = vp.currentSrc();
                                    if (vs && vs.indexOf('blob:') !== 0) report('video', vs, 0, 0, 0, 'video/mp4', '', '', '');
                                }
                            }
                        }
                    } catch (e) {}
                }

                function scanRoot(root, depth) {
                    if (!root || (depth || 0) > 4 || !root.querySelectorAll) return;
                    var mediaList = root.querySelectorAll('video, audio');
                    for (var i = 0; i < mediaList.length; i++) inspectMediaEl(mediaList[i]);

                    var standaloneSources = root.querySelectorAll('source[src], source[data-src]');
                    for (var si = 0; si < standaloneSources.length; si++) {
                        var su = standaloneSources[si].src || standaloneSources[si].getAttribute('src') || standaloneSources[si].getAttribute('data-src');
                        var st = standaloneSources[si].type || standaloneSources[si].getAttribute('type') || '';
                        if (su && (looksLikeMediaUrl(su) || isServerMediaMime(st))) report('video', su, 0, 0, 0, st || 'video/mp4', '', '', '');
                    }

                    var links = root.querySelectorAll('a[href], [data-video-url], [data-mp4], [data-hls], [data-stream-url]');
                    for (var k = 0; k < links.length && k < 350; k++) {
                        var cand = links[k].getAttribute('href') || links[k].getAttribute('data-video-url') || links[k].getAttribute('data-mp4') || links[k].getAttribute('data-hls') || links[k].getAttribute('data-stream-url') || '';
                        if (looksLikeMediaUrl(cand)) {
                            var lk = /\.(mp3|m4a|aac|ogg|opus|wav|flac)(\?|#|$)/i.test(cand) ? 'audio' : 'video';
                            report(lk, cand, 0, 0, 0, '', '', '', '');
                        }
                    }

                    var allEls = root.querySelectorAll('*');
                    for (var e = 0; e < allEls.length && e < 800; e++) {
                        if (allEls[e].shadowRoot) {
                            scanRoot(allEls[e].shadowRoot, (depth || 0) + 1);
                        }
                    }

                    var iframes = root.querySelectorAll('iframe');
                    for (var f = 0; f < iframes.length && f < 16; f++) {
                        try {
                            var idoc = iframes[f].contentDocument || (iframes[f].contentWindow && iframes[f].contentWindow.document);
                            if (idoc) scanRoot(idoc, (depth || 0) + 1);
                        } catch (ie) {}
                        try {
                            if (iframes[f].contentWindow) {
                                iframes[f].contentWindow.postMessage({ __drIdmRescanCmd: 1 }, '*');
                            }
                        } catch (pe) {}
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
                                    report(isAud ? 'audio' : 'video', u, 0, 0, 0, mh, '', '', '');
                                }
                            }
                        }
                    } catch (pe) {}
                    var snapshot = netRing.slice();
                    for (var r = 0; r < snapshot.length; r++) {
                        var ni = snapshot[r];
                        if (ni && ni.url) {
                            report(ni.kind, ni.url, ni.width, ni.height, ni.duration, ni.mime, ni.cd, ni.quality, ni.title);
                        }
                    }
                }

                window.__drIdmRescan = function(clearSeenFlag) {
                    if (clearSeenFlag) {
                        window.__drIdmSeen = Object.create(null);
                        seen = window.__drIdmSeen;
                    }
                    batchOut = [];
                    if (typeof document !== 'undefined' && document) {
                        scanRoot(document, 0);
                        scanKnownPlayers();
                        if (document.querySelectorAll) {
                            var metas = document.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]');
                            for (var m = 0; m < metas.length; m++) {
                                var c = metas[m].getAttribute('content');
                                if (c && (c.indexOf('http') === 0 || c.indexOf('//') === 0)) report('video', c, 0, 0, 0, 'video/mp4', '', '', '');
                            }
                            var scripts = document.querySelectorAll('script[type="application/json"], script[type="application/ld+json"], script#__NEXT_DATA__, script#__NUXT_DATA__');
                            for (var s = 0; s < scripts.length; s++) inspectResponseBodyText('', scripts[s].textContent);
                        }
                    }
                    scanPerformanceAndNetRing();
                    return JSON.stringify(batchOut);
                };

                if (!window.__drIdmInstalledV8) {
                    window.__drIdmInstalledV8 = true;

                    // 1. cat-catch style JSON.parse hook: catches decrypted player configs in real time!
                    try {
                        var origJsonParse = JSON.parse;
                        if (origJsonParse && !origJsonParse.__drIdm) {
                            var wrappedParse = function(text) {
                                var parsed = origJsonParse.apply(this, arguments);
                                if (internalBusy) return parsed;
                                try {
                                    if (typeof text === 'string' && text.length >= 16 && text.length <= 2500000) {
                                        if (text.indexOf('http') >= 0 || text.indexOf('.m3u8') >= 0 || text.indexOf('.mp4') >= 0) {
                                            walkParsedJson(parsed, 0, { count: 0 }, '', 0);
                                        }
                                    }
                                } catch (e) {}
                                return parsed;
                            };
                            wrappedParse.__drIdm = 1;
                            JSON.parse = wrappedParse;
                        }
                    } catch (e) {}

                    // 2. Fetch request/response + Content-Type/Content-Disposition + #EXTM3U magic sniffer
                    try {
                        if (window.fetch && !window.fetch.__drIdm) {
                            var origFetch = window.fetch;
                            var wrappedFetch = function(input, init) {
                                var reqUrl = '';
                                var ranged = false;
                                try {
                                    reqUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                                    var rh = (init && init.headers) || (input && input.headers);
                                    if (rh) {
                                        var rv = (typeof rh.get === 'function') ? rh.get('range') : (rh.Range || rh.range);
                                        ranged = isPartialRangeHeader(rv);
                                    }
                                    if (reqUrl && !ranged && looksLikeMediaUrl(reqUrl)) {
                                        report('video', reqUrl, 0, 0, 0, '', '', '', '');
                                    }
                                } catch (e) {}
                                var p = origFetch.apply(this, arguments);
                                try {
                                    p.then(function(r) {
                                        if (!r || ranged) return;
                                        var finalUrl = r.url || reqUrl;
                                        var ct = (r.headers && r.headers.get('content-type')) || '';
                                        var cd = (r.headers && r.headers.get('content-disposition')) || '';
                                        if (finalUrl && (isServerMediaMime(ct) || hasMediaFilenameInCd(cd))) {
                                            report(ct.toLowerCase().indexOf('audio/') === 0 ? 'audio' : 'video', finalUrl, 0, 0, 0, ct, cd, '', '');
                                        } else {
                                            var lowCt = ct.toLowerCase();
                                            var cl = parseInt((r.headers && r.headers.get('content-length')) || '0', 10) || 0;
                                            if (cl <= 2500000 && (lowCt.indexOf('json') >= 0 || lowCt.indexOf('text') >= 0 || lowCt.indexOf('mpegurl') >= 0 || lowCt.indexOf('octet-stream') >= 0 || lowCt === '')) {
                                                r.clone().text().then(function(bodyTxt) {
                                                    inspectResponseBodyText(finalUrl, bodyTxt);
                                                }).catch(function(){});
                                            }
                                        }
                                    }).catch(function(){});
                                } catch (e) {}
                                return p;
                            };
                            wrappedFetch.__drIdm = 1;
                            window.fetch = wrappedFetch;
                        }
                    } catch (e) {}

                    // 3. XMLHttpRequest open/setRequestHeader/send + #EXTM3U body magic sniffer
                    try {
                        if (window.XMLHttpRequest && !XMLHttpRequest.prototype.open.__drIdm) {
                            var origOpen = XMLHttpRequest.prototype.open;
                            XMLHttpRequest.prototype.open = function(method, url) {
                                try {
                                    this.__drUrl = url ? String(url) : '';
                                    this.__drRanged = false;
                                } catch (e) {}
                                return origOpen.apply(this, arguments);
                            };
                            XMLHttpRequest.prototype.open.__drIdm = 1;

                            var origSetHdr = XMLHttpRequest.prototype.setRequestHeader;
                            XMLHttpRequest.prototype.setRequestHeader = function(name, val) {
                                try {
                                    if (name && String(name).toLowerCase() === 'range' && isPartialRangeHeader(val)) {
                                        this.__drRanged = true;
                                    }
                                } catch (e) {}
                                return origSetHdr.apply(this, arguments);
                            };

                            var origSend = XMLHttpRequest.prototype.send;
                            XMLHttpRequest.prototype.send = function() {
                                try {
                                    if (this.__drUrl && !this.__drRanged && looksLikeMediaUrl(this.__drUrl)) {
                                        report('video', this.__drUrl, 0, 0, 0, '', '', '', '');
                                    }
                                    this.addEventListener('load', function() {
                                        try {
                                            if (this.__drRanged) return;
                                            var rUrl = this.responseURL || this.__drUrl || '';
                                            var rCt = (this.getResponseHeader && this.getResponseHeader('content-type')) || '';
                                            var rCd = (this.getResponseHeader && this.getResponseHeader('content-disposition')) || '';
                                            if (rUrl && (isServerMediaMime(rCt) || hasMediaFilenameInCd(rCd))) {
                                                report(rCt.toLowerCase().indexOf('audio/') === 0 ? 'audio' : 'video', rUrl, 0, 0, 0, rCt, rCd, '', '');
                                            } else if (this.responseType === '' || this.responseType === 'text') {
                                                inspectResponseBodyText(rUrl, this.responseText);
                                            } else if (this.responseType === 'json' && this.response) {
                                                walkParsedJson(this.response, 0, { count: 0 }, '');
                                            }
                                        } catch (e) {}
                                    });
                                } catch (e) {}
                                return origSend.apply(this, arguments);
                            };
                        }
                    } catch (e) {}

                    // 4. HTMLMediaElement.prototype.src & play() hooks
                    try {
                        if (window.HTMLMediaElement) {
                            var srcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                            if (srcDesc && srcDesc.set && !srcDesc.set.__drIdm) {
                                var origSet = srcDesc.set;
                                var newSet = function(val) {
                                    var ret = origSet.call(this, val);
                                    try {
                                        if (val && typeof val === 'string' && val.indexOf('blob:') !== 0) {
                                            var self = this;
                                            setTimeout(function() { inspectMediaEl(self); }, 0);
                                        }
                                    } catch (e) {}
                                    return ret;
                                };
                                newSet.__drIdm = 1;
                                Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                                    configurable: true,
                                    enumerable: srcDesc.enumerable,
                                    get: srcDesc.get,
                                    set: newSet
                                });
                            }
                            var origPlay = HTMLMediaElement.prototype.play;
                            if (origPlay && !origPlay.__drIdm) {
                                var wrappedPlay = function() {
                                    try { inspectMediaEl(this); } catch (e) {}
                                    return origPlay.apply(this, arguments);
                                };
                                wrappedPlay.__drIdm = 1;
                                HTMLMediaElement.prototype.play = wrappedPlay;
                            }
                        }
                    } catch (e) {}

                    // 5. Cross-origin iframe rescan listener + DOM MutationObserver
                    try {
                        window.addEventListener('message', function(ev) {
                            try {
                                if (ev && ev.data && ev.data.__drIdmRescanCmd === 1 && typeof window.__drIdmRescan === 'function') {
                                    window.__drIdmRescan(true);
                                }
                            } catch (e) {}
                        }, false);
                    } catch (e) {}

                    var attachObserver = function() {
                        try {
                            if (window.__drIdmDomObsAttached || !window.MutationObserver || !document || !document.documentElement) return;
                            window.__drIdmDomObsAttached = true;
                            var obsPending = false;
                            new MutationObserver(function() {
                                if (obsPending) return;
                                obsPending = true;
                                setTimeout(function() {
                                    obsPending = false;
                                    if (typeof document !== 'undefined' && document) {
                                        scanRoot(document, 0);
                                        scanKnownPlayers();
                                    }
                                }, 450);
                            }).observe(document.documentElement, {
                                childList: true,
                                subtree: true,
                                attributes: true,
                                attributeFilter: ['src', 'data-src', 'data-hls', 'data-mp4']
                            });
                        } catch (e) {}
                    };
                    attachObserver();
                    if (typeof document !== 'undefined' && document && document.addEventListener) {
                        document.addEventListener('DOMContentLoaded', function() {
                            attachObserver();
                            if (typeof window.__drIdmRescan === 'function') window.__drIdmRescan(false);
                        }, { once: true });
                    }
                }

                return window.__drIdmRescan(Boolean(forceClear));
            } catch (err) {
                return "[]";
            }
        })(false);
    """
}
