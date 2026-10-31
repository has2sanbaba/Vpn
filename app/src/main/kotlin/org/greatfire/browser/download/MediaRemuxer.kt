package org.greatfire.browser.download

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * v9.0 Ultra-Reliable Android Native A/V Remuxer & Hardware Audio Transcode Fallback
 * (`API 21` Android 5.0 through `API 35+` Android 16):
 *
 * Root causes of silent HLS fixed permanently:
 * 1. Builds a 100% clean `MediaFormat` (`buildCleanMp4AudioFormat`) without `KEY_IS_ADTS=1` or
 *    internal `MPEG2TSExtractor` keys that previously caused `MPEG4Writer` (`MediaMuxer.addTrack`)
 *    to reject the AAC track and silently write video-only MP4s.
 * 2. Never mutates the active read `MediaExtractor` when probing sample-0 ADTS headers (uses an
 *    isolated probe extractor).
 * 3. Normalizes concatenated CMAF `fMP4` `.m4s` fragments (`stripFmp4NonFragmentBoxes` removes
 *    inter-segment `styp`/`sidx`/`emsg` boxes so `[ftyp][moov][moof][mdat]...` reads end-to-end)
 *    and aligns MPEG-TS `.ts` segments to 188-byte `0x47` packet boundaries (`alignToTsSyncPacket`).
 * 4. Includes a fast hardware `MediaCodec` Audio->AAC-LC (`audio/mp4a-latm`) transcode fallback so
 *    HLS streams using MP3 (`audio/mpeg`), AC-3, E-AC-3, Vorbis, FLAC, or legacy Opus NEVER lose audio!
 * 5. Enforces a post-mux verification invariant: whenever input contained audio (muxed or separate),
 *    the output `.mp4` is verified via `inspectFileTracks(outputMp4File).hasAudio` before succeeding.
 */
object MediaRemuxer {

    private val ADTS_SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    private const val IO_BUFFER_BYTES = 512 * 1024

    data class TrackSummary(
        val hasVideo: Boolean,
        val hasAudio: Boolean
    )

    data class AdtsFrameHeader(
        val headerLength: Int,
        val frameLength: Int,
        val audioObjectType: Int,
        val sampleRateIndex: Int,
        val sampleRate: Int,
        val channelConfig: Int
    ) {
        fun buildCsd0(): ByteArray = buildAacCsd0(audioObjectType, sampleRateIndex, channelConfig)
    }

    /**
     * Pure JVM-testable ISO/IEC 14496-3 2-byte `AudioSpecificConfig` (`csd-0`) builder.
     */
    fun buildAacCsd0(audioObjectType: Int, sampleRateIndex: Int, channelConfig: Int): ByteArray {
        val obj = audioObjectType.coerceIn(1, 31)
        val sri = sampleRateIndex.coerceIn(0, 12)
        val ch = channelConfig.coerceIn(1, 7)
        val b0 = ((obj shl 3) or ((sri ushr 1) and 0x07)) and 0xFF
        val b1 = (((sri and 0x01) shl 7) or (ch shl 3)) and 0xFF
        return byteArrayOf(b0.toByte(), b1.toByte())
    }

    fun buildAacCsd0FromParams(sampleRate: Int, channelCount: Int, audioObjectType: Int = 2): ByteArray {
        val idx = ADTS_SAMPLE_RATES.indexOfFirst { it == sampleRate }.let { if (it >= 0) it else 4 }
        return buildAacCsd0(audioObjectType, idx, channelCount.coerceIn(1, 7))
    }

    /**
     * Pure JVM-testable ADTS header parser (`0xFFFx` syncword).
     */
    fun parseAdtsHeader(buf: ByteArray, offset: Int = 0, available: Int = buf.size - offset): AdtsFrameHeader? {
        if (available < 7 || offset < 0 || offset + 7 > buf.size) return null
        val b0 = buf[offset].toInt() and 0xFF
        val b1 = buf[offset + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return null

        val protectionAbsent = b1 and 0x01
        val headerLen = if (protectionAbsent != 0) 7 else 9
        if (available < headerLen) return null

        val b2 = buf[offset + 2].toInt() and 0xFF
        val b3 = buf[offset + 3].toInt() and 0xFF
        val b4 = buf[offset + 4].toInt() and 0xFF
        val b5 = buf[offset + 5].toInt() and 0xFF

        val profileObjType = ((b2 ushr 6) and 0x03) + 1
        val freqIdx = (b2 ushr 2) and 0x0F
        if (freqIdx >= ADTS_SAMPLE_RATES.size) return null
        val chanCfg = ((b2 and 0x01) shl 2) or ((b3 ushr 6) and 0x03)
        val frameLen = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 ushr 5) and 0x07)
        if (frameLen <= headerLen || frameLen > 16384) return null

