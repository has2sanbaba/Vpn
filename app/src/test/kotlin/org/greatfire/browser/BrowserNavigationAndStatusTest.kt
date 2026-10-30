package org.greatfire.browser

import org.greatfire.browser.data.BrowserDataStore
import org.greatfire.browser.data.SearchEngine
import org.greatfire.browser.download.DownloadEngine
import org.greatfire.browser.ui.MainActivity
import org.greatfire.browser.vpn.GatewayStatus
import org.greatfire.browser.web.BrowserTabManager
import org.greatfire.browser.web.ChromeWebView
import org.greatfire.browser.web.WebContextMenuPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationAndStatusTest {

    @Test
    fun resolvesChromeOmniboxInputsAcrossDomainsIpsAndPersianQueries() {
        assertEquals(
            "https://www.youtube.com/watch?v=123",
            MainActivity.resolveDestinationUrl("https://www.youtube.com/watch?v=123")
        )
        assertEquals(
            "https://bbc.com/persian",
            MainActivity.resolveDestinationUrl("bbc.com/persian")
        )
        assertEquals(
            "https://m.youtube.com",
            MainActivity.resolveDestinationUrl("  \u200Em.youtube.com\u200F  ")
        )
        assertEquals(
            "https://1.1.1.1",
            MainActivity.resolveDestinationUrl("1.1.1.1")
        )

        // Private / local hosts must default to http:// so routers & dev servers open cleanly
        assertEquals("http://localhost:3000/app", MainActivity.resolveDestinationUrl("localhost:3000/app"))
        assertEquals("http://192.168.1.1", MainActivity.resolveDestinationUrl("192.168.1.1"))
        assertEquals("http://10.0.0.1:8080", MainActivity.resolveDestinationUrl("10.0.0.1:8080"))
        assertEquals("http://172.20.0.1", MainActivity.resolveDestinationUrl("172.20.0.1"))

        // Persian text with dots MUST NOT be treated as an invalid domain
        val persianDotQuery = MainActivity.resolveDestinationUrl("سلام.خوبی", SearchEngine.GOOGLE)
        assertTrue(persianDotQuery.startsWith("https://www.google.com/search?q="))

        // Preserve Persian ZWNJ (\u200C نیم‌فاصله) in search queries
        val zwnjQuery = MainActivity.resolveDestinationUrl("می‌خواهم فیلم ببینم", SearchEngine.GOOGLE)
        assertTrue(zwnjQuery.startsWith("https://www.google.com/search?q="))

        assertEquals(BrowserTabManager.HOME_URL, MainActivity.resolveDestinationUrl("   "))
        assertEquals(BrowserTabManager.HOME_URL, MainActivity.resolveDestinationUrl("about:blank"))

        val ddgUrl = MainActivity.resolveDestinationUrl("dr browser chrome", SearchEngine.DUCKDUCKGO)
        assertEquals("https://duckduckgo.com/?q=dr+browser+chrome", ddgUrl)

        val braveUrl = MainActivity.resolveDestinationUrl("open web", SearchEngine.BRAVE)
        assertEquals("https://search.brave.com/search?q=open+web", braveUrl)
    }

    @Test
    fun detectsProxyShareLinksPastedIntoOmnibox() {
        assertTrue(MainActivity.isProxyConfigShareInput("vless://uuid@1.2.3.4:443?security=tls#Test"))
        assertTrue(MainActivity.isProxyConfigShareInput("vmess://eyJhZGQiOiIxLjIuMy40In0="))
        assertTrue(MainActivity.isProxyConfigShareInput("trojan://pass@example.com:443#Node"))
        assertTrue(MainActivity.isProxyConfigShareInput("ss://YWVzLTI1Ni1nY206cGFzcw==@1.2.3.4:8388"))
        assertTrue(MainActivity.isProxyConfigShareInput("socks5://127.0.0.1:1080"))
        assertFalse(MainActivity.isProxyConfigShareInput("https://youtube.com"))
        assertFalse(MainActivity.isProxyConfigShareInput("دانلود تلگرام"))
    }

    @Test
    fun gatewayMultiSourceStatusFormatsCleanly() {
        val fast = GatewayStatus(
            ready = true,
            gatewayPort = 18080,
            readyNodes = 26,
            activeSources = 8,
            bestNode = "Germany-Fast",
            bestSource = "MahdiBland",
            bestProto = "vmess",
            bestRttMs = 95L,
            bestSpeedKbps = 24500L
        )
        assertTrue(fast.ready)
        assertEquals("24.5 Mbps", fast.formattedSpeed())

        val homeLine = MainActivity.formatHomeProxyStatus(fast)
        assertTrue(homeLine.contains("Germany-Fast"))
        assertTrue(homeLine.contains("[MahdiBland]"))
        assertTrue(homeLine.contains("8 منبع"))
        assertTrue(homeLine.contains("24.5 Mbps"))

        val medium = GatewayStatus(ready = true, bestSpeedKbps = 740L)
        assertEquals("740 Kbps", medium.formattedSpeed())
    }

    @Test
    fun downloadEngineResolvesFilenamesAndMimesAccurately() {
        val rfc5987Name = DownloadEngine.guessBestFileName(
            url = "https://cdn.example.org/dl?id=42",
            contentDisposition = "attachment; filename*=UTF-8''Dr%20Browser%20v5.0.apk",
            mimeType = "application/octet-stream"
        )
        assertEquals("Dr Browser v5.0.apk", rfc5987Name)
        assertEquals(
            "application/vnd.android.package-archive",
            DownloadEngine.resolveMimeForFileName(rfc5987Name, "application/octet-stream")
        )

        val directPathName = DownloadEngine.guessBestFileName(
            url = "https://releases.example.com/files/update_v5.apk?sig=abc",
            contentDisposition = "",
            mimeType = null
        )
        assertEquals("update_v5.apk", directPathName)

        val upgradedBin = DownloadEngine.guessBestFileName(
            url = "https://media.example.com/stream/clip.bin",
            contentDisposition = "",
            mimeType = "video/mp4"
        )
        assertEquals("clip.mp4", upgradedBin)

        val rootQueryPdf = DownloadEngine.guessBestFileName(
            url = "https://docs.example.com?doc=991",
            contentDisposition = "",
            mimeType = "application/pdf"
        )
        assertTrue(rootQueryPdf.endsWith(".pdf"))
        assertFalse(rootQueryPdf.startsWith("docs.example.com"))

        val sanitized = DownloadEngine.sanitizeFileName("../bad:file*name?.apk")
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains(':'))
        assertTrue(sanitized.endsWith(".apk"))
    }

    @Test
    fun chromeWebViewContextMenuAndJsDecoderWorkCleanly() {
        assertEquals(
            "{\"href\":\"https://example.org/a\",\"text\":\"سلام\"}",
            ChromeWebView.decodeEvaluateJsString("\"{\\\"href\\\":\\\"https://example.org/a\\\",\\\"text\\\":\\\"سلام\\\"}\"")
        )
        assertEquals("", ChromeWebView.decodeEvaluateJsString("null"))
        assertEquals("", ChromeWebView.decodeEvaluateJsString(""))

        val richPayload = WebContextMenuPayload(
            linkUrl = "https://example.com/post",
            linkText = "Read Post",
            imageUrl = "https://example.com/thumb.jpg",
            mediaUrl = "https://example.com/video.mp4"
        )
        assertTrue(richPayload.hasLink)
        assertTrue(richPayload.hasImage)
        assertTrue(richPayload.hasMedia)

        val emptyPayload = WebContextMenuPayload()
        assertFalse(emptyPayload.hasLink)
        assertFalse(emptyPayload.hasImage)
        assertFalse(emptyPayload.hasMedia)

        assertTrue(BrowserDataStore.pickBadgeColor("youtube.com").startsWith("#"))
    }
}
