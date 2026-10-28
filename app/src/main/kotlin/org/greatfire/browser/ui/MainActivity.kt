package org.greatfire.browser.ui

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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
import org.greatfire.browser.databinding.DialogProxySettingsBinding
import org.greatfire.browser.vpn.GatewayStatus
import org.greatfire.browser.vpn.TunnelManager
import org.greatfire.browser.web.BrowserTabManager
import java.io.File
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tunnelManager: TunnelManager
    internal lateinit var tabManager: BrowserTabManager

    private var customFullscreenView: View? = null
    private var customFullscreenCallback: WebChromeClient.CustomViewCallback? = null

    data class ChromeShortcut(
        val label: String,
        val badgeText: String,
        val badgeHex: String,
        val url: String
    )

    private val chromeShortcuts = listOf(
        ChromeShortcut("Google", "G", "#4285F4", "https://www.google.com"),
        ChromeShortcut("YouTube", "▶", "#EA4335", "https://m.youtube.com"),
        ChromeShortcut("Instagram", "IG", "#E1306C", "https://www.instagram.com"),
        ChromeShortcut("Telegram", "TG", "#229ED9", "https://web.telegram.org"),
        ChromeShortcut("X", "𝕏", "#1F2937", "https://x.com"),
        ChromeShortcut("Wikipedia", "W", "#5F6368", "https://fa.m.wikipedia.org"),
        ChromeShortcut("BBC فارسی", "B", "#B80000", "https://www.bbc.com/persian"),
        ChromeShortcut("DuckDuckGo", "D", "#DE5833", "https://duckduckgo.com")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tunnelManager = TunnelManager.getInstance(this)
        styleGoogleChromeBrandWordmark()

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
            },
            onEnterFullscreen = { view, callback ->
                enterFullscreenVideo(view, callback)
            },
            onExitFullscreen = {
                exitFullscreenVideo()
            }
        )

        setupToolbarAndControls()
        setupChromeShortcutsGrid()
        setupBackNavigation()

        val launchUrl = extractUrlFromIntent(intent)
        tabManager.createTab(BrowserTabManager.HOME_URL, incognito = false)
        if (!launchUrl.isNullOrBlank()) {
            navigateToUrlSafely(launchUrl)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tunnelManager.status.collect { renderSubtleStatus(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUrlFromIntent(intent)?.let { url ->
            tabManager.createTab(BrowserTabManager.HOME_URL, incognito = false)
            navigateToUrlSafely(url)
        }
    }

    private fun styleGoogleChromeBrandWordmark() {
        // Subtle Chrome-like multicolor accent on "FreeBrowser"
        val word = "FreeBrowser"
        val span = SpannableString(word)
        val colors = intArrayOf(
            Color.parseColor("#4285F4"), // F
            Color.parseColor("#EA4335"), // r
            Color.parseColor("#FBBC05"), // e
            Color.parseColor("#34A853")  // e
        )
        for (i in colors.indices) {
            span.setSpan(ForegroundColorSpan(colors[i]), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvBrandLogo.text = span
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
        binding.btnHome.setOnClickListener {
            val tab = tabManager.activeTab ?: return@setOnClickListener
            tab.currentUrl = BrowserTabManager.HOME_URL
            tab.title = if (tab.incognito) "تب ناشناس" else "تب جدید"
            syncUiWithActiveTab()
        }

        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            val goPressed = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (goPressed) {
                val input = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) {
                    hideKeyboard()
                    binding.etUrl.clearFocus()
                    navigateToInput(input)
                }
                true
            } else {
                false
            }
        }

        binding.etHomeSearch.setOnEditorActionListener { _, actionId, event ->
            val goPressed = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (goPressed) {
                val input = binding.etHomeSearch.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) {
                    binding.etHomeSearch.setText("")
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
                if (typed.isNotEmpty()) {
                    hideKeyboard()
                    navigateToInput(typed)
                }
            } else {
                tab.webView.reload()
            }
        }

        binding.btnTabs.setOnClickListener {
            TabsBottomSheet().show(supportFragmentManager, "tabs_sheet")
        }

        binding.btnMore.setOnClickListener { anchor ->
            showChromeOverflowMenu(anchor)
        }

        binding.tvSubtleStatus.setOnClickListener {
            showProxySettingsDialog()
        }

        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.gf_accent)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.gf_card_surface)
        )
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val wv = tabManager.activeTab?.webView
            wv != null && wv.scrollY > 0
        }
        binding.swipeRefresh.setOnRefreshListener {
            val tab = tabManager.activeTab
            if (tab == null || tab.currentUrl == BrowserTabManager.HOME_URL) {
                binding.swipeRefresh.isRefreshing = false
            } else {
                tab.webView.reload()
            }
        }

        binding.etFindQuery.setOnEditorActionListener { _, _, _ ->
            val q = binding.etFindQuery.text?.toString().orEmpty()
            if (q.isNotEmpty()) {
                tabManager.activeTab?.webView?.findAllAsync(q)
            }
            true
        }
        binding.btnFindNext.setOnClickListener {
            val q = binding.etFindQuery.text?.toString().orEmpty()
            val wv = tabManager.activeTab?.webView ?: return@setOnClickListener
            if (q.isNotEmpty()) {
                wv.findNext(true)
            }
        }
        binding.btnFindClose.setOnClickListener {
            binding.findInPageBar.visibility = View.GONE
            tabManager.activeTab?.webView?.clearMatches()
            hideKeyboard()
        }
    }

    private fun setupChromeShortcutsGrid() {
        binding.quickLinksContainer.removeAllViews()
        val dp = resources.displayMetrics.density

        for (rowIdx in chromeShortcuts.chunked(4)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 4f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (20 * dp).toInt()
                }
            }

            for (item in rowIdx) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    isFocusable = true
                    val outVal = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
                    setBackgroundResource(outVal.resourceId)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                    setOnClickListener { navigateToUrlSafely(item.url) }
                }

                val circleSize = (50 * dp).toInt()
                val circle = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
                    gravity = Gravity.CENTER
                    text = item.badgeText
                    setTextColor(Color.WHITE)
                    textSize = if (item.badgeText.length > 1) 14f else 18f
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(item.badgeHex))
                    }
                }

                val titleTv = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (7 * dp).toInt()
                    }
                    text = item.label
                    textSize = 12f
                    maxLines = 1
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_primary))
                }

                cell.addView(circle)
                cell.addView(titleTv)
                row.addView(cell)
            }
            binding.quickLinksContainer.addView(row)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customFullscreenView != null) {
                    exitFullscreenVideo()
                    return
                }
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

    fun navigateToInput(rawInput: String) {
        val url = buildDestinationUrl(rawInput)
        navigateToUrlSafely(url)
    }

    private fun navigateToUrlSafely(url: String) {
        val tab = tabManager.activeTab ?: tabManager.createTab(BrowserTabManager.HOME_URL, false)
        tab.currentUrl = url
        syncUiWithActiveTab()

        lifecycleScope.launch {
            tunnelManager.ensureRunning()
            tab.webView.loadUrl(url)
        }
    }

    fun buildDestinationUrl(rawInput: String): String = resolveDestinationUrl(rawInput)

    companion object {
        fun resolveDestinationUrl(rawInput: String): String {
            val trimmed = rawInput.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return trimmed
            }
            val looksLikeDomain = !trimmed.contains(' ') &&
                trimmed.contains('.') &&
                !trimmed.startsWith('.') &&
                !trimmed.endsWith('.')
            if (looksLikeDomain) {
                return "https://$trimmed"
            }
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            return "https://www.google.com/search?q=$encoded"
        }
    }

    private fun syncUiWithActiveTab() {
        val tab = tabManager.activeTab ?: return
        binding.btnTabs.text = tabManager.tabs.size.toString()

        // Authentic Chrome Incognito dark bar vs normal Chrome toolbar
        val toolbarColor = if (tab.incognito) {
            ContextCompat.getColor(this, R.color.gf_incognito_bar)
        } else {
            ContextCompat.getColor(this, R.color.gf_bg_surface)
        }
        binding.topToolbar.setBackgroundColor(toolbarColor)

        val isHome = tab.currentUrl == BrowserTabManager.HOME_URL
        if (isHome) {
            binding.homeDashboard.visibility = View.VISIBLE
            binding.swipeRefresh.visibility = View.GONE
            if (!binding.etUrl.hasFocus()) {
                binding.etUrl.setText("")
            }
            binding.ivSslState.setImageResource(R.drawable.ic_search)
        } else {
            binding.homeDashboard.visibility = View.GONE
            binding.swipeRefresh.visibility = View.VISIBLE

            val currentChild = binding.webViewContainer.getChildAt(0)
            if (currentChild !== tab.webView) {
                binding.webViewContainer.removeAllViews()
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                binding.webViewContainer.addView(tab.webView)
            }

            if (!binding.etUrl.hasFocus()) {
                binding.etUrl.setText(tab.currentUrl)
            }
            if (tab.currentUrl.startsWith("https://", ignoreCase = true)) {
                binding.ivSslState.setImageResource(R.drawable.ic_lock)
            } else {
                binding.ivSslState.setImageResource(R.drawable.ic_search)
            }
        }
    }

    private fun renderSubtleStatus(st: GatewayStatus) {
        if (!st.ready) return
        val label = when {
            st.customUri.isNotBlank() -> "● پروکسی اختصاصی فعال است"
            st.readyNodes > 0 && st.bestRttMs > 0 ->
                "● عبور خودکار از فیلترینگ فعال است (${st.bestRttMs}ms)"
            else -> "● عبور خودکار از فیلترینگ فعال است"
        }
        binding.tvSubtleStatus.text = label
    }

    private fun showChromeOverflowMenu(anchor: View) {
        val tab = tabManager.activeTab ?: return
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu

        menu.add(0, 1, 1, "تب جدید")
        menu.add(0, 2, 2, "تب ناشناس جدید")
        if (tab.webView.canGoForward()) {
            menu.add(0, 3, 3, "صفحه بعد (→)")
        }
        if (tab.currentUrl != BrowserTabManager.HOME_URL) {
            menu.add(0, 4, 4, "بازخوانی صفحه")
            val deskLabel = if (tab.desktopMode) "✓ نسخه دسکتاپ" else "نسخه دسکتاپ"
            menu.add(0, 5, 5, deskLabel)
            menu.add(0, 6, 6, "جستجو در صفحه…")
            menu.add(0, 7, 7, "کپی لینک صفحه")
        }
        menu.add(0, 8, 8, "پاک‌سازی تاریخچه و کوکی‌ها")
        menu.add(0, 9, 9, "تنظیمات اتصال هوشمند")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> tabManager.createTab(BrowserTabManager.HOME_URL, incognito = false)
                2 -> tabManager.createTab(BrowserTabManager.HOME_URL, incognito = true)
                3 -> if (tab.webView.canGoForward()) tab.webView.goForward()
                4 -> tab.webView.reload()
                5 -> tabManager.toggleDesktopMode(tab)
                6 -> {
                    binding.findInPageBar.visibility = View.VISIBLE
                    binding.etFindQuery.requestFocus()
                }
                7 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("URL", tab.currentUrl))
                    Toast.makeText(this, "لینک کپی شد", Toast.LENGTH_SHORT).show()
                }
                8 -> {
                    tabManager.panicWipeAll()
                    Toast.makeText(this, R.string.panic_wipe_done, Toast.LENGTH_SHORT).show()
                }
                9 -> showProxySettingsDialog()
            }
            true
        }
        popup.show()
    }

    private fun showProxySettingsDialog() {
        val dialogBinding = DialogProxySettingsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.cbAdBlock.isChecked = tunnelManager.adBlockEnabled
        dialogBinding.etCustomProxyUri.setText(tunnelManager.customProxyUri)

        fun renderDialogStatus(st: GatewayStatus) {
            val modeDesc = if (st.customUri.isNotBlank()) {
                "حالت: پروکسی دلخواه کاربر"
            } else if (st.readyNodes > 0) {
                "حالت: هوشمند خودکار • ${st.readyNodes} سرور پرسرعت آماده (${st.bestRttMs}ms)"
            } else {
                "حالت: هوشمند خودکار (ضد فیلتر DoH + TLS Fragment + تونل موازی)"
            }
            dialogBinding.tvDialogStatus.text = modeDesc
        }

        renderDialogStatus(tunnelManager.status.value)
        lifecycleScope.launch {
            tunnelManager.pollStatusOnce()?.let { renderDialogStatus(it) }
        }

        dialogBinding.cbAdBlock.setOnCheckedChangeListener { _, checked ->
            tunnelManager.adBlockEnabled = checked
        }

        dialogBinding.btnDialogRefreshNodes.setOnClickListener {
            Toast.makeText(this, "درحال بروزرسانی سرورهای پرسرعت در پس‌زمینه…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                tunnelManager.refreshNodesNow()
                kotlinx.coroutines.delay(1800)
                tunnelManager.pollStatusOnce()?.let { renderDialogStatus(it) }
            }
        }

        dialogBinding.btnDialogTestPing.setOnClickListener {
            dialogBinding.btnDialogTestPing.isEnabled = false
            dialogBinding.tvDialogStatus.text = "درحال تست باز شدن یوتیوب…"
            lifecycleScope.launch {
                val res = tunnelManager.runActiveProbe()
                dialogBinding.btnDialogTestPing.isEnabled = true
                dialogBinding.tvDialogStatus.text = if (res.ok) {
                    "✅ اتصال بدون فیلتر برقرار است • پینگ واقعی: ${res.latencyMs}ms"
                } else {
                    "⚠ درحال تعویض خودکار سرور… (${res.error ?: "دوباره امتحان کنید"})"
                }
            }
        }

        dialogBinding.btnDialogClearCustom.setOnClickListener {
            dialogBinding.etCustomProxyUri.setText("")
            lifecycleScope.launch {
                tunnelManager.updateCustomProxy("")
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "حالت هوشمند خودکار فعال شد", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnDialogSave.setOnClickListener {
            val uri = dialogBinding.etCustomProxyUri.text?.toString()?.trim().orEmpty()
            lifecycleScope.launch {
                val res = tunnelManager.updateCustomProxy(uri)
                if (res.isSuccess) {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "تنظیمات ذخیره شد", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        res.exceptionOrNull()?.message ?: "لینک پروکسی معتبر نیست",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        dialog.show()
    }

    private fun enterFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customFullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        customFullscreenView = view
        customFullscreenCallback = callback
        binding.fullscreenContainer.removeAllViews()
        binding.fullscreenContainer.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.fullscreenContainer.visibility = View.VISIBLE
        binding.browserChromeRoot.visibility = View.GONE
    }

    private fun exitFullscreenVideo() {
        val v = customFullscreenView ?: return
        binding.fullscreenContainer.removeView(v)
        binding.fullscreenContainer.visibility = View.GONE
        binding.browserChromeRoot.visibility = View.VISIBLE
        customFullscreenView = null
        customFullscreenCallback?.onCustomViewHidden()
        customFullscreenCallback = null
    }

    private fun downloadFileThroughTunnel(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 901)
            return
        }

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType.ifBlank { null })
        Toast.makeText(this, "شروع دانلود: $fileName", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
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
                        val resolver = contentResolver
                        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("MediaStore URI null")
                        resolver.openOutputStream(itemUri)?.use { out ->
                            body.byteStream().copyTo(out)
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(itemUri, values, null, null)
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        val outFile = File(dir, fileName)
                        outFile.outputStream().use { out -> body.byteStream().copyTo(out) }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "✅ دانلود کامل شد: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطا در دانلود: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        if (isFinishing) {
            tabManager.destroyAll()
        }
        super.onDestroy()
    }
}
