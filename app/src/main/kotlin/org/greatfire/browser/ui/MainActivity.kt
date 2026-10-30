package org.greatfire.browser.ui

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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
import org.greatfire.browser.download.DownloadEngine
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
    internal lateinit var downloadEngine: DownloadEngine

    internal var tunnelAdBlockEnabled: Boolean
        get() = tunnelManager.adBlockEnabled
        set(value) {
            tunnelManager.adBlockEnabled = value
        }

    private var customFullscreenView: View? = null
    private var customFullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingStorageDownload: PendingDownloadArgs? = null
    private var suggestJob: Job? = null
    private var programmaticUrlChange = false
    private var lastErrorSnackbarAt = 0L

    private data class PendingDownloadArgs(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String,
        val refererUrl: String
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
        downloadEngine = DownloadEngine.getInstance(this)

        downloadEngine.onDownloadEventToast = { msg, _ ->
            runOnUiThread {
                if (!isFinishing) {
                    Snackbar.make(binding.rootLayout, msg, Snackbar.LENGTH_LONG)
                        .setAction("مشاهده") {
                            ChromeFeaturesDialogs.showDownloadsSheet(this)
                        }
                        .show()
                }
            }
        }

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
            onDownloadRequested = { tab, url, ua, cd, mime ->
                runOnUiThread {
                    startManagedDownload(url, ua, cd, mime, tab)
                }
            },
            onBlobDataReceived = { dataUrl, fileName, mime ->
                downloadEngine.saveBlobBase64Payload(dataUrl, fileName, mime)
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
                    openUrlInNewTab(
                        buildDestinationUrl(selectedText),
                        incognito = tabManager.activeTab?.incognito == true,
                        selectImmediately = true
                    )
                }
            },
            onTranslateSelected = { selectedText ->
                runOnUiThread {
                    val trUrl = "https://translate.google.com/?sl=auto&tl=fa&text=" +
                        URLEncoder.encode(selectedText, "UTF-8") + "&op=translate"
                    openUrlInNewTab(
                        trUrl,
                        incognito = tabManager.activeTab?.incognito == true,
                        selectImmediately = true
                    )
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
            onMainFrameError = { tab, _ ->
                runOnUiThread {
                    val now = System.currentTimeMillis()
                    if (now - lastErrorSnackbarAt > 6_000L && !isFinishing) {
                        lastErrorSnackbarAt = now
                        Snackbar.make(binding.rootLayout, "اختلال در بارگذاری صفحه", Snackbar.LENGTH_LONG)
                            .setAction("⚡ تعویض پروکسی و تلاش مجدد") {
                                lifecycleScope.launch {
                                    tunnelManager.switchToBestFastProxy()
                                    tab.webView.reload()
                                }
                            }
                            .show()
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
            tabManager.activeTab?.let { ensureTabRestoredAndLoaded(it) }
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
            if (isProxyConfigShareInput(target)) {
                handlePastedProxyConfigFromOmnibox(target)
            } else {
                openUrlInNewTab(buildDestinationUrl(target), incognito = false, selectImmediately = true)
            }
        }
    }

    override fun onPause() {
        tabManager.persistSessionQuietly()
        super.onPause()
    }

    fun ensureTabRestoredAndLoaded(tab: BrowserTab) {
        if (tab.currentUrl != BrowserTabManager.HOME_URL && tab.needsRestoreLoad) {
            lifecycleScope.launch {
                tunnelManager.ensureRunning()
                tabManager.ensureTabLoaded(tab)
            }
        }
    }

    fun tunnelStatusSnapshot(): GatewayStatus = tunnelManager.status.value

    fun rootViewForSnackbar(): View = binding.rootLayout

    fun updateBrandWordmarkForEngine() {
        val engine = dataStore.searchEngine
        if (engine == SearchEngine.GOOGLE) {
            val word = "Google"
            val span = SpannableString(word)
            val googleColors = intArrayOf(
                Color.parseColor("#4285F4"),
                Color.parseColor("#EA4335"),
                Color.parseColor("#FBBC05"),
                Color.parseColor("#4285F4"),
                Color.parseColor("#34A853"),
                Color.parseColor("#EA4335")
            )
            for (i in googleColors.indices) {
                span.setSpan(ForegroundColorSpan(googleColors[i]), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            binding.tvBrandLogo.text = span
            binding.tvHomeSearchHint.text = getString(R.string.home_search_hint)
        } else {
            binding.tvBrandLogo.text = engine.displayName
            binding.tvHomeSearchHint.text = "جستجو در ${engine.displayName} یا تایپ نشانی وب"
        }
    }

    private fun extractTargetFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.dataString?.trim()
                if (!data.isNullOrBlank() && (
                        data.startsWith("http://", true) ||
                            data.startsWith("https://", true) ||
                            isProxyConfigShareInput(data)
                        )
                ) {
                    data
                } else null
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }
            Intent.ACTION_SEND -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (shared.isEmpty()) null else {
                    if (isProxyConfigShareInput(shared)) {
                        shared
                    } else {
                        Regex("""https?://\S+""").find(shared)?.value ?: shared
                    }
                }
            }
            else -> null
        }
    }

    // =========================================================================
    // Fixed & Perfected Google Chrome Omnibox / Address Bar
    // =========================================================================
    private fun setupToolbarAndOmnibox() {
        binding.btnHome.setOnClickListener {
            dismissOmniboxSuggestions()
            hideKeyboard()
            binding.etUrl.clearFocus()
            val tab = tabManager.activeTab ?: return@setOnClickListener
            tabManager.navigateTabToHome(tab)
            syncUiWithActiveTab()
        }

        binding.ivSslState.setOnClickListener {
            val tab = tabManager.activeTab ?: return@setOnClickListener
            if (binding.etUrl.hasFocus()) {
                val typed = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (typed.isNotEmpty()) {
                    submitOmniboxInput(typed)
                }
            } else {
                ChromeFeaturesDialogs.showSiteSecuritySheet(this, tab)
            }
        }

        // Tapping anywhere on the Omnibox pill focuses etUrl and opens keyboard cleanly
        binding.omniboxContainer.setOnClickListener {
            focusOmniboxAndShowKeyboard()
        }

        // Tapping the New Tab Page Fakebox focuses top Omnibox seamlessly (no split-brain!)
        binding.homeSearchBox.setOnClickListener {
            focusOmniboxAndShowKeyboard(clearTextFirst = true)
        }

        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val tab = tabManager.activeTab
                if (tab != null && tab.currentUrl != BrowserTabManager.HOME_URL) {
                    // Expand display URL to full editable URL and select all (authentic Chrome behavior!)
                    programmaticUrlChange = true
                    binding.etUrl.setText(tab.currentUrl)
                    applySmartOmniboxDirection(binding.etUrl, tab.currentUrl)
                    programmaticUrlChange = false
                    binding.etUrl.post {
                        if (binding.etUrl.hasFocus()) {
                            binding.etUrl.selectAll()
                        }
                    }
                    // Show Current-Page Copy/Share/Edit + Clipboard + History before user types
                    triggerOmniboxSuggestions("", showCurrentPageHeader = true)
                } else {
                    applySmartOmniboxDirection(binding.etUrl, binding.etUrl.text)
                    triggerOmniboxSuggestions(binding.etUrl.text?.toString().orEmpty(), showCurrentPageHeader = false)
                }
                updateOmniboxAccessoryButtons()
            } else {
                binding.rootLayout.postDelayed({
                    if (!binding.etUrl.hasFocus()) {
                        dismissOmniboxSuggestions()
                        syncUiWithActiveTab()
                    }
                }, 120)
            }
        }

        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applySmartOmniboxDirection(binding.etUrl, s)
                updateOmniboxAccessoryButtons()
                if (!programmaticUrlChange && binding.etUrl.hasFocus()) {
                    val text = s?.toString().orEmpty()
                    val tab = tabManager.activeTab
                    val unchangedPageUrl = tab != null &&
                        tab.currentUrl != BrowserTabManager.HOME_URL &&
                        text == tab.currentUrl
                    triggerOmniboxSuggestions(
                        rawQuery = if (unchangedPageUrl) "" else text,
                        showCurrentPageHeader = unchangedPageUrl || text.isEmpty()
                    )
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            val goPressed = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null &&
                    (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                    event.action == KeyEvent.ACTION_DOWN)
            if (goPressed) {
                val input = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) {
                    submitOmniboxInput(input)
                }
                true
            } else false
        }

        binding.btnClearUrl.setOnClickListener {
            programmaticUrlChange = false
            binding.etUrl.setText("")
            applySmartOmniboxDirection(binding.etUrl, "")
            if (!binding.etUrl.hasFocus()) {
                focusOmniboxAndShowKeyboard()
            } else {
                showKeyboardFor(binding.etUrl)
                triggerOmniboxSuggestions("", showCurrentPageHeader = true)
            }
        }

        binding.btnVoiceSearch.setOnClickListener { startVoiceRecognition() }
        binding.btnHomeVoice.setOnClickListener { startVoiceRecognition() }

        binding.btnReload.setOnClickListener {
            val tab = tabManager.activeTab ?: return@setOnClickListener
            if (binding.etUrl.hasFocus()) {
                val typed = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (typed.isNotEmpty()) {
                    submitOmniboxInput(typed)
                    return@setOnClickListener
                }
            }
            dismissOmniboxSuggestions()
            if (tab.currentUrl == BrowserTabManager.HOME_URL) {
                focusOmniboxAndShowKeyboard()
            } else if (tab.isLoading) {
                tab.webView.stopLoading()
            } else {
                tab.webView.reload()
            }
        }

        binding.btnTabs.setOnClickListener {
            dismissOmniboxSuggestions()
            hideKeyboard()
            TabsBottomSheet().show(supportFragmentManager, "tabs_sheet")
        }

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
            binding.etUrl.clearFocus()
            ChromeFeaturesDialogs.showChromeOverflowMenu(this, anchor)
        }

        binding.proxyQuickBar.setOnClickListener {
            showProxySettingsDialog()
        }
        binding.tvSubtleStatus.setOnClickListener {
            showProxySettingsDialog()
        }
        binding.btnQuickSwitchProxy.setOnClickListener {
            switchToAnotherFastestProxyWithToast()
        }

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

    private fun focusOmniboxAndShowKeyboard(clearTextFirst: Boolean = false) {
        if (clearTextFirst) {
            programmaticUrlChange = true
            binding.etUrl.setText("")
            applySmartOmniboxDirection(binding.etUrl, "")
            programmaticUrlChange = false
        }
        binding.etUrl.requestFocus()
        showKeyboardFor(binding.etUrl)
    }

    private fun showKeyboardFor(view: EditText) {
        view.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submitOmniboxInput(rawInput: String) {
        dismissOmniboxSuggestions()
        hideKeyboard()
        binding.etUrl.clearFocus()
        navigateToInput(rawInput)
    }

    private fun updateOmniboxAccessoryButtons() {
        val focused = binding.etUrl.hasFocus()
        val hasText = !binding.etUrl.text.isNullOrEmpty()
        binding.btnClearUrl.visibility = if (focused && hasText) View.VISIBLE else View.GONE
        binding.btnVoiceSearch.visibility = if (!focused || !hasText) View.VISIBLE else View.GONE

        val tab = tabManager.activeTab
        if (focused && hasText) {
            binding.btnReload.setImageResource(R.drawable.ic_arrow_forward)
            binding.ivSslState.setImageResource(R.drawable.ic_search)
        } else if (tab != null) {
            binding.btnReload.setImageResource(
                if (tab.isLoading && tab.currentUrl != BrowserTabManager.HOME_URL) {
                    R.drawable.ic_close
                } else {
                    R.drawable.ic_refresh
                }
            )
        }
    }

    fun openFindInPageBar() {
        val wv = tabManager.activeTab?.webView ?: return
        wv.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            val current = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
            binding.tvFindCount.text = "$current/$numberOfMatches"
        }
        binding.findInPageBar.visibility = View.VISIBLE
        binding.etFindQuery.requestFocus()
        showKeyboardFor(binding.etFindQuery)
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
        val extDir = runCatching { getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        val dir = (extDir ?: File(filesDir, "downloads")).apply { mkdirs() }
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
                        localUriOrPath = f.absolutePath,
                        stagingPath = f.absolutePath
                    )
                )
                downloadEngine.refreshSnapshot()
                Snackbar.make(binding.rootLayout, "✅ صفحه آفلاین ذخیره شد: ${f.name}", Snackbar.LENGTH_LONG)
                    .setAction("مشاهده") { ChromeFeaturesDialogs.showDownloadsSheet(this) }
                    .show()
            } else {
                Toast.makeText(this, "ذخیره آفلاین پشتیبانی نشد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // Chrome Omnibox Live Dropdown (Current Page Bar + Clipboard + History + Google Suggest)
    // =========================================================================
    private data class OmniboxSuggestionItem(
        val title: String,
        val subtitle: String = "",
        val targetValue: String,
        val isProxyConfig: Boolean = false
    )

    private fun triggerOmniboxSuggestions(rawQuery: String, showCurrentPageHeader: Boolean = false) {
        suggestJob?.cancel()
        val q = stripInvisibleBidiMarks(rawQuery).trim()
        suggestJob = lifecycleScope.launch {
            val items = ArrayList<OmniboxSuggestionItem>(8)

            if (q.isEmpty()) {
                val clipText = readClipboardCandidate()
                if (clipText.isNotBlank()) {
                    when {
                        isProxyConfigShareInput(clipText) -> {
                            items.add(
                                OmniboxSuggestionItem(
                                    title = "⚡ افزودن کانفیگ کپی‌شده به پروکسی ضدسانسور",
                                    subtitle = clipText.lineSequence().firstOrNull()?.take(70).orEmpty(),
                                    targetValue = clipText,
                                    isProxyConfig = true
                                )
                            )
                        }
                        clipText.startsWith("http://", true) || clipText.startsWith("https://", true) -> {
                            items.add(
                                OmniboxSuggestionItem(
                                    title = "📋 رفتن به لینک کپی‌شده",
                                    subtitle = clipText,
                                    targetValue = clipText
                                )
                            )
                        }
                        clipText.length <= 160 -> {
                            items.add(
                                OmniboxSuggestionItem(
                                    title = "📋 جستجوی متن کپی‌شده",
                                    subtitle = clipText,
                                    targetValue = clipText
                                )
                            )
                        }
                    }
                }

                // Recent history & bookmarks quick access when address bar is empty
                for (b in dataStore.getBookmarks().take(2)) {
                    items.add(OmniboxSuggestionItem("★ ${b.title}", b.url, b.url))
                }
                for (h in dataStore.getHistory().take(3)) {
                    if (items.none { it.targetValue.equals(h.url, ignoreCase = true) }) {
                        items.add(OmniboxSuggestionItem("🕒 ${h.title}", h.url, h.url))
                    }
                }
                renderSuggestionRows(items, showCurrentPageHeader = showCurrentPageHeader)
                return@launch
            }

            // Direct URL / Search typed row at the top so user sees exact action
            if (isProxyConfigShareInput(q)) {
                items.add(
                    OmniboxSuggestionItem(
                        title = "⚡ افزودن و اتصال به این کانفیگ پروکسی",
                        subtitle = q.take(70),
                        targetValue = q,
                        isProxyConfig = true
                    )
                )
            } else if (isLikelyHostOrUrl(q)) {
                val dest = resolveDestinationUrl(q, dataStore.searchEngine)
                items.add(OmniboxSuggestionItem("🌐 باز کردن سایت $q", dest, dest))
            } else {
                items.add(OmniboxSuggestionItem("🔍 جستجوی «$q»", "", q))
            }

            for ((title, url) in dataStore.searchBookmarksAndHistory(q, limit = 3)) {
                if (items.none { it.targetValue.equals(url, ignoreCase = true) }) {
                    items.add(OmniboxSuggestionItem(title, url, url))
                }
            }
            renderSuggestionRows(items, showCurrentPageHeader = false)

            delay(130)
            val remote = withContext(Dispatchers.IO) {
                fetchGoogleAutocomplete(q)
            }
            for (sug in remote) {
                if (items.size >= 8) break
                if (items.none { it.targetValue.equals(sug, ignoreCase = true) }) {
                    items.add(OmniboxSuggestionItem("🔍 $sug", "", sug))
                }
            }
            renderSuggestionRows(items, showCurrentPageHeader = false)
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

    private fun renderSuggestionRows(
        rows: List<OmniboxSuggestionItem>,
        showCurrentPageHeader: Boolean
    ) {
        val box = binding.omniboxSuggestionsContainer
        val scroll = binding.omniboxSuggestionsScroll
        box.removeAllViews()
        if (!binding.etUrl.hasFocus()) {
            scroll.visibility = View.GONE
            return
        }

        val dp = resources.displayMetrics.density
        val activeTab = tabManager.activeTab
        val hasCurrentPageCard = showCurrentPageHeader &&
            activeTab != null &&
            activeTab.currentUrl != BrowserTabManager.HOME_URL

        if (rows.isEmpty() && !hasCurrentPageCard) {
            scroll.visibility = View.GONE
            return
        }

        // Authentic Chrome Current-Page Action Row (Copy Link | Share | Edit URL)
        if (hasCurrentPageCard && activeTab != null) {
            val pageCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            }
            pageCard.addView(TextView(this).apply {
                text = activeTab.title.ifBlank { activeTab.currentUrl }
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_primary))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            pageCard.addView(TextView(this).apply {
                text = activeTab.currentUrl
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_secondary))
                textSize = 11.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                textDirection = View.TEXT_DIRECTION_LTR
            })

            val actionBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
            }

            fun makePageQuickBtn(label: String, onClick: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_accent))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_omnibox)
                    setPadding((11 * dp).toInt(), (5 * dp).toInt(), (11 * dp).toInt(), (5 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (8 * dp).toInt() }
                    setOnClickListener { onClick() }
                }
            }

            actionBar.addView(makePageQuickBtn("📋 کپی لینک") {
                ChromeFeaturesDialogs.copyToClipboard(this, "Page URL", activeTab.currentUrl, "آدرس صفحه کپی شد")
                dismissOmniboxSuggestions()
                binding.etUrl.clearFocus()
                hideKeyboard()
            })
            actionBar.addView(makePageQuickBtn("📤 اشتراک") {
                dismissOmniboxSuggestions()
                binding.etUrl.clearFocus()
                hideKeyboard()
                ChromeFeaturesDialogs.sharePlainText(this, activeTab.currentUrl, "اشتراک‌گذاری آدرس")
            })
            actionBar.addView(makePageQuickBtn("✏️ ویرایش آدرس") {
                programmaticUrlChange = true
                binding.etUrl.setText(activeTab.currentUrl)
                applySmartOmniboxDirection(binding.etUrl, activeTab.currentUrl)
                binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
                programmaticUrlChange = false
                showKeyboardFor(binding.etUrl)
            })

            pageCard.addView(actionBar)
            box.addView(pageCard)

            box.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.gf_border))
            })
        }

        for (item in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val outVal = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outVal, true)
                setBackgroundResource(outVal.resourceId)
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                setOnClickListener {
                    dismissOmniboxSuggestions()
                    binding.etUrl.clearFocus()
                    hideKeyboard()
                    if (item.isProxyConfig) {
                        handlePastedProxyConfigFromOmnibox(item.targetValue)
                    } else {
                        navigateToInput(item.targetValue)
                    }
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = item.title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_primary))
                textSize = 13.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (item.subtitle.isNotBlank()) {
                textCol.addView(TextView(this).apply {
                    text = item.subtitle
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_secondary))
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    textDirection = View.TEXT_DIRECTION_LTR
                })
            }
            row.addView(textCol)

            // Chrome Fill-into-Omnibox button (`↖`) so user can edit a suggestion before hitting Go!
            if (!item.isProxyConfig) {
                val fillBtn = TextView(this).apply {
                    text = "↖"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_secondary))
                    layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
                    setOnClickListener {
                        programmaticUrlChange = false
                        binding.etUrl.setText(item.targetValue)
                        applySmartOmniboxDirection(binding.etUrl, item.targetValue)
                        binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
                        showKeyboardFor(binding.etUrl)
                    }
                }
                row.addView(fillBtn)
            }

            box.addView(row)
        }
        scroll.visibility = View.VISIBLE
    }

    private fun dismissOmniboxSuggestions() {
        suggestJob?.cancel()
        binding.omniboxSuggestionsScroll.visibility = View.GONE
    }

    private fun readClipboardCandidate(): String {
        return runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
            val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (txt.length in 2..4000) txt else ""
        }.getOrDefault("")
    }

    // =========================================================================
    // Customizable Chrome New Tab Shortcuts Grid
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
                if (binding.omniboxSuggestionsScroll.visibility == View.VISIBLE || binding.etUrl.hasFocus()) {
                    dismissOmniboxSuggestions()
                    binding.etUrl.clearFocus()
                    hideKeyboard()
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
                        tabManager.navigateTabToHome(tab)
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
        val cleaned = stripInvisibleBidiMarks(rawInput).trim()
        if (cleaned.isEmpty()) return
        if (isProxyConfigShareInput(cleaned)) {
            handlePastedProxyConfigFromOmnibox(cleaned)
            return
        }
        val url = buildDestinationUrl(cleaned)
        navigateToUrlSafely(url)
    }

    private fun handlePastedProxyConfigFromOmnibox(configInput: String) {
        Toast.makeText(this, "⏳ درحال تست و افزودن کانفیگ پروکسی…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = tunnelManager.addCustomConfigsOrSub(configInput)
            val msg = when {
                res.ok && res.workingCount > 0 ->
                    "✅ کانفیگ فعال شد: ${res.bestNode} (${res.bestRttMs}ms)"
                res.ok && res.addedCount > 0 ->
                    "⚠ کانفیگ ذخیره شد اما سرور آن فعلاً پاسخ نداد — در تنظیمات پروکسی قابل مدیریت است"
                else ->
                    "⚠ خطا در کانفیگ: ${res.error ?: "فرمت نامعتبر"}"
            }
            Snackbar.make(binding.rootLayout, msg, Snackbar.LENGTH_LONG)
                .setAction("پروکسی‌ها") { showProxySettingsDialog() }
                .show()
        }
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
        if (url == BrowserTabManager.HOME_URL) {
            tabManager.activeTab?.let { tabManager.navigateTabToHome(it) }
            syncUiWithActiveTab()
            return
        }
        val tab = tabManager.activeTab ?: tabManager.createTab(BrowserTabManager.HOME_URL, false)
        tab.needsRestoreLoad = false
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

        val isHome = tab.currentUrl == BrowserTabManager.HOME_URL
        if (isHome) {
            binding.homeDashboard.visibility = View.VISIBLE
            binding.swipeRefresh.visibility = View.GONE
            if (!binding.etUrl.hasFocus()) {
                programmaticUrlChange = true
                binding.etUrl.setText("")
                applySmartOmniboxDirection(binding.etUrl, "")
                programmaticUrlChange = false
                binding.ivSslState.setImageResource(
                    if (tab.incognito) R.drawable.ic_incognito else R.drawable.ic_search
                )
            }
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
                val displayUrl = formatDisplayOmniboxUrl(tab.currentUrl)
                programmaticUrlChange = true
                binding.etUrl.setText(displayUrl)
                applySmartOmniboxDirection(binding.etUrl, displayUrl, forceLtr = true)
                programmaticUrlChange = false

                when {
                    tab.currentUrl.startsWith("https://", ignoreCase = true) ->
                        binding.ivSslState.setImageResource(R.drawable.ic_lock)
                    else ->
                        binding.ivSslState.setImageResource(R.drawable.ic_info_outline)
                }
            }
        }
        updateOmniboxAccessoryButtons()
    }

    // =========================================================================
    // Multi-Source Proxy Status + Upgraded Custom Config & Sub Manager Sheet
    // =========================================================================
    private fun renderSubtleStatus(st: GatewayStatus) {
        if (!st.ready) return
        binding.tvSubtleStatus.text = formatHomeProxyStatus(st)
    }

    private fun switchToAnotherFastestProxyWithToast() {
        binding.btnQuickSwitchProxy.isEnabled = false
        binding.btnQuickSwitchProxy.text = "⏳ تست سرعت…"
        lifecycleScope.launch {
            val res = tunnelManager.switchToBestFastProxy()
            binding.btnQuickSwitchProxy.isEnabled = true
            binding.btnQuickSwitchProxy.text = "⚡ سریع‌ترین پروکسی"
            val msg = if (res.ok) {
                val src = if (res.bestSource.isNotBlank()) " [${res.bestSource}]" else ""
                val spd = if (res.bestSpeedKbps >= 1000) {
                    String.format(Locale.US, " • %.1f Mbps", res.bestSpeedKbps / 1000.0)
                } else ""
                "✅ متصل شد: ${res.bestNode}$src (${res.bestRttMs}ms$spd)"
            } else {
                res.error ?: "درحال دریافت سرورهای جدید از ۱۰ منبع — چند لحظه دیگر امتحان کنید"
            }
            Snackbar.make(binding.rootLayout, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    fun showProxySettingsDialog() {
        val dialogBinding = DialogProxySettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.cbAdBlock.isChecked = tunnelManager.adBlockEnabled

        fun renderDialogStatus(st: GatewayStatus) {
            val speedStr = st.formattedSpeed().let { if (it.isNotEmpty()) " • سرعت: $it" else "" }
            val srcBadge = if (st.bestSource.isNotBlank()) " [${st.bestSource}]" else ""
            val sourcesInfo = if (st.activeSources > 0) " از ${st.activeSources} منبع مستقل" else ""
            val modeDesc = when {
                st.readyNodes > 0 ->
                    "✅ متصل به سرور فعال: ${st.bestNode.ifBlank { "خودکار" }}$srcBadge (${st.bestProto.uppercase()})\n" +
                        "پینگ واقعی: ${st.bestRttMs}ms$speedStr • ${st.readyNodes} سرور آماده$sourcesInfo"
                else ->
                    "⏳ درحال دریافت و تست سرورها از ۱۰ منبع مختلف…\n(ضد DPI مستقیم + DoH فعال است)"
            }
            dialogBinding.tvDialogStatus.text = modeDesc
        }

        fun renderCustomSavedSection(customNodes: List<VerifiedNodeItem>, customSubs: List<String>) {
            val box = dialogBinding.llCustomConfigsList
            box.removeAllViews()
            if (customNodes.isEmpty() && customSubs.isEmpty()) return

            val dp = resources.displayMetrics.density
            box.addView(TextView(this).apply {
                text = "کانفیگ‌ها و اشتراک‌های ذخیره‌شده شما (${customNodes.size} کانفیگ${if (customSubs.isNotEmpty()) " • ${customSubs.size} اشتراک" else ""}):"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_accent))
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            })

            for (subUrl in customSubs) {
                val subRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_omnibox)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (5 * dp).toInt() }
                    setPadding((10 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
                }
                subRow.addView(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = "🔗 اشتراک: $subUrl"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_secondary))
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    textDirection = View.TEXT_DIRECTION_LTR
                })
                subRow.addView(ImageButton(this).apply {
                    layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt())
                    setImageResource(R.drawable.ic_close)
                    background = null
                    setPadding((5 * dp).toInt(), (5 * dp).toInt(), (5 * dp).toInt(), (5 * dp).toInt())
                    setOnClickListener {
                        lifecycleScope.launch {
                            tunnelManager.deleteCustomConfigOrSub(subUrl = subUrl)
                            val (cn, cs) = tunnelManager.fetchCustomConfigsAndSubs()
                            renderCustomSavedSection(cn, cs)
                            tunnelManager.pollStatusOnce()?.let { renderDialogStatus(it) }
                        }
                    }
                })
                box.addView(subRow)
            }

            for (cnode in customNodes.take(12)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(if (cnode.active) R.drawable.bg_active_tab_card else R.drawable.bg_omnibox)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (5 * dp).toInt() }
                    setPadding((10 * dp).toInt(), (7 * dp).toInt(), (6 * dp).toInt(), (7 * dp).toInt())
                    setOnClickListener {
                        lifecycleScope.launch {
                            val sel = tunnelManager.selectSpecificNode(cnode.id)
                            if (sel.ok) {
                                tunnelManager.pollStatusOnce()?.let { renderDialogStatus(it) }
                                val (cn, cs) = tunnelManager.fetchCustomConfigsAndSubs()
                                renderCustomSavedSection(cn, cs)
                                Toast.makeText(
                                    this@MainActivity,
                                    "✅ متصل شد به کانفیگ: ${sel.bestNode}" +
                                        if (sel.bestRttMs > 0) " (${sel.bestRttMs}ms)" else "",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    sel.error ?: "این کانفیگ پاسخ نداد",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                val prefix = if (cnode.active) "🟢 ★ " else "★ "
                val pingBadge = if (cnode.rttMs > 0) " • ${cnode.rttMs}ms" else " • تست/اتصال"
                row.addView(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = "$prefix${cnode.name} (${cnode.protocol})$pingBadge"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_primary))
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })

                row.addView(ImageButton(this).apply {
                    layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt())
                    setImageResource(R.drawable.ic_close)
                    background = null
                    setPadding((5 * dp).toInt(), (5 * dp).toInt(), (5 * dp).toInt(), (5 * dp).toInt())
                    setOnClickListener {
                        lifecycleScope.launch {
                            tunnelManager.deleteCustomConfigOrSub(nodeId = cnode.id)
                            val (cn, cs) = tunnelManager.fetchCustomConfigsAndSubs()
                            renderCustomSavedSection(cn, cs)
                            tunnelManager.pollStatusOnce()?.let { renderDialogStatus(it) }
                        }
                    }
                })
                box.addView(row)
            }
        }

        fun renderVerifiedNodes(nodes: List<VerifiedNodeItem>) {
            val container = dialogBinding.llVerifiedNodesList
            container.removeAllViews()
            val dp = resources.displayMetrics.density
            if (nodes.isEmpty()) {
                dialogBinding.tvVerifiedListHeader.text = "درحال اسکن سرورهای پرسرعت از ۱۰ منبع مختلف…"
                return
            }
            val distinctSources = nodes.map { it.source }.filter { it.isNotBlank() }.distinct().size.coerceAtLeast(1)
            dialogBinding.tvVerifiedListHeader.text =
                "برترین سرورهای آماده (${nodes.size} سرور از $distinctSources منبع مختلف — برای اتصال لمس کنید):"

            for (node in nodes.take(10)) {
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
                                val (cn, cs) = tunnelManager.fetchCustomConfigsAndSubs()
                                renderCustomSavedSection(cn, cs)
                                Toast.makeText(
                                    this@MainActivity,
                                    "✅ متصل شد به: ${sel.bestNode} (${sel.bestRttMs}ms)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                val srcTag = if (node.source.isNotBlank()) "[${node.source}] " else ""
                val prefix = if (node.active) "🟢 " else "⚪ "
                val nameTv = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = "$prefix$srcTag${node.name} (${node.protocol})"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gf_text_primary))
                    textSize = 12f
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
                    textSize = 11.5f
                    setTypeface(typeface, Typeface.BOLD)
                }

                row.addView(nameTv)
                row.addView(metricsTv)
                container.addView(row)
            }
        }

        suspend fun reloadAllDialogLists() {
            tunnelManager.pollStatusOnce()?.let { renderDialogStatus(it) }
            val (customNodes, customSubs) = tunnelManager.fetchCustomConfigsAndSubs()
            renderCustomSavedSection(customNodes, customSubs)
            renderVerifiedNodes(tunnelManager.fetchVerifiedNodes())
        }

        renderDialogStatus(tunnelManager.status.value)
        lifecycleScope.launch {
            reloadAllDialogLists()
        }

        dialogBinding.cbAdBlock.setOnCheckedChangeListener { _, checked ->
            tunnelManager.adBlockEnabled = checked
        }

        // Clipboard 1-Tap Paste into Custom Config box
        dialogBinding.btnPasteClipboardConfig.setOnClickListener {
            val clip = readClipboardCandidate()
            if (clip.isBlank()) {
                Toast.makeText(this, "کلیپ‌بورد خالی است", Toast.LENGTH_SHORT).show()
            } else {
                dialogBinding.etCustomProxyUri.setText(clip)
                dialogBinding.etCustomProxyUri.setSelection(clip.length)
                Toast.makeText(this, "از کلیپ‌بورد جای‌گذاری شد — روی «➕ افزودن و اتصال» بزنید", Toast.LENGTH_SHORT).show()
            }
        }

        // Add & Live-Verify Custom Config(s) or Subscription URL
        dialogBinding.btnAddCustomConfig.setOnClickListener {
            val raw = dialogBinding.etCustomProxyUri.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                Toast.makeText(this, "لطفاً لینک کانفیگ یا اشتراک را وارد یا Paste کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialogBinding.btnAddCustomConfig.isEnabled = false
            dialogBinding.btnAddCustomConfig.text = "⏳ درحال تست…"
            dialogBinding.tvDialogStatus.text = "⏳ درحال استخراج و تست اتصال واقعی کانفیگ شما…"

            lifecycleScope.launch {
                val res = tunnelManager.addCustomConfigsOrSub(raw)
                dialogBinding.btnAddCustomConfig.isEnabled = true
                dialogBinding.btnAddCustomConfig.text = "➕ افزودن و اتصال"

                if (res.ok) {
                    dialogBinding.etCustomProxyUri.setText("")
                    hideKeyboard()
                    reloadAllDialogLists()
                    if (res.workingCount > 0) {
                        val subInfo = if (res.isSub) "از لینک اشتراک (${res.workingCount}/${res.addedCount} سالم)" else ""
                        dialogBinding.tvDialogStatus.text =
                            "✅ کانفیگ با موفقیت تست و فعال شد! $subInfo\n" +
                                "سرور فعال: ${res.bestNode} (${res.bestProto}) • پینگ واقعی: ${res.bestRttMs}ms"
                        Toast.makeText(
                            this@MainActivity,
                            "✅ متصل شد: ${res.bestNode} (${res.bestRttMs}ms)",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        dialogBinding.tvDialogStatus.text =
                            "⚠ ${res.addedCount} کانفیگ ذخیره شد، اما در تست فعلی پاسخ نداد (احتمالاً سرور خاموش یا فیلتر است).\n" +
                                "مرورگر به صورت خودکار از سرورهای سالم جایگزین نیز پشتیبانی می‌کند."
                    }
                } else {
                    dialogBinding.tvDialogStatus.text =
                        "⚠ خطا در افزودن کانفیگ: ${res.error ?: "لینک معتبر vless / vmess / trojan / ss / socks5 / sub یافت نشد"}"
                }
            }
        }

        dialogBinding.btnSwitchBestProxy.setOnClickListener {
            dialogBinding.btnSwitchBestProxy.isEnabled = false
            dialogBinding.btnSwitchBestProxy.text = "⏳ درحال سنجش سرعت سرورها…"
            dialogBinding.tvDialogStatus.text = "درحال تست موازی سرعت دانلود و پینگ سرورهای ۱۰ منبع روی اینترنت شما…"
            lifecycleScope.launch {
                val res = tunnelManager.switchToBestFastProxy()
                dialogBinding.btnSwitchBestProxy.isEnabled = true
                dialogBinding.btnSwitchBestProxy.text = "⚡ اتصال به پروکسی دیگر با بهترین سرعت"
                if (res.ok) {
                    val src = if (res.bestSource.isNotBlank()) " [${res.bestSource}]" else ""
                    val spd = if (res.bestSpeedKbps >= 1000) {
                        String.format(Locale.US, " • سرعت: %.1f Mbps", res.bestSpeedKbps / 1000.0)
                    } else ""
                    dialogBinding.tvDialogStatus.text =
                        "✅ به سریع‌ترین پروکسی جدید متصل شد!\n" +
                            "سرور: ${res.bestNode}$src (${res.bestProto.uppercase()}) • پینگ: ${res.bestRttMs}ms$spd"
                    reloadAllDialogLists()
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
            dialogBinding.tvDialogStatus.text = "⏳ درحال دریافت تازه سرورها از ۱۰ منبع مستقل و ۴ شبکه CDN…"
            lifecycleScope.launch {
                tunnelManager.refreshNodesNow()
                delay(2400)
                reloadAllDialogLists()
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
                tunnelManager.deleteCustomConfigOrSub(clearAll = true)
                tunnelManager.updateCustomProxy("")
                reloadAllDialogLists()
                Toast.makeText(this@MainActivity, "حالت هوشمند خودکار ۱۰ منبعی فعال شد", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnDialogSave.setOnClickListener {
            val unsubmitted = dialogBinding.etCustomProxyUri.text?.toString()?.trim().orEmpty()
            if (unsubmitted.isNotEmpty()) {
                lifecycleScope.launch {
                    val res = tunnelManager.addCustomConfigsOrSub(unsubmitted)
                    if (res.ok) {
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "✅ کانفیگ ذخیره و فعال شد", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, res.error ?: "لینک کانفیگ معتبر نیست", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                dialog.dismiss()
            }
        }

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    // =========================================================================
    // File Upload Chooser, Voice Search, Fullscreen Video & Resumable Downloads
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
        if (requestCode == REQ_WRITE_STORAGE) {
            // Even if legacy public storage permission is denied on API 21–28,
            // DownloadEngine safely writes to app-scoped external storage via FileProvider!
            pendingStorageDownload?.let { args ->
                pendingStorageDownload = null
                enqueueIntoDownloadEngine(args)
            }
        }
    }

    fun startManagedDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        sourceTab: BrowserTab? = null
    ) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return

        // 1. Handle blob:https://... URLs via ChromeWebView JavascriptInterface bridge!
        if (trimmed.startsWith("blob:", ignoreCase = true)) {
            val targetTab = sourceTab ?: tabManager.activeTab
            if (targetTab != null) {
                val guessedName = DownloadEngine.guessBestFileName(trimmed, contentDisposition, mimeType)
                Toast.makeText(this, "📥 درحال دریافت فایل از صفحه…", Toast.LENGTH_SHORT).show()
                targetTab.webView.downloadBlobUrl(trimmed, guessedName, mimeType)
            }
            return
        }

        val referer = (sourceTab ?: tabManager.activeTab)?.currentUrl
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            .orEmpty()

        val args = PendingDownloadArgs(
            url = trimmed,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            refererUrl = referer
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStorageDownload = args
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_STORAGE)
            return
        }

        enqueueIntoDownloadEngine(args)
    }

    private fun enqueueIntoDownloadEngine(args: PendingDownloadArgs) {
        val rec = downloadEngine.enqueueDownload(
            url = args.url,
            userAgent = args.userAgent,
            contentDisposition = args.contentDisposition,
            mimeType = args.mimeType,
            refererUrl = args.refererUrl
        )
        if (rec != null) {
            Snackbar.make(binding.rootLayout, "📥 شروع دانلود: ${rec.fileName}", Snackbar.LENGTH_LONG)
                .setAction("مشاهده پیشرفت") {
                    ChromeFeaturesDialogs.showDownloadsSheet(this)
                }
                .show()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = currentFocus?.windowToken ?: binding.rootLayout.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
    }

    override fun onDestroy() {
        suggestJob?.cancel()
        downloadEngine.onDownloadEventToast = null
        tabManager.destroyAll()
        super.onDestroy()
    }

    companion object {
        private const val REQ_WRITE_STORAGE = 901

        // Invisible BiDi & zero-width marks (deliberately preserving \u200C Persian نیم‌فاصله!)
        private val INVISIBLE_BIDI_REGEX = Regex("""[\u200B\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF]""")
        private val RTL_SCRIPT_REGEX = Regex("""[\u0590-\u08FF\uFB1D-\uFDFF\uFE70-\uFEFF]""")
        private val IPV4_HOST_REGEX = Regex("""^(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?:/.*)?$""")
        private val DOMAIN_OR_URL_REGEX = Regex(
            """^(?:localhost(?::\d{1,5})?|(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,24}(?::\d{1,5})?)(?:[/?#].*)?$"""
        )

        fun stripInvisibleBidiMarks(input: String): String =
            input.replace(INVISIBLE_BIDI_REGEX, "")

        fun isProxyConfigShareInput(input: String): Boolean {
            val t = input.trim()
            return t.startsWith("vless://", ignoreCase = true) ||
                t.startsWith("vmess://", ignoreCase = true) ||
                t.startsWith("trojan://", ignoreCase = true) ||
                t.startsWith("ss://", ignoreCase = true) ||
                t.startsWith("socks5://", ignoreCase = true) ||
                t.startsWith("socks://", ignoreCase = true)
        }

        fun isLikelyHostOrUrl(cleaned: String): Boolean {
            if (cleaned.isEmpty() || cleaned.any { it.isWhitespace() }) return false
            if (cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true)) return true
            if (RTL_SCRIPT_REGEX.containsMatchIn(cleaned.substringBefore('/'))) return false
            return IPV4_HOST_REGEX.matches(cleaned) || DOMAIN_OR_URL_REGEX.matches(cleaned)
        }

        private fun isLocalOrPrivateHost(hostPort: String): Boolean {
            val h = hostPort.substringBefore('/').substringBefore(':').lowercase(Locale.US)
            if (h == "localhost" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) {
                return true
            }
            if (h.startsWith("172.")) {
                val second = h.split('.').getOrNull(1)?.toIntOrNull() ?: return false
                return second in 16..31
            }
            return false
        }

        fun resolveDestinationUrl(
            rawInput: String,
            engine: SearchEngine = SearchEngine.GOOGLE
        ): String {
            val trimmed = stripInvisibleBidiMarks(rawInput).trim()
            if (trimmed.isEmpty()) return BrowserTabManager.HOME_URL
            if (trimmed.equals(BrowserTabManager.HOME_URL, ignoreCase = true) ||
                trimmed.equals("about:blank", ignoreCase = true)
            ) {
                return BrowserTabManager.HOME_URL
            }
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("javascript:", ignoreCase = true)
            ) {
                return trimmed
            }
            if (isLikelyHostOrUrl(trimmed)) {
                val scheme = if (isLocalOrPrivateHost(trimmed)) "http://" else "https://"
                return "$scheme$trimmed"
            }
            return engine.buildQueryUrl(trimmed)
        }

        fun formatDisplayOmniboxUrl(fullUrl: String): String {
            val trimmed = fullUrl.trim()
            if (trimmed.isEmpty() || trimmed == BrowserTabManager.HOME_URL) return ""
            val withoutScheme = trimmed
                .removePrefix("https://")
                .removePrefix("HTTPS://")
                .removePrefix("http://")
                .removePrefix("HTTP://")
                .removePrefix("www.")
                .removeSuffix("/")
            return runCatching { Uri.decode(withoutScheme) }.getOrDefault(withoutScheme)
        }

        fun formatHomeProxyStatus(st: GatewayStatus): String {
            val speedPart = st.formattedSpeed().let { if (it.isNotEmpty()) " • $it" else "" }
            val srcBadge = if (st.bestSource.isNotBlank()) " [${st.bestSource}]" else ""
            val multiPart = if (st.activeSources > 1) " • ${st.activeSources} منبع" else ""
            return when {
                st.readyNodes > 0 && st.bestRttMs > 0 ->
                    "● متصل: ${st.bestNode.ifBlank { "هوشمند" }}$srcBadge (${st.bestRttMs}ms$speedPart$multiPart)"
                st.readyNodes > 0 ->
                    "● متصل: ${st.bestNode.ifBlank { "هوشمند" }}$srcBadge$multiPart"
                else -> "● اتصال هوشمند چندمنبعی فعال است"
            }
        }

        private fun applySmartOmniboxDirection(
            editText: EditText,
            text: CharSequence?,
            forceLtr: Boolean = false
        ) {
            val s = text?.toString().orEmpty()
            val dir = when {
                forceLtr -> View.TEXT_DIRECTION_LTR
                s.isEmpty() -> View.TEXT_DIRECTION_LOCALE
                s.startsWith("http://", true) ||
                    s.startsWith("https://", true) ||
                    isProxyConfigShareInput(s) -> View.TEXT_DIRECTION_LTR
                RTL_SCRIPT_REGEX.containsMatchIn(s) -> View.TEXT_DIRECTION_RTL
                else -> View.TEXT_DIRECTION_LTR
            }
            if (editText.textDirection != dir) {
                editText.textDirection = dir
            }
        }
    }
}
