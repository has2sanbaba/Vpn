package org.greatfire.browser

import org.greatfire.browser.data.BrowserDataStore
import org.greatfire.browser.data.SearchEngine
import org.greatfire.browser.ui.MainActivity
import org.greatfire.browser.vpn.GatewayStatus
import org.greatfire.browser.web.ChromeWebView
import org.greatfire.browser.web.WebContextMenuPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationAndStatusTest {

    @Test
    fun resolvesChromeOmniboxInputsAcrossSearchEnginesAndDomains() {
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
            MainActivity.resolveDestinationUrl("  m.youtube.com  ")
        )

        val googleUrl = MainActivity.resolveDestinationUrl("یوتیوب بدون فیلتر", SearchEngine.GOOGLE)
        assertTrue(googleUrl.startsWith("https://www.google.com/search?q="))

        val ddgUrl = MainActivity.resolveDestinationUrl("dr browser chrome", SearchEngine.DUCKDUCKGO)
        assertEquals("https://duckduckgo.com/?q=dr+browser+chrome", ddgUrl)

        val braveUrl = MainActivity.resolveDestinationUrl("open web", SearchEngine.BRAVE)
        assertEquals("https://search.brave.com/search?q=open+web", braveUrl)
    }

    @Test
    fun gatewayStatusFormatsSpeedAndLatencyCleanly() {
        val fast = GatewayStatus(
            ready = true,
            gatewayPort = 18080,
            readyNodes = 14,
            bestNode = "Germany-Fast",
            bestProto = "vless",
            bestRttMs = 112L,
            bestSpeedKbps = 18500L
        )
        assertTrue(fast.ready)
        assertEquals("18.5 Mbps", fast.formattedSpeed())

        val medium = GatewayStatus(ready = true, bestSpeedKbps = 740L)
        assertEquals("740 Kbps", medium.formattedSpeed())

        val idle = GatewayStatus(ready = true, bestSpeedKbps = 0L)
        assertEquals("", idle.formattedSpeed())
    }

    @Test
    fun chromeWebViewContextMenuAndJsDecoderWorkCleanly() {
        assertEquals(
            "{\"href\":\"https://example.org/a\",\"text\":\"سلام\"}",
            ChromeWebView.decodeEvaluateJsString("\"{\\\"href\\\":\\\"https://example.org/a\\\",\\\"text\\\":\\\"سلام\\\"}\"")
        )
        assertEquals("", ChromeWebView.decodeEvaluateJsString("null"))
        assertEquals("", ChromeWebView.decodeEvaluateJsString(""))

        val linkAndImg = WebContextMenuPayload(
            linkUrl = "https://example.com/post",
            linkText = "Read Post",
            imageUrl = "https://example.com/thumb.jpg"
        )
        assertTrue(linkAndImg.hasLink)
        assertTrue(linkAndImg.hasImage)

        val emptyPayload = WebContextMenuPayload()
        assertFalse(emptyPayload.hasLink)
        assertFalse(emptyPayload.hasImage)

        assertTrue(BrowserDataStore.pickBadgeColor("youtube.com").startsWith("#"))
    }
}
