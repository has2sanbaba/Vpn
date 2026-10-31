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
    val frameRate: Int = 0,
    val bitrateBps: Long = 0L,
    val codecsInfo: String = "",
    val segmentCount: Int = 0,
    val isEstimatedSize: Boolean = false,
    val explicitFileName: String = "",
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

    fun formattedBitrate(): String {
        if (bitrateBps <= 0L) return ""
        return if (bitrateBps >= 1_000_000L) {
            String.format(Locale.US, "%.1f Mbps", bitrateBps / 1_000_000.0)
        } else {
            "${(bitrateBps / 1000L).coerceAtLeast(1L)} Kbps"
        }
    }

    fun sourceHost(): String {
        return runCatching {
            URI(url).host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
        }.getOrDefault("")
    }

    fun resolutionDetail(): String {
        val fpsPart = if (frameRate > 30) " • ${frameRate}fps" else ""
        return when {
            width > 0 && height > 0 -> "${width}×${height}$fpsPart"
            height > 0 -> "${height}p$fpsPart"
            else -> ""
        }
    }

    fun containerAndCodecsLabel(): String {
        val container = when {
            isHls && kind != SniffedMediaKind.AUDIO -> "HLS ➔ MP4"
            isHls && kind == SniffedMediaKind.AUDIO -> "HLS ➔ M4A"
            else -> formatBadge
        }
        return if (codecsInfo.isNotBlank()) "$container ($codecsInfo)" else container
    }

    fun audioStatusLabel(): String = when {
        kind == SniffedMediaKind.AUDIO -> "🎵 فقط صدا"
        hasPairedAudio -> "🔊 با صدا (ادغام خودکار)"
        isHls -> "🔊 با صدا"
        else -> "🔊 ویدیو کامل"
    }

    fun suggestedFileName(): String {
        if (explicitFileName.isNotBlank() && !explicitFileName.endsWith(".m3u8", true)) {
            val cleaned = explicitFileName
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), "_")
                .trim()
            if (cleaned.length in 4..96) return cleaned
        }
        val cleanTitle = title
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(56)
            .ifBlank { "video_${System.currentTimeMillis() % 100000}" }
        val qSuffix = when {
            height > 0 && frameRate > 30 -> "_${height}p${frameRate}"
            height > 0 -> "_${height}p"
            bitrateBps >= 64_000L && kind == SniffedMediaKind.AUDIO -> "_${bitrateBps / 1000L}k"
            else -> ""
        }
        val ext = when {
            isHls && kind == SniffedMediaKind.AUDIO -> "m4a"
            isHls -> "mp4"
            formatBadge.equals("WEBM", true) -> "webm"
            formatBadge.equals("MKV", true) -> "mkv"
            formatBadge.equals("MOV", true) -> "mov"
            formatBadge.equals("MP3", true) -> "mp3"
            formatBadge.equals("M4A", true) -> "m4a"
            formatBadge.equals("AAC", true) -> "aac"
            formatBadge.equals("OGG", true) -> "ogg"
            formatBadge.equals("OPUS", true) -> "opus"
            formatBadge.equals("WAV", true) -> "wav"
            formatBadge.equals("FLAC", true) -> "flac"
            kind == SniffedMediaKind.AUDIO -> "m4a"
            else -> "mp4"
        }
        return "${cleanTitle.replace(' ', '_')}${qSuffix}.$ext"
    }
}

/**
 * v9.0 All-Qualities & Rich-Metadata Video/Stream Discovery Engine:
 * 1. Captures ALL available qualities (`144p` through `4K`, multi-bitrate variants, and standalone
 *    audio tracks) from HLS Master Playlists, `hls.js`/`Plyr`/`Artplayer`/`DPlayer`/`JWPlayer`/`Video.js`,
 *    HTML5 `<video>/<source>`, JSON responses, and page download links — never restricting to top quality!
 * 2. Caches live `#EXTM3U` manifest bodies straight from in-page `fetch`/`XMLHttpRequest` so even
 *    one-time-token playlists expand immediately with `#EXT-X-MEDIA:TYPE=AUDIO` paired!
 * 3. Tracks sibling page audio `.m3u8` renditions (`findSiblingAudioForVideoUrl`) so even standalone
 *    video sub-playlists automatically pair with their page's audio stream!
 * 4. Battery-optimized DOM/JS hooks (`1200ms` debounced `MutationObserver`, visibility-aware pause).
 */
object VideoStreamSniffer {

