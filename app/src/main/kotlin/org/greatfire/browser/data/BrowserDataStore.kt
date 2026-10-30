package org.greatfire.browser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class BookmarkItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

data class ClosedTabItem(
    val title: String,
    val url: String,
    val closedAt: Long = System.currentTimeMillis()
)

enum class DownloadState {
    DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELED
}

data class DownloadRecord(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val url: String,
    val secondaryAudioUrl: String = "",
    val mimeType: String,
    val bytesRead: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBps: Long = 0L,
    val state: DownloadState = DownloadState.DOWNLOADING,
    val localUriOrPath: String = "",
    val stagingPath: String = "",
    val refererUrl: String = "",
    val userAgent: String = "",
    val errorMsg: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class ShortcutItem(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val badgeText: String,
    val badgeHex: String,
    val url: String
)

enum class SearchEngine(val displayName: String, val searchUrlTemplate: String) {
    GOOGLE("Google (گوگل)", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BING("Microsoft Bing", "https://www.bing.com/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    YANDEX("Yandex", "https://yandex.com/search/?text=%s");

    fun buildQueryUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return String.format(searchUrlTemplate, encoded)
    }

    companion object {
        fun fromName(name: String?): SearchEngine =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GOOGLE
    }
}

enum class ThemeModePref(val displayName: String, val nightMode: Int) {
    SYSTEM("پیش‌فرض سیستم (خودکار)", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("روشن (Light)", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("تیره (Dark)", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromName(name: String?): ThemeModePref =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

enum class ChromeColorTheme(
    val displayName: String,
    val lightToolbarHex: String,
    val lightPillHex: String,
    val darkToolbarHex: String,
    val darkPillHex: String,
    val accentHex: String
) {
    DEFAULT("پیش‌فرض گوگل کروم", "#FFFFFF", "#F1F3F4", "#202124", "#303134", "#1A73E8"),
    OCEAN_BLUE("آبی اقیانوسی (Material You)", "#EEF4FF", "#DDE9FF", "#1B2230", "#263145", "#1A73E8"),
    EMERALD_MINT("سبز زمردی", "#EDF7F2", "#D9EFE4", "#192620", "#24372E", "#188038"),
    ROYAL_VIOLET("بنفش کروم", "#F5EEFF", "#E8DCFA", "#231C2E", "#322842", "#7B1FA2"),
    WARM_SUNSET("شنی گرم", "#FFF4EC", "#FBE6D7", "#2B201A", "#3B2D25", "#C5511E");

    companion object {
        fun fromName(name: String?): ChromeColorTheme =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

data class SavedSessionTab(
    val title: String,
    val url: String,
    val desktopMode: Boolean = false,
    val pinned: Boolean = false
)

class BrowserDataStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("dr_chrome_browser_store", Context.MODE_PRIVATE)

    private val lock = Any()

    // ---------------- Settings ----------------

    var searchEngine: SearchEngine
        get() = SearchEngine.fromName(prefs.getString(KEY_SEARCH_ENGINE, SearchEngine.GOOGLE.name))
        set(value) = prefs.edit().putString(KEY_SEARCH_ENGINE, value.name).apply()

    var themeMode: ThemeModePref
        get() = ThemeModePref.fromName(prefs.getString(KEY_THEME_MODE, ThemeModePref.SYSTEM.name))
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    var textZoomPercent: Int
        get() = prefs.getInt(KEY_TEXT_ZOOM, 100).coerceIn(50, 200)
        set(value) = prefs.edit().putInt(KEY_TEXT_ZOOM, value.coerceIn(50, 200)).apply()

    var javaScriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_JS_ENABLED, value).apply()

    var cookiesEnabled: Boolean
        get() = prefs.getBoolean(KEY_COOKIES_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_COOKIES_ENABLED, value).apply()

    var blockPopups: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_POPUPS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_POPUPS, value).apply()

    var defaultDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_DESKTOP, false)
        set(value) = prefs.edit().putBoolean(KEY_DEFAULT_DESKTOP, value).apply()

    var bottomAddressBar: Boolean
        get() = prefs.getBoolean(KEY_BOTTOM_ADDRESS_BAR, false)
        set(value) = prefs.edit().putBoolean(KEY_BOTTOM_ADDRESS_BAR, value).apply()

    var chromeColorTheme: ChromeColorTheme
        get() = ChromeColorTheme.fromName(prefs.getString(KEY_CHROME_COLOR_THEME, ChromeColorTheme.DEFAULT.name))
        set(value) = prefs.edit().putString(KEY_CHROME_COLOR_THEME, value.name).apply()

    // ---------------- Bookmarks ----------------

    fun getBookmarks(): List<BookmarkItem> = synchronized(lock) {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").trim()
                    if (url.isNotEmpty()) {
                        add(
                            BookmarkItem(
                                id = o.optString("id", UUID.randomUUID().toString()),
                                title = o.optString("title", url).ifBlank { url },
                                url = url,
                                createdAt = o.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun isBookmarked(url: String): Boolean {
        val normalized = normalizeUrlKey(url)
        if (normalized.isEmpty()) return false
        return getBookmarks().any { normalizeUrlKey(it.url) == normalized }
    }

    fun toggleBookmark(title: String, url: String): Boolean = synchronized(lock) {
        val normalized = normalizeUrlKey(url)
        val current = getBookmarks().toMutableList()
        val existingIdx = current.indexOfFirst { normalizeUrlKey(it.url) == normalized }
        return if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            saveBookmarksLocked(current)
            false
        } else {
            current.add(0, BookmarkItem(title = title.ifBlank { url }, url = url.trim()))
            saveBookmarksLocked(current)
            true
        }
    }

    fun updateBookmark(id: String, newTitle: String, newUrl: String) = synchronized(lock) {
        val current = getBookmarks().map {
            if (it.id == id) it.copy(title = newTitle.ifBlank { newUrl }, url = newUrl.trim()) else it
        }
        saveBookmarksLocked(current)
    }

    fun deleteBookmark(id: String) = synchronized(lock) {
        val current = getBookmarks().filterNot { it.id == id }
        saveBookmarksLocked(current)
    }

    private fun saveBookmarksLocked(list: List<BookmarkItem>) {
        val arr = JSONArray()
        for (item in list.take(500)) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("url", item.url)
                    put("createdAt", item.createdAt)
                }
            )
        }
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    // ---------------- History ----------------

    fun getHistory(): List<HistoryItem> = synchronized(lock) {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").trim()
                    if (url.isNotEmpty()) {
                        add(
                            HistoryItem(
                                id = o.optString("id", UUID.randomUUID().toString()),
                                title = o.optString("title", url).ifBlank { url },
                                url = url,
                                visitedAt = o.optLong("visitedAt", 0L)
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun recordHistoryVisit(title: String, url: String) = synchronized(lock) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return
        val current = getHistory().toMutableList()
        val now = System.currentTimeMillis()
        // Update top entry if user reloaded or title arrived for same URL within 90s
        if (current.isNotEmpty() && normalizeUrlKey(current[0].url) == normalizeUrlKey(trimmed) &&
            now - current[0].visitedAt < 90_000L
        ) {
            val bestTitle = if (title.isNotBlank() && title != trimmed) title else current[0].title
            current[0] = current[0].copy(title = bestTitle, visitedAt = now)
        } else {
            // Remove older identical entry within top 25 to avoid noisy duplicates
            val dupIdx = current.take(25).indexOfFirst { normalizeUrlKey(it.url) == normalizeUrlKey(trimmed) }
            if (dupIdx >= 0) {
                current.removeAt(dupIdx)
            }
            current.add(0, HistoryItem(title = title.ifBlank { trimmed }, url = trimmed, visitedAt = now))
        }
        saveHistoryLocked(current.take(400))
    }

    fun deleteHistoryItem(id: String) = synchronized(lock) {
        saveHistoryLocked(getHistory().filterNot { it.id == id })
    }

    fun clearHistorySince(cutoffEpochMs: Long) = synchronized(lock) {
        if (cutoffEpochMs <= 0L) {
            prefs.edit().remove(KEY_HISTORY).apply()
        } else {
            saveHistoryLocked(getHistory().filter { it.visitedAt < cutoffEpochMs })
        }
    }

    private fun saveHistoryLocked(list: List<HistoryItem>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("url", item.url)
                    put("visitedAt", item.visitedAt)
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    // ---------------- Recently Closed Tabs ----------------

    fun getRecentlyClosedTabs(): List<ClosedTabItem> = synchronized(lock) {
        val raw = prefs.getString(KEY_CLOSED_TABS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").trim()
                    if (url.isNotEmpty()) {
                        add(
                            ClosedTabItem(
                                title = o.optString("title", url),
                                url = url,
                                closedAt = o.optLong("closedAt", 0L)
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun pushRecentlyClosedTab(title: String, url: String) = synchronized(lock) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        val list = getRecentlyClosedTabs().toMutableList()
        list.removeAll { it.url == url }
        list.add(0, ClosedTabItem(title = title.ifBlank { url }, url = url))
        val arr = JSONArray()
        for (item in list.take(20)) {
            arr.put(
                JSONObject().apply {
                    put("title", item.title)
                    put("url", item.url)
                    put("closedAt", item.closedAt)
                }
            )
        }
        prefs.edit().putString(KEY_CLOSED_TABS, arr.toString()).apply()
    }

    fun clearRecentlyClosedTabs() = synchronized(lock) {
        prefs.edit().remove(KEY_CLOSED_TABS).apply()
    }

    // ---------------- Downloads ----------------

    fun getDownloads(): List<DownloadRecord> = synchronized(lock) {
        val raw = prefs.getString(KEY_DOWNLOADS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val stateStr = o.optString("state", DownloadState.COMPLETED.name)
                    val state = runCatching { DownloadState.valueOf(stateStr) }.getOrDefault(DownloadState.COMPLETED)
                    add(
                        DownloadRecord(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            fileName = o.optString("fileName", "download"),
                            url = o.optString("url", ""),
                            secondaryAudioUrl = o.optString("secondaryAudioUrl", ""),
                            mimeType = o.optString("mimeType", "application/octet-stream"),
                            bytesRead = o.optLong("bytesRead", 0L),
                            totalBytes = o.optLong("totalBytes", -1L),
                            speedBps = o.optLong("speedBps", 0L),
                            state = state,
                            localUriOrPath = o.optString("localUriOrPath", ""),
                            stagingPath = o.optString("stagingPath", ""),
                            refererUrl = o.optString("refererUrl", ""),
                            userAgent = o.optString("userAgent", ""),
                            errorMsg = o.optString("errorMsg", ""),
                            createdAt = o.optLong("createdAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsertDownload(record: DownloadRecord) = synchronized(lock) {
        val list = getDownloads().toMutableList()
        val idx = list.indexOfFirst { it.id == record.id }
        if (idx >= 0) {
            list[idx] = record
        } else {
            list.add(0, record)
        }
        saveDownloadsLocked(list.take(100))
    }

    fun deleteDownloadRecord(id: String) = synchronized(lock) {
        saveDownloadsLocked(getDownloads().filterNot { it.id == id })
    }

    fun clearDownloads() = synchronized(lock) {
        prefs.edit().remove(KEY_DOWNLOADS).apply()
    }

    fun recoverInterruptedDownloadsOnStartup() = synchronized(lock) {
        val current = getDownloads()
        var changed = false
        val fixed = current.map { d ->
            if (d.state == DownloadState.DOWNLOADING) {
                changed = true
                d.copy(state = DownloadState.PAUSED, speedBps = 0L)
            } else {
                d
            }
        }
        if (changed) {
            saveDownloadsLocked(fixed)
        }
    }

    private fun saveDownloadsLocked(list: List<DownloadRecord>) {
        val arr = JSONArray()
        for (d in list) {
            arr.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("fileName", d.fileName)
                    put("url", d.url)
                    put("secondaryAudioUrl", d.secondaryAudioUrl)
                    put("mimeType", d.mimeType)
                    put("bytesRead", d.bytesRead)
                    put("totalBytes", d.totalBytes)
                    put("speedBps", d.speedBps)
                    put("state", d.state.name)
                    put("localUriOrPath", d.localUriOrPath)
                    put("stagingPath", d.stagingPath)
                    put("refererUrl", d.refererUrl)
                    put("userAgent", d.userAgent)
                    put("errorMsg", d.errorMsg)
                    put("createdAt", d.createdAt)
                }
            )
        }
        prefs.edit().putString(KEY_DOWNLOADS, arr.toString()).apply()
    }

    // ---------------- New Tab Shortcuts ----------------

    fun getShortcuts(): List<ShortcutItem> = synchronized(lock) {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return defaultChromeShortcuts()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").trim()
                    if (url.isNotEmpty()) {
                        add(
                            ShortcutItem(
                                id = o.optString("id", UUID.randomUUID().toString()),
                                label = o.optString("label", url),
                                badgeText = o.optString("badgeText", "W"),
                                badgeHex = o.optString("badgeHex", "#1A73E8"),
                                url = url
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
        return parsed.ifEmpty { defaultChromeShortcuts() }
    }

    fun addOrUpdateShortcut(item: ShortcutItem) = synchronized(lock) {
        val current = getShortcuts().toMutableList()
        val idx = current.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            current[idx] = item
        } else {
            current.add(item)
        }
        saveShortcutsLocked(current.take(16))
    }

    fun removeShortcut(id: String) = synchronized(lock) {
        val current = getShortcuts().filterNot { it.id == id }
        saveShortcutsLocked(current)
    }

    fun resetDefaultShortcuts() = synchronized(lock) {
        prefs.edit().remove(KEY_SHORTCUTS).apply()
    }

    private fun saveShortcutsLocked(list: List<ShortcutItem>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("label", s.label)
                    put("badgeText", s.badgeText)
                    put("badgeHex", s.badgeHex)
                    put("url", s.url)
                }
            )
        }
        prefs.edit().putString(KEY_SHORTCUTS, arr.toString()).apply()
    }

    // ---------------- Omnibox Local Suggestions ----------------

    fun searchBookmarksAndHistory(query: String, limit: Int = 5): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>(limit)

        for (b in getBookmarks()) {
            if (b.title.lowercase().contains(q) || b.url.lowercase().contains(q)) {
                val key = normalizeUrlKey(b.url)
                if (seen.add(key)) {
                    out.add("★ ${b.title}" to b.url)
                    if (out.size >= limit) return out
                }
            }
        }
        for (h in getHistory()) {
            if (h.title.lowercase().contains(q) || h.url.lowercase().contains(q)) {
                val key = normalizeUrlKey(h.url)
                if (seen.add(key)) {
                    out.add(h.title to h.url)
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    // ---------------- Session Persistence ----------------

    fun saveTabSession(tabs: List<SavedSessionTab>, activeIndex: Int) = synchronized(lock) {
        val arr = JSONArray()
        for (t in tabs.take(30)) {
            arr.put(
                JSONObject().apply {
                    put("title", t.title)
                    put("url", t.url)
                    put("desktopMode", t.desktopMode)
                    put("pinned", t.pinned)
                }
            )
        }
        prefs.edit()
            .putString(KEY_SAVED_SESSION, arr.toString())
            .putInt(KEY_SAVED_ACTIVE_IDX, activeIndex)
            .apply()
    }

    fun loadTabSession(): Pair<List<SavedSessionTab>, Int> = synchronized(lock) {
        val raw = prefs.getString(KEY_SAVED_SESSION, null) ?: return emptyList<SavedSessionTab>() to 0
        val idx = prefs.getInt(KEY_SAVED_ACTIVE_IDX, 0)
        val list = runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").trim()
                    if (url.isNotEmpty()) {
                        add(
                            SavedSessionTab(
                                title = o.optString("title", "تب جدید"),
                                url = url,
                                desktopMode = o.optBoolean("desktopMode", false),
                                pinned = o.optBoolean("pinned", false)
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
        return list to idx
    }

    private fun normalizeUrlKey(url: String): String =
        url.trim().removeSuffix("/").lowercase()

    companion object {
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_TEXT_ZOOM = "text_zoom"
        private const val KEY_JS_ENABLED = "js_enabled"
        private const val KEY_COOKIES_ENABLED = "cookies_enabled"
        private const val KEY_BLOCK_POPUPS = "block_popups"
        private const val KEY_DEFAULT_DESKTOP = "default_desktop"
        private const val KEY_BOTTOM_ADDRESS_BAR = "bottom_address_bar"
        private const val KEY_CHROME_COLOR_THEME = "chrome_color_theme"
        private const val KEY_BOOKMARKS = "bookmarks_json"
        private const val KEY_HISTORY = "history_json"
        private const val KEY_CLOSED_TABS = "closed_tabs_json"
        private const val KEY_DOWNLOADS = "downloads_json"
        private const val KEY_SHORTCUTS = "shortcuts_json"
        private const val KEY_SAVED_SESSION = "saved_session_json"
        private const val KEY_SAVED_ACTIVE_IDX = "saved_active_idx"

        private val PALETTE_HEX = listOf(
            "#1A73E8", "#EA4335", "#34A853", "#FBBC04",
            "#9334E6", "#00897B", "#E1306C", "#229ED9"
        )

        fun pickBadgeColor(seed: String): String {
            val hash = (seed.hashCode() and 0x7FFFFFFF)
            return PALETTE_HEX[hash % PALETTE_HEX.size]
        }

        fun defaultChromeShortcuts(): List<ShortcutItem> = listOf(
            ShortcutItem("sc_google", "Google", "G", "#4285F4", "https://www.google.com"),
            ShortcutItem("sc_youtube", "YouTube", "▶", "#EA4335", "https://m.youtube.com"),
            ShortcutItem("sc_instagram", "Instagram", "IG", "#E1306C", "https://www.instagram.com"),
            ShortcutItem("sc_telegram", "Telegram", "TG", "#229ED9", "https://web.telegram.org"),
            ShortcutItem("sc_x", "X", "𝕏", "#202124", "https://x.com"),
            ShortcutItem("sc_wiki", "Wikipedia", "W", "#5F6368", "https://fa.m.wikipedia.org"),
            ShortcutItem("sc_bbc", "BBC فارسی", "B", "#B80000", "https://www.bbc.com/persian"),
            ShortcutItem("sc_chatgpt", "ChatGPT", "AI", "#10A37F", "https://chatgpt.com")
        )

        @Volatile
        private var INSTANCE: BrowserDataStore? = null

        fun getInstance(context: Context): BrowserDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BrowserDataStore(context).also { INSTANCE = it }
            }
        }
    }
}
