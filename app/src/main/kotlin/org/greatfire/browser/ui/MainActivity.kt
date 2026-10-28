package org.greatfire.browser.ui

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.greatfire.browser.R
import org.greatfire.browser.data.BrowserDataStore
import org.greatfire.browser.data.DownloadRecord
import org.greatfire.browser.data.DownloadState
import org.greatfire.browser.data.SearchEngine
import org.greatfire.browser.data.ShortcutItem
import org.greatfire.browser.databinding.ActivityMainBinding
import org.greatfire.browser.databinding.DialogProxySettingsBinding
import org.greatfire.browser.vpn.GatewayStatus
import org.greatfire.browser.vpn.TunnelManager
import org.greatfire.browser.vpn.VerifiedNodeItem
import org.greatfire.browser.web.BrowserTab
import org.greatfire.browser.web.BrowserTabManager
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tunnelManager: TunnelManager
    internal lateinit var dataStore: BrowserDataStore
    internal lateinit var tabManager: BrowserTabManager

    internal var tunnelAdBlockEnabled: Boolean
        get() = tunnelManager.adBlockEnabled
        set(value) {
            tunnelManager.adBlockEnabled = value
        }

    private var customFullscreenView: View? = null
    private var customFullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingStorageDownload: QuadDownloadArgs? = null
    private var suggestJob: Job? = null
    private var programmaticUrlChange = false

    private data class QuadDownloadArgs(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingFileCallback ?: return@registerForActivityResult
        pendingFileCallback = null
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            }
        } else {
            null
        }
        cb.onReceiveValue(uris)
    }

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                navigateToInput(spoken)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tunnelManager = TunnelManager.getInstance(this)
        dataStore = BrowserDataStore.getInstance(this)

        updateBrandWordmarkForEngine()

        tabManager = BrowserTabManager(
            context = this,
            tunnelManager = tunnelManager,
            dataStore = dataStore,
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
                startManagedDownload(url, ua, cd, mime)
            },
            onContextMenuRequested = { tab, payload ->
                runOnUiThread {
                    ChromeFeaturesDialogs.showWebContextMenuSheet(this, tab, payload)
                }
            },
            onFileChooserRequested = { callback, params ->
                launchWebFileChooser(callback, params)
            },
            onWebSearchSelected = { selectedText ->
                runOnUiThread {
                    openUrlInNewTab(buildDestinationUrl(selectedText), incognito = tabManager.activeTab?.incognito == true, selectImmediately = true)
                }
            },
            onTranslateSelected = { selectedText ->
                runOnUiThread {
                    val trUrl = "https://translate.google.com/?sl=auto&tl=fa&text=" +
                        URLEncoder.encode(selectedText, "UTF-8") + "&op=translate"
                    openUrlInNewTab(trUrl, incognito = tabManager.activeTab?.incognito == true, selectImmediately = true)
                }
            },
            onWebViewTouchDown = {
                runOnUiThread {
                    dismissOmniboxSuggestions()
                    if (binding.etUrl.hasFocus()) {
                        binding.etUrl.clearFocus()
                        hideKeyboard()
                    }
                }
            },
            onEnterFullscreen = { view, callback ->
                enterFullscreenVideo(view, callback)
            },
            onExitFullscreen = {
                exitFullscreenVideo()
            }
        )

        setupToolbarAndOmnibox()
        refreshChromeShortcutsGrid()
        setupBackNavigation()

        val launchTarget = extractTargetFromIntent(intent)
        val restored = tabManager.restorePreviousSessionOrDefault()
        if (!launchTarget.isNullOrBlank()) {
            if (restored && tabManager.activeTab?.currentUrl != BrowserTabManager.HOME_URL) {
                openUrlInNewTab(buildDestinationUrl(launchTarget), incognito = false, selectImmediately = true)
            } else {
                navigateToInput(launchTarget)
            }
        } else if (restored) {
            tabManager.activeTab?.let { cur ->
                if (cur.currentUrl != BrowserTabManager.HOME_URL) {
                    lifecycleScope.launch {
                        tunnelManager.ensureRunning()
                        tabManager.ensureTabLoaded(cur)
                    }
                }
            }
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
        extractTargetFromIntent(intent)?.let { target ->
            openUrlInNewTab(buildDestinationUrl(target), incognito = false, selectImmediately = true)
        }
    }

    override fun onPause() {
        tabManager.persistSessionQuietly()
        super.onPause()
    }

    fun tunnelStatusSnapshot(): GatewayStatus = tunnelManager.status.value

    fun rootViewForSnackbar(): View = binding.rootLayout

    fun updateBrandWordmarkForEngine() {
        val engine = dataStore.searchEngine
        if (engine == SearchEngine.GOOGLE) {
            val word = "Google"
            val span = SpannableString(word)
            val googleColors = intArrayOf(
                Color.parseColor("#4285F4"), // G
                Color.parseColor("#EA4335"), // o
                Color.parseColor("#FBBC05"), // o
                Color.parseColor("#4285F4"), // g
                Color.parseColor("#34A853"), // l
                Color.parseColor("#EA4335")  // e
            )
            for (i in googleColors.indices) {
                span.setSpan(ForegroundColorSpan(googleColors[i]), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            binding.tvBrandLogo.text = span
            binding.etHomeSearch.hint = getString(R.string.home_search_hint)
        } else {
            binding.tvBrandLogo.text = engine.displayName
            binding.etHomeSearch.hint = "جستجو در ${engine.displayName} یا تایپ نشانی وب"
        }
    }

    private fun extractTargetFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.dataString?.trim()
                if (!data.isNullOrBlank() && (data.startsWith("http://", true) || data.startsWith("https://", true))) {
                    data
                } else null
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }
            Intent.ACTION_SEND -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (shared.isEmpty()) null else {
                    val urlMatch = Regex("""https?://\S+""").find(shared)?.value
                    urlMatch ?: shared
                }
            }
            else -> null
        }
    }

    private fun setupToolbarAndOmnibox() {
        binding.btnHome.setOnClickListener {
            dismissOmniboxSuggestions()
            val tab = tabManager.activeTab ?: return@setOnClickListener
            tab.webView.stopLoading()
            tab.isLoading = false
            tab.currentUrl = BrowserTabManager.HOME_URL
            tab.title = if (tab.incognito) "تب ناشناس" else "تب جدید"
            tabManager.persistSessionQuietly()
            syncUiWithActiveTab()
        }

        binding.ivSslState.setOnClickListener {
            val tab = tabManager.activeTab ?: return@setOnClickListener
            ChromeFeaturesDialogs.showSiteSecuritySheet(this, tab)
        }

        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            updateOmniboxAccessoryButtons()
            if (hasFocus) {
                triggerOmniboxSuggestions(binding.etUrl.text?.toString().orEmpty())
            } else {
                binding.rootLayout.postDelayed({
                    if (!binding.etUrl.hasFocus() && !binding.etHomeSearch.hasFocus()) {
                        dismissOmniboxSuggestions()
                        syncUiWithActiveTab()
                    }
                }, 140)
            }
        }

        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateOmniboxAccessoryButtons()
                if (!programmaticUrlChange && binding.etUrl.hasFocus()) {
                    triggerOmniboxSuggestions(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            val goPressed = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (goPressed) {
                val input = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) {
                    dismissOmniboxSuggestions()
                    hideKeyboard()
                    binding.etUrl.clearFocus()
                    navigateToInput(input)
                }
                true
            } else false
        }

        binding.btnClearUrl.setOnClickListener {
            binding.etUrl.setText("")
            binding.etUrl.requestFocus()
        }

        binding.btnVoiceSearch.setOnClickListener { startVoiceRecognition() }
        binding.btnHomeVoice.setOnClickListener { startVoiceRecognition() }

        binding.etHomeSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (binding.etHomeSearch.hasFocus()) {
                    triggerOmniboxSuggestions(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etHomeSearch.setOnEditorActionListener { _, actionId, event ->
            val goPressed = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (goPressed) {
                val input = binding.etHomeSearch.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) {
                    binding.etHomeSearch.setText("")
                    dismissOmniboxSuggestions()
                    hideKeyboard()
                    navigateToInput(input)
                }
                true
            } else false
        }

        binding.btnReload.setOnClickListener {
            dismissOmniboxSuggestions()
            val tab = tabManager.activeTab ?: return@setOnClickListener
            if (tab.currentUrl == BrowserTabManager.HOME_URL) {
                val typed = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (typed.isNotEmpty()) {
                    hideKeyboard()
                    binding.etUrl.clearFocus()
                    navigateToInput(typed)
                }
            } else if (tab.isLoading) {
                tab.webView.stopLoading()
            } else {
                tab.webView.reload()
            }
        }

        binding.btnTabs.setOnClickListener {
            dismissOmniboxSuggestions()
            TabsBottomSheet().show(supportFragmentManager, "tabs_sheet")
        }

        // Authentic Chrome gesture: Long-press [N] tab button for quick New Tab / Incognito / Close Tab menu!
        binding.btnTabs.setOnLongClickListener { anchor ->
            val quick = PopupMenu(this, anchor)
            quick.menu.add(0, 1, 1, "+ تب جدید")
            quick.menu.add(0, 2, 2, "🕶 تب ناشناس جدید")
            quick.menu.add(0, 3, 3, "✕ بستن تب فعلی")
            quick.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> tabManager.createTab(BrowserTabManager.HOME_URL, incognito = false)
                    2 -> tabManager.createTab(BrowserTabManager.HOME_URL, incognito = true)
                    3 -> tabManager.closeTab(tabManager.activeIndex)
                }
                true
            }
            quick.show()
            true
        }

        binding.btnMore.setOnClickListener { anchor ->
            dismissOmniboxSuggestions()
            hideKeyboard()
            ChromeFeaturesDialogs.showChromeOverflowMenu(this, anchor)
        }

        binding.tvSubtleStatus.setOnClickListener {
            showProxySettingsDialog()
        }

        binding.btnQuickSwitchProxy.setOnClickListener {
            switchToAnotherFastestProxyWithToast()
        }

        // SwipeRefreshLayout tuned so it NEVER cancels WebView long-press or text-selection drag handles!
        binding.swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.gf_accent))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.gf_card_surface))
        binding.swipeRefresh.blockInterceptChecker = {
            val wv = tabManager.activeTab?.webView
            wv == null || wv.scrollY > 0 || wv.isSelectionActionModeActive
        }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val wv = tabManager.activeTab?.webView
            wv != null && (wv.scrollY > 0 || wv.isSelectionActionModeActive)
        }
        binding.swipeRefresh.setOnRefreshListener {
            val tab = tabManager.activeTab
            if (tab == null || tab.currentUrl == BrowserTabManager.HOME_URL) {
                binding.swipeRefresh.isRefreshing = false
            } else {
                tab.webView.reload()
            }
        }

        // Chrome Find-in-Page Bar with live match counter + Up/Down
        binding.etFindQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                val wv = tabManager.activeTab?.webView ?: return
                if (q.isEmpty()) {
                    wv.clearMatches()
                    binding.tvFindCount.text = "0/0"
                } else {
                    wv.findAllAsync(q)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.btnFindPrev.setOnClickListener {
            tabManager.activeTab?.webView?.findNext(false)
        }
        binding.btnFindNext.setOnClickListener {
            tabManager.activeTab?.webView?.findNext(true)
        }
        binding.btnFindClose.setOnClickListener {
            closeFindInPageBar()
        }
    }

    private fun updateOmniboxAccessoryButtons() {
        val focused = binding.etUrl.hasFocus()
        val hasText = !binding.etUrl.text.isNullOrEmpty()
        binding.btnClearUrl.visibility = if (focused && hasText) View.VISIBLE else View.GONE
        binding.btnVoiceSearch.visibility = if (!focused || !hasText) View.VISIBLE else View.GONE
    }

    fun openFindInPageBar() {
        val wv = tabManager.activeTab?.webView ?: return
        wv.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            val current = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
            binding.tvFindCount.text = "$current/$numberOfMatches"
        }
        binding.findInPageBar.visibility = View.VISIBLE
        binding.etFindQuery.requestFocus()
        val existing = binding.etFindQuery.text?.toString().orEmpty()
        if (existing.isNotEmpty()) {
            wv.findAllAsync(existing)
        }
    }

    private fun closeFindInPageBar() {
        binding.findInPageBar.visibility = View.GONE
        tabManager.activeTab?.webView?.clearMatches()
        hideKeyboard()
    }

    fun translateCurrentPage(tab: BrowserTab) {
        val cur = tab.currentUrl
        if (!cur.startsWith("http://", true) && !cur.startsWith("https://", true)) return
        if (cur.contains("translate.goog") || cur.contains("translate.google.com/translate")) {
            val orig = runCatching { Uri.parse(cur).getQueryParameter("u") }.getOrNull()
            if (!orig.isNullOrBlank()) {
                navigateToUrlSafely(orig)
                return
            }
        }
        val trUrl = "https://translate.google.com/translate?sl=auto&tl=fa&u=" +
            URLEncoder.encode(cur, "UTF-8")
        navigateToUrlSafely(trUrl)
    }

    fun saveCurrentPageOffline(tab: BrowserTab) {
        if (tab.currentUrl == BrowserTabManager.HOME_URL) return
        val dir = File(filesDir, "downloads").apply { mkdirs() }
        val safeTitle = tab.title.replace(Regex("""[^\p{L}\p{N}_-]+"""), "_").take(32).ifBlank { "page" }
        val outFile = File(dir, "${safeTitle}_${System.currentTimeMillis()}.mht")
        tab.webView.saveWebArchive(outFile.absolutePath, false) { savedPath ->
            if (!savedPath.isNullOrBlank()) {
                val f = File(savedPath)
                dataStore.upsertDownload(
                    DownloadRecord(
                        fileName = f.name,
                        url = tab.currentUrl,
                        mimeType = "multipart/related",
                        bytesRead = f.length(),
                        totalBytes = f.length(),
                        state = DownloadState.COMPLETED,
                        localUriOrPath = f.absolutePath
                    )
                )
                Toast.makeText(this, "✅ صفحه آفلاین ذخیره شد: ${f.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "ذخیره آفلاین پشتیبانی نشد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // Chrome Omnibox Live Autocomplete (Clipboard + Bookmarks/History + Google Suggest)
    // =========================================================================
    private fun triggerOmniboxSuggestions(rawQuery: String) {
        suggestJob?.cancel()
        val q = rawQuery.trim()
        suggestJob = lifecycleScope.launch {
            val rows = ArrayList<Pair<String, String>>(7)

            if (q.isEmpty()) {
                // Chrome feature: suggest Clipboard URL/text right when focusing Omnibox!
                val clipText = readClipboardShortText()
                if (clipText.isNotBlank()) {
                    val label = if (clipText.startsWith("http://", true) || clipText.startsWith("https://", true)) {
                        "📋 رفتن به لینک کپی‌شده"
                    } else {
                        "📋 جستجوی متن کپی‌شده"
                    }
                    rows.add(label to clipText)
                }
                renderSuggestionRows(rows)
                return@launch
            }

            rows.addAll(dataStore.searchBookmarksAndHistory(q, limit = 3))

            delay(140)
            val remote = withContext(Dispatchers.IO) {
                fetchGoogleAutocomplete(q)
            }
            for (sug in remote) {
                if (rows.size >= 6) break
                if (rows.none { it.first.equals(sug, ignoreCase = true) }) {
                    rows.add("🔍 $sug" to sug)
                }
            }
            renderSuggestionRows(rows)
        }
    }

    private fun fetchGoogleAutocomplete(query: String): List<String> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://suggestqueries.google.com/complete/search?client=firefox&hl=fa&q=$encoded")
                .header("User-Agent", "Mozilla/5.0 Chrome/130.0.0.0")
                .build()
            val client = tunnelManager.getTunneledOkHttpClient().newBuilder()
                .connectTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string().orEmpty())
                val items = arr.optJSONArray(1) ?: return emptyList()
                buildList {
                    for (i in 0 until minOf(items.length(), 5)) {
                        val s = items.optString(i).trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun renderSuggestionRows(rows: List<Pair<String, String>>) {
        val box = binding.omniboxSuggestionsContainer
        box.removeAllViews()
        if (rows.isEmpty() || (!binding.etUrl.hasFocus() && !binding.etHomeSearch.hasFocus())) {
            box.visibility = View.GONE
            return
        }
        val dp = resources.displayMetrics.density
        for ((title, target) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                val outVal = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outVal, true)
                setBackgroundResource(outVal.resourceId)
                setPadding((14 * dp).toInt(), (9 * dp).toInt(), (14 * dp).toInt(), (9 * dp).toInt())
                setOnClickListener {
                    dismissOmniboxSuggestions()
                    binding.etHomeSearch.setText("")
                    binding.etUrl.clearFocus()
                    hideKeyboard()
                    navigateToInput(target)
                }
            }
            row.addView(TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_primary))
                textSize = 13.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (target != title && !title.startsWith("🔍 ")) {
                row.addView(TextView(this).apply {
                    text = target
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_secondary))
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textDirection = View.TEXT_DIRECTION_LTR
                })
            }
            box.addView(row)
        }
        box.visibility = View.VISIBLE
    }

    private fun dismissOmniboxSuggestions() {
        suggestJob?.cancel()
        binding.omniboxSuggestionsContainer.visibility = View.GONE
    }

    private fun readClipboardShortText(): String {
        return runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
            val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (txt.length in 2..400) txt else ""
        }.getOrDefault("")
    }

    // =========================================================================
    // Customizable Chrome New Tab Shortcuts Grid (Tap + Long-Press Context Menu)
    // =========================================================================
    fun refreshChromeShortcutsGrid() {
        binding.quickLinksContainer.removeAllViews()
        val dp = resources.displayMetrics.density

        val allItems = dataStore.getShortcuts().toMutableList()
        val addPlaceholder = ShortcutItem(
            id = "__add_new__",
            label = "افزودن سایت",
            badgeText = "+",
            badgeHex = "#5F6368",
            url = ""
        )
        val displayItems = if (allItems.size < 16) allItems + addPlaceholder else allItems

        for (rowChunk in displayItems.chunked(4)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 4f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (18 * dp).toInt() }
            }

            for (item in rowChunk) {
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

                    if (item.id == "__add_new__") {
                        setOnClickListener {
                            ChromeFeaturesDialogs.showShortcutOptionsOrAddDialog(this@MainActivity, null)
                        }
                    } else {
                        setOnClickListener { navigateToUrlSafely(item.url) }
                        setOnLongClickListener { anchor ->
                            showShortcutLongPressMenu(anchor, item)
                            true
                        }
                    }
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
                        setColor(runCatching { Color.parseColor(item.badgeHex) }.getOrDefault(Color.parseColor("#1A73E8")))
                    }
                }

                val titleTv = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (6 * dp).toInt() }
                    text = item.label
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_primary))
                }

                cell.addView(circle)
                cell.addView(titleTv)
                row.addView(cell)
            }
            binding.quickLinksContainer.addView(row)
        }
    }

    private fun showShortcutLongPressMenu(anchor: View, item: ShortcutItem) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 1, "باز کردن در تب جدید")
        popup.menu.add(0, 2, 2, "باز کردن در تب ناشناس")
        popup.menu.add(0, 3, 3, "ویرایش میانبر")
        popup.menu.add(0, 4, 4, "کپی لینک")
        popup.menu.add(0, 5, 5, "حذف میانبر")
        popup.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> openUrlInNewTab(item.url, incognito = false, selectImmediately = true)
                2 -> openUrlInNewTab(item.url, incognito = true, selectImmediately = true)
                3 -> ChromeFeaturesDialogs.showShortcutOptionsOrAddDialog(this, item)
                4 -> ChromeFeaturesDialogs.copyToClipboard(this, "Shortcut", item.url, "لینک کپی شد")
                5 -> {
                    dataStore.removeShortcut(item.id)
                    refreshChromeShortcutsGrid()
                }
            }
            true
        }
        popup.show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customFullscreenView != null) {
                    exitFullscreenVideo()
                    return
                }
                if (binding.omniboxSuggestionsContainer.visibility == View.VISIBLE) {
                    dismissOmniboxSuggestions()
                    return
                }
                if (binding.findInPageBar.visibility == View.VISIBLE) {
                    closeFindInPageBar()
                    return
                }
                val tab = tabManager.activeTab
                if (tab != null && tab.currentUrl != BrowserTabManager.HOME_URL) {
                    if (tab.webView.canGoBack()) {
                        tab.webView.goBack()
                    } else {
                        tab.currentUrl = BrowserTabManager.HOME_URL
                        tab.title = if (tab.incognito) "تب ناشناس" else "تب جدید"
                        tabManager.persistSessionQuietly()
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

    fun openUrlInNewTab(url: String, incognito: Boolean, selectImmediately: Boolean): BrowserTab {
        val newTab = tabManager.createTab(
            initialUrl = BrowserTabManager.HOME_URL,
            incognito = incognito,
            selectImmediately = selectImmediately
        )
        newTab.currentUrl = url
        syncUiWithActiveTab()
        lifecycleScope.launch {
            tunnelManager.ensureRunning()
            newTab.webView.loadUrl(url)
        }
        return newTab
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

    fun buildDestinationUrl(rawInput: String): String =
        resolveDestinationUrl(rawInput, dataStore.searchEngine)

    private fun syncUiWithActiveTab() {
        val tab = tabManager.activeTab ?: return
        val count = tabManager.tabs.size
        binding.btnTabs.text = if (count > 99) ":D" else count.toString()

        val toolbarColor = if (tab.incognito) {
            ContextCompat.getColor(this, R.color.gf_incognito_bar)
        } else {
            ContextCompat.getColor(this, R.color.gf_bg_surface)
        }
        binding.topToolbar.setBackgroundColor(toolbarColor)

        binding.btnReload.setImageResource(
            if (tab.isLoading && tab.currentUrl != BrowserTabManager.HOME_URL) {
                R.drawable.ic_close
            } else {
                R.drawable.ic_refresh
            }
        )

        val isHome = tab.currentUrl == BrowserTabManager.HOME_URL
        if (isHome) {
            binding.homeDashboard.visibility = View.VISIBLE
            binding.swipeRefresh.visibility = View.GONE
            if (!binding.etUrl.hasFocus()) {
                programmaticUrlChange = true
                binding.etUrl.setText("")
                programmaticUrlChange = false
            }
            binding.ivSslState.setImageResource(if (tab.incognito) R.drawable.ic_incognito else R.drawable.ic_search)
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
                programmaticUrlChange = true
                binding.etUrl.setText(tab.currentUrl)
                programmaticUrlChange = false
            }
            when {
                tab.currentUrl.startsWith("https://", ignoreCase = true) ->
                    binding.ivSslState.setImageResource(R.drawable.ic_lock)
                else ->
                    binding.ivSslState.setImageResource(R.drawable.ic_info_outline)
            }
        }
        updateOmniboxAccessoryButtons()
    }

    // =========================================================================
    // Smart First-Launch Status + 1-Tap Switch to Fastest Proxy for User's ISP
    // =========================================================================
    private fun renderSubtleStatus(st: GatewayStatus) {
        if (!st.ready) return
        val speedPart = st.formattedSpeed().let { if (it.isNotEmpty()) " • $it" else "" }
        val label = when {
            st.customUri.isNotBlank() -> "● متصل به پروکسی اختصاصی کاربر"
            st.readyNodes > 0 && st.bestRttMs > 0 ->
                "● متصل به سریع‌ترین پروکسی: ${st.bestNode.ifBlank { "هوشمند" }} (${st.bestRttMs}ms$speedPart)"
            else -> "● اتصال هوشمند ضد فیلتر فعال است"
        }
        binding.tvSubtleStatus.text = label
    }

    private fun switchToAnotherFastestProxyWithToast() {
        binding.btnQuickSwitchProxy.isEnabled = false
        binding.btnQuickSwitchProxy.text = "⏳ درحال تست سرعت…"
        lifecycleScope.launch {
            val res = tunnelManager.switchToBestFastProxy()
            binding.btnQuickSwitchProxy.isEnabled = true
            binding.btnQuickSwitchProxy.text = "⚡ سریع‌ترین پروکسی"
            val msg = if (res.ok) {
                val spd = if (res.bestSpeedKbps >= 1000) String.format(Locale.US, " • %.1f Mbps", res.bestSpeedKbps / 1000.0) else ""
                "✅ متصل به سریع‌ترین پروکسی جدید: ${res.bestNode} (${res.bestRttMs}ms$spd)"
            } else {
                res.error ?: "درحال دریافت سرورهای جدید — چند لحظه دیگر امتحان کنید"
            }
            Snackbar.make(binding.rootLayout, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    fun showProxySettingsDialog() {
        val dialogBinding = DialogProxySettingsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.cbAdBlock.isChecked = tunnelManager.adBlockEnabled
        dialogBinding.etCustomProxyUri.setText(tunnelManager.customProxyUri)

        fun renderDialogStatus(st: GatewayStatus) {
            val speedStr = st.formattedSpeed().let { if (it.isNotEmpty()) " • سرعت: $it" else "" }
            val modeDesc = when {
                st.customUri.isNotBlank() -> "وضعیت: متصل به پروکسی اختصاصی دلخواه کاربر"
                st.readyNodes > 0 ->
                    "✅ متصل به بهترین سرور: ${st.bestNode.ifBlank { "خودکار" }} (${st.bestProto.uppercase()})\n" +
                        "پینگ واقعی: ${st.bestRttMs}ms$speedStr • سرورهای پرسرعت آماده: ${st.readyNodes}"
                else ->
                    "وضعیت: درحال انتخاب سریع‌ترین سرور متناسب با اینترنت شما…\n(ضد DPI مستقیم + DoH فعال است)"
            }
            dialogBinding.tvDialogStatus.text = modeDesc
        }

        fun renderVerifiedNodes(nodes: List<VerifiedNodeItem>) {
            val container = dialogBinding.llVerifiedNodesList
            container.removeAllViews()
            val dp = resources.displayMetrics.density
            if (nodes.isEmpty()) {
                dialogBinding.tvVerifiedListHeader.text = "درحال اسکن سرورهای پرسرعت برای اینترنت شما…"
                return
            }
            dialogBinding.tvVerifiedListHeader.text =
                "برترین سرورهای تست‌شده روی اینترنت شما (${nodes.size} سرور آماده — برای اتصال مستقیم لمس کنید):"

            for (node in nodes.take(6)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(if (node.active) R.drawable.bg_active_tab_card else R.drawable.bg_card_dark)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (6 * dp).toInt() }
                    setPadding((12 * dp).toInt(), (9 * dp).toInt(), (12 * dp).toInt(), (9 * dp).toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        lifecycleScope.launch {
                            val sel = tunnelManager.selectSpecificNode(node.id)
                            if (sel.ok) {
                                tunnelManager.pollStatusOnce()?.let { renderDialogStatus(it) }
                                renderVerifiedNodes(tunnelManager.fetchVerifiedNodes())
                                Toast.makeText(
                                    this@MainActivity,
                                    "✅ متصل شد به: ${sel.bestNode} (${sel.bestRttMs}ms)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                val nameTv = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    val prefix = if (node.active) "🟢 " else "⚪ "
                    text = "$prefix${node.name} (${node.protocol})"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_primary))
                    textSize = 12.5f
                    if (node.active) setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                val spdText = if (node.speedKbps >= 1000) {
                    String.format(Locale.US, "%.1fM • ", node.speedKbps / 1000.0)
                } else ""
                val metricsTv = TextView(this).apply {
                    text = "${spdText}${node.rttMs}ms"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_accent))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                }

                row.addView(nameTv)
                row.addView(metricsTv)
                container.addView(row)
            }
        }

        renderDialogStatus(tunnelManager.status.value)
        lifecycleScope.launch {
            tunnelManager.pollStatusOnce()?.let { renderDialogStatus(it) }
            renderVerifiedNodes(tunnelManager.fetchVerifiedNodes())
        }

        dialogBinding.cbAdBlock.setOnCheckedChangeListener { _, checked ->
            tunnelManager.adBlockEnabled = checked
        }

        // User-requested button: Switch to ANOTHER proxy with the BEST SPEED for current internet!
        dialogBinding.btnSwitchBestProxy.setOnClickListener {
            dialogBinding.btnSwitchBestProxy.isEnabled = false
            dialogBinding.btnSwitchBestProxy.text = "⏳ درحال سنجش سرعت سرورها با اینترنت شما…"
            dialogBinding.tvDialogStatus.text = "درحال تست موازی سرعت دانلود و پینگ سرورهای جدید روی اینترنت فعلی شما…"
            lifecycleScope.launch {
                val res = tunnelManager.switchToBestFastProxy()
                dialogBinding.btnSwitchBestProxy.isEnabled = true
                dialogBinding.btnSwitchBestProxy.text = "⚡ اتصال به پروکسی دیگر با بهترین سرعت"
                if (res.ok) {
                    val spd = if (res.bestSpeedKbps >= 1000) {
                        String.format(Locale.US, " • سرعت: %.1f Mbps", res.bestSpeedKbps / 1000.0)
                    } else ""
                    dialogBinding.etCustomProxyUri.setText("")
                    dialogBinding.tvDialogStatus.text =
                        "✅ با موفقیت به سریع‌ترین پروکسی جدید متصل شد!\n" +
                            "سرور جدید: ${res.bestNode} (${res.bestProto.uppercase()}) • پینگ: ${res.bestRttMs}ms$spd"
                    renderVerifiedNodes(tunnelManager.fetchVerifiedNodes())
                    tabManager.activeTab?.let { cur ->
                        if (cur.currentUrl != BrowserTabManager.HOME_URL) cur.webView.reload()
                    }
                } else {
                    dialogBinding.tvDialogStatus.text =
                        "⚠ ${res.error ?: "درحال دریافت لیست جدید سرورها — چند ثانیه دیگر امتحان کنید"}"
                }
            }
        }

        dialogBinding.btnDialogRefreshNodes.setOnClickListener {
            Toast.makeText(this, "درحال دریافت و تست سرورهای پرسرعت جدید…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                tunnelManager.refreshNodesNow()
                delay(2000)
                tunnelManager.pollStatusOnce()?.let { renderDialogStatus(it) }
                renderVerifiedNodes(tunnelManager.fetchVerifiedNodes())
            }
        }

        dialogBinding.btnDialogTestPing.setOnClickListener {
            dialogBinding.btnDialogTestPing.isEnabled = false
            dialogBinding.tvDialogStatus.text = "درحال تست باز شدن واقعی یوتیوب و سنجش پینگ…"
            lifecycleScope.launch {
                val res = tunnelManager.runActiveProbe()
                dialogBinding.btnDialogTestPing.isEnabled = true
                val spd = if (res.speedKbps >= 1000) {
                    String.format(Locale.US, " • سرعت: %.1f Mbps", res.speedKbps / 1000.0)
                } else ""
                dialogBinding.tvDialogStatus.text = if (res.ok) {
                    "✅ اینترنت بدون فیلتر متصل است!\nسرور: ${res.bestNode.ifBlank { "هوشمند" }} • پینگ واقعی: ${res.latencyMs}ms$spd"
                } else {
                    "⚠ پاسخ کند بود (${res.error ?: "تایم‌اوت"}) — دکمه «اتصال به پروکسی دیگر با بهترین سرعت» را بزنید."
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
                    Toast.makeText(this@MainActivity, "تنظیمات اتصال ذخیره شد", Toast.LENGTH_SHORT).show()
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

    // =========================================================================
    // File Upload Chooser, Voice Search, Fullscreen Video & Managed Downloads
    // =========================================================================
    private fun launchWebFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback

        return try {
            val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            fileChooserLauncher.launch(Intent.createChooser(intent, "انتخاب فایل"))
            true
        } catch (_: Throwable) {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            false
        }
    }

    private fun startVoiceRecognition() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "عبارت مورد نظر را بگویید…")
            }
            voiceSearchLauncher.launch(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "جستجوی صوتی روی این دستگاه فعال نیست", Toast.LENGTH_SHORT).show()
        }
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
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        binding.fullscreenContainer.visibility = View.VISIBLE
        binding.browserChromeRoot.visibility = View.GONE

        WindowCompat.getInsetsController(window, binding.fullscreenContainer).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreenVideo() {
        val v = customFullscreenView ?: return
        binding.fullscreenContainer.removeView(v)
        binding.fullscreenContainer.visibility = View.GONE
        binding.browserChromeRoot.visibility = View.VISIBLE
        customFullscreenView = null
        customFullscreenCallback?.onCustomViewHidden()
        customFullscreenCallback = null

        WindowCompat.getInsetsController(window, binding.rootLayout)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            pendingStorageDownload?.let { args ->
                pendingStorageDownload = null
                startManagedDownload(args.url, args.userAgent, args.contentDisposition, args.mimeType)
            }
        }
    }

    fun startManagedDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStorageDownload = QuadDownloadArgs(url, userAgent, contentDisposition, mimeType)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_STORAGE)
            return
        }

        // Handle data:image/...;base64,... inline images cleanly
        if (url.startsWith("data:", ignoreCase = true)) {
            saveDataUriDownload(url)
            return
        }

        val cleanMime = if (mimeType == "image/*" || mimeType.isBlank()) null else mimeType
        val fileName = URLUtil.guessFileName(url, contentDisposition, cleanMime)
        var record = DownloadRecord(
            fileName = fileName,
            url = url,
            mimeType = cleanMime ?: "application/octet-stream",
            state = DownloadState.DOWNLOADING
        )
        dataStore.upsertDownload(record)

        Snackbar.make(binding.rootLayout, "📥 درحال دانلود: $fileName", Snackbar.LENGTH_SHORT)
            .setAction("دانلودها") { ChromeFeaturesDialogs.showDownloadsSheet(this) }
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                tunnelManager.ensureRunning()
                val reqBuilder = Request.Builder().url(url)
                if (userAgent.isNotBlank()) reqBuilder.header("User-Agent", userAgent)
                tunnelManager.getTunneledOkHttpClient().newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("Empty body")
                    val totalLen = body.contentLength()
                    val resolvedMime = body.contentType()?.toString() ?: record.mimeType

                    var savedLocation = ""
                    var bytesCopied = 0L

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val resolver = contentResolver
                        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("MediaStore URI null")
                        savedLocation = itemUri.toString()

                        resolver.openOutputStream(itemUri)?.use { out ->
                            val buf = ByteArray(16 * 1024)
                            var lastUpdate = 0L
                            val ins = body.byteStream()
                            while (true) {
                                val r = ins.read(buf)
                                if (r <= 0) break
                                out.write(buf, 0, r)
                                bytesCopied += r
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 500L) {
                                    lastUpdate = now
                                    record = record.copy(bytesRead = bytesCopied, totalBytes = totalLen, mimeType = resolvedMime)
                                    dataStore.upsertDownload(record)
                                }
                            }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(itemUri, values, null, null)
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        val outFile = File(dir, fileName)
                        savedLocation = outFile.absolutePath
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(16 * 1024)
                            val ins = body.byteStream()
                            while (true) {
                                val r = ins.read(buf)
                                if (r <= 0) break
                                out.write(buf, 0, r)
                                bytesCopied += r
                            }
                        }
                    }

                    record = record.copy(
                        bytesRead = bytesCopied,
                        totalBytes = totalLen,
                        mimeType = resolvedMime,
                        state = DownloadState.COMPLETED,
                        localUriOrPath = savedLocation
                    )
                    dataStore.upsertDownload(record)
                }

                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.rootLayout, "✅ دانلود کامل شد: $fileName", Snackbar.LENGTH_LONG)
                        .setAction("مشاهده") { ChromeFeaturesDialogs.showDownloadsSheet(this@MainActivity) }
                        .show()
                }
            } catch (t: Throwable) {
                record = record.copy(state = DownloadState.FAILED, errorMsg = t.message.orEmpty())
                dataStore.upsertDownload(record)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطا در دانلود: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveDataUriDownload(dataUri: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val comma = dataUri.indexOf(',')
                require(comma > 5) { "Invalid data URI" }
                val meta = dataUri.substring(5, comma)
                val mime = meta.substringBefore(';').ifBlank { "image/png" }
                val ext = mime.substringAfter('/').take(4).ifBlank { "png" }
                val bytes = if (meta.contains("base64", true)) {
                    Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
                } else {
                    Uri.decode(dataUri.substring(comma + 1)).toByteArray()
                }
                val name = "image_${System.currentTimeMillis()}.$ext"
                val dir = File(filesDir, "downloads").apply { mkdirs() }
                val outFile = File(dir, name)
                outFile.writeBytes(bytes)
                dataStore.upsertDownload(
                    DownloadRecord(
                        fileName = name,
                        url = "",
                        mimeType = mime,
                        bytesRead = bytes.size.toLong(),
                        totalBytes = bytes.size.toLong(),
                        state = DownloadState.COMPLETED,
                        localUriOrPath = outFile.absolutePath
                    )
                )
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.rootLayout, "✅ تصویر ذخیره شد: $name", Snackbar.LENGTH_LONG)
                        .setAction("مشاهده") { ChromeFeaturesDialogs.showDownloadsSheet(this@MainActivity) }
                        .show()
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطا در ذخیره تصویر", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        suggestJob?.cancel()
        tabManager.destroyAll()
        super.onDestroy()
    }

    companion object {
        private const val REQ_WRITE_STORAGE = 901

        fun resolveDestinationUrl(
            rawInput: String,
            engine: SearchEngine = SearchEngine.GOOGLE
        ): String {
            val trimmed = rawInput.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("javascript:", ignoreCase = true)
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
            return engine.buildQueryUrl(trimmed)
        }
    }
}