    private val VIDEO_EXTS = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "3gp", "avi", "ogv")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val HLS_EXTS = setOf("m3u8", "m3u")

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

    // Maps normalized video variant URL -> paired #EXT-X-MEDIA:TYPE=AUDIO URL
    private val variantAudioPairMap = ConcurrentHashMap<String, String>()
    // Maps normalized pageUrl -> latest detected HLS audio rendition URL on that page
    private val pageFallbackAudioMap = ConcurrentHashMap<String, String>()
    // Bounded in-memory cache of #EXTM3U manifest text captured directly from WebView XHR/Fetch
    private val manifestCacheLock = Any()
    private val manifestBodyCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 32
    }

    private val _updatesFlow = MutableStateFlow(0L)
    val updatesFlow: StateFlow<Long> = _updatesFlow.asStateFlow()

    fun cacheHlsManifest(url: String, body: String) {
        if (url.isBlank() || body.isBlank() || body.length > 600_000) return
        if (!HlsPlaylistParser.looksLikeHlsManifest(body)) return
        val key = normalizeDedupeUrl(url)
        synchronized(manifestCacheLock) {
            manifestBodyCache[key] = body
        }
    }

    fun getCachedHlsManifest(url: String): String? {
        if (url.isBlank()) return null
        val key = normalizeDedupeUrl(url)
        return synchronized(manifestCacheLock) {
            manifestBodyCache[key]
        }
    }

    fun registerPageAudioHls(pageUrl: String, audioHlsUrl: String) {
        if (audioHlsUrl.isBlank()) return
        if (pageUrl.isNotBlank()) {
            pageFallbackAudioMap[normalizeDedupeUrl(pageUrl)] = audioHlsUrl.trim()
        }
    }

    fun findSiblingAudioForVideoUrl(videoUrl: String, pageUrl: String = ""): String? {
        val normVideo = normalizeDedupeUrl(videoUrl)
        variantAudioPairMap[normVideo]?.takeIf { it.isNotBlank() && normalizeDedupeUrl(it) != normVideo }?.let {
            return it
        }
        if (pageUrl.isNotBlank()) {
            pageFallbackAudioMap[normalizeDedupeUrl(pageUrl)]
                ?.takeIf { it.isNotBlank() && normalizeDedupeUrl(it) != normVideo }
                ?.let { return it }
        }
        return null
    }

    fun getMediaForTab(tabId: String): List<DetectedMediaItem> {
        val map = tabMediaMap[tabId] ?: return emptyList()
        return synchronized(map) {
            val all = map.values.toList()
            val hasSubstantialVideo = all.any {
                it.kind != SniffedMediaKind.AUDIO &&
                    (it.durationSec >= 8 || it.contentLengthBytes >= 600_000L || it.height >= 240 || it.isHls)
            }
            all.sortedWith(
                compareByDescending<DetectedMediaItem> { it.kind != SniffedMediaKind.AUDIO }
                    .thenByDescending { item ->
                        if (!hasSubstantialVideo) true
                        else {
                            val tinySize = item.contentLengthBytes in 1L..200_000L
                            val tinyDur = item.durationSec in 1..3
                            !tinySize && !tinyDur
                        }
                    }
                    .thenByDescending { it.isMasterVariant }
                    .thenByDescending { it.height }
                    .thenByDescending { it.bitrateBps }
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
        isEstimatedSize: Boolean = false,
        durationSec: Int = 0,
        probedMime: String = "",
        fileNameHint: String = "",
        segmentCount: Int = 0,
        bitrateBps: Long = 0L,
        codecsInfo: String = ""
    ) {
        val map = tabMediaMap[tabId] ?: return
        var updated = false
        synchronized(map) {
            val existing = map[itemId] ?: return
            val nextLen = if (contentLength > 0L) contentLength else existing.contentLengthBytes
            val nextEst = if (contentLength > 0L) isEstimatedSize else existing.isEstimatedSize
            val nextDur = if (durationSec > 0) maxOf(existing.durationSec, durationSec) else existing.durationSec
            val nextSegs = if (segmentCount > 0) segmentCount else existing.segmentCount
            val nextBw = if (bitrateBps > 0L) bitrateBps else existing.bitrateBps
            val nextCodecs = codecsInfo.ifBlank { existing.codecsInfo }
            val nextExplicitName = fileNameHint.ifBlank { existing.explicitFileName }
            val nextTitle = if (fileNameHint.isNotBlank() && existing.title in GENERIC_PLACEHOLDER_TITLES) {
                fileNameHint.substringBeforeLast('.').take(64).ifBlank { existing.title }
            } else {
                existing.title
            }
            val cleanProbedMime = probedMime.substringBefore(';').trim().lowercase(Locale.US)
            val upgradedHls = cleanProbedMime.contains("mpegurl") && !existing.isHls
            val siblingAudio = if ((existing.isHls || upgradedHls) && existing.audioUrl.isBlank() && existing.kind != SniffedMediaKind.AUDIO) {
                findSiblingAudioForVideoUrl(existing.url, existing.pageUrl).orEmpty()
            } else {
                existing.audioUrl
            }
            val next = existing.copy(
                title = nextTitle,
                audioUrl = siblingAudio,
                kind = if (upgradedHls) SniffedMediaKind.HLS_STREAM else existing.kind,
                mimeType = if (upgradedHls) "application/vnd.apple.mpegurl" else existing.mimeType,
                formatBadge = if (upgradedHls) "HLS" else existing.formatBadge,
                contentLengthBytes = nextLen,
                isEstimatedSize = nextEst,
                durationSec = nextDur,
                segmentCount = nextSegs,
                bitrateBps = nextBw,
                codecsInfo = nextCodecs,
                explicitFileName = nextExplicitName
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
     * Expands an HLS Master Playlist into ALL available quality variants (`144p` through `4K`,
     * multi-bitrate streams, plus standalone Audio rendition), pairs EVERY video variant with its
     * `#EXT-X-MEDIA:TYPE=AUDIO` separate audio track, and suppresses raw un-paired child sub-playlists.
     */
    fun replaceMasterWithResolvedVariants(
        tabId: String,
        masterItem: DetectedMediaItem,
        variants: List<HlsPlaylistParser.VariantStream>,
        audioRenditions: List<HlsPlaylistParser.AudioRendition>,
        probedDurationSec: Int = 0,
        probedSegmentCount: Int = 0
    ): Boolean {
        if (variants.isEmpty() && audioRenditions.isEmpty()) return false
        val childSet = tabChildSubPlaylists.getOrPut(tabId) { ConcurrentHashMap.newKeySet() }

        for (a in audioRenditions) {
            if (!a.url.isNullOrBlank()) {
                childSet.add(normalizeDedupeUrl(a.url))
                registerPageAudioHls(masterItem.pageUrl, a.url)
            }
        }
        for (v in variants) {
            val normV = normalizeDedupeUrl(v.url)
            childSet.add(normV)
            if (!v.audioUrl.isNullOrBlank()) {
                childSet.add(normalizeDedupeUrl(v.audioUrl))
                variantAudioPairMap[normV] = v.audioUrl
                registerPageAudioHls(masterItem.pageUrl, v.audioUrl)
            }
        }

        // Count how many variants share the same resolution height so multi-bitrate streams get distinct labels!
        val heightCounts = HashMap<Int, Int>()
        for (v in variants) {
            if (!v.isAudioOnly && v.height > 0) {
                heightCounts[v.height] = (heightCounts[v.height] ?: 0) + 1
            }
        }

        val effectiveDuration = maxOf(masterItem.durationSec, probedDurationSec)
        val map = tabMediaMap.getOrPut(tabId) { LinkedHashMap() }
        synchronized(map) {
            map.remove(masterItem.id)

            // Remove raw un-paired child sub-playlists caught earlier from hls.js
            val iter = map.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key in childSet && !entry.value.isMasterVariant) {
                    iter.remove()
                }
            }

            // 1. Add ALL video & audio variants (sorted highest -> lowest by HlsPlaylistParser)
            for (v in variants) {
                val id = normalizeDedupeUrl(v.url)
                val pairedAudio = v.audioUrl.orEmpty()
                // Include ~128 kbps audio overhead in size estimate when audio is a separate rendition
                val totalBw = if (v.bandwidth > 0L) {
                    v.bandwidth + (if (pairedAudio.isNotBlank() && !v.codecs.contains("mp4a", true)) 128_000L else 0L)
                } else {
                    0L
                }
                val estBytes = if (totalBw > 0L && effectiveDuration > 0) {
                    (totalBw * effectiveDuration) / 8L
                } else {
                    -1L
                }
                val qBadge = v.qualityBadge()
                val duplicateHeight = v.height > 0 && (heightCounts[v.height] ?: 0) > 1
                val bwSuffix = if (duplicateHeight && v.bitrateLabel().isNotBlank()) " (${v.bitrateLabel()})" else ""
                val fpsInt = v.frameRate.toInt()
                val qLabel = when {
                    v.isAudioOnly -> "فقط صدا (${v.bitrateLabel().ifBlank { "AAC" }})"
                    else -> "$qBadge$bwSuffix"
                }

                map[id] = DetectedMediaItem(
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
                    isMasterVariant = true,
                    frameRate = fpsInt,
                    bitrateBps = v.bandwidth,
                    codecsInfo = v.readableCodecs(),
                    segmentCount = probedSegmentCount,
                    isEstimatedSize = estBytes > 0L
                )
            }

            // 2. Also expose best standalone #EXT-X-MEDIA:TYPE=AUDIO rendition as an audio-only M4A download!
            val hasAudioVariantAlready = variants.any { it.isAudioOnly }
            if (!hasAudioVariantAlready) {
                val bestAudioRendition = audioRenditions
                    .filter { !it.url.isNullOrBlank() }
                    .sortedWith(
                        compareByDescending<HlsPlaylistParser.AudioRendition> { it.isDefault }
                            .thenByDescending { it.autoSelect }
                            .thenByDescending { it.channels }
                    )
                    .firstOrNull()
                if (bestAudioRendition != null && !bestAudioRendition.url.isNullOrBlank()) {
                    val audioId = normalizeDedupeUrl(bestAudioRendition.url) + "#audio_only"
                    val estAudioBytes = if (effectiveDuration > 0) (128_000L * effectiveDuration) / 8L else -1L
                    val langTag = bestAudioRendition.language.ifBlank { bestAudioRendition.name }.take(12)
                    map[audioId] = DetectedMediaItem(
                        id = audioId,
                        tabId = tabId,
                        url = bestAudioRendition.url,
                        audioUrl = "",
                        kind = SniffedMediaKind.AUDIO,
                        title = masterItem.title,
                        pageUrl = masterItem.pageUrl,
                        mimeType = "application/vnd.apple.mpegurl",
                        formatBadge = "M4A",
                        qualityLabel = if (langTag.isNotBlank()) "صدا فقط ($langTag)" else "فایل صوتی (AAC)",
                        width = 0,
                        height = 0,
                        durationSec = effectiveDuration,
                        contentLengthBytes = estAudioBytes,
                        isMasterVariant = true,
                        bitrateBps = 128_000L,
                        codecsInfo = "AAC",
                        segmentCount = probedSegmentCount,
                        isEstimatedSize = estAudioBytes > 0L
                    )
                }
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
        qualityHint: String = "",
        bitrateHintBps: Long = 0L
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
            qualityHint = qualityHint,
            bitrateHintBps = bitrateHintBps
        ) ?: return null

        // Remember if this is a standalone HLS audio sub-playlist so mute video sub-playlists on the same page can pair with it!
        val lowerUrl = classified.url.lowercase(Locale.US)
        if (classified.isHls && (lowerUrl.contains("/audio") || lowerUrl.contains("_audio") || lowerUrl.contains("audio_") || lowerUrl.contains("aac"))) {
            registerPageAudioHls(classified.pageUrl, classified.url)
        }

        val knownChildren = tabChildSubPlaylists[tabId]
        if (knownChildren != null && classified.id in knownChildren && classified.audioUrl.isBlank()) {
            val existingInMap = tabMediaMap[tabId]?.let { synchronized(it) { it[classified.id] } }
            if (existingInMap == null || existingInMap.isMasterVariant) {
                return existingInMap
            }
        }

        val autoAudio = if (classified.isHls && classified.audioUrl.isBlank() && classified.kind != SniffedMediaKind.AUDIO) {
            findSiblingAudioForVideoUrl(classified.url, classified.pageUrl).orEmpty()
        } else {
            classified.audioUrl
        }
        val enriched = if (autoAudio.isNotBlank() && autoAudio != classified.audioUrl) {
            classified.copy(audioUrl = autoAudio)
        } else {
            classified
        }

        val map = tabMediaMap.getOrPut(tabId) { LinkedHashMap() }
        synchronized(map) {
            val existing = map[enriched.id]
            if (existing != null) {
                val merged = existing.copy(
                    audioUrl = existing.audioUrl.ifBlank { enriched.audioUrl },
                    title = if (enriched.title.isNotBlank() &&
                        enriched.title !in GENERIC_PLACEHOLDER_TITLES &&
                        (existing.title.isBlank() || existing.title in GENERIC_PLACEHOLDER_TITLES || existing.title.contains('.'))
                    ) enriched.title else existing.title,
                    pageUrl = existing.pageUrl.ifBlank { enriched.pageUrl },
                    width = maxOf(existing.width, enriched.width),
                    height = maxOf(existing.height, enriched.height),
                    durationSec = maxOf(existing.durationSec, enriched.durationSec),
                    bitrateBps = maxOf(existing.bitrateBps, enriched.bitrateBps),
                    explicitFileName = existing.explicitFileName.ifBlank { enriched.explicitFileName },
                    qualityLabel = if (existing.isMasterVariant) existing.qualityLabel else enriched.qualityLabel.ifBlank { existing.qualityLabel }
                )
                if (merged != existing) {
                    map[enriched.id] = merged
                    bumpUpdate()
                }
                return merged
            }
            if (map.size >= 80) {
                val oldestKey = map.keys.firstOrNull { map[it]?.isMasterVariant == false }
                    ?: map.keys.firstOrNull()
                if (oldestKey != null) map.remove(oldestKey)
            }
            map[enriched.id] = enriched
        }
        bumpUpdate()
        return enriched
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
        qualityHint: String = "",
        bitrateHintBps: Long = 0L
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

        // 1. Progressive googlevideo streams (muxed video+audio itag 18/22/37 or clean audio 139/140/249/250/251)
        if (host.endsWith("googlevideo.com") && lowerPath.contains("videoplayback")) {
            val itag = queryMap["itag"]?.toIntOrNull()
            val qMime = runCatching { URLDecoder.decode(queryMap["mime"].orEmpty(), "UTF-8") }
                .getOrDefault(queryMap["mime"].orEmpty())
                .lowercase(Locale.US)
            val isMuxedMp4 = itag == 18 || itag == 22 || itag == 37 || itag == 59 || itag == 78
            val isCleanAudio = itag == 139 || itag == 140 || itag == 141 || itag == 249 || itag == 250 || itag == 251 ||
                (itag == null && qMime.startsWith("audio/"))
            if (!isMuxedMp4 && !isCleanAudio) return null

            val cleanUrl = stripQueryKeys(trimmed, setOf("range", "rn", "rbuf", "ump", "srfvp"))
            val resHeight = when (itag) {
                37 -> 1080
                22 -> 720
                59, 78 -> 480
                18 -> 360
                else -> 0
            }
            val qLabel = when (itag) {
                37 -> "1080p Full HD"
                22 -> "720p HD"
                59, 78 -> "480p MP4"
                18 -> "360p MP4"
                251 -> "Opus 160k Audio"
                140 -> "M4A 128k Audio"
                139, 249, 250 -> "Audio Low"
                else -> "فایل صوتی"
            }
            val isOpus = itag == 249 || itag == 250 || itag == 251 || qMime.contains("webm")
            val kind = if (isCleanAudio) SniffedMediaKind.AUDIO else SniffedMediaKind.VIDEO
            val badge = when {
                isCleanAudio && isOpus -> "OPUS"
                isCleanAudio -> "M4A"
                else -> "MP4"
            }
            val clen = queryMap["clen"]?.toLongOrNull() ?: -1L
            val durFromQ = queryMap["dur"]?.substringBefore('.')?.toIntOrNull() ?: durationSec
            val id = normalizeDedupeUrl(cleanUrl)
            return DetectedMediaItem(
                id = id,
                tabId = tabId,
                url = cleanUrl,
                kind = kind,
                title = bestTitle(pageTitle, cleanUrl, kind, contentDisposition),
                pageUrl = pageUrl,
                mimeType = if (isCleanAudio) (if (isOpus) "audio/webm" else "audio/mp4") else "video/mp4",
                formatBadge = badge,
                qualityLabel = qLabel,
                width = 0,
                height = resHeight,
                durationSec = durFromQ,
                contentLengthBytes = clen,
                codecsInfo = if (isCleanAudio) (if (isOpus) "Opus" else "AAC") else "H.264 + AAC"
            )
        }

        // 2. Strip byte-slice range query parameters from progressive CDN URLs
        var normalizedDownloadUrl = trimmed
        if (queryMap.containsKey("bytestart") || queryMap.containsKey("byteend")) {
            normalizedDownloadUrl = stripQueryKeys(trimmed, setOf("bytestart", "byteend"))
        }

        val cdFileName = extractFilenameFromContentDisposition(contentDisposition)
        val cdExt = cdFileName.substringAfterLast('.', "").lowercase(Locale.US)
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

        val qParamHint = (queryMap["quality"] ?: queryMap["res"] ?: queryMap["resolution"] ?: queryMap["label"]).orEmpty()
        val combinedHint = qualityHint.ifBlank { qParamHint }
        val hintHeight = parseHeightFromQualityHint(combinedHint)
        val inferredHeight = when {
            height > 0 -> height
            hintHeight > 0 -> hintHeight
            else -> inferResolutionFromUrl(normalizedDownloadUrl)
        }
        val inferredWidth = when {
            width > 0 -> width
            inferredHeight == 2160 -> 3840
            inferredHeight == 1440 -> 2560
            inferredHeight == 1080 -> 1920
            inferredHeight == 720 -> 1280
            inferredHeight == 540 -> 960
            inferredHeight == 480 -> 854
            inferredHeight == 360 -> 640
            inferredHeight == 240 -> 426
            inferredHeight == 144 -> 256
            else -> 0
        }

        val qLabel = when {
            isHls && inferredHeight > 0 -> "${inferredHeight}p"
            isHls -> "چند کیفیتی (HLS)"
            inferredHeight >= 2160 -> "${inferredHeight}p 4K"
            inferredHeight >= 1440 -> "${inferredHeight}p 2K"
            inferredHeight >= 1080 -> "${inferredHeight}p Full HD"
            inferredHeight >= 720 -> "${inferredHeight}p HD"
            inferredHeight > 0 -> "${inferredHeight}p"
            combinedHint.isNotBlank() -> combinedHint.take(20)
            isAudio -> "فایل صوتی"
            else -> ""
        }

        val codecsGuess = when {
            badge == "WEBM" -> "VP9 + Opus"
            badge == "MP4" -> "H.264 + AAC"
            badge == "M4A" || badge == "AAC" -> "AAC"
            badge == "MP3" -> "MP3"
            badge == "OPUS" -> "Opus"
            else -> ""
        }

        val urlFileName = extractCleanFileNameFromUrl(normalizedDownloadUrl)
        val explicitName = cdFileName.ifBlank { urlFileName }

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
            width = inferredWidth,
            height = inferredHeight,
            durationSec = durationSec,
            bitrateBps = bitrateHintBps,
            codecsInfo = codecsGuess,
            explicitFileName = explicitName
        )
    }

    private fun extractFilenameFromContentDisposition(cd: String): String {
        if (cd.isBlank()) return ""
        val m = Regex("""filename\*?\s*=\s*(?:UTF-8''|utf-8'')?["']?([^"';\r\n]+)["']?""", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (m.isEmpty()) return ""
        return runCatching { URLDecoder.decode(m, "UTF-8") }.getOrDefault(m).trim()
    }

    private fun extractCleanFileNameFromUrl(url: String): String {
        val seg = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .trim()
        if ('.' !in seg || seg.length !in 5..96) return ""
        val base = seg.substringBeforeLast('.').lowercase(Locale.US)
        val generic = setOf("master", "index", "playlist", "manifest", "video", "stream", "play", "videoplayback")
        return if (base in generic) "" else seg
    }

    private fun parseHeightFromQualityHint(hint: String): Int {
        if (hint.isBlank()) return 0
        if (hint.equals("4k", true) || hint.equals("uhd", true)) return 2160
        if (hint.equals("2k", true) || hint.equals("qhd", true)) return 1440
        if (hint.equals("fhd", true) || hint.equals("fullhd", true)) return 1080
        val m = Regex("""(2160|1440|1080|720|576|540|480|360|270|240|180|144)""").find(hint)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun inferResolutionFromUrl(url: String): Int {
        val m = Regex("""(?:^|[/_.,=-])(2160|1440|1080|720|576|540|480|360|270|240|180|144)p?(?:[/_?.,&-]|$)""", RegexOption.IGNORE_CASE)
            .find(url)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun bestTitle(
        pageTitle: String,
        url: String,
        kind: SniffedMediaKind,
        contentDisposition: String = ""
    ): String {
        val cdName = extractFilenameFromContentDisposition(contentDisposition).substringBeforeLast('.').trim()
        if (cdName.length in 3..80) return cdName

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
     * v9.0 All-Qualities + Live Manifest Body + Battery-Optimized Page Sniffer (`DOCUMENT_START` Ready):
     * - Forwards `#EXTM3U` response text directly via `DrIdmBridge.onHlsManifestCaptured` so Master
     *   Playlists & `#EXT-X-MEDIA:TYPE=AUDIO` tracks expand immediately with zero extra network call!
     * - Extracts ALL quality arrays from `JSON.parse`, `JWPlayer`, `Video.js`, `Plyr`, `Artplayer`,
     *   `DPlayer`, `hls.js`, HTML5 `<video><source>` lists, and `<a href>` multi-resolution download buttons!
     * - Low-CPU design: `1200ms` debounced `MutationObserver`, skips hidden background tabs, and checks
     *   Shadow DOM only on custom web-components (`tagName.indexOf('-') > 0`).
     */
    const val IDM_PAGE_SNIFFER_JS = """
        (function(forceClear) {
            try {
                if (forceClear) {
                    window.__drIdmSeen = Object.create(null);
                    window.__drIdmManifestSeen = Object.create(null);
                }
                var seen = window.__drIdmSeen || (window.__drIdmSeen = Object.create(null));
                var manifestSeen = window.__drIdmManifestSeen || (window.__drIdmManifestSeen = Object.create(null));
                var netRing = window.__drIdmNetItems || (window.__drIdmNetItems = []);
                var batchOut = [];

                var MEDIA_EXT_RE = /\.(mp4|m4v|webm|mkv|mov|3gp|flv|m3u8|m3u|mp3|m4a|aac|ogg|opus|wav|flac)(\?|#|$)/i;
                var IGNORE_EXT_RE = /\.(ts|m2ts|m4s|m4f|cmfv|cmfa|mpd|vtt|srt|jpg|jpeg|png|webp|gif|svg|ico|css|js|mjs|json|xml|html|htm|php|woff|woff2|ttf|wasm)(\?|#|$)/i;
                var SEG_PATH_RE = /\/(?:seg|segment|segments|frag|fragments|chunk|chunks)[\/_-]\d+|[_\/-](?:seg|chunk|frag)-?\d+\.(?:ts|m4s|mp4|aac)|\/range\/\d+-\d+/i;
                var STRICT_VIDEO_KEYS = /^(hls|hlsurl|hls_url|m3u8|m3u8_url|mp4|mp4_url|video_url|videourl|playbackurl|playback_url|stream_url|streamurl|progressive_url|contenturl|download_url|downloadurl|backup_url)$/i;
                var GENERIC_SRC_KEYS = /^(file|src|url|link)$/i;
                var QUALITY_LABEL_RE = /^(4k|2k|uhd|fhd|hd|sd|2160|1440|1080|720|576|540|480|360|270|240|180|144)p?$/i;
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
                    return low.indexOf('.m3u8') >= 0 ||
                        low.indexOf('mime=video') >= 0 ||
                        low.indexOf('mime=audio') >= 0 ||
                        low.indexOf('mime%3dvideo') >= 0 ||
                        low.indexOf('format=m3u8') >= 0 ||
                        low.indexOf('videoplayback') >= 0;
                }

                function rememberNetItem(obj) {
                    if (netRing.length >= 160) netRing.shift();
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

                function topPageHref() {
                    try { return window.top.location.href; } catch (e) { return document.referrer || location.href; }
                }

                function reportHlsManifestBody(rawUrl, bodyText) {
                    try {
                        if (!rawUrl || !bodyText || bodyText.length > 550000) return;
                        var trimmed = String(rawUrl).trim();
                        if (!trimmed || trimmed.indexOf('data:') === 0) return;
                        if (trimmed.indexOf('//') === 0) trimmed = (location.protocol || 'https:') + trimmed;
                        var abs = (trimmed.indexOf('http') === 0) ? trimmed : new URL(trimmed, (document && document.baseURI) || location.href).href;
                        if (abs.indexOf('http') !== 0) return;
                        var key = abs.split('#')[0];
                        if (manifestSeen[key]) return;
                        manifestSeen[key] = 1;
                        if (window.DrIdmBridge && window.DrIdmBridge.onHlsManifestCaptured) {
                            internalBusy = true;
                            try {
                                window.DrIdmBridge.onHlsManifestCaptured(JSON.stringify({
                                    url: abs,
                                    pageUrl: topPageHref(),
                                    title: bestDocTitle(),
                                    body: bodyText
                                }));
                            } finally {
                                internalBusy = false;
                            }
                        }
                    } catch (e) { internalBusy = false; }
                }

                function report(kind, rawUrl, w, h, dur, mimeHint, cdHint, qualityHint, titleHint, bitrateBps) {
                    try {
                        if (!rawUrl || typeof rawUrl !== 'string') return;
                        var trimmed = rawUrl.trim();
                        if (!trimmed || trimmed.indexOf('blob:') === 0 || trimmed.indexOf('data:') === 0 || trimmed.indexOf('javascript:') === 0) return;
                        if (trimmed.indexOf('//') === 0) trimmed = (location.protocol || 'https:') + trimmed;
                        var abs = new URL(trimmed, (document && document.baseURI) || location.href).href;
                        if (abs.indexOf('http') !== 0) return;
                        if (IGNORE_EXT_RE.test(abs) || SEG_PATH_RE.test(abs)) return;

                        var cleanMime = (mimeHint || '').split(';')[0].trim();
                        if (!isServerMediaMime(cleanMime) && !hasMediaFilenameInCd(cdHint) && !looksLikeMediaUrl(abs)) {
                            return;
                        }

                        var key = abs.split('#')[0];
                        var metaKey = key + '|' + (h || 0) + '|' + (qualityHint || '') + '|' + cleanMime;
                        var item = {
                            url: abs,
                            kind: kind || (cleanMime.indexOf('audio/') === 0 ? 'audio' : 'video'),
                            mime: cleanMime,
                            cd: cdHint || '',
                            quality: qualityHint || '',
                            pageUrl: topPageHref(),
                            title: titleHint || bestDocTitle(),
                            width: w || 0,
                            height: h || 0,
                            duration: (isFinite(dur) && dur > 0) ? Math.round(dur) : 0,
                            bitrate: (isFinite(bitrateBps) && bitrateBps > 0) ? Math.round(bitrateBps) : 0
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
                    if (!node || depth > 8 || budget.count > 450) return;
                    budget.count++;
                    if (typeof node === 'string') {
                        if (node.length >= 12 && node.length <= 4096) {
                            if (node.trim().indexOf('#EXTM3U') === 0) return;
                            if (looksLikeMediaUrl(node)) {
                                report('video', node, 0, 0, inheritedDur || 0, '', '', '', inheritedTitle || '', 0);
                            }
                        }
                        return;
                    }
                    if (typeof node !== 'object') return;
                    if (Array.isArray(node)) {
                        for (var i = 0; i < node.length && i < 64; i++) {
                            walkParsedJson(node[i], depth + 1, budget, inheritedTitle, inheritedDur);
                        }
                        return;
                    }

                    var localTitle = (typeof node.title === 'string' && node.title.length >= 2) ? node.title : (inheritedTitle || '');
                    var w = (typeof node.width === 'number') ? node.width : 0;
                    var h = (typeof node.height === 'number') ? node.height : ((typeof node.size === 'number' && node.size >= 144 && node.size <= 4320) ? node.size : 0);
                    var dur = (typeof node.duration === 'number' && node.duration > 0) ? node.duration : (inheritedDur || 0);
                    var br = (typeof node.bitrate === 'number') ? node.bitrate : ((typeof node.bandwidth === 'number') ? node.bandwidth : 0);
                    var qLabel = '';
                    if (typeof node.label === 'string') qLabel = node.label;
                    else if (typeof node.quality === 'string' || typeof node.quality === 'number') qLabel = String(node.quality);
                    else if (typeof node.profile === 'string') qLabel = node.profile;
                    else if (typeof node.res === 'string' || typeof node.res === 'number') qLabel = String(node.res);
                    var mType = (typeof node.type === 'string' && (node.type.indexOf('/') > 0 || node.type === 'hls')) ? node.type : ((typeof node.mime_type === 'string') ? node.mime_type : '');
                    if (mType === 'hls') mType = 'application/vnd.apple.mpegurl';
                    var hasStrongPlayerContext = isServerMediaMime(mType) || QUALITY_LABEL_RE.test(qLabel) || (h >= 144 && h <= 4320);

                    var keys = Object.keys(node);
                    for (var k = 0; k < keys.length && k < 64; k++) {
                        var prop = keys[k];
                        var val = node[prop];
                        if (typeof val === 'string' && val.length >= 10 && val.length <= 4096) {
                            var unesc = val.replace(/\\\//g, '/');
                            var isHttpOrProto = unesc.indexOf('http://') === 0 || unesc.indexOf('https://') === 0 || unesc.indexOf('//') === 0;
                            var keyLooksQuality = QUALITY_LABEL_RE.test(prop);
                            var effectiveQ = qLabel || (keyLooksQuality ? prop : '');
                            if (looksLikeMediaUrl(unesc)) {
                                report('video', unesc, w, h, dur, mType, '', effectiveQ, localTitle, br);
                            } else if (isHttpOrProto && (STRICT_VIDEO_KEYS.test(prop) || keyLooksQuality)) {
                                var inferredMime = mType || (/hls|m3u8/i.test(prop) ? 'application/vnd.apple.mpegurl' : 'video/mp4');
                                report('video', unesc, w, h, dur, inferredMime, '', effectiveQ, localTitle, br);
                            } else if (isHttpOrProto && GENERIC_SRC_KEYS.test(prop) && hasStrongPlayerContext) {
                                report('video', unesc, w, h, dur, mType || 'video/mp4', '', effectiveQ, localTitle, br);
                            }
                        } else if (val && typeof val === 'object') {
                            walkParsedJson(val, depth + 1, budget, localTitle, dur);
                        }
                    }
                }

                function inspectResponseBodyText(sourceUrl, text) {
                    try {
                        if (!text || typeof text !== 'string' || text.length > 2500000) return;
                        var lead = text.slice(0, 96).trim();
                        if (lead.indexOf('#EXTM3U') === 0 && sourceUrl) {
                            reportHlsManifestBody(sourceUrl, text);
                            report('video', sourceUrl, 0, 0, 0, 'application/vnd.apple.mpegurl', '', '', '', 0);
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
                        while ((match = re.exec(unescaped)) && count < 80) {
                            count++;
                            var u = match[0];
                            if (IGNORE_EXT_RE.test(u) || SEG_PATH_RE.test(u)) continue;
                            report(/\.(mp3|m4a)(\?|$)/i.test(u) ? 'audio' : 'video', u, 0, 0, 0, '', '', '', '', 0);
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
                    if (el.currentSrc && el.currentSrc.indexOf('blob:') !== 0) report(k, el.currentSrc, w, h, d, defaultMime, '', '', '', 0);
                    if (el.src && el.src.indexOf('blob:') !== 0) report(k, el.src, w, h, d, defaultMime, '', '', '', 0);
                    var attrs = ['data-src', 'data-video-src', 'data-mp4', 'data-hls', 'data-stream', 'data-url', 'data-setup', 'data-plyr-config'];
                    for (var a = 0; a < attrs.length; a++) {
                        var av = el.getAttribute ? el.getAttribute(attrs[a]) : null;
                        if (!av) continue;
                        if (av.indexOf('{') === 0 || av.indexOf('[') === 0) {
                            try { walkParsedJson(JSON.parse(av), 0, { count: 0 }, '', d); } catch (e) { inspectResponseBodyText('', av); }
                        } else if (av.indexOf('blob:') !== 0) {
                            report(k, av, w, h, d, defaultMime, '', '', '', 0);
                        }
                    }
                    // Extract ALL <source> quality tags inside <video> (e.g. 1080p, 720p, 480p, 360p, 240p)
                    var sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
                    for (var i = 0; i < sources.length; i++) {
                        var sEl = sources[i];
                        var sUrl = sEl.src || (sEl.getAttribute && (sEl.getAttribute('src') || sEl.getAttribute('data-src')));
                        var sType = (sEl.type || (sEl.getAttribute && sEl.getAttribute('type'))) || defaultMime;
                        var sLabel = (sEl.getAttribute && (sEl.getAttribute('label') || sEl.getAttribute('size') || sEl.getAttribute('res') || sEl.getAttribute('data-quality') || sEl.getAttribute('title'))) || '';
                        var sH = parseInt(sLabel, 10) || 0;
                        if (sUrl && sUrl.indexOf('blob:') !== 0) report(k, sUrl, sH > 0 ? 0 : w, sH > 0 ? sH : h, d, sType, '', sLabel, '', 0);
                    }
                    // Check attached hls.js / Plyr instance on element
                    try {
                        var hlsInst = el.hls || el._hls || el.__hls;
                        if (hlsInst && hlsInst.url) report('video', hlsInst.url, w, h, d, 'application/vnd.apple.mpegurl', '', '', '', 0);
                        if (hlsInst && Array.isArray(hlsInst.levels)) {
                            for (var lv = 0; lv < hlsInst.levels.length; lv++) {
                                var level = hlsInst.levels[lv];
                                var lUrl = (level && Array.isArray(level.url) && level.url[0]) || (level && level.url);
                                if (typeof lUrl === 'string') report('video', lUrl, level.width || 0, level.height || 0, d, 'application/vnd.apple.mpegurl', '', '', '', level.bitrate || 0);
                            }
                        }
                    } catch (he) {}
                    if (!el.__drIdmHooked) {
                        el.__drIdmHooked = true;
                        ['play', 'loadedmetadata'].forEach(function(ev) {
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
                                if (pl) walkParsedJson(pl, 0, { count: 0 }, '', 0);
                            }
                        }
                    } catch (e) {}
                    try {
                        if (window.videojs && window.videojs.players) {
                            var vPlayers = window.videojs.players;
                            var pKeys = Object.keys(vPlayers);
                            for (var vi = 0; vi < pKeys.length; vi++) {
                                var vp = vPlayers[pKeys[vi]];
                                if (!vp) continue;
                                if (typeof vp.currentSources === 'function') walkParsedJson(vp.currentSources(), 0, { count: 0 }, '', 0);
                                if (typeof vp.currentSrc === 'function') {
                                    var vs = vp.currentSrc();
                                    if (vs && vs.indexOf('blob:') !== 0) report('video', vs, 0, 0, 0, '', '', '', '', 0);
                                }
                            }
                        }
                    } catch (e) {}
                    try {
                        if (window.hls && window.hls.url) {
                            report('video', window.hls.url, 0, 0, 0, 'application/vnd.apple.mpegurl', '', '', '', 0);
                        }
                    } catch (e) {}
                }

                function scanRoot(root, depth) {
                    if (!root || (depth || 0) > 3 || !root.querySelectorAll) return;
                    var mediaList = root.querySelectorAll('video, audio');
                    for (var i = 0; i < mediaList.length; i++) inspectMediaEl(mediaList[i]);

                    var standaloneSources = root.querySelectorAll('source[src], source[data-src]');
                    for (var si = 0; si < standaloneSources.length; si++) {
                        var sEl = standaloneSources[si];
                        var su = sEl.src || sEl.getAttribute('src') || sEl.getAttribute('data-src');
                        var st = sEl.type || sEl.getAttribute('type') || '';
                        var sl = sEl.getAttribute('label') || sEl.getAttribute('size') || sEl.getAttribute('res') || '';
                        if (su && (looksLikeMediaUrl(su) || isServerMediaMime(st))) {
                            report('video', su, 0, parseInt(sl, 10) || 0, 0, st || 'video/mp4', '', sl, '', 0);
                        }
                    }

                    // Multi-quality download links (<a href="...1080p.mp4">دانلود کیفیت 1080p</a>)
                    var links = root.querySelectorAll('a[href], [data-video-url], [data-mp4], [data-hls], [data-stream-url]');
                    for (var k = 0; k < links.length && k < 320; k++) {
                        var lnk = links[k];
                        var cand = lnk.getAttribute('href') || lnk.getAttribute('data-video-url') || lnk.getAttribute('data-mp4') || lnk.getAttribute('data-hls') || lnk.getAttribute('data-stream-url') || '';
                        if (looksLikeMediaUrl(cand)) {
                            var lk = /\.(mp3|m4a|aac|ogg|opus|wav|flac)(\?|#|$)/i.test(cand) ? 'audio' : 'video';
                            var linkTxt = (lnk.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 32);
                            report(lk, cand, 0, 0, 0, '', '', linkTxt, '', 0);
                        }
                    }

                    // Battery-cool Shadow DOM scan: ONLY inspect custom elements (containing '-') instead of all <div>/<span>!
                    var allEls = root.getElementsByTagName ? root.getElementsByTagName('*') : [];
                    for (var e = 0; e < allEls.length && e < 450; e++) {
                        var tag = allEls[e].tagName;
                        if (tag && tag.indexOf('-') > 0 && allEls[e].shadowRoot) {
                            scanRoot(allEls[e].shadowRoot, (depth || 0) + 1);
                        }
                    }

                    var iframes = root.querySelectorAll('iframe');
                    for (var f = 0; f < iframes.length && f < 12; f++) {
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
                                    report(isAud ? 'audio' : 'video', u, 0, 0, 0, mh, '', '', '', 0);
                                }
                            }
                        }
                    } catch (pe) {}
                    var snapshot = netRing.slice();
                    for (var r = 0; r < snapshot.length; r++) {
                        var ni = snapshot[r];
                        if (ni && ni.url) {
                            report(ni.kind, ni.url, ni.width, ni.height, ni.duration, ni.mime, ni.cd, ni.quality, ni.title, ni.bitrate);
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
                                if (c && (c.indexOf('http') === 0 || c.indexOf('//') === 0)) report('video', c, 0, 0, 0, 'video/mp4', '', '', '', 0);
                            }
                            var scripts = document.querySelectorAll('script[type="application/json"], script[type="application/ld+json"], script#__NEXT_DATA__, script#__NUXT_DATA__');
                            for (var s = 0; s < scripts.length; s++) inspectResponseBodyText('', scripts[s].textContent);
                        }
                    }
                    scanPerformanceAndNetRing();
                    return JSON.stringify(batchOut);
                };

                if (!window.__drIdmInstalledV9) {
                    window.__drIdmInstalledV9 = true;

                    // 1. JSON.parse hook: extracts ALL video qualities from player JSON configs
                    try {
                        var origJsonParse = JSON.parse;
                        if (origJsonParse && !origJsonParse.__drIdm) {
                            var wrappedParse = function(text) {
                                var parsed = origJsonParse.apply(this, arguments);
                                if (internalBusy) return parsed;
                                try {
                                    if (typeof text === 'string' && text.length >= 24 && text.length <= 2000000) {
                                        if (text.indexOf('.m3u8') >= 0 || text.indexOf('.mp4') >= 0 || text.indexOf('.webm') >= 0 || text.indexOf('playback') >= 0 || text.indexOf('video_url') >= 0 || text.indexOf('sources') >= 0) {
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

                    // 2. Fetch hook + live #EXTM3U body capture
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
                                        report('video', reqUrl, 0, 0, 0, '', '', '', '', 0);
                                    }
                                } catch (e) {}
                                var p = origFetch.apply(this, arguments);
                                try {
                                    p.then(function(r) {
                                        if (!r || ranged) return;
                                        var finalUrl = r.url || reqUrl;
                                        var ct = (r.headers && r.headers.get('content-type')) || '';
                                        var cd = (r.headers && r.headers.get('content-disposition')) || '';
                                        var lowCt = ct.toLowerCase();
                                        var isM3u8 = lowCt.indexOf('mpegurl') >= 0 || (finalUrl && /\.m3u8(\?|#|$)/i.test(finalUrl));
                                        if (isM3u8) {
                                            r.clone().text().then(function(bodyTxt) {
                                                inspectResponseBodyText(finalUrl, bodyTxt);
                                            }).catch(function(){});
                                        } else if (finalUrl && (isServerMediaMime(ct) || hasMediaFilenameInCd(cd))) {
                                            report(lowCt.indexOf('audio/') === 0 ? 'audio' : 'video', finalUrl, 0, 0, 0, ct, cd, '', '', 0);
                                        } else {
                                            var cl = parseInt((r.headers && r.headers.get('content-length')) || '0', 10) || 0;
                                            if (cl <= 1800000 && (lowCt.indexOf('json') >= 0 || lowCt.indexOf('text') >= 0 || lowCt === '')) {
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

                    // 3. XMLHttpRequest hook + live #EXTM3U body capture
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
                                        report('video', this.__drUrl, 0, 0, 0, '', '', '', '', 0);
                                    }
                                    this.addEventListener('load', function() {
                                        try {
                                            if (this.__drRanged) return;
                                            var rUrl = this.responseURL || this.__drUrl || '';
                                            var rCt = (this.getResponseHeader && this.getResponseHeader('content-type')) || '';
                                            var rCd = (this.getResponseHeader && this.getResponseHeader('content-disposition')) || '';
                                            if ((this.responseType === '' || this.responseType === 'text') && typeof this.responseText === 'string' && this.responseText.slice(0, 64).trim().indexOf('#EXTM3U') === 0) {
                                                inspectResponseBodyText(rUrl, this.responseText);
                                            } else if (rUrl && (isServerMediaMime(rCt) || hasMediaFilenameInCd(rCd))) {
                                                report(rCt.toLowerCase().indexOf('audio/') === 0 ? 'audio' : 'video', rUrl, 0, 0, 0, rCt, rCd, '', '', 0);
                                            } else if (this.responseType === '' || this.responseType === 'text') {
                                                inspectResponseBodyText(rUrl, this.responseText);
                                            } else if (this.responseType === 'json' && this.response) {
                                                walkParsedJson(this.response, 0, { count: 0 }, '', 0);
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

                    // 5. Cross-origin iframe rescan listener + Battery-Cool 1200ms debounced MutationObserver
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
                                if (obsPending || document.hidden) return;
                                obsPending = true;
                                setTimeout(function() {
                                    obsPending = false;
                                    if (typeof document !== 'undefined' && document && !document.hidden) {
                                        scanRoot(document, 0);
                                        scanKnownPlayers();
                                    }
                                }, 1200);
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
