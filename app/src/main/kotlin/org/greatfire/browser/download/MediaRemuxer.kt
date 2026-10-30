package org.greatfire.browser.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Lossless Android native stream-copy remuxer & A/V merger (`MediaExtractor` + `MediaMuxer` +
 * pure-Kotlin ADTS AAC fallback) compatible with ALL Android versions from API 21 (Android 5.0)
 * through API 35+ (Android 16):
 * 1. Remuxes single-stream MPEG-TS (`.ts`) or Fragmented MP4 (`fMP4` `.m4s`) into fast-seeking `.mp4`.
 * 2. Merges demuxed HLS Video + `#EXT-X-MEDIA:TYPE=AUDIO` separate Audio streams (`.ts`, `fMP4`,
 *    `.m4a`, or Packed-Audio `.aac` ADTS) into a single `.mp4` with synchronized sound!
 * 3. Synthesizes missing AAC `csd-0` (`AudioSpecificConfig`) and strips 7/9-byte ADTS headers
 *    automatically so MPEG-TS / ADTS audio tracks are NEVER dropped silently!
 */
object MediaRemuxer {

    private val ADTS_SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

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
        // 12-bit syncword 0xFFF and layer bits (b1 & 0x06) == 0x00
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
     * standard fast-play `.mp4` with full audio preservation.
     */
    fun remuxOrMergeToMp4(
        primaryInputFile: File,
        separateAudioFile: File?,
        outputMp4File: File
    ): Boolean {
        if (!primaryInputFile.exists() || primaryInputFile.length() <= 0L) return false
        if (separateAudioFile != null && separateAudioFile.exists() && separateAudioFile.length() > 0L) {
            if (mergeSeparateVideoAndAudio(primaryInputFile, separateAudioFile, outputMp4File)) {
                return true
            }
        }
        return remuxSingleContainerToMp4(primaryInputFile, outputMp4File)
    }

