package org.greatfire.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.greatfire.browser.vpn.TunnelManager
import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val incognito: Boolean = false,
    var title: String = "صفحه اصلی GreatFire",
    var currentUrl: String = BrowserTabManager.HOME_URL,
    var desktopMode: Boolean = false,
    val webView: WebView
)

class BrowserTabManager(
    private val context: Context,
    private val tunnelManager: TunnelManager,
    private val onTabStateUpdated: () -> Unit,
    private val onProgressChanged: (Int) -> Unit,
    private val onDownloadRequested: (url: String, userAgent: String, contentDisposition: String, mimeType: String) -> Unit
) {
    private val _tabs = mutableListOf<BrowserTab>()
    val tabs: List<BrowserTab> get() = _tabs

    var activeIndex: Int = -1
        private set

    val activeTab: BrowserTab?
        get() = _tabs.getOrNull(activeIndex)

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun createTab(initialUrl: String = HOME_URL, incognito: Boolean = false): BrowserTab {
        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = !incognito
        settings.databaseEnabled = !incognito
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        if (incognito) {
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                settings.saveFormData = false
            }
        } else {
            settings.cacheMode = WebSettings.LOAD_DEFAULT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = false // Prevent direct Google SafeBrowsing leaks outside proxy
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(!incognito)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        // Inject document-start WebRTC IP-leak shield on modern WebViews
        if (tunnelManager.repository.webRtcShieldEnabled &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    EnvoyWebViewClient.WEBRTC_SHIELD_JS,
                    setOf("*")
                )
            }
        }

        lateinit var tabRef: BrowserTab

        webView.webViewClient = EnvoyWebViewClient(
            tunnelManager = tunnelManager,
            onUrlChanged = { _, newUrl ->
                tabRef.currentUrl = newUrl
                onTabStateUpdated()
            },
            onLoadingStateChanged = { _, _ ->
                onTabStateUpdated()
            }
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view == activeTab?.webView) {
                    onProgressChanged(newProgress)
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank() && tabRef.currentUrl != HOME_URL) {
                    tabRef.title = title
                    onTabStateUpdated()
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, ua, cd, mime, _ ->
            if (!url.isNullOrBlank()) {
                onDownloadRequested(url, ua.orEmpty(), cd.orEmpty(), mime.orEmpty())
            }
        })

        tabRef = BrowserTab(
            incognito = incognito,
            title = if (incognito) "🕶 تب ناشناس" else "صفحه اصلی GreatFire",
            currentUrl = initialUrl,
            webView = webView
        )

        _tabs.add(tabRef)
        activeIndex = _tabs.lastIndex

        if (initialUrl != HOME_URL) {
            webView.loadUrl(initialUrl)
        }
        onTabStateUpdated()
        return tabRef
    }

    fun selectTab(index: Int) {
        if (index in _tabs.indices) {
            activeIndex = index
            onTabStateUpdated()
        }
    }

    fun closeTab(index: Int) {
        if (index !in _tabs.indices) return
        val removed = _tabs.removeAt(index)
        destroyWebViewCleanly(removed.webView)

        if (_tabs.isEmpty()) {
            createTab(HOME_URL, incognito = false)
        } else {
            activeIndex = activeIndex.coerceIn(0, _tabs.lastIndex)
            onTabStateUpdated()
        }
    }

    fun toggleDesktopMode(tab: BrowserTab) {
        tab.desktopMode = !tab.desktopMode
        val settings = tab.webView.settings
        if (tab.desktopMode) {
            settings.userAgentString = DESKTOP_UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        } else {
            settings.userAgentString = null
        }
        if (tab.currentUrl != HOME_URL) {
            tab.webView.reload()
        }
    }

    fun panicWipeAll() {
        _tabs.forEach { destroyWebViewCleanly(it.webView) }
        _tabs.clear()

        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }

        createTab(HOME_URL, incognito = false)
    }

    fun destroyAll() {
        _tabs.forEach { destroyWebViewCleanly(it.webView) }
        _tabs.clear()
    }

    private fun destroyWebViewCleanly(wv: WebView) {
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.clearHistory()
            wv.clearCache(true)
            wv.loadUrl("about:blank")
            wv.destroy()
        }
    }

    companion object {
        const val HOME_URL = "gf://home"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }
}
