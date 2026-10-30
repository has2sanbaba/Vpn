package org.greatfire.browser.media

import java.net.URI
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin RFC 8216 HLS (`.m3u8`) Master & Media Playlist parser for Dr Browser's IDM Grabber:
 * - Parses `#EXT-X-MEDIA:TYPE=AUDIO` separate audio renditions and pairs them automatically with
 *   `#EXT-X-STREAM-INF` `AUDIO="..."` video variants so demuxed HLS downloads NEVER save without sound!
 * - Supports `#EXT-X-MAP` fMP4 initialization segments, `#EXT-X-BYTERANGE` (`length[@offset]`),
 *   interleaved tags between `#EXTINF` and segment URIs, and Packed-Audio `ID3` header stripping.
 * - Supports standard `METHOD=AES-128` transport segment decryption (`AES/CBC/PKCS5Padding`) while
 *   strictly flagging genuine DRM (`SAMPLE-AES`, Widevine, PlayReady, FairPlay) so protected streams
 *   are never circumvented.
 */
object HlsPlaylistParser {

    data class ByteRangeSpec(
        val offset: Long,
        val length: Long
    ) {
        fun toHttpRangeHeader(): String = "bytes=$offset-${offset + length - 1L}"
    }

    data class AudioRendition(
        val groupId: String,
        val name: String,
        val language: String,
        val isDefault: Boolean,
        val autoSelect: Boolean,
        val channels: Int,
        val url: String?
    )

    data class VariantStream(
        val url: String,
        val bandwidth: Long,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Double = 0.0,
        val codecs: String = "",
        val name: String = "",
        val audioGroupId: String = "",
        val audioUrl: String? = null,
        val audioLanguage: String = ""
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

        val hasSeparateAudio: Boolean
            get() = !audioUrl.isNullOrBlank()

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
        val sequence: Long,
        val byteRange: ByteRangeSpec? = null,
        val aes128KeyUrl: String? = null,
        val aes128Iv: ByteArray? = null
    )

    data class ParsedMediaPlaylist(
        val segments: List<MediaSegment>,
        val initMapUrl: String? = null,
        val initMapByteRange: ByteRangeSpec? = null,
        val initMapAesKeyUrl: String? = null,
        val initMapAesIv: ByteArray? = null,
        val totalDurationSec: Double = 0.0,
        val hasEndList: Boolean = false,
        val isLiveStream: Boolean = false,
        val isDrmOrEncrypted: Boolean = false,
        val encryptionMethod: String = ""
    )

    private val ATTR_REGEX = Regex("""([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))""")

    fun looksLikeHlsManifest(text: String): Boolean {
        val trimmed = text.trimStart().removePrefix("\uFEFF")
        return trimmed.startsWith("#EXTM3U", ignoreCase = true) ||
            trimmed.contains("#EXT-X-STREAM-INF", ignoreCase = true) ||
            trimmed.contains("#EXTINF:", ignoreCase = true)
    }

