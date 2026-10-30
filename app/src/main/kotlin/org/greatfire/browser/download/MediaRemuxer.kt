package org.greatfire.browser.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Lossless Android native stream-copy remuxer (`MediaExtractor` + `MediaMuxer`) compatible with
 * ALL Android versions from API 21 (Android 5.0 Lollipop) through API 35+ (Android 16).
 * Converts concatenated HLS MPEG-TS (`.ts`) streams (H.264/HEVC + AAC/MP3) into standard fast-play
 * `.mp4` containers without any re-encoding or external FFmpeg bloat.
 */
object MediaRemuxer {

    fun remuxTsToMp4(inputTsFile: File, outputMp4File: File): Boolean {
        if (!inputTsFile.exists() || inputTsFile.length() <= 0L) return false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputTsFile.absolutePath)
        } catch (_: Throwable) {
            runCatching { extractor.release() }
            return false
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
            val trackMap = HashMap<Int, Int>()
            var hasVideoTrack = false

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val supported = mime == "video/avc" ||
                    mime == "video/hevc" ||
                    mime == "audio/mp4a-latm" ||
                    mime == "audio/mpeg"
                if (!supported) continue

                if (mime.startsWith("video/") && !format.containsKey("csd-0")) continue
                if (mime == "audio/mp4a-latm" && !format.containsKey("csd-0")) continue

                extractor.selectTrack(i)
                val outTrack = muxer.addTrack(format)
                trackMap[i] = outTrack
                if (mime.startsWith("video/")) {
                    hasVideoTrack = true
                    runCatching { format.getInteger("rotation-degrees") }.getOrNull()?.let { rot ->
                        if (rot == 90 || rot == 180 || rot == 270) {
                            muxer.setOrientationHint(rot)
                        }
                    }
                }
            }

            if (trackMap.isEmpty() || (!hasVideoTrack && trackMap.size > 1)) {
                return false
            }

            muxer.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(1536 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastPtsByTrack = HashMap<Int, Long>()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val inTrack = extractor.sampleTrackIndex
                val outTrack = trackMap[inTrack]
                if (outTrack != null && sampleSize > 0) {
                    var pts = extractor.sampleTime.coerceAtLeast(0L)
                    val prevPts = lastPtsByTrack[outTrack]
                    if (prevPts != null && pts < prevPts) {
                        pts = prevPts + 1L
                    }
                    lastPtsByTrack[outTrack] = pts

                    info.offset = 0
                    info.size = sampleSize
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
            outputMp4File.exists() && outputMp4File.length() > 1024L
        } catch (_: Throwable) {
            runCatching { outputMp4File.delete() }
            false
        } finally {
            if (started) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }
}
