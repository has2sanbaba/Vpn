package org.greatfire.browser.media

import java.net.URI
import java.util.Locale

/**
 * Pure-Kotlin HLS (`.m3u8`) Master & Media Playlist parser for Dr Browser's IDM Stream Grabber.
 * Strictly supports standard unencrypted HTTP Live Streaming (`METHOD=NONE`) and explicitly flags
 * DRM / encrypted (`SAMPLE-AES`, `AES-128`, Widevine, PlayReady, FairPlay) playlists so protected
 * content is never circumvented.
 */
object HlsPlaylistParser {

    data class VariantStream(
        val url: String,
        val bandwidth: Long,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Double = 0.0,
        val codecs: String = "",
        val name: String = ""
    ) {
        val isAudioOnly: Boolean
            get() = height <= 0 && (
                codecs.isNotBlank() &&
                    !codecs.contains("avc", ignoreCase = true) &&
                    !codecs.contains("hev", ignoreCase = true) &&
                    !codecs.contains("hvc", ignoreCase = true) &&
                    !codecs.contains("vp09", ignoreCase = true) &&
                    !codecs.contains("av01", ignoreCase = true)
                )

        fun qualityBadge(): String = when {
            height >= 2160 -> "${height}p 4K"
            height >= 1440 -> "${height}p 2K"
            height >= 1080 -> "${height}p Full HD"
            height >= 720 -> "${height}p HD"
            height > 0 -> "${height}p"
            isAudioOnly -> "صوتی (Audio)"
            name.isNotBlank() -> name
            bandwidth > 0 -> "${bandwidth / 1000} Kbps"
            else -> "کیفیت خودکار"
        }

        fun bitrateLabel(): String {
            if (bandwidth <= 0L) return ""
            return if (bandwidth >= 1_000_000L) {
                String.format(Locale.US, "%.1f Mbps", bandwidth / 1_000_000.0)
            } else {
                "${bandwidth / 1000L} Kbps"
            }
        }
    }

    data class MediaSegment(
        val url: String,
        val durationSec: Double,
        val sequence: Long
    )

    data class ParsedMediaPlaylist(
        val segments: List<MediaSegment>,
        val initMapUrl: String? = null,
        val totalDurationSec: Double = 0.0,
        val isLiveStream: Boolean = false,
        val isDrmOrEncrypted: Boolean = false,
        val encryptionMethod: String = ""
    )

    private val ATTR_REGEX = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")

    fun looksLikeHlsManifest(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("#EXTM3U") ||
            trimmed.contains("#EXT-X-STREAM-INF") ||
            trimmed.contains("#EXTINF:")
    }

    fun isMasterPlaylist(text: String): Boolean =
        text.contains("#EXT-X-STREAM-INF")

    fun resolveRelativeUrl(baseUrl: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return runCatching {
            URI(baseUrl.trim()).resolve(trimmed).toString()
        }.getOrDefault(trimmed)
    }

    fun parseMasterPlaylist(text: String, baseUrl: String): List<VariantStream> {
        val variants = ArrayList<VariantStream>()
        val seenUrls = HashSet<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val attrs = parseAttributes(line.substringAfter(':', ""))
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trimStart().startsWith("#"))) {
                    j++
                }
                if (j < lines.size) {
                    val resolved = resolveRelativeUrl(baseUrl, lines[j])
                    if (resolved.isNotEmpty() && seenUrls.add(resolved)) {
                        val bw = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                            ?: attrs["BANDWIDTH"]?.toLongOrNull()
                            ?: 0L
                        val res = attrs["RESOLUTION"].orEmpty()
                        val w = res.substringBefore('x', "").toIntOrNull() ?: 0
                        val h = res.substringAfter('x', "").toIntOrNull() ?: 0
                        val fps = attrs["FRAME-RATE"]?.toDoubleOrNull() ?: 0.0
                        variants.add(
                            VariantStream(
                                url = resolved,
                                bandwidth = bw,
                                width = w,
                                height = h,
                                frameRate = fps,
                                codecs = attrs["CODECS"].orEmpty(),
                                name = attrs["NAME"].orEmpty()
                            )
                        )
                    }
                }
                i = j
            }
            i++
        }
        return variants.sortedWith(
            compareByDescending<VariantStream> { it.height }
                .thenByDescending { it.bandwidth }
        )
    }

    fun parseMediaPlaylist(text: String, baseUrl: String): ParsedMediaPlaylist {
        val segments = ArrayList<MediaSegment>()
        var initUrl: String? = null
        var seq = 0L
        var pendingDur = 0.0
        var totalDur = 0.0
        var hasEndList = false
        var encrypted = false
        var encMethod = ""

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                    seq = line.substringAfter(':', "0").trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-SESSION-KEY", ignoreCase = true) ||
                    line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':', ""))
                    val method = attrs["METHOD"]?.uppercase(Locale.US).orEmpty()
                    if (method.isNotEmpty() && method != "NONE") {
                        encrypted = true
                        encMethod = method
                    }
                }
                line.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':', ""))
                    val mapUri = attrs["URI"].orEmpty()
                    if (mapUri.isNotEmpty()) {
                        initUrl = resolveRelativeUrl(baseUrl, mapUri)
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingDur = line.substringAfter(':', "0")
                        .substringBefore(',')
                        .trim()
                        .toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    hasEndList = true
                }
                !line.startsWith("#") -> {
                    val segUrl = resolveRelativeUrl(baseUrl, line)
                    if (segUrl.isNotEmpty()) {
                        segments.add(
                            MediaSegment(
                                url = segUrl,
                                durationSec = pendingDur,
                                sequence = seq
                            )
                        )
                        totalDur += pendingDur
                        pendingDur = 0.0
                        seq++
                    }
                }
            }
        }

        val isLive = !hasEndList && segments.isNotEmpty() && segments.size <= 12
        return ParsedMediaPlaylist(
            segments = segments,
            initMapUrl = initUrl,
            totalDurationSec = totalDur,
            isLiveStream = isLive,
            isDrmOrEncrypted = encrypted,
            encryptionMethod = encMethod
        )
    }

    internal fun parseAttributes(attrString: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in ATTR_REGEX.findAll(attrString)) {
            val key = m.groupValues[1].uppercase(Locale.US)
            val unquoted = m.groupValues[3].ifEmpty { m.groupValues[2] }
            out[key] = unquoted.trim()
        }
        return out
    }
}
