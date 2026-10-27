package org.greatfire.browser.ui

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.greatfire.browser.R
import org.greatfire.browser.databinding.ActivityMainBinding
import org.greatfire.browser.vpn.TunnelManager
import org.greatfire.browser.vpn.TunnelUiState
import org.greatfire.browser.vpn.VpnConnectionState
import org.greatfire.browser.web.BrowserTabManager
import java.io.File
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tunnelManager: TunnelManager
    internal lateinit var tabManager: BrowserTabManager

    data class QuickSite(val title: String, val subtitle: String, val url: String)

    private val quickSites = listOf(
        QuickSite("Google", "جستجوی گوگل", "https://www.google.com"),
        QuickSite("YouTube", "ویدیوها بدون فیلتر", "https://m.youtube.com"),
        QuickSite("Wikipedia", "دانشنامه آزاد فارسی", "https://fa.m.wikipedia.org"),
        QuickSite("DuckDuckGo", "جستجوی ناشناس", "https://duckduckgo.com"),
        QuickSite("Telegram Web", "تلگرام وب", "https://web.telegram.org"),
        QuickSite("X / Twitter", "شبکه اجتماعی X", "https://x.com"),
        QuickSite("BBC فارسی", "اخبار بدون سانسور", "https://www.bbc.com/persian"),
        QuickSite("Instagram", "اینستاگرام وب", "https://www.instagram.com"),
        QuickSite("GitHub", "گیت‌هاب", "https://github.com"),
        QuickSite("IP & DNS Check", "تست مخفی بودن آی‌پی", "https://ipleak.net")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tunnelManager = TunnelManager.getInstance(this)
        requestNotificationPermissionIfNeeded()

        tabManager = BrowserTabManager(
            context = this,
            tunnelManager = tunnelManager,
            onTabStateUpdated = { runOnUiThread { syncUiWithActiveTab() } },
            onProgressChanged = { progress ->
                runOnUiThread {
                    if (progress in 1..99) {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.progress = progress
                    } else {
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                    }
                }
            },
            onDownloadRequested = { url, ua, cd, mime ->
                downloadFileThroughTunnel(url, ua, cd, mime)
            }
        )

        setupToolbarAndControls()
        setupQuickAccessGrid()
        setupBackNavigation()

        val launchUrl = extractUrlFromIntent(intent)
        tabManager.createTab(launchUrl ?: BrowserTabManager.HOME_URL, incognito = false)

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "universal"
        binding.tvDeviceCompatInfo.text =
            "GreatFire Envoy + Cronet + IEnvoyProxy + FreeBrowser\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • CPU: $abi"

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tunnelManager.uiState.collect { renderTunnelState(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUrlFromIntent(intent)?.let { url ->
            tabManager.createTab(url, incognito = false)
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.dataString
            if (!data.isNullOrBlank() && (data.startsWith("http://") || data.startsWith("https://"))) {
                return data
            }
        } else if (intent.action == Intent.ACTION_WEB_SEARCH) {
            val q = intent.getStringExtra(SearchManager.QUERY)
            if (!q.isNullOrBlank()) {
                return buildDestinationUrl(q)
            }
        }
        return null
    }

    private fun setupToolbarAndControls() {
        binding.btnVpnShield.setOnClickListener { openVpnHub() }
        binding.btnBottomVpn.setOnClickListener { openVpnHub() }
        binding.btnHeroOpenHub.setOnClickListener { openVpnHub() }

        binding.btnHeroToggleVpn.setOnClickListener {
            val st = tunnelManager.uiState.value.state
            if (st == VpnConnectionState.CONNECTED || st == VpnConnectionState.CONNECTING) {
                tunnelManager.disconnect()
            } else {
                tunnelManager.connect()
            }
        }

        binding.btnHeroProbe.setOnClickListener {
            binding.btnHeroProbe.isEnabled = false
            lifecycleScope.launch {
                val res = tunnelManager.runActiveProbe()
                binding.btnHeroProbe.isEnabled = true
                val msg = if (res.ok) {
                    "✅ اتصال امن برقرار است • پینگ واقعی: ${res.latencyMs}ms"
                } else {
                    "⚠ تست ناموفق: ${res.error ?: "سرور دیگری را از لیست انتخاب کنید"}"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            val goPressed = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (goPressed) {
                val input = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) {
                    hideKeyboard()
                    navigateToInput(input)
                }
                true
            } else {
                false
            }
        }

        binding.btnReload.setOnClickListener {
            val tab = tabManager.activeTab ?: return@setOnClickListener
            if (tab.currentUrl == BrowserTabManager.HOME_URL) {
                val typed = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (typed.isNotEmpty()) navigateToInput(typed)
            } else {
                tab.webView.reload()
            }
        }

        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.gf_accent)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.gf_bg_elevated)
        )
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val wv = tabManager.activeTab?.webView
            wv == null || tabManager.activeTab?.currentUrl == BrowserTabManager.HOME_URL || wv.scrollY > 0
        }
        binding.swipeRefresh.setOnRefreshListener {
            val tab = tabManager.activeTab
            if (tab != null && tab.currentUrl != BrowserTabManager.HOME_URL) {
                tab.webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
            }
        }

        binding.btnTabs.setOnClickListener {
            TabsBottomSheet().show(supportFragmentManager, "tabs_sheet")
        }

        binding.btnMore.setOnClickListener { anchor ->
            showOverflowMenu(anchor)
        }

        binding.btnBack.setOnClickListener {
            val tab = tabManager.activeTab ?: return@setOnClickListener
            if (tab.currentUrl != BrowserTabManager.HOME_URL && tab.webView.canGoBack()) {
                tab.webView.goBack()
            } else if (tab.currentUrl != BrowserTabManager.HOME_URL) {
                tab.currentUrl = BrowserTabManager.HOME_URL
                syncUiWithActiveTab()
            }
        }

        binding.btnForward.setOnClickListener {
            val tab = tabManager.activeTab ?: return@setOnClickListener
            if (tab.webView.canGoForward()) {
                tab.webView.goForward()
            }
        }

        binding.btnHome.setOnClickListener {
            val tab = tabManager.activeTab ?: return@setOnClickListener
            tab.currentUrl = BrowserTabManager.HOME_URL
            binding.etUrl.setText("")
            syncUiWithActiveTab()
        }

        binding.btnPanicWipe.setOnClickListener {
            tabManager.panicWipeAll()
            binding.etUrl.setText("")
            Toast.makeText(this, getString(R.string.panic_wipe_done), Toast.LENGTH_LONG).show()
        }

        // Find-in-page controls
        binding.etFindQuery.setOnEditorActionListener { _, _, _ ->
            val q = binding.etFindQuery.text?.toString().orEmpty()
            tabManager.activeTab?.webView?.findAllAsync(q)
            true
        }
        binding.btnFindNext.setOnClickListener {
            val q = binding.etFindQuery.text?.toString().orEmpty()
            if (q.isNotEmpty()) {
                tabManager.activeTab?.webView?.findNext(true)
            }
        }
        binding.btnFindClose.setOnClickListener {
            binding.findInPageBar.visibility = View.GONE
            tabManager.activeTab?.webView?.clearMatches()
            hideKeyboard()
        }
    }

    private fun setupQuickAccessGrid() {
        binding.quickLinksContainer.removeAllViews()
        val pairs = quickSites.chunked(2)
        val density = resources.displayMetrics.density

        for (rowItems in pairs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }
            }

            rowItems.forEachIndexed { idx, site ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_dark)
                    val pad = (13 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (idx == 0 && rowItems.size > 1) {
                            marginEnd = (8 * density).toInt()
                        }
                    }
                    setOnClickListener { navigateToInput(site.url) }
                }

                val tvTitle = TextView(this).apply {
                    text = site.title
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_primary))
                    textSize = 13.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                val tvSub = TextView(this).apply {
                    text = site.subtitle
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_secondary))
                    textSize = 11.5f
                }
                card.addView(tvTitle)
                card.addView(tvSub)
                row.addView(card)
            }
            binding.quickLinksContainer.addView(row)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.findInPageBar.visibility == View.VISIBLE) {
                    binding.findInPageBar.visibility = View.GONE
                    tabManager.activeTab?.webView?.clearMatches()
                    return
                }
                val tab = tabManager.activeTab
                if (tab != null && tab.currentUrl != BrowserTabManager.HOME_URL) {
                    if (tab.webView.canGoBack()) {
                        tab.webView.goBack()
                    } else {
                        tab.currentUrl = BrowserTabManager.HOME_URL
                        syncUiWithActiveTab()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun navigateToInput(rawInput: String) {
        val url = buildDestinationUrl(rawInput)
        val tab = tabManager.activeTab ?: tabManager.createTab(url)
        tab.currentUrl = url
        syncUiWithActiveTab()
        tab.webView.loadUrl(url)
    }

    private fun buildDestinationUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return trimmed
        }
        val looksLikeDomain = !trimmed.contains(' ') && trimmed.contains('.') &&
            !trimmed.startsWith('.') && !trimmed.endsWith('.')
        if (looksLikeDomain) {
            return "https://$trimmed"
        }
        val q = URLEncoder.encode(trimmed, "UTF-8")
        return "https://duckduckgo.com/?q=$q"
    }

    private fun syncUiWithActiveTab() {
        val tab = tabManager.activeTab ?: return
        binding.btnTabs.text = tabManager.tabs.size.toString()

        binding.topToolbar.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (tab.incognito) R.color.gf_incognito_bar else R.color.gf_bg_surface
            )
        )

        val currentContainerChild = binding.webViewContainer.getChildAt(0)
        if (currentContainerChild !== tab.webView) {
            binding.webViewContainer.removeAllViews()
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            binding.webViewContainer.addView(tab.webView)
        }

        val isHome = tab.currentUrl == BrowserTabManager.HOME_URL
        binding.homeDashboard.visibility = if (isHome) View.VISIBLE else View.GONE

        if (!binding.etUrl.hasFocus()) {
            binding.etUrl.setText(if (isHome) "" else tab.currentUrl)
        }

        val secure = isHome || tab.currentUrl.startsWith("https://", true)
        binding.ivSslState.alpha = if (secure) 1.0f else 0.45f
        binding.btnBack.alpha = if (!isHome) 1.0f else 0.4f
        binding.btnForward.alpha = if (tab.webView.canGoForward()) 1.0f else 0.4f
    }

    private fun renderTunnelState(state: TunnelUiState) {
        when (state.state) {
            VpnConnectionState.CONNECTED -> {
                binding.btnVpnShield.setBackgroundResource(R.drawable.bg_shield_pill_connected)
                binding.ivShieldIcon.setImageResource(R.drawable.ic_shield_ok)
                binding.tvShieldStatus.text = if (state.latencyMs > 0) {
                    "VPN • ${state.latencyMs}ms"
                } else {
                    getString(R.string.vpn_connected)
                }

                binding.tvHeroTitle.text = "تونل ضد فیلتر GreatFire فعال است"
                binding.tvHeroProfile.text = state.activeProfile?.name ?: state.statusDetail
                binding.btnHeroToggleVpn.text = getString(R.string.btn_disconnect)
            }

            VpnConnectionState.CONNECTING -> {
                binding.btnVpnShield.setBackgroundResource(R.drawable.bg_shield_pill_connecting)
                binding.ivShieldIcon.setImageResource(R.drawable.ic_shield_warn)
                binding.tvShieldStatus.text = getString(R.string.vpn_connecting)

                binding.tvHeroTitle.text = "درحال برقراری تونل امن…"
                binding.tvHeroProfile.text = state.statusDetail
                binding.btnHeroToggleVpn.text = getString(R.string.btn_disconnect)
            }

            VpnConnectionState.DISCONNECTED -> {
                binding.btnVpnShield.setBackgroundResource(R.drawable.bg_shield_pill_disconnected)
                binding.ivShieldIcon.setImageResource(R.drawable.ic_shield_warn)
                binding.tvShieldStatus.text = if (state.killSwitchEnabled) {
                    "قفل"
                } else {
                    getString(R.string.vpn_direct)
                }

                binding.tvHeroTitle.text = if (state.killSwitchEnabled) {
                    "VPN متوقف شد (قفل Kill-Switch فعال)"
                } else {
                    "VPN خاموش است"
                }
                binding.tvHeroProfile.text = state.statusDetail
                binding.btnHeroToggleVpn.text = getString(R.string.btn_connect)
            }
        }

        val downStr = formatBytes(state.bytesDown)
        val upStr = formatBytes(state.bytesUp)
        val ksLabel = if (state.killSwitchEnabled) "ON" else "OFF"
        binding.tvHeroMetrics.text =
            "↓ $downStr   ↑ $upStr   •   DoH: ${state.dohQueries}   •   Kill-Switch: $ksLabel"
    }

    private fun showOverflowMenu(anchor: View) {
        val tab = tabManager.activeTab ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, if (tab.desktopMode) "✓ نمای دسکتاپ" else "نمای دسکتاپ")
        popup.menu.add(0, 2, 1, "تب جدید (+)")
        popup.menu.add(0, 3, 2, "تب ناشناس (🕶)")
        popup.menu.add(0, 4, 3, "جستجو در صفحه")
        popup.menu.add(0, 5, 4, "کپی آدرس سایت")
        popup.menu.add(0, 6, 5, "اشتراک‌گذاری لینک")
        popup.menu.add(0, 7, 6, "تنظیمات VPN و سرورها")
        popup.menu.add(0, 8, 7, "پاک‌سازی کامل تاریخچه و کوکی‌ها")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> tabManager.toggleDesktopMode(tab)
                2 -> tabManager.createTab(BrowserTabManager.HOME_URL, incognito = false)
                3 -> tabManager.createTab(BrowserTabManager.HOME_URL, incognito = true)
                4 -> {
                    binding.findInPageBar.visibility = View.VISIBLE
                    binding.etFindQuery.requestFocus()
                }
                5 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("URL", tab.currentUrl))
                    Toast.makeText(this, "لینک کپی شد", Toast.LENGTH_SHORT).show()
                }
                6 -> {
                    if (tab.currentUrl != BrowserTabManager.HOME_URL) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, tab.currentUrl)
                        }
                        startActivity(Intent.createChooser(send, "اشتراک‌گذاری"))
                    }
                }
                7 -> openVpnHub()
                8 -> {
                    tabManager.panicWipeAll()
                    Toast.makeText(this, getString(R.string.panic_wipe_done), Toast.LENGTH_LONG).show()
                }
            }
            true
        }
        popup.show()
    }

    private fun openVpnHub() {
        VpnHubBottomSheet().show(supportFragmentManager, "vpn_hub")
    }

    /**
     * Downloads files THROUGH our active GreatFire VPN tunnel (never via system DownloadManager,
     * which would bypass the browser tunnel and fail on censored sites or leak the user's IP!).
     */
    private fun downloadFileThroughTunnel(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType.ifBlank { null })
        Toast.makeText(this, "⬇ درحال دانلود امن از طریق VPN: $fileName", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val reqBuilder = Request.Builder().url(url)
                    if (userAgent.isNotBlank()) reqBuilder.header("User-Agent", userAgent)
                    tunnelManager.getTunneledOkHttpClient().newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IllegalStateException("Empty body")

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val values = ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                                put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                                put(MediaStore.Downloads.IS_PENDING, 1)
                            }
                            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                ?: throw IllegalStateException("Cannot create download entry")
                            contentResolver.openOutputStream(uri)?.use { out ->
                                body.byteStream().copyTo(out)
                            }
                            values.clear()
                            values.put(MediaStore.Downloads.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                        } else {
                            val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                            if (!baseDir.exists()) baseDir.mkdirs()
                            val dest = File(baseDir, fileName)
                            dest.outputStream().use { out ->
                                body.byteStream().copyTo(out)
                            }
                        }
                        fileName
                    }
                }
            }

            result.onSuccess { saved ->
                Toast.makeText(this@MainActivity, "✅ دانلود کامل شد: $saved", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "خطا در دانلود: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        super.onDestroy()
    }
}
