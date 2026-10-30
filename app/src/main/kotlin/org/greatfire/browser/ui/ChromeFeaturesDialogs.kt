package org.greatfire.browser.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.greatfire.browser.R
import org.greatfire.browser.data.BookmarkItem
import org.greatfire.browser.data.BrowserDataStore
import org.greatfire.browser.data.DownloadRecord
import org.greatfire.browser.data.DownloadState
import org.greatfire.browser.data.SearchEngine
import org.greatfire.browser.data.ShortcutItem
import org.greatfire.browser.data.ThemeModePref
import org.greatfire.browser.web.BrowserTab
import org.greatfire.browser.web.BrowserTabManager
import org.greatfire.browser.web.WebContextMenuPayload
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChromeFeaturesDialogs {

    // =========================================================================
    // 1. Authentic Google Chrome Long-Press Context Menu (Links, Images & Media)
    // =========================================================================
    fun showWebContextMenuSheet(
        activity: MainActivity,
        tab: BrowserTab,
        payload: WebContextMenuPayload
    ) {
        if (activity.isFinishing) return
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
            setPadding(0, (12 * dp).toInt(), 0, (16 * dp).toInt())
        }

        val headerBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = (20 * dp).toInt()
            setPadding(hPad, (8 * dp).toInt(), hPad, (12 * dp).toInt())
        }
        val titleStr = when {
            payload.linkText.isNotBlank() -> payload.linkText
            payload.imageAlt.isNotBlank() -> payload.imageAlt
            payload.hasLink -> "گزینه‌های لینک"
            payload.hasMedia -> "گزینه‌های رسانه (ویدیو / صدا)"
            else -> "گزینه‌های تصویر"
        }
        val urlPreviewStr = when {
            payload.linkUrl.isNotBlank() -> payload.linkUrl
            payload.mediaUrl.isNotBlank() -> payload.mediaUrl
            else -> payload.imageUrl
        }

        val tvHeaderTitle = TextView(activity).apply {
            text = titleStr
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvHeaderUrl = TextView(activity).apply {
            text = urlPreviewStr
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(0, (3 * dp).toInt(), 0, 0)
            setOnClickListener {
                maxLines = if (maxLines == 2) 10 else 2
            }
        }
        headerBox.addView(tvHeaderTitle)
        headerBox.addView(tvHeaderUrl)
        root.addView(headerBox)
        root.addView(makeDivider(activity))

        val itemsScroll = ScrollView(activity)
        val itemsCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        itemsScroll.addView(itemsCol)

        fun addActionRow(iconRes: Int, label: String, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setSelectableRipple(activity, this)
                setPadding((20 * dp).toInt(), (13 * dp).toInt(), (20 * dp).toInt(), (13 * dp).toInt())
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            val iv = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams((21 * dp).toInt(), (21 * dp).toInt())
                setImageResource(iconRes)
            }
            val tv = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (16 * dp).toInt()
                }
                text = label
                setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
                textSize = 14.5f
            }
            row.addView(iv)
            row.addView(tv)
            itemsCol.addView(row)
        }

        if (payload.hasLink) {
            val link = payload.linkUrl
            val isWebLink = link.startsWith("http://", true) || link.startsWith("https://", true)
            val isDownloadableLink = isWebLink || link.startsWith("blob:", true) || link.startsWith("data:", true)

            if (isWebLink) {
                addActionRow(R.drawable.ic_add, "باز کردن در تب جدید") {
                    val opened = activity.openUrlInNewTab(link, incognito = tab.incognito, selectImmediately = false)
                    Snackbar.make(activity.rootViewForSnackbar(), "در تب جدید باز شد", Snackbar.LENGTH_LONG)
                        .setAction("مشاهده") {
                            activity.tabManager.selectTabById(opened.id)
                        }
                        .show()
                }
                addActionRow(R.drawable.ic_incognito, "باز کردن در تب ناشناس") {
                    activity.openUrlInNewTab(link, incognito = true, selectImmediately = true)
                }
            } else {
                addActionRow(R.drawable.ic_arrow_forward, "باز کردن لینک") {
                    activity.navigateToInput(link)
                }
            }

            addActionRow(R.drawable.ic_lock, "کپی آدرس لینک") {
                val cleanCopy = link.removePrefix("mailto:").removePrefix("tel:")
                copyToClipboard(activity, "Link", cleanCopy, "آدرس لینک کپی شد")
            }

            if (payload.linkText.isNotBlank()) {
                addActionRow(R.drawable.ic_info_outline, "کپی متن لینک") {
                    copyToClipboard(activity, "Link Text", payload.linkText, "متن لینک کپی شد")
                }
            }

            addActionRow(R.drawable.ic_share, "اشتراک‌گذاری لینک") {
                sharePlainText(
                    activity,
                    if (payload.linkText.isNotBlank()) "${payload.linkText}\n$link" else link,
                    "اشتراک‌گذاری لینک"
                )
            }

            if (isDownloadableLink) {
                addActionRow(R.drawable.ic_download, "دانلود لینک") {
                    activity.startManagedDownload(link, "", "", "", tab)
                }
            }
        }

        if (payload.hasImage) {
            if (payload.hasLink) {
                itemsCol.addView(makeDivider(activity))
            }
            val imgUrl = payload.imageUrl
            addActionRow(R.drawable.ic_add, "باز کردن تصویر در تب جدید") {
                activity.openUrlInNewTab(imgUrl, incognito = tab.incognito, selectImmediately = true)
            }
            addActionRow(R.drawable.ic_download, "دانلود تصویر") {
                activity.startManagedDownload(imgUrl, "", "", "image/*", tab)
            }
            addActionRow(R.drawable.ic_lock, "کپی آدرس تصویر") {
                copyToClipboard(activity, "Image URL", imgUrl, "آدرس تصویر کپی شد")
            }
            addActionRow(R.drawable.ic_share, "اشتراک‌گذاری تصویر") {
                sharePlainText(activity, imgUrl, "اشتراک‌گذاری تصویر")
            }
            if (imgUrl.startsWith("http://", true) || imgUrl.startsWith("https://", true)) {
                addActionRow(R.drawable.ic_search, "جستجوی تصویر با گوگل (Google Lens)") {
                    val lensUrl = "https://lens.google.com/uploadbyurl?url=" + URLEncoder.encode(imgUrl, "UTF-8")
                    activity.openUrlInNewTab(lensUrl, incognito = tab.incognito, selectImmediately = true)
                }
            }
        }

        if (payload.hasMedia) {
            if (payload.hasLink || payload.hasImage) {
                itemsCol.addView(makeDivider(activity))
            }
            val mediaUrl = payload.mediaUrl
            addActionRow(R.drawable.ic_video_grab, "باز کردن در دانلودر هوشمند ویدیو (IDM)") {
                IdmAndChromeExtrasSheets.showIdmVideoGrabberSheet(activity)
            }
            addActionRow(R.drawable.ic_download, "دانلود مستقیم ویدیو / فایل صوتی") {
                activity.startManagedDownload(mediaUrl, "", "", "video/mp4", tab)
            }
            if (mediaUrl.startsWith("http://", true) || mediaUrl.startsWith("https://", true)) {
                addActionRow(R.drawable.ic_add, "باز کردن رسانه در تب جدید") {
                    activity.openUrlInNewTab(mediaUrl, incognito = tab.incognito, selectImmediately = true)
                }
            }
            addActionRow(R.drawable.ic_lock, "کپی آدرس رسانه") {
                copyToClipboard(activity, "Media URL", mediaUrl, "آدرس رسانه کپی شد")
            }
        }

        root.addView(itemsScroll)
        dialog.setContentView(root)
        dialog.show()
    }

    // =========================================================================
    // 2. Authentic Google Chrome 3-Dot Overflow Menu (Top 5-Icon Bar + Full Items)
    // =========================================================================
    fun showChromeOverflowMenu(activity: MainActivity, anchor: View) {
        val tab = activity.tabManager.activeTab ?: return
        val store = activity.dataStore
        val dp = activity.resources.displayMetrics.density
        val isHome = tab.currentUrl == BrowserTabManager.HOME_URL

        val cardWidth = (284 * dp).toInt()
        val popup = PopupWindow(activity).apply {
            width = cardWidth
            height = WindowManager.LayoutParams.WRAP_CONTENT
            isFocusable = true
            isOutsideTouchable = true
            elevation = 16 * dp
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_chrome_menu)
            setPadding(0, (6 * dp).toInt(), 0, (8 * dp).toInt())
        }

        // Top 5-Icon Chrome Action Row (Forward, Star Bookmark, Offline Save, Site Info, Reload/Stop)
        val iconRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
        }

        fun makeTopIconBtn(iconRes: Int, desc: String, enabled: Boolean = true, onClick: () -> Unit): ImageButton {
            return ImageButton(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, (42 * dp).toInt(), 1f)
                setImageResource(iconRes)
                contentDescription = desc
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.35f
                setSelectableBorderlessRipple(activity, this)
                setPadding((9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt())
                setOnClickListener {
                    popup.dismiss()
                    onClick()
                }
            }
        }

        val canFwd = !isHome && tab.webView.canGoForward()
        iconRow.addView(makeTopIconBtn(R.drawable.ic_arrow_forward, "صفحه بعد", canFwd) {
            if (tab.webView.canGoForward()) tab.webView.goForward()
        })

        val isStarred = !isHome && store.isBookmarked(tab.currentUrl)
        iconRow.addView(
            makeTopIconBtn(
                if (isStarred) R.drawable.ic_star_filled else R.drawable.ic_star_outline,
                "نشانک",
                !isHome
            ) {
                val added = store.toggleBookmark(tab.title, tab.currentUrl)
                Toast.makeText(
                    activity,
                    if (added) "★ به نشانک‌ها اضافه شد" else "از نشانک‌ها حذف شد",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        iconRow.addView(makeTopIconBtn(R.drawable.ic_download, "ذخیره آفلاین صفحه", !isHome) {
            activity.saveCurrentPageOffline(tab)
        })

        iconRow.addView(makeTopIconBtn(R.drawable.ic_info_outline, "اطلاعات سایت", true) {
            showSiteSecuritySheet(activity, tab)
        })

        iconRow.addView(
            makeTopIconBtn(
                if (tab.isLoading) R.drawable.ic_close else R.drawable.ic_refresh,
                if (tab.isLoading) "توقف" else "بازخوانی",
                !isHome
            ) {
                if (tab.isLoading) tab.webView.stopLoading() else tab.webView.reload()
            }
        )

        card.addView(iconRow)
        card.addView(makeDivider(activity))

        val maxScrollHeight = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedSpec = MeasureSpec.makeMeasureSpec(maxScrollHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cappedSpec)
            }
        }
        val menuList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(menuList)

        fun addMenuRow(
            iconRes: Int,
            title: String,
            badge: String? = null,
            checkedState: Boolean? = null,
            onClick: () -> Unit
        ) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setSelectableRipple(activity, this)
                setPadding((16 * dp).toInt(), (11 * dp).toInt(), (14 * dp).toInt(), (11 * dp).toInt())
                setOnClickListener {
                    popup.dismiss()
                    onClick()
                }
            }
            val iv = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
                setImageResource(iconRes)
            }
            val tv = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (14 * dp).toInt()
                }
                text = title
                setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
                textSize = 14f
            }
            row.addView(iv)
            row.addView(tv)

            if (!badge.isNullOrBlank()) {
                val badgeTv = TextView(activity).apply {
                    text = badge
                    setTextColor(ContextCompat.getColor(activity, R.color.gf_accent))
                    textSize = 11.5f
                    setTypeface(typeface, Typeface.BOLD)
                }
                row.addView(badgeTv)
            }
            if (checkedState != null) {
                val cb = CheckBox(activity).apply {
                    isChecked = checkedState
                    isClickable = false
                    isFocusable = false
                }
                row.addView(cb)
            }
            menuList.addView(row)
        }

        addMenuRow(R.drawable.ic_add, "تب جدید") {
            activity.tabManager.createTab(BrowserTabManager.HOME_URL, incognito = false)
        }
        addMenuRow(R.drawable.ic_incognito, "تب ناشناس جدید") {
            activity.tabManager.createTab(BrowserTabManager.HOME_URL, incognito = true)
        }
        addMenuRow(
            R.drawable.ic_pin,
            if (tab.pinned) "برداشتن پین تب" else "پین کردن تب",
            checkedState = tab.pinned
        ) {
            val pinned = activity.tabManager.togglePinTab(tab)
            Toast.makeText(
                activity,
                if (pinned) "📌 تب پین شد" else "پین تب برداشته شد",
                Toast.LENGTH_SHORT
            ).show()
        }
        menuList.addView(makeDivider(activity))

        val sniffedCount = org.greatfire.browser.media.VideoStreamSniffer.getCountForTab(tab.id)
        addMenuRow(
            R.drawable.ic_video_grab,
            "دانلودر ویدیو و استریم (IDM)",
            badge = if (sniffedCount > 0) "$sniffedCount ویدیو" else "آماده"
        ) {
            IdmAndChromeExtrasSheets.showIdmVideoGrabberSheet(activity)
        }
        addMenuRow(R.drawable.ic_history, "تاریخچه") {
            showHistorySheet(activity)
        }
        addMenuRow(R.drawable.ic_close, "پاک کردن اطلاعات مرور…") {
            showClearBrowsingDataDialog(activity)
        }
        val activeDlCount = activity.downloadEngine.downloadsFlow.value.count {
            it.state == DownloadState.DOWNLOADING
        }
        addMenuRow(
            R.drawable.ic_download,
            "دانلودها",
            badge = if (activeDlCount > 0) "$activeDlCount فعال" else null
        ) {
            showDownloadsSheet(activity)
        }
        addMenuRow(R.drawable.ic_star_outline, "نشانک‌ها") {
            showBookmarksSheet(activity)
        }
        addMenuRow(R.drawable.ic_history, "تب‌های اخیر") {
            showRecentTabsSheet(activity)
        }
        menuList.addView(makeDivider(activity))

        if (!isHome) {
            addMenuRow(R.drawable.ic_share, "اشتراک‌گذاری…") {
                sharePlainText(activity, "${tab.title}\n${tab.currentUrl}", "اشتراک‌گذاری صفحه")
            }
            addMenuRow(R.drawable.ic_search, "جستجو در صفحه…") {
                activity.openFindInPageBar()
            }
            addMenuRow(R.drawable.ic_translate, "ترجمه صفحه به فارسی") {
                activity.translateCurrentPage(tab)
            }
            addMenuRow(R.drawable.ic_volume_up, "خواندن صوتی این صفحه") {
                activity.startListenToCurrentPage(tab)
            }
            addMenuRow(R.drawable.ic_reader, "حالت مطالعه تمیز (Reader Mode)") {
                IdmAndChromeExtrasSheets.showChromeReaderModeSheet(activity, tab)
            }
            addMenuRow(R.drawable.ic_add, "افزودن به صفحه اول") {
                val host = runCatching { Uri.parse(tab.currentUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
                val badgeCh = host.firstOrNull()?.uppercaseChar()?.toString() ?: "W"
                store.addOrUpdateShortcut(
                    ShortcutItem(
                        label = tab.title.take(18).ifBlank { host },
                        badgeText = badgeCh,
                        badgeHex = BrowserDataStore.pickBadgeColor(host),
                        url = tab.currentUrl
                    )
                )
                activity.refreshChromeShortcutsGrid()
                Toast.makeText(activity, "به میانبرهای صفحه اول اضافه شد", Toast.LENGTH_SHORT).show()
            }
            addMenuRow(R.drawable.ic_download, "چاپ / ذخیره به صورت PDF") {
                printCurrentWebPage(activity, tab)
            }
            addMenuRow(R.drawable.ic_desktop, "سایت مخصوص رایانه", checkedState = tab.desktopMode) {
                activity.tabManager.toggleDesktopMode(tab)
            }
            menuList.addView(makeDivider(activity))
        }

        addMenuRow(R.drawable.ic_palette, "ظاهر و رنگ‌بندی کروم") {
            IdmAndChromeExtrasSheets.showChromeAppearanceSheet(activity)
        }
        val st = activity.tunnelStatusSnapshot()
        val pingBadge = when {
            st.readyNodes > 0 && st.bestRttMs > 0 && st.activeSources > 1 ->
                "${st.bestRttMs}ms • ${st.activeSources} منبع"
            st.readyNodes > 0 && st.bestRttMs > 0 -> "${st.bestRttMs}ms"
            else -> "فعال"
        }
        addMenuRow(R.drawable.ic_bolt, "پروکسی چندمنبعی و کانفیگ اختصاصی", badge = pingBadge) {
            activity.showProxySettingsDialog()
        }
        addMenuRow(R.drawable.ic_settings, "تنظیمات") {
            showChromeSettingsDialog(activity)
        }

        card.addView(scroll)
        popup.contentView = card

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val screenW = activity.resources.displayMetrics.widthPixels
        val margin = (8 * dp).toInt()
        val popupX = if (loc[0] > screenW / 2) {
            (loc[0] + anchor.width - cardWidth).coerceAtLeast(margin)
        } else {
            loc[0].coerceIn(margin, (screenW - cardWidth - margin).coerceAtLeast(margin))
        }
        val popupY = (loc[1] + (6 * dp).toInt()).coerceAtLeast(margin)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popupX, popupY)
    }

    // =========================================================================
    // 3. Chrome Bookmarks Manager Sheet
    // =========================================================================
    fun showBookmarksSheet(activity: MainActivity) {
        val store = activity.dataStore
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }

        val titleTv = TextView(activity).apply {
            text = "⭐ نشانک‌ها (Bookmarks)"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(titleTv)

        val searchInput = EditText(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (40 * dp).toInt()
            ).apply { topMargin = (10 * dp).toInt() }
            setBackgroundResource(R.drawable.bg_omnibox)
            hint = "جستجو در نشانک‌ها…"
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            setSingleLine()
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
        }
        root.addView(searchInput)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (340 * dp).toInt()
            ).apply { topMargin = (10 * dp).toInt() }
            addView(listContainer)
        }
        root.addView(scroll)

        fun renderList(filter: String) {
            listContainer.removeAllViews()
            val q = filter.trim().lowercase()
            val items = store.getBookmarks().filter {
                q.isEmpty() || it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
            }
            if (items.isEmpty()) {
                listContainer.addView(makeEmptyStateLabel(activity, "هنوز نشانکی ذخیره نشده است.\nاز منوی سه‌نقطه (☆) هر صفحه‌ای را نشانک کنید."))
                return
            }
            for (b in items) {
                listContainer.addView(
                    makeTwoLineListRow(
                        activity = activity,
                        title = b.title,
                        subtitle = b.url,
                        onRowClick = {
                            dialog.dismiss()
                            activity.navigateToInput(b.url)
                        },
                        onRowLongClick = {
                            showEditBookmarkDialog(activity, b) { renderList(searchInput.text?.toString().orEmpty()) }
                        },
                        onDeleteClick = {
                            store.deleteBookmark(b.id)
                            renderList(searchInput.text?.toString().orEmpty())
                        }
                    )
                )
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        renderList("")
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showEditBookmarkDialog(activity: MainActivity, item: BookmarkItem, onSaved: () -> Unit) {
        val dp = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, (12 * dp).toInt(), p, 0)
        }
        val etTitle = EditText(activity).apply {
            hint = "عنوان نشانک"
            setText(item.title)
            setSingleLine()
        }
        val etUrl = EditText(activity).apply {
            hint = "آدرس اینترنتی"
            setText(item.url)
            setSingleLine()
            textDirection = View.TEXT_DIRECTION_LTR
        }
        box.addView(etTitle)
        box.addView(etUrl)

        val dlg = AlertDialog.Builder(activity)
            .setTitle("ویرایش نشانک")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                val newU = etUrl.text?.toString()?.trim().orEmpty()
                if (newU.isNotEmpty()) {
                    activity.dataStore.updateBookmark(item.id, etTitle.text?.toString().orEmpty(), newU)
                    onSaved()
                }
            }
            .setNeutralButton("باز کردن در تب جدید") { _, _ ->
                activity.openUrlInNewTab(item.url, incognito = false, selectImmediately = true)
            }
            .setNegativeButton("انصراف", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
    }

    // =========================================================================
    // 4. Chrome History Manager Sheet + Clear Browsing Data Dialog
    // =========================================================================
    fun showHistorySheet(activity: MainActivity) {
        val store = activity.dataStore
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val timeFmt = SimpleDateFormat("MM/dd HH:mm", Locale.US)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "🕒 تاریخچه مرور"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        val clearBtn = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "پاک‌سازی…"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_danger))
            textSize = 12.5f
            setOnClickListener {
                dialog.dismiss()
                showClearBrowsingDataDialog(activity)
            }
        }
        headerRow.addView(titleTv)
        headerRow.addView(clearBtn)
        root.addView(headerRow)

        val searchInput = EditText(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (40 * dp).toInt()
            ).apply { topMargin = (8 * dp).toInt() }
            setBackgroundResource(R.drawable.bg_omnibox)
            hint = "جستجو در تاریخچه…"
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            setSingleLine()
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
        }
        root.addView(searchInput)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (340 * dp).toInt()
            ).apply { topMargin = (10 * dp).toInt() }
            addView(listContainer)
        }
        root.addView(scroll)

        fun renderList(filter: String) {
            listContainer.removeAllViews()
            val q = filter.trim().lowercase()
            val items = store.getHistory().filter {
                q.isEmpty() || it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
            }
            if (items.isEmpty()) {
                listContainer.addView(makeEmptyStateLabel(activity, "تاریخچه مرور خالی است."))
                return
            }
            for (h in items) {
                val stamp = if (h.visitedAt > 0L) timeFmt.format(Date(h.visitedAt)) + " • " else ""
                listContainer.addView(
                    makeTwoLineListRow(
                        activity = activity,
                        title = h.title,
                        subtitle = "$stamp${h.url}",
                        onRowClick = {
                            dialog.dismiss()
                            activity.navigateToInput(h.url)
                        },
                        onRowLongClick = {
                            copyToClipboard(activity, "History URL", h.url, "لینک کپی شد")
                        },
                        onDeleteClick = {
                            store.deleteHistoryItem(h.id)
                            renderList(searchInput.text?.toString().orEmpty())
                        }
                    )
                )
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        renderList("")
        dialog.setContentView(root)
        dialog.show()
    }

    fun showClearBrowsingDataDialog(activity: MainActivity) {
        val dp = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, (12 * dp).toInt(), p, (4 * dp).toInt())
        }

        val rangeLabels = arrayOf("ساعت گذشته", "۲۴ ساعت گذشته", "۷ روز گذشته", "همه زمان‌ها")
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, rangeLabels)
            setSelection(3)
        }
        box.addView(spinner)

        val cbHistory = CheckBox(activity).apply {
            text = "تاریخچه مرور (Browsing history)"
            isChecked = true
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        val cbCookies = CheckBox(activity).apply {
            text = "کوکی‌ها و داده‌های سایت‌ها"
            isChecked = true
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        val cbCache = CheckBox(activity).apply {
            text = "تصاویر و فایل‌های حافظه پنهان (Cache)"
            isChecked = true
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        box.addView(cbHistory)
        box.addView(cbCookies)
        box.addView(cbCache)

        AlertDialog.Builder(activity)
            .setTitle("پاک کردن اطلاعات مرور")
            .setView(box)
            .setPositiveButton("پاک کردن اطلاعات") { _, _ ->
                val now = System.currentTimeMillis()
                val since = when (spinner.selectedItemPosition) {
                    0 -> now - 3600_000L
                    1 -> now - 24 * 3600_000L
                    2 -> now - 7L * 24 * 3600_000L
                    else -> 0L
                }
                activity.tabManager.clearBrowsingData(
                    clearHistory = cbHistory.isChecked,
                    clearCookiesAndSiteData = cbCookies.isChecked,
                    clearCacheFiles = cbCache.isChecked,
                    sinceEpochMs = since
                )
                Toast.makeText(activity, R.string.panic_wipe_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // =========================================================================
    // 5. Chrome Recent Tabs Sheet
    // =========================================================================
    fun showRecentTabsSheet(activity: MainActivity) {
        val store = activity.dataStore
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }

        val titleTv = TextView(activity).apply {
            text = "📑 تب‌های اخیر بسته‌شده"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(titleTv)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (300 * dp).toInt()
            ).apply { topMargin = (10 * dp).toInt() }
            addView(listContainer)
        }
        root.addView(scroll)

        val items = store.getRecentlyClosedTabs()
        if (items.isEmpty()) {
            listContainer.addView(makeEmptyStateLabel(activity, "تب بسته‌شده اخیری وجود ندارد."))
        } else {
            for (c in items) {
                listContainer.addView(
                    makeTwoLineListRow(
                        activity = activity,
                        title = c.title,
                        subtitle = c.url,
                        onRowClick = {
                            dialog.dismiss()
                            activity.openUrlInNewTab(c.url, incognito = false, selectImmediately = true)
                        },
                        onRowLongClick = {
                            copyToClipboard(activity, "URL", c.url, "لینک کپی شد")
                        },
                        onDeleteClick = null
                    )
                )
            }
        }

        dialog.setContentView(root)
        dialog.show()
    }

    // =========================================================================
    // 6. Upgraded Chrome Resumable Tunneled Downloads Manager Sheet (v5.0)
    //    Live Speed/Progress, Pause, Resume/Retry, Cancel, + Download by URL,
    //    and Safe APK Installation / FileProvider Sharing across API 21–35!
    // =========================================================================
    fun showDownloadsSheet(activity: MainActivity) {
        val engine = activity.downloadEngine
        val store = activity.dataStore
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "📥 مدیریت دانلودها"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
        }
        val addUrlBtn = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "➕ لینک جدید"
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.gf_accent))
            setOnClickListener {
                showNewDownloadByUrlDialog(activity)
            }
        }
        val clearBtn = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "پاک‌سازی"
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.gf_danger))
            setOnClickListener {
                engine.clearFinishedDownloads()
            }
        }
        headerRow.addView(titleTv)
        headerRow.addView(addUrlBtn)
        headerRow.addView(clearBtn)
        root.addView(headerRow)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (390 * dp).toInt()
            ).apply { topMargin = (10 * dp).toInt() }
            addView(listContainer)
        }
        root.addView(scroll)

        fun renderDownloads(items: List<DownloadRecord>) {
            listContainer.removeAllViews()
            if (items.isEmpty()) {
                listContainer.addView(
                    makeEmptyStateLabel(
                        activity,
                        "هنوز فایلی دانلود نشده است.\nمی‌توانید روی «➕ لینک جدید» بزنید یا روی هر فایل در وب نگه دارید."
                    )
                )
                return
            }

            for (d in items) {
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_card_dark)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * dp).toInt() }
                    setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                    setOnLongClickListener {
                        if (d.url.isNotBlank()) {
                            copyToClipboard(activity, "Download URL", d.url, "لینک دانلود کپی شد")
                        }
                        true
                    }
                }

                val icon = fileTypeEmoji(d.fileName, d.mimeType)
                card.addView(TextView(activity).apply {
                    text = "$icon ${d.fileName}"
                    setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                })

                val host = runCatching { Uri.parse(d.url).host.orEmpty() }.getOrDefault("")
                if (host.isNotBlank()) {
                    card.addView(TextView(activity).apply {
                        text = host
                        setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
                        textSize = 11f
                        textDirection = View.TEXT_DIRECTION_LTR
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }

                val pct = if (d.totalBytes > 0L) {
                    ((d.bytesRead * 100L) / d.totalBytes).toInt().coerceIn(0, 100)
                } else -1

                if (d.state == DownloadState.DOWNLOADING || d.state == DownloadState.PAUSED) {
                    val pb = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (6 * dp).toInt()
                        ).apply {
                            topMargin = (6 * dp).toInt()
                            bottomMargin = (4 * dp).toInt()
                        }
                        max = 100
                        isIndeterminate = pct < 0 && d.state == DownloadState.DOWNLOADING
                        if (pct >= 0) progress = pct
                    }
                    card.addView(pb)
                }

                val sizePart = if (d.totalBytes > 0L) {
                    "${formatBytes(d.bytesRead)} / ${formatBytes(d.totalBytes)}" +
                        if (pct >= 0) " ($pct%)" else ""
                } else {
                    formatBytes(d.bytesRead)
                }

                val isApk = d.fileName.endsWith(".apk", ignoreCase = true) ||
                    d.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)

                val statusText = when (d.state) {
                    DownloadState.DOWNLOADING -> {
                        val spd = if (d.speedBps > 0L) " • ${formatBytes(d.speedBps)}/s" else ""
                        val hlsSegInfo = if (d.errorMsg.startsWith("قطعه ")) " (${d.errorMsg})" else ""
                        "⏳ در حال دانلود$hlsSegInfo • $sizePart$spd"
                    }
                    DownloadState.PAUSED -> "⏸ متوقف شده • $sizePart"
                    DownloadState.COMPLETED -> {
                        if (isApk) "✅ ذخیره در پوشه Downloads • ${formatBytes(d.bytesRead)}"
                        else "✅ تکمیل شد • ${formatBytes(d.bytesRead)}"
                    }
                    DownloadState.CANCELED -> "✕ لغو شده"
                    DownloadState.FAILED -> "⚠ ناموفق • ${d.errorMsg.ifBlank { sizePart }}"
                }

                val statusColor = when (d.state) {
                    DownloadState.COMPLETED -> R.color.gf_green
                    DownloadState.FAILED, DownloadState.CANCELED -> R.color.gf_danger
                    DownloadState.DOWNLOADING -> R.color.gf_accent
                    DownloadState.PAUSED -> R.color.gf_warning
                }

                card.addView(TextView(activity).apply {
                    text = statusText
                    setTextColor(ContextCompat.getColor(activity, statusColor))
                    textSize = 12f
                    setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                })

                val actionsRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }

                fun addActionChip(label: String, colorRes: Int, onClick: () -> Unit) {
                    actionsRow.addView(TextView(activity).apply {
                        text = label
                        setTextColor(ContextCompat.getColor(activity, colorRes))
                        textSize = 12.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setSelectableRipple(activity, this)
                        setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                        setOnClickListener { onClick() }
                    })
                }

                when (d.state) {
                    DownloadState.DOWNLOADING -> {
                        addActionChip("⏸ توقف موقت", R.color.gf_warning) {
                            engine.pauseDownload(d.id)
                        }
                        addActionChip("✕ لغو", R.color.gf_danger) {
                            engine.cancelDownload(d.id)
                        }
                    }
                    DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELED -> {
                        if (d.url.startsWith("http", ignoreCase = true)) {
                            val lbl = if (d.state == DownloadState.PAUSED) "▶ ادامه دانلود" else "🔄 تلاش مجدد"
                            addActionChip(lbl, R.color.gf_accent) {
                                engine.resumeOrRetryDownload(d.id)
                            }
                        }
                        addActionChip("🗑 حذف", R.color.gf_danger) {
                            engine.deleteDownload(d.id, deleteDiskFile = true)
                        }
                    }
                    DownloadState.COMPLETED -> {
                        addActionChip(if (isApk) "📂 پوشه دانلودها" else "📂 باز کردن", R.color.gf_accent) {
                            openDownloadedFile(activity, d)
                        }
                        addActionChip("اشتراک‌گذاری", R.color.gf_text_primary) {
                            shareDownloadedFile(activity, d)
                        }
                        addActionChip("حذف از لیست", R.color.gf_danger) {
                            engine.deleteDownload(d.id, deleteDiskFile = false)
                        }
                    }
                }

                card.addView(actionsRow)
                listContainer.addView(card)
            }
        }

        renderDownloads(engine.downloadsFlow.value.ifEmpty { store.getDownloads() })

        val collectJob: Job = activity.lifecycleScope.launch {
            engine.downloadsFlow.collectLatest { updated ->
                renderDownloads(updated)
            }
        }
        dialog.setOnDismissListener {
            collectJob.cancel()
        }

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    internal fun showNewDownloadByUrlDialog(activity: MainActivity) {
        val dp = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, (12 * dp).toInt(), p, (4 * dp).toInt())
        }
        val etUrl = EditText(activity).apply {
            hint = "https://example.com/video.mp4 یا stream.m3u8"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR
            setSingleLine()
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim().orEmpty()
            if (clip.startsWith("http://", true) || clip.startsWith("https://", true)) {
                setText(clip)
                setSelection(clip.length)
            }
        }
        box.addView(etUrl)

        val dlg = AlertDialog.Builder(activity)
            .setTitle("دانلود مستقیم فایل یا استریم m3u8 (شتاب‌دهنده IDM)")
            .setView(box)
            .setPositiveButton("شروع دانلود") { _, _ ->
                val rawUrl = etUrl.text?.toString()?.trim().orEmpty()
                if (!rawUrl.startsWith("http://", true) && !rawUrl.startsWith("https://", true)) {
                    Toast.makeText(activity, "لطفاً یک لینک معتبر http یا https وارد کنید", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                activity.startManagedDownload(rawUrl, "", "", "")
            }
            .setNegativeButton("انصراف", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
    }

    private fun resolveDownloadContentUri(activity: MainActivity, d: DownloadRecord): Uri? {
        // 1. Prefer app staging File via FileProvider (100% reliable across API 21–35 & APK PackageInstaller)
        if (d.stagingPath.isNotBlank()) {
            val f = File(d.stagingPath)
            if (f.exists() && f.length() > 0L) {
                val fp = runCatching {
                    FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", f)
                }.getOrNull()
                if (fp != null) return fp
            }
        }

        // 2. Fallback to localUriOrPath
        if (d.localUriOrPath.isNotBlank()) {
            if (d.localUriOrPath.startsWith("content://")) {
                val u = Uri.parse(d.localUriOrPath)
                val ok = runCatching {
                    activity.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (ok) return u
            } else {
                val path = d.localUriOrPath.removePrefix("file://")
                val f = File(path)
                if (f.exists()) {
                    return runCatching {
                        FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", f)
                    }.getOrNull()
                }
            }
        }
        return null
    }

    private fun resolveDownloadMime(d: DownloadRecord): String {
        val ext = d.fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext == "apk") return "application/vnd.android.package-archive"
        if (d.mimeType.isNotBlank() && d.mimeType != "application/octet-stream") {
            return d.mimeType
        }
        if (ext.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return d.mimeType.ifBlank { "*/*" }
    }

    private fun openDownloadedFile(activity: MainActivity, d: DownloadRecord) {
        val mime = resolveDownloadMime(d)
        // Google Play Protect Compliance: never invoke package-installer intents from inside browser;
        // guide user to standard system Downloads app for .apk files.
        if (mime == "application/vnd.android.package-archive") {
            Toast.makeText(
                activity,
                "فایل در پوشه Downloads گوشی ذخیره شده است؛ از مدیریت فایل گوشی باز کنید.",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                activity.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            }
            return
        }

        val uri = resolveDownloadContentUri(activity, d)
        if (uri == null) {
            Toast.makeText(activity, "فایل روی حافظه یافت نشد (ممکن است جابه‌جا یا حذف شده باشد)", Toast.LENGTH_SHORT).show()
            return
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            activity.startActivity(viewIntent)
        }.onFailure {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                activity.startActivity(Intent.createChooser(fallback, d.fileName))
            }.onFailure {
                Toast.makeText(activity, "برنامه‌ای برای باز کردن این فایل روی گوشی نصب نیست", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareDownloadedFile(activity: MainActivity, d: DownloadRecord) {
        val uri = resolveDownloadContentUri(activity, d)
        if (uri == null) {
            Toast.makeText(activity, "فایل برای اشتراک‌گذاری یافت نشد", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = resolveDownloadMime(d)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(share, d.fileName))
        }
    }

    private fun fileTypeEmoji(fileName: String, mime: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".apk") || mime.contains("android.package-archive") -> "📦"
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || mime.startsWith("video/") -> "🎬"
            lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") || mime.startsWith("audio/") -> "🎵"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || mime.startsWith("image/") -> "🖼️"
            lower.endsWith(".pdf") || mime.contains("pdf") -> "📕"
            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> "🗜️"
            else -> "📄"
        }
    }

    // =========================================================================
    // 7. Chrome Site Security & Connection Info Sheet (Lock Icon Tap)
    // =========================================================================
    fun showSiteSecuritySheet(activity: MainActivity, tab: BrowserTab) {
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity)
        val isHttps = tab.currentUrl.startsWith("https://", true)
        val isHome = tab.currentUrl == BrowserTabManager.HOME_URL

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
            val p = (20 * dp).toInt()
            setPadding(p, p, p, p)
        }

        val secTitle = when {
            isHome -> "صفحه شروع Dr Browser v6.0"
            isHttps -> "🔒 اتصال به این سایت امن است (HTTPS)"
            else -> "ℹ اتصال این صفحه رمزگذاری نشده است (HTTP)"
        }
        box.addView(TextView(activity).apply {
            text = secTitle
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })

        if (!isHome) {
            box.addView(TextView(activity).apply {
                text = tab.currentUrl
                setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
                textSize = 12.5f
                textDirection = View.TEXT_DIRECTION_LTR
                setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
            })
        }

        val st = activity.tunnelStatusSnapshot()
        val srcBadge = if (st.bestSource.isNotBlank()) " [${st.bestSource}]" else ""
        val multiBadge = if (st.activeSources > 0) " • از ${st.activeSources} منبع مستقل" else ""
        val proxyDesc = if (st.readyNodes > 0) {
            "عبور هوشمند ضد فیلتر: فعال • سرور ${st.bestNode.ifBlank { "خودکار" }}$srcBadge (${st.bestRttMs}ms)$multiBadge"
        } else {
            "عبور هوشمند ضد فیلتر: فعال (DoH + ضد DPI)"
        }
        box.addView(TextView(activity).apply {
            text = proxyDesc
            setTextColor(ContextCompat.getColor(activity, R.color.gf_accent))
            textSize = 13f
            setPadding(0, (4 * dp).toInt(), 0, (14 * dp).toInt())
        })

        if (!isHome) {
            val copyBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "کپی آدرس کامل سایت"
                setOnClickListener {
                    dialog.dismiss()
                    copyToClipboard(activity, "URL", tab.currentUrl, "آدرس سایت کپی شد")
                }
            }
            val clearSiteBtn = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "پاک کردن کوکی‌ها و بازخوانی سایت"
                setTextColor(ContextCompat.getColor(activity, R.color.gf_danger))
                setOnClickListener {
                    dialog.dismiss()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    tab.webView.reload()
                    Toast.makeText(activity, "داده‌های سایت پاک شد", Toast.LENGTH_SHORT).show()
                }
            }
            box.addView(copyBtn)
            box.addView(clearSiteBtn)
        }

        dialog.setContentView(box)
        dialog.show()
    }

    // =========================================================================
    // 8. Full Google Chrome Browser Settings Sheet
    // =========================================================================
    fun showChromeSettingsDialog(activity: MainActivity) {
        val store = activity.dataStore
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity)

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, p, p, p)
        }
        scroll.addView(box)

        box.addView(TextView(activity).apply {
            text = "⚙️ تنظیمات مرورگر (Dr Browser v6.0)"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 17.5f
            setTypeface(typeface, Typeface.BOLD)
        })

        // Search Engine
        box.addView(makeSectionLabel(activity, "موتور جستجوی پیش‌فرض:"))
        val engines = SearchEngine.entries
        val spEngine = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, engines.map { it.displayName })
            setSelection(engines.indexOf(store.searchEngine).coerceAtLeast(0))
        }
        box.addView(spEngine)

        // Theme (System / Light / Dark)
        box.addView(makeSectionLabel(activity, "پوسته ظاهری (Theme):"))
        val themes = ThemeModePref.entries
        val spTheme = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, themes.map { it.displayName })
            setSelection(themes.indexOf(store.themeMode).coerceAtLeast(0))
        }
        box.addView(spTheme)

        // Text Zoom
        var chosenZoom = store.textZoomPercent
        val tvZoom = makeSectionLabel(activity, "اندازه متن صفحات وب: ${chosenZoom}%")
        box.addView(tvZoom)
        val seekZoom = SeekBar(activity).apply {
            max = 100 // maps 0..100 -> 75%..175%
            progress = (chosenZoom - 75).coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    chosenZoom = 75 + progress
                    tvZoom.text = "اندازه متن صفحات وب: ${chosenZoom}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        box.addView(seekZoom)

        val cbJs = CheckBox(activity).apply {
            text = "اجرای جاوااسکریپت (JavaScript)"
            isChecked = store.javaScriptEnabled
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        val cbCookies = CheckBox(activity).apply {
            text = "ذخیره کوکی‌های ورود به سایت‌ها"
            isChecked = store.cookiesEnabled
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        val cbPopups = CheckBox(activity).apply {
            text = "مسدودسازی پنجره‌های پاپ‌آپ مزاحم"
            isChecked = store.blockPopups
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        val cbAdblock = CheckBox(activity).apply {
            text = "حذف تبلیغات و ردیاب‌ها (AdBlock)"
            isChecked = activity.tunnelAdBlockEnabled
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        val cbDesktopDefault = CheckBox(activity).apply {
            text = "باز کردن تب‌های جدید در حالت دسکتاپ"
            isChecked = store.defaultDesktopMode
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        box.addView(cbJs)
        box.addView(cbCookies)
        box.addView(cbPopups)
        box.addView(cbAdblock)
        box.addView(cbDesktopDefault)

        val btnResetShortcuts = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "بازنشانی میانبرهای صفحه اول به پیش‌فرض"
            setOnClickListener {
                store.resetDefaultShortcuts()
                activity.refreshChromeShortcutsGrid()
                Toast.makeText(activity, "میانبرهای پیش‌فرض بازیابی شدند", Toast.LENGTH_SHORT).show()
            }
        }
        box.addView(btnResetShortcuts)

        val btnSave = MaterialButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * dp).toInt()
            ).apply { topMargin = (10 * dp).toInt() }
            text = "ذخیره تنظیمات"
            setOnClickListener {
                val prevTheme = store.themeMode
                val nextEngine = engines[spEngine.selectedItemPosition]
                val nextTheme = themes[spTheme.selectedItemPosition]

                store.searchEngine = nextEngine
                store.themeMode = nextTheme
                store.textZoomPercent = chosenZoom
                store.javaScriptEnabled = cbJs.isChecked
                store.cookiesEnabled = cbCookies.isChecked
                store.blockPopups = cbPopups.isChecked
                store.defaultDesktopMode = cbDesktopDefault.isChecked
                activity.tunnelAdBlockEnabled = cbAdblock.isChecked

                activity.tabManager.applySettingsToAllTabs()
                activity.updateBrandWordmarkForEngine()
                dialog.dismiss()

                if (prevTheme != nextTheme) {
                    activity.tabManager.persistSessionQuietly()
                    AppCompatDelegate.setDefaultNightMode(nextTheme.nightMode)
                }
                Toast.makeText(activity, "تنظیمات ذخیره شد", Toast.LENGTH_SHORT).show()
            }
        }
        box.addView(btnSave)

        dialog.setContentView(scroll)
        dialog.show()
    }

    // =========================================================================
    // 9. Shortcut Add/Edit Dialog & Native Print-to-PDF Helper
    // =========================================================================
    fun showShortcutOptionsOrAddDialog(activity: MainActivity, existing: ShortcutItem?) {
        val store = activity.dataStore
        val dp = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, (12 * dp).toInt(), p, 0)
        }
        val etLabel = EditText(activity).apply {
            hint = "نام سایت (مثلاً ورزش سه یا گیت‌هاب)"
            setText(existing?.label.orEmpty())
            setSingleLine()
        }
        val etUrl = EditText(activity).apply {
            hint = "آدرس سایت (مثلاً https://github.com)"
            setText(existing?.url.orEmpty())
            setSingleLine()
            textDirection = View.TEXT_DIRECTION_LTR
        }
        box.addView(etLabel)
        box.addView(etUrl)

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "افزودن میانبر به صفحه اول" else "ویرایش میانبر")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                var u = etUrl.text?.toString()?.trim().orEmpty()
                if (u.isNotEmpty()) {
                    if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
                        u = "https://$u"
                    }
                    val lbl = etLabel.text?.toString()?.trim().orEmpty().ifBlank { u }
                    val ch = lbl.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "W"
                    val item = existing?.copy(label = lbl, url = u, badgeText = ch)
                        ?: ShortcutItem(
                            label = lbl,
                            badgeText = ch,
                            badgeHex = BrowserDataStore.pickBadgeColor(u),
                            url = u
                        )
                    store.addOrUpdateShortcut(item)
                    activity.refreshChromeShortcutsGrid()
                }
            }
            .setNegativeButton("انصراف", null)

        if (existing != null) {
            builder.setNeutralButton("حذف میانبر") { _, _ ->
                store.removeShortcut(existing.id)
                activity.refreshChromeShortcutsGrid()
            }
        }
        val dlg = builder.create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
    }

    fun printCurrentWebPage(activity: MainActivity, tab: BrowserTab) {
        runCatching {
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
            val jobName = "DrBrowser_${tab.title.take(24).ifBlank { "Page" }}"
            val adapter = tab.webView.createPrintDocumentAdapter(jobName)
            printManager.print(jobName, adapter, PrintAttributes.Builder().build())
        }.onFailure {
            Toast.makeText(activity, "خطا در آماده‌سازی چاپ PDF: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // Shared UI Helpers
    // =========================================================================
    private fun makeTwoLineListRow(
        activity: Context,
        title: String,
        subtitle: String,
        onRowClick: () -> Unit,
        onRowLongClick: (() -> Unit)? = null,
        onDeleteClick: (() -> Unit)? = null
    ): View {
        val dp = activity.resources.displayMetrics.density
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card_dark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (7 * dp).toInt() }
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onRowClick() }
            if (onRowLongClick != null) {
                setOnLongClickListener {
                    onRowLongClick()
                    true
                }
            }
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(activity).apply {
            text = title
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(activity).apply {
            text = subtitle
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
            textSize = 11.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(textCol)

        if (onDeleteClick != null) {
            val delBtn = ImageButton(activity).apply {
                layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt())
                setImageResource(R.drawable.ic_close)
                setSelectableBorderlessRipple(activity, this)
                setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { onDeleteClick() }
            }
            row.addView(delBtn)
        }
        return row
    }

    private fun makeEmptyStateLabel(context: Context, text: String): TextView {
        val dp = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.gf_text_secondary))
            textSize = 13.5f
            setPadding((16 * dp).toInt(), (40 * dp).toInt(), (16 * dp).toInt(), (40 * dp).toInt())
        }
    }

    private fun makeSectionLabel(context: Context, text: String): TextView {
        val dp = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.gf_text_secondary))
            textSize = 12.5f
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }
    }

    private fun makeDivider(context: Context): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(ContextCompat.getColor(context, R.color.gf_border))
        }
    }

    private fun setSelectableRipple(context: Context, view: View) {
        val outVal = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outVal, true)
        view.setBackgroundResource(outVal.resourceId)
    }

    private fun setSelectableBorderlessRipple(context: Context, view: View) {
        val outVal = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
        view.setBackgroundResource(outVal.resourceId)
    }

    fun copyToClipboard(context: Context, label: String, text: String, toastMsg: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }

    fun sharePlainText(context: Context, text: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
