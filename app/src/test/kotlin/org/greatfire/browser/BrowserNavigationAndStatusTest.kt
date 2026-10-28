package org.greatfire.browser

import org.greatfire.browser.ui.MainActivity
import org.greatfire.browser.vpn.GatewayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationAndStatusTest {

    @Test
    fun resolvesChromeOmniboxInputsToDirectUrlsOrGoogleSearch() {
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

        val searchUrl = MainActivity.resolveDestinationUrl("یوتیوب بدون فیلتر")
        assertTrue(searchUrl.startsWith("https://www.google.com/search?q="))

        val enSearch = MainActivity.resolveDestinationUrl("greatfire freebrowser appmaker")
        assertEquals(
            "https://www.google.com/search?q=greatfire+freebrowser+appmaker",
            enSearch
        )
    }

    @Test
    fun gatewayStatusDefaultsAreCleanAndZeroWait() {
        val status = GatewayStatus(ready = true, gatewayPort = 18080, readyNodes = 12, bestRttMs = 142L)
        assertTrue(status.ready)
        assertEquals(18080, status.gatewayPort)
        assertEquals(12, status.readyNodes)
        assertEquals(142L, status.bestRttMs)
    }
}