    fun isMasterPlaylist(text: String): Boolean =
        text.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    fun resolveRelativeUrl(baseUrl: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            val scheme = baseUrl.substringBefore("://", "https").ifBlank { "https" }
            return "$scheme:$trimmed"
        }
        return runCatching {
            URI(baseUrl.trim()).resolve(trimmed).toString()
        }.getOrDefault(trimmed)
    }

    fun parseAudioRenditions(text: String, baseUrl: String): List<AudioRendition> {
        val out = ArrayList<AudioRendition>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (!line.startsWith("#EXT-X-MEDIA", ignoreCase = true)) continue
            val attrs = parseAttributes(line.substringAfter(':', ""))
            if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) continue
            val groupId = attrs["GROUP-ID"].orEmpty().trim()
            if (groupId.isEmpty()) continue
            val uriAttr = attrs["URI"].orEmpty().trim()
            val resolvedUri = if (uriAttr.isNotEmpty()) resolveRelativeUrl(baseUrl, uriAttr) else null
            val channels = attrs["CHANNELS"].orEmpty().substringBefore('/').trim().toIntOrNull() ?: 0
            out.add(
                AudioRendition(
                    groupId = groupId,
                    name = attrs["NAME"].orEmpty(),
                    language = attrs["LANGUAGE"].orEmpty(),
                    isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                    autoSelect = attrs["AUTOSELECT"].equals("YES", ignoreCase = true),
                    channels = channels,
                    url = resolvedUri
                )
            )
        }
        return out
    }

    private fun chooseBestAudioForGroup(
        audioByGroup: Map<String, List<AudioRendition>>,
        requestedGroupId: String,
        codecs: String
    ): AudioRendition? {
        val candidates = if (requestedGroupId.isNotBlank()) {
            audioByGroup[requestedGroupId.lowercase(Locale.US)].orEmpty()
        } else if (audioByGroup.size == 1 && !codecsContainAudio(codecs)) {
            audioByGroup.values.first()
        } else {
            emptyList()
        }
        if (candidates.isEmpty()) return null

        // If the chosen DEFAULT rendition has no URI, RFC 8216 states audio is already muxed in video!
        val sorted = candidates.sortedWith(
            compareByDescending<AudioRendition> { it.isDefault }
                .thenByDescending { it.autoSelect }
                .thenByDescending { it.channels }
        )
        val top = sorted.first()
        if (top.url.isNullOrBlank()) {
            // Check if another rendition in the same group provides an explicit URI only when top wasn't muxed default
            return if (top.isDefault) null else sorted.firstOrNull { !it.url.isNullOrBlank() }
        }
        return top
    }

    private fun codecsContainAudio(codecs: String): Boolean {
        if (codecs.isBlank()) return false
        val low = codecs.lowercase(Locale.US)
        return low.contains("mp4a") || low.contains("ac-3") || low.contains("ec-3") ||
            low.contains("opus") || low.contains("vorbis") || low.contains("flac") || low.contains("mp3")
    }

    fun parseMasterPlaylist(text: String, baseUrl: String): List<VariantStream> {
        val audioRenditions = parseAudioRenditions(text, baseUrl)
        val audioByGroup = audioRenditions.groupBy { it.groupId.lowercase(Locale.US) }

        val variants = ArrayList<VariantStream>()
        val seenUrls = HashSet<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val attrs = parseAttributes(line.substringAfter(':', ""))
                var j = i + 1
                while (j < lines.size) {
                    val nextTrim = lines[j].trim()
                    if (nextTrim.isEmpty()) {
                        j++
                        continue
                    }
                    // Stop if another #EXT-X-STREAM-INF starts (malformed playlist missing URI)
                    if (nextTrim.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        break
                    }
                    if (nextTrim.startsWith("#")) {
                        j++
                        continue
                    }
                    break
                }
                if (j < lines.size && !lines[j].trim().startsWith("#")) {
                    val resolved = resolveRelativeUrl(baseUrl, lines[j])
                    if (resolved.isNotEmpty() && seenUrls.add(resolved)) {
                        val bw = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                            ?: attrs["BANDWIDTH"]?.toLongOrNull()
                            ?: 0L
                        val res = attrs["RESOLUTION"].orEmpty()
                        val w = res.substringBefore('x', "").trim().toIntOrNull() ?: 0
                        val h = res.substringAfter('x', "").trim().toIntOrNull() ?: 0
                        val fps = attrs["FRAME-RATE"]?.toDoubleOrNull() ?: 0.0
                        val codecs = attrs["CODECS"].orEmpty()
                        val audioGroup = attrs["AUDIO"].orEmpty()
                        val pairedAudio = chooseBestAudioForGroup(audioByGroup, audioGroup, codecs)

                        variants.add(
                            VariantStream(
                                url = resolved,
                                bandwidth = bw,
                                width = w,
                                height = h,
                                frameRate = fps,
                                codecs = codecs,
                                name = attrs["NAME"].orEmpty(),
                                audioGroupId = audioGroup,
                                audioUrl = pairedAudio?.url,
                                audioLanguage = pairedAudio?.language.orEmpty()
                            )
                        )
                    }
                    i = j
                }
            }
            i++
        }
        return variants.sortedWith(
            compareByDescending<VariantStream> { !it.isAudioOnly }
                .thenByDescending { it.height }
                .thenByDescending { it.bandwidth }
        )
    }

    fun parseMediaPlaylist(text: String, baseUrl: String): ParsedMediaPlaylist {
        val segments = ArrayList<MediaSegment>()
        var initUrl: String? = null
        var initRange: ByteRangeSpec? = null
        var initKeyUrl: String? = null
        var initKeyIv: ByteArray? = null

        var seq = 0L
        var pendingDur = 0.0
        var pendingByteRange: ByteRangeSpec? = null
        val lastEndByUrl = HashMap<String, Long>()

        var currentAesKeyUrl: String? = null
        var currentAesIv: ByteArray? = null

        var totalDur = 0.0
        var hasEndList = false
        var drmProtected = false
        var drmMethod = ""

        for (rawLine in text.lines()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                    seq = line.substringAfter(':', "0").trim().toLongOrNull() ?: 0L
                }

                line.startsWith("#EXT-X-SESSION-KEY", ignoreCase = true) ||
                    line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':', ""))
                    val method = attrs["METHOD"]?.uppercase(Locale.US).orEmpty()
                    val keyFormat = attrs["KEYFORMAT"]?.lowercase(Locale.US).orEmpty()
                    val isDrmFormat = keyFormat.isNotEmpty() && keyFormat != "identity"

                    when {
                        method.isEmpty() || method == "NONE" -> {
                            currentAesKeyUrl = null
                            currentAesIv = null
                        }
                        method == "AES-128" && !isDrmFormat -> {
                            val keyUri = attrs["URI"].orEmpty()
                            if (keyUri.startsWith("skd://", ignoreCase = true)) {
                                drmProtected = true
                                drmMethod = "FAIRPLAY"
                            } else if (keyUri.isNotEmpty()) {
                                currentAesKeyUrl = resolveRelativeUrl(baseUrl, keyUri)
                                currentAesIv = attrs["IV"]?.let(::parseHexIv)
                                if (drmMethod.isEmpty()) {
                                    drmMethod = "AES-128"
                                }
                            }
                        }
                        else -> {
                            drmProtected = true
                            drmMethod = method.ifEmpty { keyFormat.uppercase(Locale.US) }
                        }
                    }
                }

                line.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':', ""))
                    val mapUri = attrs["URI"].orEmpty()
                    if (mapUri.isNotEmpty()) {
                        val resolvedMap = resolveRelativeUrl(baseUrl, mapUri)
                        initUrl = resolvedMap
                        initRange = attrs["BYTERANGE"]?.let { parseByteRangeAttr(it, 0L) }
                        initKeyUrl = currentAesKeyUrl
                        initKeyIv = currentAesIv
                    }
                }

                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) -> {
                    val spec = line.substringAfter(':', "").trim()
                    pendingByteRange = parseByteRangeAttr(spec, -1L)
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
                        val finalRange = pendingByteRange?.let { br ->
                            val actualOffset = if (br.offset >= 0L) {
                                br.offset
                            } else {
                                lastEndByUrl[segUrl] ?: 0L
                            }
                            lastEndByUrl[segUrl] = actualOffset + br.length
                            ByteRangeSpec(actualOffset, br.length)
                        }
                        segments.add(
                            MediaSegment(
                                url = segUrl,
                                durationSec = pendingDur,
                                sequence = seq,
                                byteRange = finalRange,
                                aes128KeyUrl = currentAesKeyUrl,
                                aes128Iv = currentAesIv
                            )
                        )
                        totalDur += pendingDur
                        pendingDur = 0.0
                        pendingByteRange = null
                        seq++
                    }
                }
            }
        }

        val isLive = !hasEndList && segments.isNotEmpty() && segments.size <= 10 && totalDur in 0.1..45.0
        return ParsedMediaPlaylist(
            segments = segments,
            initMapUrl = initUrl,
            initMapByteRange = initRange,
            initMapAesKeyUrl = initKeyUrl,
            initMapAesIv = initKeyIv,
            totalDurationSec = totalDur,
            hasEndList = hasEndList,
            isLiveStream = isLive,
            isDrmOrEncrypted = drmProtected,
            encryptionMethod = drmMethod
        )
    }

    private fun parseByteRangeAttr(rawSpec: String, defaultImplicitOffset: Long): ByteRangeSpec? {
        val clean = rawSpec.trim().removeSurrounding("\"")
        if (clean.isEmpty()) return null
        val lenPart = clean.substringBefore('@').trim().toLongOrNull() ?: return null
        if (lenPart <= 0L) return null
        val offPart = if ('@' in clean) {
            clean.substringAfter('@').trim().toLongOrNull() ?: return null
        } else {
            defaultImplicitOffset
        }
        return ByteRangeSpec(offset = offPart, length = lenPart)
    }

    internal fun parseHexIv(rawIv: String): ByteArray? {
        val clean = rawIv.trim().removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty() || clean.length > 32) return null
        val padded = clean.padStart(32, '0')
        return runCatching {
            ByteArray(16) { idx ->
                padded.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    /**
     * Standard RFC 8216 §5.2 AES-128-CBC segment decryption.
     */
    fun decryptAes128(
        cipherBytes: ByteArray,
        keyBytes: ByteArray,
        explicitIv: ByteArray?,
        sequence: Long
    ): ByteArray {
        require(keyBytes.size == 16) { "Invalid AES-128 key size (${keyBytes.size} bytes)" }
        val iv = if (explicitIv != null && explicitIv.size == 16) {
            explicitIv
        } else {
            ByteArray(16).also { buf ->
                for (k in 0 until 8) {
                    buf[15 - k] = ((sequence ushr (8 * k)) and 0xFFL).toByte()
                }
            }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherBytes)
    }

    /**
     * Strips leading RFC 8216 §3.4 Packed-Audio `ID3` timestamp metadata tags from raw `.aac`/`.mp3`/`.ac3`
     * HLS audio segments so concatenated audio segments form a valid continuous elementary stream.
     */
    fun stripPackedAudioId3Headers(bytes: ByteArray): ByteArray {
        if (bytes.size < 12) return bytes
        var offset = 0
        while (offset + 10 <= bytes.size &&
            bytes[offset] == 0x49.toByte() &&     // 'I'
            bytes[offset + 1] == 0x44.toByte() && // 'D'
            bytes[offset + 2] == 0x33.toByte()    // '3'
        ) {
            val flags = bytes[offset + 5].toInt() and 0xFF
            val b6 = bytes[offset + 6].toInt() and 0x7F
            val b7 = bytes[offset + 7].toInt() and 0x7F
            val b8 = bytes[offset + 8].toInt() and 0x7F
            val b9 = bytes[offset + 9].toInt() and 0x7F
            val tagBodyLen = (b6 shl 21) or (b7 shl 14) or (b8 shl 7) or b9
            val footerLen = if ((flags and 0x10) != 0) 10 else 0
            val nextOffset = offset + 10 + tagBodyLen + footerLen
            if (nextOffset <= offset || nextOffset >= bytes.size) break
            offset = nextOffset
        }
        if (offset == 0) return bytes
        // Verify payload starts with ADTS/MP3 sync (0xFF) or AC-3/E-AC-3 sync (0x0B 0x77)
        val p0 = bytes[offset].toInt() and 0xFF
        val p1 = if (offset + 1 < bytes.size) bytes[offset + 1].toInt() and 0xFF else 0
        return if (p0 == 0xFF || (p0 == 0x0B && p1 == 0x77)) {
            bytes.copyOfRange(offset, bytes.size)
        } else {
            bytes
        }
    }

    internal fun parseAttributes(attrString: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in ATTR_REGEX.findAll(attrString)) {
            val key = m.groupValues[1].uppercase(Locale.US)
            val value = if (m.groups[2] != null) m.groupValues[2] else m.groupValues[3]
            out[key] = value.trim()
        }
        return out
    }
}
