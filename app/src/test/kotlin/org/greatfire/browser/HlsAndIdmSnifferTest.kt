package org.greatfire.browser

import org.greatfire.browser.download.DownloadEngine
import org.greatfire.browser.download.MediaRemuxer
import org.greatfire.browser.media.HlsPlaylistParser
import org.greatfire.browser.media.SniffedMediaKind
import org.greatfire.browser.media.VideoStreamSniffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsAndIdmSnifferTest {

    @Test
    fun parsesHlsMasterPlaylistAndSortsByQualityDescending() {
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=850000,RESOLUTION=640x360,NAME="360p"
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4800000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            https://cdn.example.com/live/1080p/playlist.m3u8?token=abc
            #EXT-X-STREAM-INF:BANDWIDTH=2100000,RESOLUTION=1280x720
            ../720p/prog.m3u8
        """.trimIndent()

        assertTrue(HlsPlaylistParser.looksLikeHlsManifest(masterM3u8))
        assertTrue(HlsPlaylistParser.isMasterPlaylist(masterM3u8))

        val variants = HlsPlaylistParser.parseMasterPlaylist(
            masterM3u8,
            "https://video.example.com/streams/master/main.m3u8"
        )
        assertEquals(3, variants.size)

        assertEquals(1080, variants[0].height)
        assertEquals("1080p Full HD", variants[0].qualityBadge())
        assertEquals("https://cdn.example.com/live/1080p/playlist.m3u8?token=abc", variants[0].url)

        assertEquals(720, variants[1].height)
        assertEquals("720p HD", variants[1].qualityBadge())
        assertEquals("https://video.example.com/streams/720p/prog.m3u8", variants[1].url)

        assertEquals(360, variants[2].height)
        assertEquals("https://video.example.com/streams/master/360p/index.m3u8", variants[2].url)
    }

    @Test
    fun pairsSeparateExtXMediaAudioRenditionsWithVideoVariants() {
        val splitAudioMaster = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-aac",NAME="English",LANGUAGE="en",AUTOSELECT=YES,DEFAULT=NO,URI="audio/en/index.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-aac",NAME="Persian",LANGUAGE="fa",AUTOSELECT=YES,DEFAULT=YES,CHANNELS="2",URI="audio/fa/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud-aac"
            video/1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aud-aac"
            video/720p/index.m3u8
        """.trimIndent()

        val baseUrl = "https://cdn.cinema.example/vod/movie42/master.m3u8"
        val renditions = HlsPlaylistParser.parseAudioRenditions(splitAudioMaster, baseUrl)
        assertEquals(2, renditions.size)

        val variants = HlsPlaylistParser.parseMasterPlaylist(splitAudioMaster, baseUrl)
        assertEquals(2, variants.size)
        assertTrue(variants[0].hasSeparateAudio)
        assertEquals("https://cdn.cinema.example/vod/movie42/audio/fa/index.m3u8", variants[0].audioUrl)
        assertEquals("fa", variants[0].audioLanguage)
        assertEquals("https://cdn.cinema.example/vod/movie42/video/1080p/index.m3u8", variants[0].url)
        assertEquals("https://cdn.cinema.example/vod/movie42/audio/fa/index.m3u8", variants[1].audioUrl)
    }

    @Test
    fun parsesAes128AndByterangeSegmentsAndStripsPackedAudioId3() {
        val mediaM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA-SEQUENCE:10
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="720@0"
            #EXT-X-KEY:METHOD=AES-128,URI="keys/aes.key"
            #EXTINF:4.000,
            #EXT-X-BYTERANGE:5000@720
            main.mp4
            #EXTINF:4.000,
            #EXT-X-BYTERANGE:6200
            main.mp4
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsPlaylistParser.parseMediaPlaylist(
            mediaM3u8,
            "https://edge.example.org/fmp4/v1/prog.m3u8"
        )
        // AES-128 is standard transport encryption (supported!), NOT unsupported DRM
        assertFalse(parsed.isDrmOrEncrypted)
        assertEquals("AES-128", parsed.encryptionMethod)
        assertEquals("https://edge.example.org/fmp4/v1/main.mp4", parsed.initMapUrl)
        assertEquals("bytes=0-719", parsed.initMapByteRange!!.toHttpRangeHeader())
        assertEquals(2, parsed.segments.size)
        assertEquals(10L, parsed.segments[0].sequence)
        assertEquals("https://edge.example.org/fmp4/v1/keys/aes.key", parsed.segments[0].aes128KeyUrl)
        assertEquals("bytes=720-5719", parsed.segments[0].byteRange!!.toHttpRangeHeader())
        // Implicit offset advances cleanly from 720 + 5000 = 5720!
        assertEquals("bytes=5720-11919", parsed.segments[1].byteRange!!.toHttpRangeHeader())

        // Verify AES-128 decryption with RFC 8216 sequence-derived big-endian IV
        val key = ByteArray(16) { (it + 1).toByte() }
        val expectedIv = ByteArray(16).apply { this[15] = 10 }
        val plainPayload = "MPEGTS-VIDEO-PAYLOAD-SEGMENT-10-TEST".toByteArray(Charsets.UTF_8)
        val encCipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(expectedIv))
        }
        val cipherBytes = encCipher.doFinal(plainPayload)
        val decrypted = HlsPlaylistParser.decryptAes128(cipherBytes, key, null, sequence = 10L)
        assertArrayEquals(plainPayload, decrypted)

        // Verify Packed-Audio ID3 stripping + ADTS header & AAC csd-0 generation
        // ADTS header for AAC-LC (profile=1 -> objType=2), 48kHz (idx=3), stereo (ch=2), frameLen=11 bytes (7 hdr + 4 raw)
        val adtsFrame = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x4C.toByte(), 0x80.toByte(),
            0x01.toByte(), 0x7F.toByte(), 0xFC.toByte(),
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()
        )
        val id3Header = byteArrayOf(
            0x49, 0x44, 0x33, 0x04, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x04, // synchsafe body length = 4 bytes
            0x01, 0x02, 0x03, 0x04
        )
        val packedSegment = id3Header + adtsFrame
        val stripped = HlsPlaylistParser.stripPackedAudioId3Headers(packedSegment)
        assertArrayEquals(adtsFrame, stripped)

        val parsedAdts = MediaRemuxer.parseAdtsHeader(stripped)
        assertNotNull(parsedAdts)
        assertEquals(48000, parsedAdts!!.sampleRate)
        assertEquals(2, parsedAdts.channelConfig)
        assertEquals(11, parsedAdts.frameLength)
        // AAC-LC (2), 48kHz (3), Stereo (2) => 0x11, 0x90
        assertArrayEquals(byteArrayOf(0x11.toByte(), 0x90.toByte()), parsedAdts.buildCsd0())

        // True DRM (SAMPLE-AES) MUST still be flagged and rejected
        val drmManifest = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://drm.provider/key"
            #EXTINF:4.0,
            enc_01.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val drmParsed = HlsPlaylistParser.parseMediaPlaylist(drmManifest, "https://drm.example.org/stream.m3u8")
        assertTrue(drmParsed.isDrmOrEncrypted)
        assertEquals("SAMPLE-AES", drmParsed.encryptionMethod)
    }

    @Test
    fun persistsShardAndHlsProgressForTruePauseResumeWithoutReset() {
        val tmpDir = Files.createTempDirectory("gf_resume_test").toFile()
        try {
            // 1. Multi-connection HTTP Range shard persistence
            val staging = File(tmpDir, "movie_1080p.mp4.part")
            val totalBytes = 10_000_000L
            val shards = DownloadEngine.buildParallelShards(totalBytes, 4)
            assertEquals(4, shards.size)
            assertEquals(0L, shards[0].start)
            assertEquals(2_499_999L, shards[0].endInclusive)
            assertEquals(9_999_999L, shards[3].endInclusive)

            val plan = DownloadEngine.ParallelShardPlan(
                totalBytes = totalBytes,
                finalMime = "video/mp4",
                shards = shards
            )
            DownloadEngine.saveShardPlan(staging, plan)

            // Simulate user pausing after shard 0 downloaded 1.2 MB and shard 2 downloaded 800 KB
            DownloadEngine.partFileFor(staging, 0).writeBytes(ByteArray(1_200_000))
            DownloadEngine.partFileFor(staging, 2).writeBytes(ByteArray(800_000))

            val loaded = DownloadEngine.loadShardPlan(staging)
            assertNotNull(loaded)
            assertEquals(totalBytes, loaded!!.totalBytes)
            assertEquals("video/mp4", loaded.finalMime)
            assertEquals(4, loaded.shards.size)

            // Exact persisted bytes MUST equal 2,000,000 (NOT 0 and NOT 10,000,000!)
            assertEquals(2_000_000L, DownloadEngine.computePersistedBytesForStaging(staging))

            DownloadEngine.cleanupStagingArtifacts(staging, deleteTargetFile = true)
            assertNull(DownloadEngine.loadShardPlan(staging))

            // 2. Segmented HLS .done checkpoint persistence
            val hlsStaging = File(tmpDir, "lecture_hls.mp4.part")
            val hlsDir = DownloadEngine.hlsStagingDirFor(hlsStaging).apply { mkdirs() }
            File(hlsDir, "v_seg_000000.done").writeBytes(ByteArray(350_000))
            File(hlsDir, "a_seg_000000.done").writeBytes(ByteArray(64_000))
            File(hlsDir, "v_seg_000001.tmp").writeBytes(ByteArray(99_999)) // incomplete .tmp ignored!

            assertEquals(414_000L, DownloadEngine.computePersistedBytesForStaging(hlsStaging))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun rebuiltVideoSnifferSuppressesChildSubPlaylistsAndAdmitsExtensionlessCdnMedia() {
        val tabId = "test_tab_v8_discovery"
        VideoStreamSniffer.clearTab(tabId)

        // 1. Ad beacons & HLS/DASH micro-segments are strictly rejected
        assertNull(
            VideoStreamSniffer.recordCandidate(
                tabId = tabId,
                rawUrl = "https://googleads.g.doubleclick.net/pagead/ad_video.mp4",
                pageUrl = "https://site.example.com/watch"
            )
        )
        assertNull(
            VideoStreamSniffer.recordCandidate(
                tabId = tabId,
                rawUrl = "https://cdn.example.com/hls/segment_00042.m4s",
                pageUrl = "https://site.example.com/watch"
            )
        )

        // 2. Extensionless CDN download URL admitted via Content-Disposition / server video MIME!
        val cdnItem = VideoStreamSniffer.recordCandidate(
            tabId = tabId,
            rawUrl = "https://edge-dl.example.net/v2/stream?token=xyz987",
            pageUrl = "https://cinema.example.net/watch/55",
            pageTitle = "قسمت پنجم",
            mimeHint = "video/mp4",
            contentDisposition = "attachment; filename=\"Episode_05_1080p.mp4\"",
            qualityHint = "1080p"
        )
        assertNotNull(cdnItem)
        assertEquals(SniffedMediaKind.VIDEO, cdnItem!!.kind)

        // 3. Raw Master Playlist detected & expanded into paired Video+Audio variants,
        //    automatically suppressing un-paired silent hls.js child sub-playlists!
        val masterText = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",NAME="Main",DEFAULT=YES,URI="aud/main.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,AUDIO="a1"
            vid/1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720,AUDIO="a1"
            vid/720p.m3u8
        """.trimIndent()
        val masterUrl = "https://hls.example.net/vod/99/master.m3u8"
        val masterItem = VideoStreamSniffer.recordCandidate(
            tabId = tabId,
            rawUrl = masterUrl,
            pageUrl = "https://cinema.example.net/watch/99",
            pageTitle = "فیلم مستند طبیعت"
        )!!
        // Simulate hls.js requesting raw silent video sub-playlist BEFORE master expansion finishes
        VideoStreamSniffer.recordCandidate(
            tabId = tabId,
            rawUrl = "https://hls.example.net/vod/99/vid/720p.m3u8",
            pageUrl = "https://cinema.example.net/watch/99"
        )

        val variants = HlsPlaylistParser.parseMasterPlaylist(masterText, masterUrl)
        val audios = HlsPlaylistParser.parseAudioRenditions(masterText, masterUrl)
        assertTrue(
            VideoStreamSniffer.replaceMasterWithResolvedVariants(
                tabId = tabId,
                masterItem = masterItem,
                variants = variants,
                audioRenditions = audios,
                probedDurationSec = 600
            )
        )

        // Subsequent hls.js child audio/video sub-playlist requests MUST be suppressed (no mute duplicates!)
        VideoStreamSniffer.recordCandidate(
            tabId = tabId,
            rawUrl = "https://hls.example.net/vod/99/aud/main.m3u8",
            pageUrl = "https://cinema.example.net/watch/99"
        )

        val items = VideoStreamSniffer.getMediaForTab(tabId)
        // Expected: 2 paired HLS video variants (1080p + 720p) + 1 standalone M4A audio rendition + 1 direct CDN MP4 = 4 items
        assertEquals(4, items.size)
        val topHls = items.first { it.height == 1080 && it.isHls }
        assertTrue(topHls.hasPairedAudio)
        assertEquals("https://hls.example.net/vod/99/aud/main.m3u8", topHls.audioUrl)
        assertTrue(topHls.audioStatusLabel().contains("با صدا"))
        assertEquals("hls.example.net", topHls.sourceHost())
        assertTrue(topHls.contentLengthBytes > 0L)
        assertTrue(topHls.suggestedFileName().endsWith("_1080p.mp4"))

        // Sibling audio auto-pairing rescues standalone video sub-playlist URLs too!
        assertEquals(
            "https://hls.example.net/vod/99/aud/main.m3u8",
            VideoStreamSniffer.findSiblingAudioForVideoUrl(
                "https://hls.example.net/vod/99/vid/720p.m3u8",
                "https://cinema.example.net/watch/99"
            )
        )

        VideoStreamSniffer.clearTab(tabId)
    }

    @Test
    fun exposesAllNineQualitiesAndRichMetadataPlusFmp4AndTsNormalization() {
        val tabId = "test_tab_v9_all_qualities"
        VideoStreamSniffer.clearTab(tabId)

        // 8 distinct video qualities (4K down to 144p, including two 1080p bitrates) + 1 separate audio track = 9 items!
        val fullLadderMaster = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac-hi",NAME="Stereo",LANGUAGE="fa",DEFAULT=YES,CHANNELS="2",URI="audio/128k.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=14000000,RESOLUTION=3840x2160,FRAME-RATE=60.0,CODECS="hvc1.1.6.L150.90,mp4a.40.2",AUDIO="aac-hi"
            v/2160p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=2560x1440,CODECS="avc1.640032,mp4a.40.2",AUDIO="aac-hi"
            v/1440p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5500000,RESOLUTION=1920x1080,FRAME-RATE=60.0,CODECS="avc1.64002a,mp4a.40.2",AUDIO="aac-hi"
            v/1080p60.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1920x1080,FRAME-RATE=30.0,CODECS="avc1.640028,mp4a.40.2",AUDIO="aac-hi"
            v/1080p30.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1900000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aac-hi"
            v/720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1100000,RESOLUTION=854x480,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aac-hi"
            v/480p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=650000,RESOLUTION=640x360,CODECS="avc1.42e01e,mp4a.40.2",AUDIO="aac-hi"
            v/360p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=350000,RESOLUTION=426x240,CODECS="avc1.42e015,mp4a.40.2",AUDIO="aac-hi"
            v/240p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=180000,RESOLUTION=256x144,CODECS="avc1.42e00c,mp4a.40.2",AUDIO="aac-hi"
            v/144p.m3u8
        """.trimIndent()

        val masterUrl = "https://stream.cinema.ir/movies/hero/master.m3u8"
        val masterItem = VideoStreamSniffer.recordCandidate(
            tabId = tabId,
            rawUrl = masterUrl,
            pageUrl = "https://cinema.ir/movies/hero",
            pageTitle = "قهرمان"
        )!!
        val variants = HlsPlaylistParser.parseMasterPlaylist(fullLadderMaster, masterUrl)
        val audios = HlsPlaylistParser.parseAudioRenditions(fullLadderMaster, masterUrl)
        assertEquals(9, variants.size)

        VideoStreamSniffer.replaceMasterWithResolvedVariants(
            tabId = tabId,
            masterItem = masterItem,
            variants = variants,
            audioRenditions = audios,
            probedDurationSec = 120,
            probedSegmentCount = 30
        )

        val allItems = VideoStreamSniffer.getMediaForTab(tabId)
        // ALL 9 video qualities + 1 standalone audio track = 10 items (ZERO 6-variant truncation!)
        assertEquals(10, allItems.size)
        val heights = allItems.filter { it.isHls }.map { it.height }
        assertEquals(listOf(2160, 1440, 1080, 1080, 720, 480, 360, 240, 144), heights)

        val item4k = allItems.first()
        assertEquals("3840×2160 • 60fps", item4k.resolutionDetail())
        assertEquals("HLS ➔ MP4 (HEVC/H.265 + AAC)", item4k.containerAndCodecsLabel())
        assertEquals("14.0 Mbps", item4k.formattedBitrate())
        assertEquals(30, item4k.segmentCount)
        assertTrue(item4k.contentLengthBytes > 200_000_000L)

        // Verify fMP4 styp/sidx stripping preserves moof + mdat boxes intact
        val stypBox = byteArrayOf(0, 0, 0, 12, 's'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 1, 2, 3, 4)
        val sidxBox = byteArrayOf(0, 0, 0, 12, 's'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(), 'x'.code.toByte(), 5, 6, 7, 8)
        val moofBox = byteArrayOf(0, 0, 0, 10, 'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'f'.code.toByte(), 9, 10)
        val mdatBox = byteArrayOf(0, 0, 0, 10, 'm'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 11, 12)
        val strippedFmp4 = MediaRemuxer.stripFmp4NonFragmentBoxes(stypBox + sidxBox + moofBox + mdatBox)
        assertArrayEquals(moofBox + mdatBox, strippedFmp4)

        // Verify MPEG-TS sync alignment strips fake PNG header before genuine 188-byte 0x47 packets
        val fakePngPrefix = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val tsPackets = ByteArray(188 * 3).apply {
            this[0] = 0x47
            this[188] = 0x47
            this[376] = 0x47
        }
        val alignedTs = MediaRemuxer.alignToTsSyncPacket(fakePngPrefix + tsPackets)
        assertArrayEquals(tsPackets, alignedTs)

        VideoStreamSniffer.clearTab(tabId)
    }
}
