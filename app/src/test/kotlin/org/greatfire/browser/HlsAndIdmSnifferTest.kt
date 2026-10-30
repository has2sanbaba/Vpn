package org.greatfire.browser

import org.greatfire.browser.media.HlsPlaylistParser
import org.greatfire.browser.media.SniffedMediaKind
import org.greatfire.browser.media.VideoStreamSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        // Highest quality first (1080p -> 720p -> 360p)
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
    fun parsesMediaPlaylistInitMapAndStrictlyFlagsDrmStreams() {
        val cleanVod = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.006,
            seg_001.ts
            #EXTINF:5.500,
            seg_002.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsPlaylistParser.parseMediaPlaylist(
            cleanVod,
            "https://media.example.org/vod/720p/index.m3u8"
        )
        assertFalse(parsed.isDrmOrEncrypted)
        assertFalse(parsed.isLiveStream)
        assertEquals("https://media.example.org/vod/720p/init.mp4", parsed.initMapUrl)
        assertEquals(2, parsed.segments.size)
        assertEquals("https://media.example.org/vod/720p/seg_001.ts", parsed.segments[0].url)
        assertEquals(11.506, parsed.totalDurationSec, 0.01)

        val drmManifest = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://drm.provider/key"
            #EXTINF:4.0,
            enc_01.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val drmParsed = HlsPlaylistParser.parseMediaPlaylist(
            drmManifest,
            "https://drm.example.org/stream.m3u8"
        )
        assertTrue(drmParsed.isDrmOrEncrypted)
        assertEquals("SAMPLE-AES", drmParsed.encryptionMethod)
    }

    @Test
    fun sniffsProgressiveAndHlsMediaWhileRejectingAdsAndByteChunks() {
        val tabId = "test_tab_idm_1"
        VideoStreamSniffer.clearTab(tabId)

        // 1. Ad network video must be ignored
        assertNull(
            VideoStreamSniffer.recordCandidate(
                tabId = tabId,
                rawUrl = "https://googleads.g.doubleclick.net/pagead/ad_video.mp4",
                pageUrl = "https://site.example.com/watch",
                pageTitle = "Sample Page"
            )
        )

        // 2. HLS segment (.ts) must NOT spam the sniffer list (only the .m3u8 playlist should appear)
        assertNull(
            VideoStreamSniffer.recordCandidate(
                tabId = tabId,
                rawUrl = "https://cdn.example.com/hls/chunk_00042.ts",
                pageUrl = "https://site.example.com/watch",
                pageTitle = "Lecture 1"
            )
        )

        // 3. Instagram / CDN byte-sliced URL should strip bytestart & byteend to capture full MP4!
        val instaItem = VideoStreamSniffer.recordCandidate(
            tabId = tabId,
            rawUrl = "https://scontent.cdninstagram.com/v/t50.2886-16/reel_clip.mp4?efg=abc&bytestart=0&byteend=524288&oh=xyz",
            pageUrl = "https://www.instagram.com/reel/123/",
            pageTitle = "آموزش برنامه‌نویسی اندروید",
            mimeHint = "video/mp4"
        )
        assertNotNull(instaItem)
        assertFalse(instaItem!!.url.contains("bytestart="))
        assertFalse(instaItem.url.contains("byteend="))
        assertTrue(instaItem.url.contains("oh=xyz"))
        assertEquals(SniffedMediaKind.VIDEO, instaItem.kind)

        // 4. Master HLS stream detected via DOM/JS report with 1080p metadata
        VideoStreamSniffer.recordCandidate(
            tabId = tabId,
            rawUrl = "https://stream.example.com/course/lesson4/master.m3u8",
            pageUrl = "https://academy.example.com/lesson4",
            pageTitle = "Lesson 4 Architecture",
            mimeHint = "application/x-mpegURL",
            width = 1920,
            height = 1080,
            durationSec = 325
        )

        val all = VideoStreamSniffer.getMediaForTab(tabId)
        assertEquals(2, all.size)
        assertEquals(SniffedMediaKind.HLS_STREAM, all[0].kind)
        assertEquals("1080p استریم", all[0].qualityLabel)
        assertEquals("05:25", all[0].formattedDuration())
        assertTrue(all[0].suggestedFileName().endsWith(".mp4"))

        VideoStreamSniffer.clearTab(tabId)
        assertEquals(0, VideoStreamSniffer.getCountForTab(tabId))
    }
}