    private fun isMp4MuxableVideoMime(mime: String): Boolean =
        mime == "video/avc" ||
            mime == "video/hevc" ||
            mime == "video/mp4v-es" ||
            mime == "video/3gpp" ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (mime == "video/x-vnd.on2.vp9" || mime == "video/av01"))

    private fun isMp4MuxableAudioMime(mime: String): Boolean =
        mime == "audio/mp4a-latm" ||
            mime == "audio/mpeg" ||
            mime == "audio/3gpp" ||
            mime == "audio/amr-wb" ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mime == "audio/opus")

    private fun ensureAudioCsdIfNeeded(
        extractor: MediaExtractor,
        trackIndex: Int,
        format: MediaFormat
    ): Boolean {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime != "audio/mp4a-latm") return true
        if (format.containsKey("csd-0")) {
            val existing = runCatching { format.getByteBuffer("csd-0") }.getOrNull()
            if (existing != null && existing.remaining() >= 2) return true
        }

        // Peek first audio sample to see if it carries an ADTS header; otherwise synthesize from sampleRate/channels
        val tempEx = MediaExtractor()
        var synthesized: ByteArray? = null
        try {
            // Try reading from a peek buffer on the already-configured extractor if possible, else params
            val sr = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48000)
            val ch = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            extractor.selectTrack(trackIndex)
            val peekBuf = ByteBuffer.allocate(8192)
            val n = extractor.readSampleData(peekBuf, 0)
            if (n >= 7) {
                val arr = ByteArray(minOf(n, 64))
                peekBuf.position(0)
                peekBuf.get(arr, 0, arr.size)
                val adts = parseAdtsHeader(arr, 0, arr.size)
                if (adts != null) {
                    synthesized = adts.buildCsd0()
                }
            }
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            extractor.unselectTrack(trackIndex)
            if (synthesized == null && sr > 0 && ch > 0) {
                synthesized = buildAacCsd0FromParams(sr, ch)
            }
        } catch (_: Throwable) {
            runCatching { extractor.unselectTrack(trackIndex) }
            val sr = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48000)
            val ch = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            synthesized = buildAacCsd0FromParams(sr, ch)
        } finally {
            runCatching { tempEx.release() }
        }

        val csd = synthesized ?: return false
        // Clear KEY_IS_ADTS before handing format to MediaMuxer (MP4 muxer expects raw AAC frames + csd-0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { format.removeKey(MediaFormat.KEY_IS_ADTS) }
        } else {
            runCatching { format.setInteger(MediaFormat.KEY_IS_ADTS, 0) }
        }
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        return true
    }

    private fun remuxSingleContainerToMp4(inputFile: File, outputMp4File: File): Boolean {
        val extractor = MediaExtractor()
        var extractorOk = false
        try {
            extractor.setDataSource(inputFile.absolutePath)
            extractorOk = extractor.trackCount > 0
        } catch (_: Throwable) {
            runCatching { extractor.release() }
        }

        // Pure ADTS .aac audio-only fallback when MediaExtractor cannot open raw ADTS directly
        if (!extractorOk) {
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

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && !videoSelected && isMp4MuxableVideoMime(mime)) {
                    if ((mime == "video/avc" || mime == "video/hevc") && !format.containsKey("csd-0")) continue
                    val outIdx = runCatching { muxer.addTrack(format) }.getOrNull() ?: continue
                    trackMap[i] = outIdx
                    videoSelected = true
                    runCatching { format.getInteger("rotation-degrees") }.getOrNull()?.let { rot ->
                        if (rot == 90 || rot == 180 || rot == 270) muxer.setOrientationHint(rot)
                    }
                } else if (mime.startsWith("audio/") && !audioSelected && isMp4MuxableAudioMime(mime)) {
                    if (!ensureAudioCsdIfNeeded(extractor, i, format)) continue
                    val outIdx = runCatching { muxer.addTrack(format) }.getOrNull() ?: continue
                    trackMap[i] = outIdx
                    audioSelected = true
                    if (mime == "audio/mp4a-latm") isAacTrack.add(i)
                }
            }

            if (trackMap.isEmpty()) return false

            // Determine initial PTS across selected tracks for zero-based timeline normalization
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

            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val adtsScratch = ByteArray(16)
            val info = MediaCodec.BufferInfo()
            val lastPtsByOut = HashMap<Int, Long>()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

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
                    info.flags = if ((rawFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(outTrack, buffer, info)
                }
                extractor.advance()
            }

            muxer.stop()
            started = false
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
        var vFormat: MediaFormat? = null
        for (i in 0 until vExtractor.trackCount) {
            val f = vExtractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (m.startsWith("video/") && isMp4MuxableVideoMime(m)) {
                if ((m == "video/avc" || m == "video/hevc") && !f.containsKey("csd-0")) continue
                vTrackIdx = i
                vFormat = f
                break
            }
        }
        if (vTrackIdx < 0 || vFormat == null) {
            runCatching { vExtractor.release() }
            return false
        }

        // Open audio via MediaExtractor first; fall back to pure-Kotlin AdtsStreamParser for raw .aac!
        var aExtractor: MediaExtractor? = MediaExtractor()
        var aTrackIdx = -1
        var aFormat: MediaFormat? = null
        var aIsAac = false
        try {
            aExtractor!!.setDataSource(audioFile.absolutePath)
            for (i in 0 until aExtractor.trackCount) {
                val f = aExtractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (m.startsWith("audio/") && isMp4MuxableAudioMime(m)) {
                    if (!ensureAudioCsdIfNeeded(aExtractor, i, f)) continue
                    aTrackIdx = i
                    aFormat = f
                    aIsAac = (m == "audio/mp4a-latm")
                    break
                }
            }
        } catch (_: Throwable) {
        }

        var useRawAdtsFallback = false
        if (aTrackIdx < 0 || aFormat == null) {
            runCatching { aExtractor?.release() }
            aExtractor = null
            val firstAdts = peekFirstAdtsHeader(audioFile)
            if (firstAdts != null) {
                val fmt = MediaFormat.createAudioFormat(
                    "audio/mp4a-latm",
                    firstAdts.sampleRate,
                    firstAdts.channelConfig
                )
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(firstAdts.buildCsd0()))
                aFormat = fmt
                useRawAdtsFallback = true
            } else {
                // Separate audio unreadable; at least remux video cleanly
                runCatching { vExtractor.release() }
                return remuxSingleContainerToMp4(videoFile, outputMp4File)
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
            val vOutTrack = muxer.addTrack(vFormat)
            val aOutTrack = muxer.addTrack(aFormat!!)
            runCatching { vFormat.getInteger("rotation-degrees") }.getOrNull()?.let { rot ->
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

            // Preserve sub-frame lip-sync when both streams share the same encoder PTS timeline;
            // normalize independently when clocks come from unrelated epochs (e.g., TS 90kHz vs 0-based ADTS).
            val sharedClock = !useRawAdtsFallback && kotlin.math.abs(vFirstPts - aFirstPts) <= 2_500_000L
            val vBasePts = if (sharedClock) minOf(vFirstPts, aFirstPts) else vFirstPts
            val aBasePts = if (sharedClock) minOf(vFirstPts, aFirstPts) else aFirstPts

            muxer.start()
            started = true

            val buf = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            // 1. Copy all video frames and record total video timeline duration
            var lastVideoPts = -1L
            while (true) {
                val n = vExtractor.readSampleData(buf, 0)
                if (n < 0) break
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

            val maxAudioPts = if (lastVideoPts > 0L) lastVideoPts + 750_000L else Long.MAX_VALUE

            // 2. Copy all audio frames (via MediaExtractor or pure-Kotlin ADTS parser)
            if (useRawAdtsFallback) {
                writeAdtsFramesToMuxer(audioFile, muxer, aOutTrack, maxAudioPts)
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
                    }
                    aExtractor.advance()
                }
            }

            muxer.stop()
            started = false
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
