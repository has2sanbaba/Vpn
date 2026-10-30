package org.greatfire.browser.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.greatfire.browser.data.BrowserDataStore
import org.greatfire.browser.data.SavedSessionTab
import org.greatfire.browser.vpn.TunnelManager
import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val incognito: Boolean = false,
    var title: String = "تب جدید",
    var currentUrl: String = BrowserTabManager.HOME_URL,
    var desktopMode: Boolean = false,
    var isLoading: Boolean = false,
    var needsRestoreLoad: Boolean = false,
    val webView: ChromeWebView
)

class BrowserTabManager(
    private val context: Context,
    private val tunnelManager: TunnelManager,
    private val dataStore: BrowserDataStore,
    private val onTabStateUpdated: () -> Unit,
    private val onProgressChanged: (Int) -> Unit,
    private val onDownloadRequested: (tab: BrowserTab, url: String, userAgent: String, contentDisposition: String, mimeType: String) -> Unit,
    private val onBlobDataReceived: ((dataUrl: String, fileName: String, mimeType: String) -> Unit)? = null,
    private val onContextMenuRequested: (BrowserTab, WebContextMenuPayload) -> Unit,
    private val onFileChooserRequested: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean)? = null,
    private val onWebSearchSelected: ((String) -> Unit)? = null,
    private val onTranslateSelected: ((String) -> Unit)? = null,
    private val onWebViewTouchDown: (() -> Unit)? = null,
    private val onMainFrameError: ((BrowserTab, String) -> Unit)? = null,
    private val onEnterFullscreen: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
    private val onExitFullscreen: (() -> Unit)? = null
) {
    private val _tabs = mutableListOf<BrowserTab>()
    val tabs: List<BrowserTab> get() = _tabs

    var activeIndex: Int = -1
        private set

    val activeTab: BrowserTab?
        get() = _tabs.getOrNull(activeIndex)

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun createTab(
        initialUrl: String = HOME_URL,
        incognito: Boolean = false,
        selectImmediately: Boolean = true,
        initialDesktopMode: Boolean = dataStore.defaultDesktopMode
    ): BrowserTab {
        val webView = ChromeWebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val settings = webView.settings
        settings.javaScriptEnabled = dataStore.javaScriptEnabled
        settings.domStorageEnabled = !incognito
        @Suppress("DEPRECATION")
        settings.databaseEnabled = !incognito
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.textZoom = dataStore.textZoomPercent
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = !dataStore.blockPopups
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        if (initialDesktopMode) {
            settings.userAgentString = DESKTOP_UA
        }

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
            settings.safeBrowsingEnabled = false
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(!incognito && dataStore.cookiesEnabled)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        if (tunnelManager.webRtcShieldEnabled &&
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

        webView.onAnyTouchDown = {
            onWebViewTouchDown?.invoke()
        }
        webView.onContextMenuRequested = { payload ->
            onContextMenuRequested(tabRef, payload)
        }
        webView.onWebSearchSelectedText = { query ->
            onWebSearchSelected?.invoke(query)
        }
        webView.onTranslateSelectedText = { text ->
            onTranslateSelected?.invoke(text)
        }
        webView.onBlobBase64Fetched = { dataUrl, fileName, mime ->
            onBlobDataReceived?.invoke(dataUrl, fileName, mime)
        }

        webView.webViewClient = EnvoyWebViewClient(
            tunnelManager = tunnelManager,
            onUrlChanged = { _, newUrl ->
                if (newUrl != "about:blank") {
                    tabRef.currentUrl = newUrl
                    persistSessionQuietly()
                    onTabStateUpdated()
                }
            },
            onLoadingStateChanged = { _, loading ->
                tabRef.isLoading = loading
                onTabStateUpdated()
            },
            onHistoryVisited = { pageTitle, visitedUrl ->
                if (!tabRef.incognito && visitedUrl != "about:blank" && visitedUrl != HOME_URL) {
                    dataStore.recordHistoryVisit(pageTitle.ifBlank { tabRef.title }, visitedUrl)
                }
            },
            onMainFrameNetworkError = { _, failingUrl ->
                if (tabRef === activeTab && tabRef.currentUrl != HOME_URL) {
                    onMainFrameError?.invoke(tabRef, failingUrl)
                }
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
                    if (!tabRef.incognito &&
                        (tabRef.currentUrl.startsWith("http://", true) || tabRef.currentUrl.startsWith("https://", true))
                    ) {
                        dataStore.recordHistoryVisit(title, tabRef.currentUrl)
                    }
                    persistSessionQuietly()
                    onTabStateUpdated()
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (resultMsg == null) return false
                if (!isUserGesture && dataStore.blockPopups) {
                    return false
                }
                val newChildTab = createTab(
                    initialUrl = HOME_URL,
                    incognito = tabRef.incognito,
                    selectImmediately = true
                )
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newChildTab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                val idx = _tabs.indexOfFirst { it.webView === window }
                if (idx >= 0 && _tabs.size > 1) {
                    closeTab(idx)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                val handler = onFileChooserRequested ?: run {
                    filePathCallback.onReceiveValue(null)
                    return false
                }
                return handler(filePathCallback, fileChooserParams)
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val act = context as? Activity
                if (act == null || act.isFinishing || result == null) {
                    result?.cancel()
                    return true
                }
                AlertDialog.Builder(context)
                    .setMessage(message.orEmpty())
                    .setPositiveButton("تأیید") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val act = context as? Activity
                if (act == null || act.isFinishing || result == null) {
                    result?.cancel()
                    return true
                }
                AlertDialog.Builder(context)
                    .setMessage(message.orEmpty())
                    .setPositiveButton("تأیید") { _, _ -> result.confirm() }
                    .setNegativeButton("انصراف") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val act = context as? Activity
                if (act == null || act.isFinishing || result == null) {
                    result?.cancel()
                    return true
                }
                val dp = context.resources.displayMetrics.density
                val input = EditText(context).apply {
                    setText(defaultValue.orEmpty())
                    setSingleLine()
                }
                val box = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (20 * dp).toInt()
                    setPadding(pad, (10 * dp).toInt(), pad, 0)
                    addView(input)
                }
                AlertDialog.Builder(context)
                    .setTitle(message.orEmpty())
                    .setView(box)
                    .setPositiveButton("تأیید") { _, _ -> result.confirm(input.text?.toString().orEmpty()) }
                    .setNegativeButton("انصراف") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null && onEnterFullscreen != null) {
                    onEnterFullscreen.invoke(view, callback)
                } else {
                    super.onShowCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                if (onExitFullscreen != null) {
                    onExitFullscreen.invoke()
                } else {
                    super.onHideCustomView()
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, ua, cd, mime, _ ->
            if (!url.isNullOrBlank()) {
                onDownloadRequested(tabRef, url, ua.orEmpty(), cd.orEmpty(), mime.orEmpty())
            }
        })

        tabRef = BrowserTab(
            incognito = incognito,
            title = if (incognito) "تب ناشناس" else "تب جدید",
            currentUrl = initialUrl,
            desktopMode = initialDesktopMode,
            webView = webView
        )

        _tabs.add(tabRef)
        if (selectImmediately || activeIndex !in _tabs.indices) {
            activeIndex = _tabs.lastIndex
        }

        if (initialUrl != HOME_URL) {
            webView.loadUrl(initialUrl)
        }
        persistSessionQuietly()
        onTabStateUpdated()
        return tabRef
    }

    fun selectTab(index: Int) {
        if (index in _tabs.indices) {
            activeIndex = index
            activeTab?.let { ensureTabLoaded(it) }
            persistSessionQuietly()
            onTabStateUpdated()
        }
    }

    fun selectTabById(tabId: String) {
        val idx = _tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) {
            selectTab(idx)
        }
    }

    fun closeTab(index: Int): Pair<String, String>? {
        if (index !in _tabs.indices) return null
        val removed = _tabs.removeAt(index)
        val closedInfo = if (!removed.incognito && removed.currentUrl != HOME_URL) {
            dataStore.pushRecentlyClosedTab(removed.title, removed.currentUrl)
            removed.title to removed.currentUrl
        } else {
            null
        }
        destroyWebViewCleanly(removed.webView)

        if (_tabs.isEmpty()) {
            createTab(HOME_URL, incognito = false)
        } else {
            if (activeIndex >= _tabs.size) {
                activeIndex = _tabs.lastIndex
            } else if (index < activeIndex) {
                activeIndex = (activeIndex - 1).coerceAtLeast(0)
            }
            activeTab?.let { ensureTabLoaded(it) }
            persistSessionQuietly()
            onTabStateUpdated()
        }
        return closedInfo
    }

    fun closeAllTabs(incognitoOnly: Boolean) {
        val toClose = if (incognitoOnly) {
            _tabs.filter { it.incognito }
        } else {
            _tabs.toList()
        }
        for (t in toClose) {
            if (!t.incognito && t.currentUrl != HOME_URL) {
                dataStore.pushRecentlyClosedTab(t.title, t.currentUrl)
            }
            destroyWebViewCleanly(t.webView)
            _tabs.remove(t)
        }
        if (_tabs.isEmpty()) {
            createTab(HOME_URL, incognito = false)
        } else {
            activeIndex = activeIndex.coerceIn(0, _tabs.lastIndex)
            persistSessionQuietly()
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
        persistSessionQuietly()
        if (tab.currentUrl != HOME_URL) {
            tab.webView.reload()
        } else {
            onTabStateUpdated()
        }
    }

    fun applySettingsToAllTabs() {
        val js = dataStore.javaScriptEnabled
        val zoom = dataStore.textZoomPercent
        val cookies = dataStore.cookiesEnabled
        val blockPop = dataStore.blockPopups

        for (tab in _tabs) {
            tab.webView.settings.javaScriptEnabled = js
            tab.webView.settings.textZoom = zoom
            tab.webView.settings.javaScriptCanOpenWindowsAutomatically = !blockPop
            CookieManager.getInstance().setAcceptCookie(!tab.incognito && cookies)
        }
    }

    fun restorePreviousSessionOrDefault(): Boolean {
        val (saved, savedIdx) = dataStore.loadTabSession()
        if (saved.isEmpty()) {
            createTab(HOME_URL, incognito = false)
            return false
        }
        for (item in saved) {
            val t = createTab(
                initialUrl = HOME_URL,
                incognito = false,
                selectImmediately = false,
                initialDesktopMode = item.desktopMode
            )
            t.title = item.title
            t.currentUrl = item.url
            t.needsRestoreLoad = (item.url != HOME_URL)
        }
        activeIndex = savedIdx.coerceIn(0, _tabs.lastIndex)
        onTabStateUpdated()
        return true
    }

    fun navigateTabToHome(tab: BrowserTab) {
        tab.isLoading = false
        tab.needsRestoreLoad = false
        tab.currentUrl = HOME_URL
        tab.title = if (tab.incognito) "تب ناشناس" else "تب جدید"
        tab.webView.stopLoading()
        tab.webView.stopAllMediaPlayback()
        tab.webView.loadUrl("about:blank")
        persistSessionQuietly()
        onTabStateUpdated()
    }

    fun ensureTabLoaded(tab: BrowserTab) {
        if (tab.needsRestoreLoad && tab.currentUrl != HOME_URL) {
            tab.needsRestoreLoad = false
            tab.webView.loadUrl(tab.currentUrl)
        }
    }

    fun clearBrowsingData(
        clearHistory: Boolean,
        clearCookiesAndSiteData: Boolean,
        clearCacheFiles: Boolean,
        sinceEpochMs: Long
    ) {
        if (clearHistory) {
            dataStore.clearHistorySince(sinceEpochMs)
            if (sinceEpochMs <= 0L) {
                dataStore.clearRecentlyClosedTabs()
            }
            _tabs.forEach { it.webView.clearHistory() }
        }
        if (clearCookiesAndSiteData) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
        if (clearCacheFiles) {
            runCatching {
                _tabs.firstOrNull()?.webView?.clearCache(true)
            }
        }
    }

    fun panicWipeAll() {
        _tabs.forEach { destroyWebViewCleanly(it.webView) }
        _tabs.clear()
        dataStore.clearHistorySince(0L)
        dataStore.clearRecentlyClosedTabs()

        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }

        createTab(HOME_URL, incognito = false)
    }

    fun persistSessionQuietly() {
        runCatching {
            val nonIncognito = _tabs.filter { !it.incognito }.map {
                SavedSessionTab(title = it.title, url = it.currentUrl, desktopMode = it.desktopMode)
            }
            val currentTab = activeTab
            val savedActive = if (currentTab != null && !currentTab.incognito) {
                _tabs.filter { !it.incognito }.indexOfFirst { it.id == currentTab.id }.coerceAtLeast(0)
            } else {
                0
            }
            dataStore.saveTabSession(nonIncognito, savedActive)
        }
    }

    fun destroyAll() {
        persistSessionQuietly()
        _tabs.forEach { destroyWebViewCleanly(it.webView) }
        _tabs.clear()
    }

    private fun destroyWebViewCleanly(wv: WebView) {
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }
    }

    companion object {
        const val HOME_URL = "gf://home"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