        return AdtsFrameHeader(
            headerLength = headerLen,
            frameLength = frameLen,
            audioObjectType = profileObjType,
            sampleRateIndex = freqIdx,
            sampleRate = ADTS_SAMPLE_RATES[freqIdx],
            channelConfig = if (chanCfg == 0) 2 else chanCfg
        )
    }

    /**
     * Pure JVM-testable ISO BMFF `.m4s` segment cleaner: strips top-level `styp`, `sidx`, `emsg`,
     * and `prft` boxes from media fragments so `[ftyp][moov][moof][mdat][moof][mdat]...` parses
     * cleanly across all fragments in Android `MediaExtractor`.
     */
    fun stripFmp4NonFragmentBoxes(bytes: ByteArray): ByteArray {
        if (bytes.size < 16) return bytes
        var pos = 0
        var needsCopy = false
        while (pos + 8 <= bytes.size) {
            val size32 = readUint32BE(bytes, pos)
            val boxType = readAscii4(bytes, pos + 4)
            val boxLen: Long = when {
                size32 == 1L -> {
                    if (pos + 16 > bytes.size) return bytes
                    readUint64BE(bytes, pos + 8)
                }
                size32 == 0L -> (bytes.size - pos).toLong()
                else -> size32
            }
            if (boxLen < 8L || pos + boxLen > bytes.size.toLong()) {
                return bytes
            }
            if (boxType == "styp" || boxType == "sidx" || boxType == "emsg" || boxType == "prft") {
                needsCopy = true
            }
            pos += boxLen.toInt()
        }
        if (!needsCopy) return bytes

        val out = java.io.ByteArrayOutputStream(bytes.size)
        pos = 0
        while (pos + 8 <= bytes.size) {
            val size32 = readUint32BE(bytes, pos)
            val boxType = readAscii4(bytes, pos + 4)
            val boxLen = when {
                size32 == 1L -> readUint64BE(bytes, pos + 8).toInt()
                size32 == 0L -> bytes.size - pos
                else -> size32.toInt()
            }
            if (boxType != "styp" && boxType != "sidx" && boxType != "emsg" && boxType != "prft") {
                out.write(bytes, pos, boxLen)
            }
            pos += boxLen
        }
        val cleaned = out.toByteArray()
        return if (cleaned.isNotEmpty()) cleaned else bytes
    }

    /**
     * Pure JVM-testable MPEG-TS segment aligner: trims any leading non-TS prefix (e.g. stray text,
     * ID3, or PNG fake header Tricks used by anti-hotlink CDNs!) right up to the first genuine
     * 188-byte `0x47` TS packet cadence.
     */
    fun alignToTsSyncPacket(bytes: ByteArray): ByteArray {
        if (bytes.size < 376) return bytes
        if (bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()) return bytes
        val maxScan = minOf(bytes.size - 376, 8192)
        for (i in 0..maxScan) {
            if (bytes[i] == 0x47.toByte() &&
                bytes[i + 188] == 0x47.toByte() &&
                (i + 376 >= bytes.size || bytes[i + 376] == 0x47.toByte())
            ) {
                return if (i == 0) bytes else bytes.copyOfRange(i, bytes.size)
            }
        }
        return bytes
    }

    private fun readUint32BE(b: ByteArray, off: Int): Long {
        return ((b[off].toLong() and 0xFFL) shl 24) or
            ((b[off + 1].toLong() and 0xFFL) shl 16) or
            ((b[off + 2].toLong() and 0xFFL) shl 8) or
            (b[off + 3].toLong() and 0xFFL)
    }

    private fun readUint64BE(b: ByteArray, off: Int): Long {
        return (readUint32BE(b, off) shl 32) or (readUint32BE(b, off + 4) and 0xFFFFFFFFL)
    }

    private fun readAscii4(b: ByteArray, off: Int): String {
        return charArrayOf(
            (b[off].toInt() and 0xFF).toChar(),
            (b[off + 1].toInt() and 0xFF).toChar(),
            (b[off + 2].toInt() and 0xFF).toChar(),
            (b[off + 3].toInt() and 0xFF).toChar()
        ).concatToString()
    }

    fun inspectFileTracks(file: File): TrackSummary {
        if (!file.exists() || file.length() <= 0L) return TrackSummary(false, false)
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            var hasV = false
            var hasA = false
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasV = true
                if (mime.startsWith("audio/")) hasA = true
            }
            if (!hasA && !hasV) {
                val adts = peekFirstAdtsHeader(file)
                if (adts != null) hasA = true
            }
            TrackSummary(hasV, hasA)
        } catch (_: Throwable) {
            val adts = peekFirstAdtsHeader(file)
            TrackSummary(false, adts != null)
        } finally {
            runCatching { ex.release() }
        }
    }

    fun remuxTsToMp4(inputTsFile: File, outputMp4File: File): Boolean =
        remuxOrMergeToMp4(inputTsFile, null, outputMp4File)

    /**
     * Remuxes a single HLS stream OR merges a demuxed Video + Separate Audio stream into a
     * standard fast-play `.mp4`, GUARANTEEING audio preservation whenever audio exists in the input!
     */
    fun remuxOrMergeToMp4(
        primaryInputFile: File,
        separateAudioFile: File?,
        outputMp4File: File
    ): Boolean {
        if (!primaryInputFile.exists() || primaryInputFile.length() <= 0L) return false

        val hasSeparateAudio = separateAudioFile != null && separateAudioFile.exists() && separateAudioFile.length() > 0L
        val primaryTracks = inspectFileTracks(primaryInputFile)
        val expectedAudio = hasSeparateAudio || primaryTracks.hasAudio

        // Path A: Separate Video + Audio HLS renditions (#EXT-X-MEDIA:TYPE=AUDIO)
        if (hasSeparateAudio && separateAudioFile != null) {
            if (mergeSeparateVideoAndAudio(primaryInputFile, separateAudioFile, outputMp4File)) {
                val outTracks = inspectFileTracks(outputMp4File)
                if (!expectedAudio || outTracks.hasAudio) {
                    return true
                }
            }
            // Fallback A2: Hardware-transcode separate audio (e.g., MP3/EC-3/Opus/raw) -> AAC M4A, then merge!
            val tempAacFile = File(outputMp4File.parentFile, "${outputMp4File.nameWithoutExtension}.transcoded_audio.m4a")
            try {
                if (transcodeAudioTrackToTempM4a(separateAudioFile, tempAacFile)) {
                    if (mergeSeparateVideoAndAudio(primaryInputFile, tempAacFile, outputMp4File)) {
                        val outTracks = inspectFileTracks(outputMp4File)
                        if (outTracks.hasAudio) return true
                    }
                }
            } finally {
                runCatching { if (tempAacFile.exists()) tempAacFile.delete() }
            }
            // Do NOT fall through to silent video-only remux when separate audio was supplied unless video itself has audio!
            if (!primaryTracks.hasAudio) {
                return false
            }
        }

        // Path B: Single muxed container (.ts, fMP4, or pure ADTS .aac)
        if (remuxSingleContainerToMp4(primaryInputFile, outputMp4File)) {
            val outTracks = inspectFileTracks(outputMp4File)
            if (!expectedAudio || outTracks.hasAudio) {
                return true
            }
        }

        // Fallback B2: Input container had non-MP4-muxable audio (e.g., MPEG-TS with MP3/AC-3/Opus)!
        // Transcode ONLY the audio track to AAC-LC while keeping 100% lossless H.264/HEVC video!
        if (primaryTracks.hasVideo && primaryTracks.hasAudio) {
            val tempAacFile = File(outputMp4File.parentFile, "${outputMp4File.nameWithoutExtension}.mux_audio.m4a")
            try {
                if (transcodeAudioTrackToTempM4a(primaryInputFile, tempAacFile)) {
                    if (mergeSeparateVideoAndAudio(primaryInputFile, tempAacFile, outputMp4File)) {
                        val outTracks = inspectFileTracks(outputMp4File)
                        if (outTracks.hasVideo && outTracks.hasAudio) {
                            return true
                        }
                    }
                }
            } finally {
                runCatching { if (tempAacFile.exists()) tempAacFile.delete() }
            }
        }

        return false
    }

    private fun isMp4MuxableVideoMime(mime: String): Boolean =
        mime == "video/avc" ||
            mime == "video/hevc" ||
            mime == "video/mp4v-es" ||
            mime == "video/3gpp" ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (mime == "video/x-vnd.on2.vp9" || mime == "video/av01"))

    /**
     * Strictly lists audio MIME types that Android's `MPEG4Writer` (`MediaMuxer` MP4) genuinely accepts
     * across API levels. Note: `audio/mpeg` (MP3), `audio/ac3`, and `audio/eac3` are REJECTED by
     * `MPEG4Writer` and must go through `transcodeAudioTrackToTempM4a`!
     */
    private fun isDirectMp4MuxableAudioMime(mime: String): Boolean =
        mime == "audio/mp4a-latm" ||
            mime == "audio/3gpp" ||
            mime == "audio/amr-wb" ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mime == "audio/opus")

    /**
     * Builds a 100% clean, `MPEG4Writer`-safe audio `MediaFormat` WITHOUT `KEY_IS_ADTS=1` or
     * extractor-private keys, using an isolated `MediaExtractor` if sample-0 ADTS probing is needed.
     */
    private fun buildCleanMp4AudioFormat(
        sourceFile: File,
        trackIndex: Int,
        rawFormat: MediaFormat
    ): MediaFormat? {
        val mime = rawFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        if (!isDirectMp4MuxableAudioMime(mime)) return null

        if (mime != "audio/mp4a-latm") {
            return rawFormat
        }

        var sampleRate = runCatching { rawFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
        var channelCount = runCatching { rawFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(0)
        var csdBytes: ByteArray? = null

        if (rawFormat.containsKey("csd-0")) {
            val dup = runCatching { rawFormat.getByteBuffer("csd-0")?.duplicate() }.getOrNull()
            if (dup != null) {
                dup.rewind()
                if (dup.remaining() >= 2) {
                    val arr = ByteArray(dup.remaining())
                    dup.get(arr)
                    csdBytes = arr
                }
            }
        }

        // Probe first audio sample on an ISOLATED MediaExtractor so the caller's extractor state is untouched!
        if (csdBytes == null || sampleRate <= 0 || channelCount <= 0) {
            val probeEx = MediaExtractor()
            try {
                probeEx.setDataSource(sourceFile.absolutePath)
                if (trackIndex < probeEx.trackCount) {
                    probeEx.selectTrack(trackIndex)
                    val tmp = ByteBuffer.allocate(8192)
                    val n = probeEx.readSampleData(tmp, 0)
                    if (n >= 7) {
                        val arr = ByteArray(minOf(n, 64))
                        tmp.position(0)
                        tmp.get(arr)
                        val adts = parseAdtsHeader(arr, 0, arr.size)
                        if (adts != null) {
                            if (sampleRate <= 0) sampleRate = adts.sampleRate
                            if (channelCount <= 0) channelCount = adts.channelConfig
                            if (csdBytes == null) csdBytes = adts.buildCsd0()
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { probeEx.release() }
            }
        }

        if (sampleRate <= 0) sampleRate = 44100
        if (channelCount <= 0) channelCount = 2
        val finalCsd = csdBytes ?: buildAacCsd0FromParams(sampleRate, channelCount, 2)

        return MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(finalCsd))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 262144)
        }
    }

    private fun buildCleanMp4VideoFormat(rawFormat: MediaFormat): MediaFormat? {
        val mime = rawFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        if (!isMp4MuxableVideoMime(mime)) return null
        val width = runCatching { rawFormat.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
        val height = runCatching { rawFormat.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
        if (width <= 0 || height <= 0) return rawFormat
        if ((mime == "video/avc" || mime == "video/hevc") && !rawFormat.containsKey("csd-0")) {
            return null
        }

        val clean = MediaFormat.createVideoFormat(mime, width, height)
        for (csdKey in arrayOf("csd-0", "csd-1", "csd-2")) {
            if (rawFormat.containsKey(csdKey)) {
                val dup = runCatching { rawFormat.getByteBuffer(csdKey)?.duplicate() }.getOrNull()
                if (dup != null) {
                    dup.rewind()
                    if (dup.remaining() > 0) {
                        val copy = ByteArray(dup.remaining())
                        dup.get(copy)
                        clean.setByteBuffer(csdKey, ByteBuffer.wrap(copy))
                    }
                }
            }
        }
        runCatching { rawFormat.getInteger("rotation-degrees") }.getOrNull()?.let {
            clean.setInteger("rotation-degrees", it)
        }
        clean.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, IO_BUFFER_BYTES)
        return clean
    }

    private fun remuxSingleContainerToMp4(inputFile: File, outputMp4File: File): Boolean {
        val extractor = MediaExtractor()
        val extractorOk = try {
            extractor.setDataSource(inputFile.absolutePath)
            extractor.trackCount > 0
        } catch (_: Throwable) {
            false
        }

        if (!extractorOk) {
            runCatching { extractor.release() }
            return muxPureAdtsFileToMp4(inputFile, outputMp4File)
        }

        outputMp4File.parentFile?.mkdirs()
        if (outputMp4File.exists()) outputMp4File.delete()

        val muxer = try {
            MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (_: Throwable) {
            runCatching { extractor.release() }
            return false
        }

        var started = false
        return try {
            val trackMap = LinkedHashMap<Int, Int>()
            val isAacTrack = HashSet<Int>()
            var videoSelected = false
            var audioSelected = false
            var sourceHadAnyAudio = false

            for (i in 0 until extractor.trackCount) {
                val rawFormat = extractor.getTrackFormat(i)
                val mime = rawFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    sourceHadAnyAudio = true
                }
                if (mime.startsWith("video/") && !videoSelected) {
                    val cleanV = buildCleanMp4VideoFormat(rawFormat) ?: continue
                    val outIdx = runCatching { muxer.addTrack(cleanV) }.getOrNull() ?: continue
                    trackMap[i] = outIdx
                    videoSelected = true
                    runCatching { rawFormat.getInteger("rotation-degrees") }.getOrNull()?.let { rot ->
                        if (rot == 90 || rot == 180 || rot == 270) muxer.setOrientationHint(rot)
                    }
                } else if (mime.startsWith("audio/") && !audioSelected) {
                    val cleanA = buildCleanMp4AudioFormat(inputFile, i, rawFormat) ?: continue
                    val outIdx = runCatching { muxer.addTrack(cleanA) }.getOrNull() ?: continue
                    trackMap[i] = outIdx
                    audioSelected = true
                    if (mime == "audio/mp4a-latm") isAacTrack.add(i)
                }
            }

            // CRITICAL: Never silently drop an audio track when the source container had audio!
            if (sourceHadAnyAudio && !audioSelected) {
                return false
            }
            if (trackMap.isEmpty()) return false

            val firstPtsMap = HashMap<Int, Long>()
            for (inIdx in trackMap.keys) {
                extractor.selectTrack(inIdx)
                val t = extractor.sampleTime
                if (t >= 0L) firstPtsMap[inIdx] = t
                extractor.unselectTrack(inIdx)
            }
            for (inIdx in trackMap.keys) {
                extractor.selectTrack(inIdx)
            }
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val minPts = firstPtsMap.values.minOrNull() ?: 0L
            val maxPts = firstPtsMap.values.maxOrNull() ?: 0L
            val shareCommonBase = (maxPts - minPts) <= 5_000_000L

            muxer.start()
            started = true

            var buffer = ByteBuffer.allocateDirect(IO_BUFFER_BYTES)
            val adtsScratch = ByteArray(16)
            val info = MediaCodec.BufferInfo()
            val lastPtsByOut = HashMap<Int, Long>()
            var writtenAudioSamples = 0L

            while (true) {
                var sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                // Grow direct buffer on the rare 4K keyframe > 512 KB without permanently wasting 2 MB RAM
                if (sampleSize >= buffer.capacity() - 16 && buffer.capacity() < 4 * 1024 * 1024) {
                    buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                    sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                }

                val inTrack = extractor.sampleTrackIndex
                val outTrack = trackMap[inTrack]
                if (outTrack != null && sampleSize > 0) {
                    val rawPts = extractor.sampleTime.coerceAtLeast(0L)
                    val base = if (shareCommonBase) minPts else (firstPtsMap[inTrack] ?: 0L)
                    var pts = (rawPts - base).coerceAtLeast(0L)
                    val prev = lastPtsByOut[outTrack]
                    if (prev != null && pts <= prev) {
                        pts = prev + 1L
                    }
                    lastPtsByOut[outTrack] = pts

                    var sampleOffset = 0
                    var payloadSize = sampleSize
                    if (inTrack in isAacTrack && sampleSize >= 8) {
                        buffer.position(0)
                        buffer.get(adtsScratch, 0, minOf(sampleSize, adtsScratch.size))
                        buffer.position(0)
                        val adts = parseAdtsHeader(adtsScratch, 0, minOf(sampleSize, adtsScratch.size))
                        if (adts != null && adts.frameLength == sampleSize && sampleSize > adts.headerLength) {
                            sampleOffset = adts.headerLength
                            payloadSize = sampleSize - adts.headerLength
                        }
                    }

                    info.offset = sampleOffset
                    info.size = payloadSize
                    info.presentationTimeUs = pts
                    val rawFlags = extractor.sampleFlags
                    info.flags = if (inTrack in isAacTrack || (rawFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(outTrack, buffer, info)
                    if (inTrack in isAacTrack || (audioSelected && !videoSelected) || (audioSelected && extractor.getTrackFormat(inTrack).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true)) {
                        writtenAudioSamples++
                    }
                }
                extractor.advance()
            }

            muxer.stop()
            started = false
            if (sourceHadAnyAudio && writtenAudioSamples == 0L) {
                runCatching { outputMp4File.delete() }
                return false
            }
            outputMp4File.exists() && outputMp4File.length() > 512L
        } catch (_: Throwable) {
            runCatching { outputMp4File.delete() }
            false
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    private fun mergeSeparateVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputMp4File: File
    ): Boolean {
        val vExtractor = MediaExtractor()
        try {
            vExtractor.setDataSource(videoFile.absolutePath)
        } catch (_: Throwable) {
            runCatching { vExtractor.release() }
            return false
        }

        var vTrackIdx = -1
        var vRawFormat: MediaFormat? = null
        var vCleanFormat: MediaFormat? = null
        for (i in 0 until vExtractor.trackCount) {
            val f = vExtractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (m.startsWith("video/")) {
                val clean = buildCleanMp4VideoFormat(f) ?: continue
                vTrackIdx = i
                vRawFormat = f
                vCleanFormat = clean
                break
            }
        }
        if (vTrackIdx < 0 || vCleanFormat == null) {
            runCatching { vExtractor.release() }
            return false
        }

        var aExtractor: MediaExtractor? = MediaExtractor()
        var aTrackIdx = -1
        var aCleanFormat: MediaFormat? = null
        var aIsAac = false
        try {
            aExtractor!!.setDataSource(audioFile.absolutePath)
            for (i in 0 until aExtractor.trackCount) {
                val f = aExtractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (m.startsWith("audio/")) {
                    val cleanA = buildCleanMp4AudioFormat(audioFile, i, f) ?: continue
                    aTrackIdx = i
                    aCleanFormat = cleanA
                    aIsAac = (m == "audio/mp4a-latm")
                    break
                }
            }
        } catch (_: Throwable) {
        }

        var useRawAdtsFallback = false
        if (aTrackIdx < 0 || aCleanFormat == null) {
            runCatching { aExtractor?.release() }
            aExtractor = null
            val firstAdts = peekFirstAdtsHeader(audioFile)
            if (firstAdts != null) {
                aCleanFormat = MediaFormat.createAudioFormat(
                    "audio/mp4a-latm",
                    firstAdts.sampleRate,
                    firstAdts.channelConfig
                ).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(firstAdts.buildCsd0()))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                }
                useRawAdtsFallback = true
            } else {
                // Separate audio needs hardware MediaCodec transcoding in caller; NEVER return silent video here!
                runCatching { vExtractor.release() }
                return false
            }
        }

        outputMp4File.parentFile?.mkdirs()
        if (outputMp4File.exists()) outputMp4File.delete()

        val muxer = try {
            MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (_: Throwable) {
            runCatching { vExtractor.release() }
            runCatching { aExtractor?.release() }
            return false
        }

        var started = false
        return try {
            val vOutTrack = muxer.addTrack(vCleanFormat)
            val aOutTrack = muxer.addTrack(aCleanFormat!!)
            runCatching { vRawFormat?.getInteger("rotation-degrees") }.getOrNull()?.let { rot ->
                if (rot == 90 || rot == 180 || rot == 270) muxer.setOrientationHint(rot)
            }

            vExtractor.selectTrack(vTrackIdx)
            val vFirstPts = vExtractor.sampleTime.coerceAtLeast(0L)

            val aFirstPts = if (!useRawAdtsFallback && aExtractor != null) {
                aExtractor.selectTrack(aTrackIdx)
                aExtractor.sampleTime.coerceAtLeast(0L)
            } else {
                0L
            }

            val sharedClock = !useRawAdtsFallback && abs(vFirstPts - aFirstPts) <= 2_500_000L
            val vBasePts = if (sharedClock) minOf(vFirstPts, aFirstPts) else vFirstPts
            val aBasePts = if (sharedClock) minOf(vFirstPts, aFirstPts) else aFirstPts

            muxer.start()
            started = true

            var buf = ByteBuffer.allocateDirect(IO_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()

            // 1. Copy all video frames losslessly
            var lastVideoPts = -1L
            while (true) {
                var n = vExtractor.readSampleData(buf, 0)
                if (n < 0) break
                if (n >= buf.capacity() - 16 && buf.capacity() < 4 * 1024 * 1024) {
                    buf = ByteBuffer.allocateDirect(buf.capacity() * 2)
                    n = vExtractor.readSampleData(buf, 0)
                    if (n < 0) break
                }
                if (n > 0) {
                    var pts = (vExtractor.sampleTime.coerceAtLeast(0L) - vBasePts).coerceAtLeast(0L)
                    if (pts <= lastVideoPts) pts = lastVideoPts + 1L
                    lastVideoPts = pts

                    info.offset = 0
                    info.size = n
                    info.presentationTimeUs = pts
                    info.flags = if ((vExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(vOutTrack, buf, info)
                }
                vExtractor.advance()
            }

            val maxAudioPts = if (lastVideoPts > 0L) lastVideoPts + 1_200_000L else Long.MAX_VALUE
            var writtenAudioFrames = 0L

            // 2. Copy all audio frames
            if (useRawAdtsFallback) {
                writtenAudioFrames = writeAdtsFramesToMuxer(audioFile, muxer, aOutTrack, maxAudioPts)
            } else if (aExtractor != null) {
                val adtsScratch = ByteArray(16)
                var lastAudioPts = -1L
                while (true) {
                    val n = aExtractor.readSampleData(buf, 0)
                    if (n < 0) break
                    if (n > 0) {
                        var pts = (aExtractor.sampleTime.coerceAtLeast(0L) - aBasePts).coerceAtLeast(0L)
                        if (pts > maxAudioPts) break
                        if (pts <= lastAudioPts) pts = lastAudioPts + 1L
                        lastAudioPts = pts

                        var off = 0
                        var len = n
                        if (aIsAac && n >= 8) {
                            buf.position(0)
                            buf.get(adtsScratch, 0, minOf(n, adtsScratch.size))
                            buf.position(0)
                            val adts = parseAdtsHeader(adtsScratch, 0, minOf(n, adtsScratch.size))
                            if (adts != null && adts.frameLength == n && n > adts.headerLength) {
                                off = adts.headerLength
                                len = n - adts.headerLength
                            }
                        }

                        info.offset = off
                        info.size = len
                        info.presentationTimeUs = pts
                        info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                        muxer.writeSampleData(aOutTrack, buf, info)
                        writtenAudioFrames++
                    }
                    aExtractor.advance()
                }
            }

            muxer.stop()
            started = false
            if (writtenAudioFrames == 0L) {
                runCatching { outputMp4File.delete() }
                return false
            }
            outputMp4File.exists() && outputMp4File.length() > 1024L
        } catch (_: Throwable) {
            runCatching { outputMp4File.delete() }
            false
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { vExtractor.release() }
            runCatching { aExtractor?.release() }
        }
    }

    /**
     * Hardware `MediaCodec` Audio-Only Decode -> AAC-LC (`audio/mp4a-latm`) Encode fallback.
     * Handles HLS streams with MP3 (`audio/mpeg`), AC-3, E-AC-3, Vorbis, FLAC, or legacy Opus
     * on API 21–35 without touching or re-encoding a single video pixel!
     */
    private fun transcodeAudioTrackToTempM4a(sourceFile: File, outM4aFile: File): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrack = -1
            var inFormat: MediaFormat? = null
            var inMime = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (m.startsWith("audio/")) {
                    audioTrack = i
                    inFormat = f
                    inMime = m
                    break
                }
            }
            if (audioTrack < 0 || inFormat == null) return false

            extractor.selectTrack(audioTrack)
            val firstPts = extractor.sampleTime.coerceAtLeast(0L)

            val sampleRate = runCatching { inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(44100)
                .coerceIn(8000, 48000)
            val channelCount = runCatching { inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(2)
                .coerceIn(1, 2)

            decoder = MediaCodec.createDecoderByType(inMime).apply {
                configure(inFormat, null, null, 0)
                start()
            }

            val encFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channelCount == 1) 96_000 else 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm").apply {
                configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            outM4aFile.parentFile?.mkdirs()
            if (outM4aFile.exists()) outM4aFile.delete()
            muxer = MediaMuxer(outM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var muxTrackIdx = -1
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var lastMuxPts = -1L
            var writtenSamples = 0L

            while (!encoderDone) {
                // 1. Feed compressed audio into decoder
                if (!inputDone) {
                    val inBufIdx = decoder.dequeueInputBuffer(5_000L)
                    if (inBufIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inBufIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val sz = extractor.readSampleData(inBuf, 0)
                            if (sz < 0) {
                                decoder.queueInputBuffer(inBufIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = (extractor.sampleTime.coerceAtLeast(0L) - firstPts).coerceAtLeast(0L)
                                decoder.queueInputBuffer(inBufIdx, 0, sz, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. Drain PCM from decoder into AAC encoder
                if (!decoderDone) {
                    val decOutIdx = decoder.dequeueOutputBuffer(decInfo, 5_000L)
                    if (decOutIdx >= 0) {
                        val decOutBuf = decoder.getOutputBuffer(decOutIdx)
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (decOutBuf != null && decInfo.size > 0) {
                            decOutBuf.position(decInfo.offset)
                            decOutBuf.limit(decInfo.offset + decInfo.size)
                            while (decOutBuf.hasRemaining()) {
                                val encInIdx = encoder.dequeueInputBuffer(10_000L)
                                if (encInIdx < 0) break
                                val encInBuf = encoder.getInputBuffer(encInIdx) ?: break
                                encInBuf.clear()
                                val chunk = minOf(decOutBuf.remaining(), encInBuf.remaining())
                                val oldLimit = decOutBuf.limit()
                                decOutBuf.limit(decOutBuf.position() + chunk)
                                encInBuf.put(decOutBuf)
                                decOutBuf.limit(oldLimit)
                                encoder.queueInputBuffer(encInIdx, 0, chunk, decInfo.presentationTimeUs.coerceAtLeast(0L), 0)
                            }
                        }
                        decoder.releaseOutputBuffer(decOutIdx, false)
                        if (isEos) {
                            val eosIdx = encoder.dequeueInputBuffer(15_000L)
                            if (eosIdx >= 0) {
                                encoder.queueInputBuffer(eosIdx, 0, 0, decInfo.presentationTimeUs.coerceAtLeast(0L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                            decoderDone = true
                        }
                    }
                }

                // 3. Drain AAC frames from encoder into M4A muxer
                while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 5_000L)
                    if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            muxTrackIdx = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (encOutIdx >= 0) {
                        val encOutBuf = encoder.getOutputBuffer(encOutIdx)
                        val isCodecCfg = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isCodecCfg && encOutBuf != null && encInfo.size > 0 && muxerStarted && muxTrackIdx >= 0) {
                            var pts = encInfo.presentationTimeUs.coerceAtLeast(0L)
                            if (pts <= lastMuxPts) pts = lastMuxPts + 1L
                            lastMuxPts = pts
                            encInfo.presentationTimeUs = pts
                            muxer.writeSampleData(muxTrackIdx, encOutBuf, encInfo)
                            writtenSamples++
                        }
                        encoder.releaseOutputBuffer(encOutIdx, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                            break
                        }
                    } else {
                        break
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }
            writtenSamples > 0L && outM4aFile.exists() && outM4aFile.length() > 256L
        } catch (_: Throwable) {
            runCatching { outM4aFile.delete() }
            false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun muxPureAdtsFileToMp4(adtsFile: File, outputMp4File: File): Boolean {
        val first = peekFirstAdtsHeader(adtsFile) ?: return false
        outputMp4File.parentFile?.mkdirs()
        if (outputMp4File.exists()) outputMp4File.delete()

        val muxer = try {
            MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (_: Throwable) {
            return false
        }
        var started = false
        return try {
            val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", first.sampleRate, first.channelConfig)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(first.buildCsd0()))
            val outTrack = muxer.addTrack(fmt)
            muxer.start()
            started = true
            val frames = writeAdtsFramesToMuxer(adtsFile, muxer, outTrack, Long.MAX_VALUE)
            muxer.stop()
            started = false
            frames > 0 && outputMp4File.exists() && outputMp4File.length() > 256L
        } catch (_: Throwable) {
            runCatching { outputMp4File.delete() }
            false
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun peekFirstAdtsHeader(file: File): AdtsFrameHeader? {
        if (!file.exists() || file.length() < 16L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val probe = ByteArray(minOf(raf.length(), 16384L).toInt())
                val read = raf.read(probe)
                if (read < 9) return null
                for (pos in 0..(read - 9)) {
                    val hdr = parseAdtsHeader(probe, pos, read - pos) ?: continue
                    val nextPos = pos + hdr.frameLength
                    if (nextPos + 2 <= read) {
                        val nb0 = probe[nextPos].toInt() and 0xFF
                        val nb1 = probe[nextPos + 1].toInt() and 0xFF
                        if (nb0 != 0xFF || (nb1 and 0xF6) != 0xF0) continue
                    }
                    return hdr
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeAdtsFramesToMuxer(
        adtsFile: File,
        muxer: MediaMuxer,
        outTrack: Int,
        maxPtsUs: Long
    ): Long {
        val sampleBuf = ByteBuffer.allocateDirect(32 * 1024)
        val info = MediaCodec.BufferInfo()
        val ioBuf = ByteArray(128 * 1024)
        var validLen = 0
        var frameIndex = 0L
        var lastPts = -1L

        adtsFile.inputStream().use { input ->
            while (true) {
                if (validLen < 16384) {
                    val n = input.read(ioBuf, validLen, ioBuf.size - validLen)
                    if (n > 0) validLen += n
                }
                if (validLen < 7) break

                var syncPos = 0
                var hdr: AdtsFrameHeader? = null
                while (syncPos <= validLen - 7) {
                    val cand = parseAdtsHeader(ioBuf, syncPos, validLen - syncPos)
                    if (cand != null) {
                        hdr = cand
                        break
                    }
                    syncPos++
                }
                if (hdr == null) {
                    validLen = 0
                    continue
                }
                if (syncPos > 0) {
                    System.arraycopy(ioBuf, syncPos, ioBuf, 0, validLen - syncPos)
                    validLen -= syncPos
                }
                if (validLen < hdr.frameLength) {
                    val need = hdr.frameLength - validLen
                    var got = 0
                    while (got < need) {
                        val r = input.read(ioBuf, validLen + got, need - got)
                        if (r <= 0) break
                        got += r
                    }
                    validLen += got
                    if (validLen < hdr.frameLength) break
                }

                val payloadLen = hdr.frameLength - hdr.headerLength
                if (payloadLen > 0 && payloadLen <= sampleBuf.capacity()) {
                    var pts = (frameIndex * 1_024_000_000L) / hdr.sampleRate.toLong()
                    if (pts > maxPtsUs) break
                    if (pts <= lastPts) pts = lastPts + 1L
                    lastPts = pts

                    sampleBuf.clear()
                    sampleBuf.put(ioBuf, hdr.headerLength, payloadLen)
                    sampleBuf.flip()

                    info.offset = 0
                    info.size = payloadLen
                    info.presentationTimeUs = pts
                    info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                    muxer.writeSampleData(outTrack, sampleBuf, info)
                    frameIndex++
                }

                val rem = validLen - hdr.frameLength
                if (rem > 0) {
                    System.arraycopy(ioBuf, hdr.frameLength, ioBuf, 0, rem)
                }
                validLen = rem
            }
        }
        return frameIndex
    }
}
