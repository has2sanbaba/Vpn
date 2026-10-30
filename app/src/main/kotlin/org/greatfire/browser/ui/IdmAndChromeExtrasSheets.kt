package org.greatfire.browser.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.greatfire.browser.R
import org.greatfire.browser.data.ChromeColorTheme
import org.greatfire.browser.data.ThemeModePref
import org.greatfire.browser.download.DownloadEngine
import org.greatfire.browser.media.DetectedMediaItem
import org.greatfire.browser.media.HlsPlaylistParser
import org.greatfire.browser.media.SniffedMediaKind
import org.greatfire.browser.media.VideoStreamSniffer
import org.greatfire.browser.vpn.TunnelManager
import org.greatfire.browser.web.BrowserTab
import org.greatfire.browser.web.BrowserTabManager

object IdmAndChromeExtrasSheets {

    // =========================================================================
    // 1. IDM-Style Video & Stream Sniffer + HLS Quality Picker BottomSheet
    // =========================================================================
    fun showIdmVideoGrabberSheet(activity: MainActivity) {
        if (activity.isFinishing) return
        val activeTab = activity.tabManager.activeTab
        activeTab?.webView?.triggerIdmMediaRescan()

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
            text = "🎬 دانلودر ویدیو و استریم (IDM)"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
        }
        val rescanBtn = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "🔄 اسکن مجدد"
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.gf_accent))
            setOnClickListener {
                activity.tabManager.activeTab?.webView?.triggerIdmMediaRescan()
                Toast.makeText(activity, "درحال اسکن عمیق ویدیوهای صفحه…", Toast.LENGTH_SHORT).show()
            }
        }
        val manualUrlBtn = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "➕ لینک"
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.gf_accent))
            setOnClickListener {
                dialog.dismiss()
                ChromeFeaturesDialogs.showNewDownloadByUrlDialog(activity)
            }
        }
        headerRow.addView(titleTv)
        headerRow.addView(rescanBtn)
        headerRow.addView(manualUrlBtn)
        root.addView(headerRow)

        val subtitleTv = TextView(activity).apply {
            text = "شناسایی خودکار فایل‌های ویدیویی، صوتی و استریم‌های چندکیفیتی (.mp4 / .m3u8 / .webm / .mp3)"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
            textSize = 11.5f
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        }
        root.addView(subtitleTv)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (400 * dp).toInt()
            )
            addView(listContainer)
        }
        root.addView(scroll)

        val probedHlsVariantsCache = HashMap<String, List<HlsPlaylistParser.VariantStream>>()
        val probedHlsDrmCache = HashMap<String, String>()
        val inFlightProbes = HashSet<String>()

        fun probeMediaMetadataAsync(tab: BrowserTab, item: DetectedMediaItem) {
            if (!inFlightProbes.add(item.id)) return
            activity.lifecycleScope.launch {
                val client = TunnelManager.getInstance(activity).getStreamingDownloadOkHttpClient()
                if (item.isHls) {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val reqBuilder = Request.Builder()
                                .url(item.url)
                                .header("User-Agent", DownloadEngine.DEFAULT_DOWNLOAD_UA)
                            runCatching {
                                val ck = CookieManager.getInstance().getCookie(item.url)
                                if (!ck.isNullOrBlank()) reqBuilder.header("Cookie", ck)
                            }
                            if (tab.currentUrl.startsWith("http", true)) {
                                reqBuilder.header("Referer", tab.currentUrl)
                            }
                            client.newCall(reqBuilder.build()).execute().use { resp ->
                                if (!resp.isSuccessful) return@use null
                                resp.body?.string()
                            }
                        }.getOrNull()
                    }
                    if (!result.isNullOrBlank() && HlsPlaylistParser.looksLikeHlsManifest(result)) {
                        if (HlsPlaylistParser.isMasterPlaylist(result)) {
                            val vars = HlsPlaylistParser.parseMasterPlaylist(result, item.url)
                            if (vars.isNotEmpty()) {
                                probedHlsVariantsCache[item.id] = vars
                            }
                        } else {
                            val mediaPl = HlsPlaylistParser.parseMediaPlaylist(result, item.url)
                            if (mediaPl.isDrmOrEncrypted) {
                                probedHlsDrmCache[item.id] = mediaPl.encryptionMethod
                            }
                        }
                    }
                } else if (item.contentLengthBytes <= 0L) {
                    val len = withContext(Dispatchers.IO) {
                        runCatching {
                            val reqBuilder = Request.Builder()
                                .url(item.url)
                                .header("User-Agent", DownloadEngine.DEFAULT_DOWNLOAD_UA)
                                .header("Range", "bytes=0-0")
                            runCatching {
                                val ck = CookieManager.getInstance().getCookie(item.url)
                                if (!ck.isNullOrBlank()) reqBuilder.header("Cookie", ck)
                            }
                            if (tab.currentUrl.startsWith("http", true)) {
                                reqBuilder.header("Referer", tab.currentUrl)
                            }
                            client.newCall(reqBuilder.build()).execute().use { resp ->
                                if (resp.code == 206) {
                                    resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                                } else if (resp.isSuccessful) {
                                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                                } else -1L
                            }
                        }.getOrDefault(-1L)
                    }
                    if (len > 0L) {
                        VideoStreamSniffer.updateProbedSize(tab.id, item.id, len)
                    }
                }
            }
        }

        fun renderDetectedItems() {
            listContainer.removeAllViews()
            val tab = activity.tabManager.activeTab
            val items = if (tab != null) VideoStreamSniffer.getMediaForTab(tab.id) else emptyList()

            if (tab == null || tab.currentUrl == BrowserTabManager.HOME_URL || items.isEmpty()) {
                val hintBox = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_card_dark)
                    setPadding((18 * dp).toInt(), (28 * dp).toInt(), (18 * dp).toInt(), (28 * dp).toInt())
                }
                hintBox.addView(TextView(activity).apply {
                    text = if (tab?.currentUrl == BrowserTabManager.HOME_URL) {
                        "🎬 آماده شناسایی خودکار ویدیوها!\n\nوارد هر سایت ویدیویی یا آموزشی شوید و روی دکمه پخش (Play) ویدیو بزنید؛ دکمه شناور آبی‌رنگ IDM بلافاصله ظاهر می‌شود و تمام لینک‌های استریم و کیفیت‌ها را برای دانلود در اختیار شما می‌گذارد."
                    } else {
                        "⏳ هنوز ویدیویی در این صفحه پخش نشده است.\n\nاگر صفحه دارای ویدیو است، یک‌بار روی دکمه پخش (▶) ویدیو بزنید و سپس «🔄 اسکن مجدد» را لمس کنید."
                    }
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
                    textSize = 13f
                })
                listContainer.addView(hintBox)
                return
            }

            for (item in items) {
                probeMediaMetadataAsync(tab, item)

                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_card_dark)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (10 * dp).toInt() }
                    setPadding((13 * dp).toInt(), (11 * dp).toInt(), (13 * dp).toInt(), (11 * dp).toInt())
                }

                // Top Row: Format Badge + Title
                val topRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val badgeColor = when (item.kind) {
                    SniffedMediaKind.HLS_STREAM -> "#9334E6"
                    SniffedMediaKind.AUDIO -> "#188038"
                    SniffedMediaKind.VIDEO -> "#1A73E8"
                }
                val badgeTv = TextView(activity).apply {
                    text = item.formatBadge
                    setTextColor(Color.WHITE)
                    textSize = 10.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8 * dp
                        setColor(Color.parseColor(badgeColor))
                    }
                }
                val itemTitleTv = TextView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (8 * dp).toInt()
                    }
                    text = item.title
                    setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                topRow.addView(badgeTv)
                topRow.addView(itemTitleTv)
                card.addView(topRow)

                // Metadata Row: Quality + Duration + Size
                val metaParts = ArrayList<String>(4)
                if (item.qualityLabel.isNotBlank()) metaParts.add(item.qualityLabel)
                val durStr = item.formattedDuration()
                if (durStr.isNotEmpty()) metaParts.add("⏱ $durStr")
                if (item.contentLengthBytes > 0L) {
                    metaParts.add("📦 ${ChromeFeaturesDialogs.formatBytes(item.contentLengthBytes)}")
                } else if (!item.isHls) {
                    metaParts.add("شتاب‌دهنده چندکاناله IDM")
                }

                card.addView(TextView(activity).apply {
                    text = metaParts.joinToString("  •  ")
                    setTextColor(ContextCompat.getColor(activity, R.color.gf_accent))
                    textSize = 11.5f
                    setPadding(0, (5 * dp).toInt(), 0, (4 * dp).toInt())
                })

                val drmMethod = probedHlsDrmCache[item.id]
                val variants = probedHlsVariantsCache[item.id]

                if (drmMethod != null) {
                    card.addView(TextView(activity).apply {
                        text = "🔒 این استریم دارای قفل DRM ($drmMethod) است و دانلود آن مجاز نیست."
                        setTextColor(ContextCompat.getColor(activity, R.color.gf_danger))
                        textSize = 11.5f
                    })
                } else if (!variants.isNullOrEmpty()) {
                    card.addView(TextView(activity).apply {
                        text = "انتخاب کیفیت استریم (تبدیل خودکار به MP4):"
                        setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
                        textSize = 11.5f
                        setPadding(0, (2 * dp).toInt(), 0, (5 * dp).toInt())
                    })
                    val chipScroll = HorizontalScrollView(activity).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val chipRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    for (v in variants.take(6)) {
                        val br = v.bitrateLabel().let { if (it.isNotEmpty()) " • $it" else "" }
                        val btn = MaterialButton(activity).apply {
                            text = "⬇ ${v.qualityBadge()}$br"
                            textSize = 11.5f
                            insetTop = 0
                            insetBottom = 0
                            cornerRadius = (16 * dp).toInt()
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                (36 * dp).toInt()
                            ).apply { marginEnd = (6 * dp).toInt() }
                            setOnClickListener {
                                dialog.dismiss()
                                val customName = item.copy(height = v.height).suggestedFileName()
                                activity.startManagedDownload(
                                    url = v.url,
                                    userAgent = "",
                                    contentDisposition = "",
                                    mimeType = "application/vnd.apple.mpegurl",
                                    sourceTab = tab,
                                    suggestedFileNameOverride = customName
                                )
                            }
                        }
                        chipRow.addView(btn)
                    }
                    chipScroll.addView(chipRow)
                    card.addView(chipScroll)
                }

                // Bottom Action Row
                val actionsRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(0, (6 * dp).toInt(), 0, 0)
                }

                val copyChip = TextView(activity).apply {
                    text = "📋 کپی لینک"
                    setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                    setOnClickListener {
                        ChromeFeaturesDialogs.copyToClipboard(activity, "Stream URL", item.url, "لینک اصلی استریم کپی شد")
                    }
                }
                actionsRow.addView(copyChip)

                if (drmMethod == null) {
                    val dlBtn = MaterialButton(activity).apply {
                        text = if (item.isHls) "⬇ دانلود بهترین کیفیت" else "⬇ دانلود پرسرعت IDM"
                        textSize = 12f
                        insetTop = 0
                        insetBottom = 0
                        cornerRadius = (18 * dp).toInt()
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            (36 * dp).toInt()
                        ).apply { marginStart = (6 * dp).toInt() }
                        setOnClickListener {
                            dialog.dismiss()
                            activity.startManagedDownload(
                                url = item.url,
                                userAgent = "",
                                contentDisposition = "",
                                mimeType = item.mimeType,
                                sourceTab = tab,
                                suggestedFileNameOverride = item.suggestedFileName()
                            )
                        }
                    }
                    actionsRow.addView(dlBtn)
                }

                card.addView(actionsRow)
                listContainer.addView(card)
            }
        }

        renderDetectedItems()

        val watchJob: Job = activity.lifecycleScope.launch {
            VideoStreamSniffer.updatesFlow.collectLatest {
                renderDetectedItems()
            }
        }
        dialog.setOnDismissListener {
            watchJob.cancel()
        }

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    // =========================================================================
    // 2. Chrome Reader Mode / Simplified View Sheet (حالت مطالعه بدون تبلیغ)
    // =========================================================================
    fun showChromeReaderModeSheet(activity: MainActivity, tab: BrowserTab) {
        if (tab.currentUrl == BrowserTabManager.HOME_URL) return
        tab.webView.extractReadablePageText { extractedTitle, extractedBody ->
            if (extractedBody.length < 40) {
                Toast.makeText(activity, "متن مقاله‌ای برای حالت مطالعه در این صفحه یافت نشد", Toast.LENGTH_SHORT).show()
                return@extractReadablePageText
            }

            val dp = activity.resources.displayMetrics.density
            val dialog = BottomSheetDialog(activity)
            var fontSizeSp = 16.5f
            var sepiaMode = false

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
                val p = (18 * dp).toInt()
                setPadding(p, p, p, p)
            }

            val controlsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val headerTitle = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "📖 حالت مطالعه کروم"
                setTextColor(ContextCompat.getColor(activity, R.color.gf_accent))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            }

            lateinit var bodyTv: TextView
            lateinit var articleTitleTv: TextView

            fun makeReaderBtn(lbl: String, onClick: () -> Unit): TextView {
                return TextView(activity).apply {
                    text = lbl
                    setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_ntp_chip)
                    setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (6 * dp).toInt() }
                    setOnClickListener { onClick() }
                }
            }

            controlsRow.addView(headerTitle)
            controlsRow.addView(makeReaderBtn("A-") {
                fontSizeSp = (fontSizeSp - 1.5f).coerceAtLeast(13f)
                bodyTv.textSize = fontSizeSp
            })
            controlsRow.addView(makeReaderBtn("A+") {
                fontSizeSp = (fontSizeSp + 1.5f).coerceAtMost(26f)
                bodyTv.textSize = fontSizeSp
            })
            controlsRow.addView(makeReaderBtn("📜 سپیا") {
                sepiaMode = !sepiaMode
                if (sepiaMode) {
                    root.setBackgroundColor(Color.parseColor("#FBF0D9"))
                    articleTitleTv.setTextColor(Color.parseColor("#3B2E1E"))
                    bodyTv.setTextColor(Color.parseColor("#4A3B28"))
                } else {
                    root.setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
                    articleTitleTv.setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
                    bodyTv.setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
                }
            })
            controlsRow.addView(makeReaderBtn("🔊 خواندن") {
                dialog.dismiss()
                activity.startListenToCurrentPage(tab)
            })
            root.addView(controlsRow)

            val scroll = ScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (450 * dp).toInt()
                ).apply { topMargin = (12 * dp).toInt() }
            }
            val contentCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            articleTitleTv = TextView(activity).apply {
                text = extractedTitle.ifBlank { tab.title }
                setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, (12 * dp).toInt())
            }
            bodyTv = TextView(activity).apply {
                text = extractedBody
                setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
                textSize = fontSizeSp
                setLineSpacing(6 * dp, 1.15f)
                setTextIsSelectable(true)
            }
            contentCol.addView(articleTitleTv)
            contentCol.addView(bodyTv)
            scroll.addView(contentCol)
            root.addView(scroll)

            dialog.setContentView(root)
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.show()
        }
    }

    // =========================================================================
    // 3. Authentic 2026 Chrome Appearance & Colors Sheet (Screenshot #1!)
    // =========================================================================
    fun showChromeAppearanceSheet(activity: MainActivity) {
        val store = activity.dataStore
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.gf_bg_surface))
            val p = (20 * dp).toInt()
            setPadding(p, p, p, p)
        }

        root.addView(TextView(activity).apply {
            text = "🎨 شخصی‌سازی ظاهر و رنگ کروم"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
            textSize = 17.5f
            setTypeface(typeface, Typeface.BOLD)
        })

        // 1. Chrome Color Swatches (Default, Ocean Blue, Emerald, Violet, Warm Sunset)
        root.addView(TextView(activity).apply {
            text = "رنگ‌بندی کروم (Chrome Colors):"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
            textSize = 12.5f
            setPadding(0, (14 * dp).toInt(), 0, (8 * dp).toInt())
        })

        val swatchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        fun rebuildSwatches() {
            swatchRow.removeAllViews()
            val currentColor = store.chromeColorTheme
            for (themeItem in ChromeColorTheme.entries) {
                val selected = themeItem == currentColor
                val size = (44 * dp).toInt()
                val swatch = TextView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (10 * dp).toInt()
                    }
                    gravity = Gravity.CENTER
                    text = if (selected) "✓" else ""
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(themeItem.accentHex))
                        if (selected) {
                            setStroke(
                                (3 * dp).toInt(),
                                if (isDark) Color.WHITE else Color.parseColor("#202124")
                            )
                        }
                    }
                    contentDescription = themeItem.displayName
                    setOnClickListener {
                        store.chromeColorTheme = themeItem
                        activity.applyChromeAppearanceLive()
                        rebuildSwatches()
                        Toast.makeText(activity, "رنگ کروم: ${themeItem.displayName}", Toast.LENGTH_SHORT).show()
                    }
                }
                swatchRow.addView(swatch)
            }
        }
        rebuildSwatches()
        root.addView(swatchRow)

        // 2. Address Bar Position: Top vs Bottom (Exact Chrome Android Feature!)
        root.addView(TextView(activity).apply {
            text = "مکان نوار آدرس (Address bar):"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
            textSize = 12.5f
            setPadding(0, (16 * dp).toInt(), 0, (6 * dp).toInt())
        })

        val barPosGroup = RadioGroup(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rbTop = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "بالای صفحه (کلاسیک گوگل کروم)"
            isChecked = !store.bottomAddressBar
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        val rbBottom = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "پایین صفحه (دسترسی راحت با یک دست)"
            isChecked = store.bottomAddressBar
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_primary))
        }
        barPosGroup.addView(rbTop)
        barPosGroup.addView(rbBottom)
        barPosGroup.setOnCheckedChangeListener { _, checkedId ->
            store.bottomAddressBar = (checkedId == rbBottom.id)
            activity.applyAddressBarPlacement()
        }
        root.addView(barPosGroup)

        // 3. Light / Dark / System Mode
        root.addView(TextView(activity).apply {
            text = "حالت شب و روز:"
            setTextColor(ContextCompat.getColor(activity, R.color.gf_text_secondary))
            textSize = 12.5f
            setPadding(0, (14 * dp).toInt(), 0, (6 * dp).toInt())
        })

        val modeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        for (mode in ThemeModePref.entries) {
            val active = store.themeMode == mode
            val btn = MaterialButton(
                activity,
                null,
                if (active) com.google.android.material.R.attr.materialButtonStyle
                else com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply {
                    marginEnd = (4 * dp).toInt()
                }
                insetTop = 0
                insetBottom = 0
                textSize = 11f
                text = when (mode) {
                    ThemeModePref.SYSTEM -> "خودکار"
                    ThemeModePref.LIGHT -> "☀ روشن"
                    ThemeModePref.DARK -> "🌙 تیره"
                }
                setOnClickListener {
                    val prev = store.themeMode
                    store.themeMode = mode
                    dialog.dismiss()
                    if (prev != mode) {
                        activity.tabManager.persistSessionQuietly()
                        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
                    }
                }
            }
            modeRow.addView(btn)
        }
        root.addView(modeRow)

        dialog.setContentView(root)
        dialog.show()
    }
}
