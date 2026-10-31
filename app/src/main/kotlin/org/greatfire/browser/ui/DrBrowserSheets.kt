package org.greatfire.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.greatfire.browser.data.DownloadRecord
import org.greatfire.browser.data.DownloadState
import org.greatfire.browser.download.DownloadEngine
import org.greatfire.browser.media.DetectedMediaItem
import org.greatfire.browser.media.VideoStreamSniffer
import org.greatfire.browser.media.WebViewMediaInspector
import org.greatfire.browser.vpn.TunnelManager
import org.greatfire.browser.vpn.VerifiedNodeItem
import java.io.File
import java.util.Locale

/**
 * Scroll-safe, ultra-lightweight Material BottomSheets for Dr Browser v7.0:
 * 1. Smart Proxy & Custom Configs (50 live sources + periodic 3-min auto-retest + XHTTP/Reality/WS/gRPC/Sub).
 * 2. IDM Video & File Grabber (working async Rescan + smooth NestedScrollView list + multi-connection & HLS -> MP4).
 *
 * Strictly uses [NestedScrollView] (`isNestedScrollingEnabled = true`) coordinated with [BottomSheetBehavior]
 * (`STATE_EXPANDED`, `skipCollapsed = true`, and scroll-locked dragging while `scrollY > 0`) so menu and
 * detected-video list scrolling NEVER conflicts with sheet drag gestures.
 */
object DrBrowserSheets {

    private val shownUpgradeNodeIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Observes background proxy health probes and notifies the user with a non-intrusive
     * Material [Snackbar] ONLY when a significantly faster verified server is discovered.
     * The active server is NEVER switched automatically — switching happens ONLY if the user
     * explicitly taps «تغییر سرور»; otherwise the current exit IP stays pinned!
     */
    fun attachProxySuggestionObserver(activity: FragmentActivity, anchorView: View) {
        val tunnelManager = TunnelManager.getInstance(activity)
        activity.lifecycleScope.launch {
            tunnelManager.status.collect { st ->
                val sug = st.suggestedUpgrade ?: return@collect
                if (sug.id.isBlank() || !shownUpgradeNodeIds.add(sug.id)) return@collect
                if (activity.isFinishing || activity.isDestroyed) return@collect

                val gain = (st.bestRttMs - sug.rttMs).coerceAtLeast(0L)
                val gainStr = if (gain > 0L) " (${gain}ms سریع‌تر)" else ""
                val msg = "⚡ سرور سریع‌تر پیدا شد: ${sug.name} (${sug.rttMs}ms)$gainStr"

                var userAccepted = false
                val snackbar = Snackbar.make(anchorView, msg, 9000)
                    .setBackgroundTint(Color.parseColor("#1F2937"))
                    .setTextColor(Color.parseColor("#F0F6FC"))
                    .setActionTextColor(Color.parseColor("#3FB950"))
                    .setAction("تغییر سرور") {
                        userAccepted = true
                        activity.lifecycleScope.launch {
                            val res = tunnelManager.selectSpecificNode(sug.id)
                            if (res.ok) {
                                Toast.makeText(
                                    activity,
                                    "✅ سرور تغییر کرد به: ${res.bestNode} (${res.bestRttMs}ms)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            if (!userAccepted && event != DISMISS_EVENT_CONSECUTIVE) {
                                activity.lifecycleScope.launch {
                                    tunnelManager.dismissSuggestedUpgrade(sug.id)
                                }
                            }
                        }
                    })
                snackbar.view.layoutDirection = View.LAYOUT_DIRECTION_RTL
                snackbar.show()
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun roundedBg(fillColor: Int, radiusDp: Int, context: Context, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fillColor)
            if (strokeColor != 0) {
                setStroke(dp(context, 1), strokeColor)
            }
        }

    /**
     * Configures a [BottomSheetDialog] so internal [NestedScrollView] lists scroll effortlessly
     * from top to bottom on all Android versions (API 21–35+) without accidental sheet collapse.
     */
    private fun bindScrollSafeBottomSheet(
        activity: FragmentActivity,
        dialog: BottomSheetDialog,
        scrollView: NestedScrollView
    ) {
        scrollView.isNestedScrollingEnabled = true
        scrollView.isFillViewport = false
        scrollView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            bottomSheet.setBackgroundColor(Color.TRANSPARENT)

            val screenH = activity.resources.displayMetrics.heightPixels
            bottomSheet.layoutParams = bottomSheet.layoutParams?.apply {
                height = (screenH * 0.88f).toInt()
            }

            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.skipCollapsed = true
            behavior.isFitToContents = true
            behavior.peekHeight = (screenH * 0.88f).toInt()
            behavior.state = BottomSheetBehavior.STATE_EXPANDED

            scrollView.setOnScrollChangeListener(
                NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                    // Lock sheet dragging while user is scrolled down inside the list so upward/downward
                    // swipes always scroll the list cleanly; re-enable sheet drag at scrollY == 0.
                    behavior.isDraggable = scrollY <= 0
                }
            )
        }
    }

    // =========================================================================
    // 1. Smart Proxy, 50-Source Speed Test & Custom Config/Sub Sheet
    // =========================================================================

    fun showProxyControlSheet(activity: FragmentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val tunnelManager = TunnelManager.getInstance(activity)
        val dialog = BottomSheetDialog(activity)

        val surfaceBg = Color.parseColor("#161B22")
        val cardBg = Color.parseColor("#21262D")
        val borderCol = Color.parseColor("#30363D")
        val accentGreen = Color.parseColor("#2EA043")
        val accentBlue = Color.parseColor("#2F81F7")
        val textPrimary = Color.parseColor("#F0F6FC")
        val textSecondary = Color.parseColor("#8B949E")

        val outerRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundedBg(surfaceBg, 20, activity, borderCol)
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 16))
        }

        // Drag handle
        val handle = View(activity).apply {
            background = roundedBg(borderCol, 4, activity)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(activity, 10)
            }
        }
        outerRoot.addView(handle)

        // Header bar (pinned outside scroll view so user can always close or see title)
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(activity, 10))
        }
        val titleTv = TextView(activity).apply {
            text = "🛡 پروکسی هوشمند و ضد فیلتر (۵۰ منبع زنده)"
            setTextColor(textPrimary)
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(activity).apply {
            text = "✕"
            setTextColor(textSecondary)
            textSize = 18f
            setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 6), dp(activity, 4))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleTv)
        headerRow.addView(closeBtn)
        outerRoot.addView(headerRow)

        val scrollView = NestedScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 4), 0, dp(activity, 28))
        }
        scrollView.addView(contentCol)
        outerRoot.addView(scrollView)

        // Status Card
        val statusCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cardBg, 14, activity, borderCol)
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 12) }
        }
        val statusHeadline = TextView(activity).apply {
            setTextColor(Color.parseColor("#3FB950"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        val statusDetails = TextView(activity).apply {
            setTextColor(textSecondary)
            textSize = 12.5f
            setPadding(0, dp(activity, 6), 0, 0)
        }
        statusCard.addView(statusHeadline)
        statusCard.addView(statusDetails)
        contentCol.addView(statusCard)

        // Optional Better-Server Upgrade Suggestion Banner (User-Confirmed Switch Only!)
        val suggestionCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedBg(Color.parseColor("#182B24"), 14, activity, accentGreen)
            setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 12) }
        }
        val suggestionTitleTv = TextView(activity).apply {
            setTextColor(Color.parseColor("#3FB950"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        val suggestionSubTv = TextView(activity).apply {
            setTextColor(textSecondary)
            textSize = 11.5f
            setPadding(0, dp(activity, 4), 0, dp(activity, 8))
            text = "سرور فعلی شما برای جلوگیری از تغییر آی‌پی و قطع دانلود ثابت نگه داشته شده است. آیا مایل به تغییر به سرور سریع‌تر هستید؟"
        }
        val suggestionBtnsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val acceptUpgradeBtn = TextView(activity).apply {
            text = "✅ تغییر به سرور سریع‌تر"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(accentGreen, 10, activity)
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 6)
            }
        }
        val dismissUpgradeBtn = TextView(activity).apply {
            text = "نگه داشتن سرور فعلی"
            gravity = Gravity.CENTER
            setTextColor(textPrimary)
            textSize = 12f
            background = roundedBg(Color.parseColor("#30363D"), 10, activity)
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 6)
            }
        }
        suggestionBtnsRow.addView(acceptUpgradeBtn)
        suggestionBtnsRow.addView(dismissUpgradeBtn)
        suggestionCard.addView(suggestionTitleTv)
        suggestionCard.addView(suggestionSubTv)
        suggestionCard.addView(suggestionBtnsRow)
        contentCol.addView(suggestionCard)

        // Action Buttons Row (High-speed Retest + Switch Best)
        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 12) }
        }
        val retestBtn = TextView(activity).apply {
            text = "⚡ تست سرعت و بهترین سرور"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(accentGreen, 12, activity)
            setPadding(dp(activity, 10), dp(activity, 11), dp(activity, 10), dp(activity, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 6)
            }
        }
        val switchBtn = TextView(activity).apply {
            text = "🔄 تغییر سرور فعال"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(accentBlue, 12, activity)
            setPadding(dp(activity, 10), dp(activity, 11), dp(activity, 10), dp(activity, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 6)
            }
        }
        actionsRow.addView(retestBtn)
        actionsRow.addView(switchBtn)
        contentCol.addView(actionsRow)

        // Custom Config / Subscription Input Card (VLESS XHTTP / Reality / WS / gRPC / VMess / Trojan / SS / Sub)
        val customCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cardBg, 14, activity, borderCol)
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 12) }
        }
        val customLabel = TextView(activity).apply {
            text = "➕ افزودن کانفیگ اختصاصی یا لینک اشتراک (Sub)"
            setTextColor(textPrimary)
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
        }
        val customHint = TextView(activity).apply {
            text = "پشتیبانی کامل از VLESS (شامل XHTTP، Reality، WS، gRPC، TCP)، VMess، Trojan، SS، Clash و لینک‌های https://..."
            setTextColor(textSecondary)
            textSize = 11.5f
            setPadding(0, dp(activity, 4), 0, dp(activity, 8))
        }
        val configInput = EditText(activity).apply {
            hint = "vless://... یا vmess://... یا https://sub..."
            setHintTextColor(Color.parseColor("#6E7681"))
            setTextColor(textPrimary)
            textSize = 12.5f
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START or Gravity.TOP
            minLines = 2
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = roundedBg(Color.parseColor("#0D1117"), 10, activity, borderCol)
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
        }
        val customBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 8), 0, 0)
        }
        val pasteBtn = TextView(activity).apply {
            text = "📋 جای‌گذاری از کلیپ‌بورد"
            gravity = Gravity.CENTER
            setTextColor(textPrimary)
            textSize = 12.5f
            background = roundedBg(Color.parseColor("#30363D"), 10, activity)
            setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 10), dp(activity, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 6)
            }
            setOnClickListener {
                val cm = ContextCompat.getSystemService(activity, ClipboardManager::class.java)
                val clipTxt = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
                if (clipTxt.isNotBlank()) {
                    configInput.setText(clipTxt.trim())
                } else {
                    Toast.makeText(activity, "کلیپ‌بورد خالی است", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val saveCustomBtn = TextView(activity).apply {
            text = "✅ بررسی و اتصال"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(accentBlue, 10, activity)
            setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 10), dp(activity, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 6)
            }
        }
        customBtnRow.addView(pasteBtn)
        customBtnRow.addView(saveCustomBtn)

        val customResultTv = TextView(activity).apply {
            textSize = 12f
            visibility = View.GONE
            setPadding(0, dp(activity, 8), 0, 0)
        }

        customCard.addView(customLabel)
        customCard.addView(customHint)
        customCard.addView(configInput)
        customCard.addView(customBtnRow)
        customCard.addView(customResultTv)
        contentCol.addView(customCard)

        // Verified Nodes List Section
        val listHeader = TextView(activity).apply {
            text = "🌐 سرورهای تاییدشده با پینگ واقعی (ضربه برای انتخاب)"
            setTextColor(textPrimary)
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(activity, 4), 0, dp(activity, 8))
        }
        contentCol.addView(listHeader)

        val nodesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentCol.addView(nodesContainer)

        fun renderStatusAndNodes(nodes: List<VerifiedNodeItem>, customNodes: List<VerifiedNodeItem>) {
            val st = tunnelManager.status.value
            statusHeadline.text = if (st.bestNode.isNotBlank()) {
                "🟢 متصل به: ${st.bestNode}"
            } else if (st.ready) {
                "🟡 در حال انتخاب سریع‌ترین سرور از ${st.configuredSources} منبع..."
            } else {
                "⏳ در حال راه‌اندازی موتور ضد فیلتر..."
            }
            val speedStr = st.formattedSpeed().let { if (it.isNotBlank()) " • سرعت: $it" else "" }
            val pingStr = if (st.bestRttMs > 0) "${st.bestRttMs}ms" else "—"
            statusDetails.text = buildString {
                append("پروتکل: ${st.bestProto.uppercase(Locale.US).ifBlank { "SMART" }} • پینگ: $pingStr$speedStr\n")
                append("سرورهای آماده: ${st.readyNodes} سرور از ${maxOf(st.activeSources, st.reachableSources)}/${st.configuredSources} منبع فعال\n")
                append("🔒 آی‌پی ثابت فعال است (تغییر سرور فقط با تایید شما انجام می‌شود)")
            }

            val sug = st.suggestedUpgrade
            if (sug != null && sug.id.isNotBlank()) {
                val gain = (st.bestRttMs - sug.rttMs).coerceAtLeast(0L)
                val gainTxt = if (gain > 0L) " (${gain}ms پینگ بهتر)" else ""
                suggestionTitleTv.text = "⚡ سرور سریع‌تر آماده است: ${sug.name} • پینگ ${sug.rttMs}ms$gainTxt"
                suggestionCard.visibility = View.VISIBLE
                acceptUpgradeBtn.setOnClickListener {
                    activity.lifecycleScope.launch {
                        val res = tunnelManager.selectSpecificNode(sug.id)
                        if (res.ok) {
                            Toast.makeText(activity, "✅ متصل به سرور سریع‌تر: ${res.bestNode}", Toast.LENGTH_SHORT).show()
                        }
                        tunnelManager.pollStatusOnce()
                        val fresh = tunnelManager.fetchVerifiedNodes()
                        val (cList, _) = tunnelManager.fetchCustomConfigsAndSubs()
                        renderStatusAndNodes(fresh, cList)
                    }
                }
                dismissUpgradeBtn.setOnClickListener {
                    suggestionCard.visibility = View.GONE
                    activity.lifecycleScope.launch {
                        tunnelManager.dismissSuggestedUpgrade(sug.id)
                        tunnelManager.pollStatusOnce()
                    }
                }
            } else {
                suggestionCard.visibility = View.GONE
            }

            nodesContainer.removeAllViews()
            val merged = LinkedHashMap<String, VerifiedNodeItem>()
            for (c in customNodes) merged[c.id] = c
            for (n in nodes) merged[n.id] = n

            if (merged.isEmpty()) {
                nodesContainer.addView(TextView(activity).apply {
                    text = "در حال تست موازی سرورها... چند لحظه صبر کنید یا دکمه «تست سرعت» را بزنید."
                    setTextColor(textSecondary)
                    textSize = 12.5f
                    setPadding(dp(activity, 8), dp(activity, 12), dp(activity, 8), dp(activity, 12))
                })
                return
            }

            for (node in merged.values) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val stroke = if (node.active) accentGreen else borderCol
                    val bg = if (node.active) Color.parseColor("#192B22") else cardBg
                    background = roundedBg(bg, 12, activity, stroke)
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(activity, 8) }
                }

                val infoCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameTv = TextView(activity).apply {
                    val badge = if (node.active) "✓ " else ""
                    val tag = if (node.custom) " [اختصاصی]" else ""
                    text = "$badge${node.name}$tag"
                    setTextColor(textPrimary)
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTypeface(typeface, if (node.active) Typeface.BOLD else Typeface.NORMAL)
                }
                val metaTv = TextView(activity).apply {
                    val rttTxt = if (node.rttMs > 0) "${node.rttMs} ms" else "آماده"
                    text = "${node.protocol} • منبع: ${node.source.ifBlank { "Global" }} • پینگ: $rttTxt"
                    setTextColor(textSecondary)
                    textSize = 11.5f
                }
                infoCol.addView(nameTv)
                infoCol.addView(metaTv)
                row.addView(infoCol)

                if (node.custom) {
                    val delBtn = TextView(activity).apply {
                        text = "حذف"
                        setTextColor(Color.parseColor("#F85149"))
                        textSize = 11.5f
                        setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6))
                        setOnClickListener {
                            activity.lifecycleScope.launch {
                                tunnelManager.deleteCustomConfigOrSub(nodeId = node.id)
                                val fresh = tunnelManager.fetchVerifiedNodes()
                                val (cList, _) = tunnelManager.fetchCustomConfigsAndSubs()
                                renderStatusAndNodes(fresh, cList)
                            }
                        }
                    }
                    row.addView(delBtn)
                }

                row.setOnClickListener {
                    activity.lifecycleScope.launch {
                        val res = tunnelManager.selectSpecificNode(node.id)
                        if (res.ok) {
                            Toast.makeText(activity, "✅ متصل به: ${res.bestNode}", Toast.LENGTH_SHORT).show()
                        }
                        val fresh = tunnelManager.fetchVerifiedNodes()
                        val (cList, _) = tunnelManager.fetchCustomConfigsAndSubs()
                        renderStatusAndNodes(fresh, cList)
                    }
                }
                nodesContainer.addView(row)
            }
        }

        fun refreshAllAsync() {
            activity.lifecycleScope.launch {
                tunnelManager.pollStatusOnce()
                val readyList = tunnelManager.fetchVerifiedNodes()
                val (customList, _) = tunnelManager.fetchCustomConfigsAndSubs()
                renderStatusAndNodes(readyList, customList)
            }
        }

        retestBtn.setOnClickListener {
            retestBtn.isEnabled = false
            retestBtn.text = "⏳ در حال تست پرسرعت..."
            activity.lifecycleScope.launch {
                tunnelManager.refreshNodesNow()
                val res = tunnelManager.runInstantRetest()
                retestBtn.isEnabled = true
                retestBtn.text = "⚡ تست سرعت و بهترین سرور"
                val msg = if (res.ok) {
                    "✅ بهترین سرور فعال شد: ${res.bestNode} (${res.bestRttMs}ms)"
                } else {
                    res.error ?: "در حال تکمیل دریافت منابع..."
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                refreshAllAsync()
            }
        }

        switchBtn.setOnClickListener {
            switchBtn.isEnabled = false
            switchBtn.text = "⏳ در حال جابه‌جایی..."
            activity.lifecycleScope.launch {
                val res = tunnelManager.switchToBestFastProxy()
                switchBtn.isEnabled = true
                switchBtn.text = "🔄 تغییر سرور فعال"
                val msg = if (res.ok) {
                    "✅ سرور جدید: ${res.bestNode} (${res.bestRttMs}ms)"
                } else {
                    res.error ?: "سرور جایگزین آماده نشد"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                refreshAllAsync()
            }
        }

        saveCustomBtn.setOnClickListener {
            val rawText = configInput.text?.toString()?.trim().orEmpty()
            if (rawText.isEmpty()) {
                Toast.makeText(activity, "لطفاً کانفیگ یا لینک اشتراک را وارد کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveCustomBtn.isEnabled = false
            saveCustomBtn.text = "⏳ در حال بررسی..."
            customResultTv.visibility = View.VISIBLE
            customResultTv.setTextColor(textSecondary)
            customResultTv.text = "در حال تجزیه و تست اتصال کانفیگ..."
            activity.lifecycleScope.launch {
                val res = tunnelManager.addCustomConfigsOrSub(rawText)
                saveCustomBtn.isEnabled = true
                saveCustomBtn.text = "✅ بررسی و اتصال"
                if (res.ok) {
                    configInput.setText("")
                    customResultTv.setTextColor(Color.parseColor("#3FB950"))
                    customResultTv.text = res.message.ifBlank { "✅ کانفیگ با موفقیت اضافه و فعال شد" }
                    refreshAllAsync()
                } else {
                    customResultTv.setTextColor(Color.parseColor("#F85149"))
                    customResultTv.text = res.error ?: "فرمت کانفیگ معتبر نیست"
                }
            }
        }

        val livePollJob: Job = activity.lifecycleScope.launch {
            while (isActive) {
                refreshAllAsync()
                delay(4000L)
            }
        }
        dialog.setOnDismissListener { livePollJob.cancel() }

        dialog.setContentView(outerRoot)
        bindScrollSafeBottomSheet(activity, dialog, scrollView)
        dialog.show()
    }

    // =========================================================================
    // 2. IDM Video & File Grabber + Multi-Connection Download Manager Sheet
    // =========================================================================

    fun showIdmDownloaderSheet(
        activity: FragmentActivity,
        activeWebView: WebView?,
        initialTabDownloads: Boolean = false
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val downloadEngine = DownloadEngine.getInstance(activity)
        val tabKey = WebViewMediaInspector.tabKeyFor(activeWebView)
        val dialog = BottomSheetDialog(activity)

        val surfaceBg = Color.parseColor("#161B22")
        val cardBg = Color.parseColor("#21262D")
        val borderCol = Color.parseColor("#30363D")
        val accentBlue = Color.parseColor("#2F81F7")
        val accentGreen = Color.parseColor("#2EA043")
        val textPrimary = Color.parseColor("#F0F6FC")
        val textSecondary = Color.parseColor("#8B949E")

        var showingDownloadsTab = initialTabDownloads

        val outerRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundedBg(surfaceBg, 20, activity, borderCol)
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 16))
        }

        val handle = View(activity).apply {
            background = roundedBg(borderCol, 4, activity)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(activity, 10)
            }
        }
        outerRoot.addView(handle)

        // Header Row
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(activity, 10))
        }
        val titleTv = TextView(activity).apply {
            text = "⬇ دانلودگر هوشمند ویدیو و فایل (IDM)"
            setTextColor(textPrimary)
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(activity).apply {
            text = "✕"
            setTextColor(textSecondary)
            textSize = 18f
            setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 6), dp(activity, 4))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleTv)
        headerRow.addView(closeBtn)
        outerRoot.addView(headerRow)

        // Mode Switcher Row + Rescan Button (Pinned above NestedScrollView so always accessible!)
        val tabBarRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 10) }
        }
        val detectedTabBtn = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 10), dp(activity, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 5)
            }
        }
        val downloadsTabBtn = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 10), dp(activity, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 5)
            }
        }
        tabBarRow.addView(detectedTabBtn)
        tabBarRow.addView(downloadsTabBtn)
        outerRoot.addView(tabBarRow)

        // Dedicated Rescan Action Bar
        val rescanRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 10) }
        }
        val rescanBtn = TextView(activity).apply {
            text = "🔄 اسکن دوباره صفحه (یافتن ویدیوهای جدید)"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(accentBlue, 10, activity)
            setPadding(dp(activity, 12), dp(activity, 9), dp(activity, 12), dp(activity, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rescanRow.addView(rescanBtn)
        outerRoot.addView(rescanRow)

        val scanStatusTv = TextView(activity).apply {
            setTextColor(textSecondary)
            textSize = 11.5f
            visibility = View.GONE
            setPadding(0, 0, 0, dp(activity, 8))
        }
        outerRoot.addView(scanStatusTv)

        // Scroll-safe NestedScrollView list container
        val scrollView = NestedScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 2), 0, dp(activity, 28))
        }
        scrollView.addView(listContainer)
        outerRoot.addView(scrollView)

        fun renderSheetContent() {
            val detectedItems = if (activeWebView != null) {
                VideoStreamSniffer.getMediaForTab(tabKey)
            } else {
                emptyList()
            }
            val downloads = downloadEngine.downloadsFlow.value

            detectedTabBtn.text = "🎬 لینک‌های صفحه (${detectedItems.size})"
            downloadsTabBtn.text = "📁 دانلودها (${downloads.size})"

            detectedTabBtn.background = roundedBg(
                if (!showingDownloadsTab) accentBlue else cardBg,
                10,
                activity,
                borderCol
            )
            detectedTabBtn.setTextColor(if (!showingDownloadsTab) Color.WHITE else textSecondary)

            downloadsTabBtn.background = roundedBg(
                if (showingDownloadsTab) accentBlue else cardBg,
                10,
                activity,
                borderCol
            )
            downloadsTabBtn.setTextColor(if (showingDownloadsTab) Color.WHITE else textSecondary)

            rescanRow.visibility = if (!showingDownloadsTab && activeWebView != null) View.VISIBLE else View.GONE

            listContainer.removeAllViews()

            if (!showingDownloadsTab) {
                if (detectedItems.isEmpty()) {
                    listContainer.addView(TextView(activity).apply {
                        text = "هنوز ویدیو یا استریمی در این صفحه ثبت نشده است.\nاگر ویدیو جدیدی در صفحه باز یا پخش کرده‌اید، دکمه «🔄 اسکن دوباره صفحه» در بالا را بزنید."
                        setTextColor(textSecondary)
                        textSize = 13f
                        setPadding(dp(activity, 8), dp(activity, 18), dp(activity, 8), dp(activity, 18))
                    })
                } else {
                    for (media in detectedItems) {
                        listContainer.addView(buildDetectedMediaCard(activity, media, cardBg, borderCol, accentGreen, textPrimary, textSecondary) {
                            downloadEngine.enqueueDownload(
                                url = media.url,
                                mimeType = media.mimeType,
                                refererUrl = media.pageUrl,
                                suggestedFileNameOverride = media.suggestedFileName(),
                                secondaryAudioUrl = media.audioUrl
                            )
                            Toast.makeText(activity, "⬇ دانلود آغاز شد: ${media.suggestedFileName()}", Toast.LENGTH_SHORT).show()
                            showingDownloadsTab = true
                            renderSheetContent()
                        })
                    }
                }
            } else {
                if (downloads.isNotEmpty()) {
                    val clearFinishedBtn = TextView(activity).apply {
                        text = "🧹 پاک کردن لیست تکمیل‌شده‌ها"
                        setTextColor(textSecondary)
                        textSize = 12f
                        gravity = Gravity.END
                        setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 6), dp(activity, 8))
                        setOnClickListener {
                            downloadEngine.clearFinishedDownloads()
                            renderSheetContent()
                        }
                    }
                    listContainer.addView(clearFinishedBtn)
                }
                if (downloads.isEmpty()) {
                    listContainer.addView(TextView(activity).apply {
                        text = "لیست دانلودها خالی است."
                        setTextColor(textSecondary)
                        textSize = 13f
                        setPadding(dp(activity, 8), dp(activity, 18), dp(activity, 8), dp(activity, 18))
                    })
                } else {
                    for (rec in downloads) {
                        listContainer.addView(
                            buildDownloadRecordCard(
                                activity = activity,
                                rec = rec,
                                engine = downloadEngine,
                                cardBg = cardBg,
                                borderCol = borderCol,
                                accentGreen = accentGreen,
                                accentBlue = accentBlue,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                onRefresh = ::renderSheetContent
                            )
                        )
                    }
                }
            }
        }

        detectedTabBtn.setOnClickListener {
            showingDownloadsTab = false
            renderSheetContent()
        }
        downloadsTabBtn.setOnClickListener {
            showingDownloadsTab = true
            renderSheetContent()
        }

        fun triggerRescan(showToast: Boolean) {
            if (activeWebView == null) return
            rescanBtn.isEnabled = false
            rescanBtn.text = "⏳ در حال اسکن عمیق صفحه..."
            scanStatusTv.visibility = View.VISIBLE
            scanStatusTv.text = "در حال بررسی تگ‌های ویدیو، شبکه (Performance/XHR/Fetch) و قاب‌های صفحه..."
            WebViewMediaInspector.rescanWebViewNow(activeWebView) { count ->
                rescanBtn.isEnabled = true
                rescanBtn.text = "🔄 اسکن دوباره صفحه (یافتن ویدیوهای جدید)"
                scanStatusTv.text = "✅ اسکن کامل شد — $count لینک قابل دانلود در این صفحه یافت شد"
                renderSheetContent()
                if (showToast) {
                    Toast.makeText(
                        activity,
                        "✅ اسکن انجام شد: $count مورد یافت شد",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        rescanBtn.setOnClickListener { triggerRescan(showToast = true) }

        // Automatically run a fresh rescan whenever user opens the IDM sheet on an active page!
        if (!initialTabDownloads && activeWebView != null) {
            triggerRescan(showToast = false)
        } else {
            renderSheetContent()
        }

        val liveUiJob = activity.lifecycleScope.launch {
            launch {
                VideoStreamSniffer.updatesFlow.collect {
                    if (!showingDownloadsTab) renderSheetContent()
                }
            }
            launch {
                downloadEngine.downloadsFlow.collect {
                    if (showingDownloadsTab) renderSheetContent()
                }
            }
        }
        dialog.setOnDismissListener { liveUiJob.cancel() }

        dialog.setContentView(outerRoot)
        bindScrollSafeBottomSheet(activity, dialog, scrollView)
        dialog.show()
    }

    private fun buildDetectedMediaCard(
        activity: FragmentActivity,
        media: DetectedMediaItem,
        cardBg: Int,
        borderCol: Int,
        accentGreen: Int,
        textPrimary: Int,
        textSecondary: Int,
        onDownloadClick: () -> Unit
    ): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cardBg, 12, activity, borderCol)
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 9) }
        }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(activity).apply {
            text = media.title
            setTextColor(textPrimary)
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(titleTv)

        val badgeLabel = media.qualityLabel.ifBlank { media.formatBadge }
        val badgeColor = when {
            media.height >= 1080 -> Color.parseColor("#1F6FEB")
            media.height >= 720 -> Color.parseColor("#238636")
            media.kind == org.greatfire.browser.media.SniffedMediaKind.AUDIO -> Color.parseColor("#8957E5")
            else -> Color.parseColor("#388BFD")
        }
        val qualityPillTv = TextView(activity).apply {
            text = badgeLabel
            setTextColor(Color.WHITE)
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(badgeColor, 7, activity)
            setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(activity, 6) }
        }
        topRow.addView(qualityPillTv)

        val fileNameStr = media.suggestedFileName()
        val sizeStr = if (media.contentLengthBytes > 0L) {
            val formatted = Formatter.formatFileSize(activity, media.contentLengthBytes)
            if (media.isEstimatedSize) "~$formatted" else formatted
        } else {
            "در حال دریافت..."
        }

        // Line 1: File Size + Duration + Audio Status
        val primaryMetaParts = ArrayList<String>(4)
        primaryMetaParts.add("📦 حجم: $sizeStr")
        if (media.formattedDuration().isNotBlank()) {
            primaryMetaParts.add("⏱ زمان: ${media.formattedDuration()}")
        }
        primaryMetaParts.add(media.audioStatusLabel())

        val primaryMetaTv = TextView(activity).apply {
            text = primaryMetaParts.joinToString("  •  ")
            setTextColor(Color.parseColor("#C9D1D9"))
            textSize = 11.5f
            setPadding(0, dp(activity, 5), 0, dp(activity, 2))
        }

        // Line 2: Exact Filename + Container/Codecs + Resolution + Bitrate + Segments + Source Host
        val detailLines = StringBuilder()
        detailLines.append("📄 نام فایل: ").append(fileNameStr)
        detailLines.append("\n🎞 فرمت: ").append(media.containerAndCodecsLabel())
        if (media.resolutionDetail().isNotBlank()) {
            detailLines.append(" • ابعاد: ").append(media.resolutionDetail())
        }
        if (media.formattedBitrate().isNotBlank()) {
            detailLines.append(" • بیت‌ریت: ").append(media.formattedBitrate())
        }
        if (media.segmentCount > 0) {
            detailLines.append(" • ${media.segmentCount} قطعه")
        }
        if (media.sourceHost().isNotBlank()) {
            detailLines.append(" • 🌐 ").append(media.sourceHost())
        }

        val detailMetaTv = TextView(activity).apply {
            text = detailLines.toString()
            setTextColor(textSecondary)
            textSize = 11f
            setLineSpacing(dp(activity, 2).toFloat(), 1.0f)
            setPadding(0, 0, 0, dp(activity, 8))
        }

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val copyBtn = TextView(activity).apply {
            text = "کپی لینک"
            setTextColor(textPrimary)
            textSize = 11.5f
            background = roundedBg(Color.parseColor("#30363D"), 8, activity)
            setPadding(dp(activity, 10), dp(activity, 7), dp(activity, 10), dp(activity, 7))
            setOnClickListener {
                val cm = ContextCompat.getSystemService(activity, ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("media_url", media.url))
                Toast.makeText(activity, "لینک کپی شد", Toast.LENGTH_SHORT).show()
            }
        }
        val spacer = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val dlBtn = TextView(activity).apply {
            text = when {
                media.kind == org.greatfire.browser.media.SniffedMediaKind.AUDIO -> "⬇ دانلود فایل صوتی"
                media.hasPairedAudio -> "⬇ دانلود با صدا (MP4)"
                media.isHls -> "⬇ دانلود با صدا (MP4)"
                else -> "⬇ دانلود پرسرعت"
            }
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(accentGreen, 8, activity)
            setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 12), dp(activity, 7))
            setOnClickListener { onDownloadClick() }
        }
        btnRow.addView(copyBtn)
        btnRow.addView(spacer)
        btnRow.addView(dlBtn)

        card.addView(topRow)
        card.addView(primaryMetaTv)
        card.addView(detailMetaTv)
        card.addView(btnRow)
        return card
    }

    private fun buildDownloadRecordCard(
        activity: FragmentActivity,
        rec: DownloadRecord,
        engine: DownloadEngine,
        cardBg: Int,
        borderCol: Int,
        accentGreen: Int,
        accentBlue: Int,
        textPrimary: Int,
        textSecondary: Int,
        onRefresh: () -> Unit
    ): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cardBg, 12, activity, borderCol)
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 8) }
        }

        val nameTv = TextView(activity).apply {
            text = rec.fileName
            setTextColor(textPrimary)
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(nameTv)

        val readStr = Formatter.formatFileSize(activity, rec.bytesRead.coerceAtLeast(0L))
        val totalStr = if (rec.totalBytes > 0) Formatter.formatFileSize(activity, rec.totalBytes) else "—"
        val speedStr = if (rec.speedBps > 0 && rec.state == DownloadState.DOWNLOADING) {
            " • ${Formatter.formatFileSize(activity, rec.speedBps)}/s"
        } else ""
        val statusText = when (rec.state) {
            DownloadState.DOWNLOADING -> "در حال دانلود: $readStr از $totalStr$speedStr"
            DownloadState.PAUSED -> "متوقف شده: $readStr از $totalStr"
            DownloadState.COMPLETED -> "✅ تکمیل شد ($readStr) — ذخیره در پوشه Downloads"
            DownloadState.FAILED -> "❌ خطا: ${rec.errorMsg.ifBlank { "ارتباط قطع شد" }}"
            DownloadState.CANCELED -> "لغو شده"
        }
        val statusTv = TextView(activity).apply {
            text = statusText
            setTextColor(if (rec.state == DownloadState.COMPLETED) Color.parseColor("#3FB950") else textSecondary)
            textSize = 11.5f
            setPadding(0, dp(activity, 4), 0, dp(activity, 6))
        }
        card.addView(statusTv)

        if (rec.state == DownloadState.DOWNLOADING || rec.state == DownloadState.PAUSED) {
            val pb = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = rec.totalBytes <= 0L && rec.state == DownloadState.DOWNLOADING
                max = 100
                progress = if (rec.totalBytes > 0L) {
                    ((rec.bytesRead * 100L) / rec.totalBytes).toInt().coerceIn(0, 100)
                } else 0
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(activity, 6)
                ).apply { bottomMargin = dp(activity, 8) }
            }
            card.addView(pb)
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        when (rec.state) {
            DownloadState.DOWNLOADING -> {
                actions.addView(smallActionBtn(activity, "توقف موقت", Color.parseColor("#30363D"), textPrimary) {
                    engine.pauseDownload(rec.id)
                    onRefresh()
                })
                actions.addView(smallActionBtn(activity, "لغو", Color.parseColor("#3D1E20"), Color.parseColor("#F85149")) {
                    engine.cancelDownload(rec.id)
                    onRefresh()
                })
            }

            DownloadState.PAUSED, DownloadState.FAILED -> {
                actions.addView(smallActionBtn(activity, "ادامه دانلود", accentBlue, Color.WHITE) {
                    engine.resumeOrRetryDownload(rec.id)
                    onRefresh()
                })
                actions.addView(smallActionBtn(activity, "حذف", Color.parseColor("#3D1E20"), Color.parseColor("#F85149")) {
                    engine.cancelDownload(rec.id)
                    onRefresh()
                })
            }

            DownloadState.COMPLETED -> {
                actions.addView(smallActionBtn(activity, "پخش / باز کردن", accentGreen, Color.WHITE) {
                    openSafeCompletedFile(activity, rec)
                })
                actions.addView(smallActionBtn(activity, "حذف از لیست", Color.parseColor("#30363D"), textSecondary) {
                    engine.deleteDownload(rec.id, deleteDiskFile = false)
                    onRefresh()
                })
            }

            DownloadState.CANCELED -> {
                actions.addView(smallActionBtn(activity, "حذف", Color.parseColor("#30363D"), textSecondary) {
                    engine.deleteDownload(rec.id, deleteDiskFile = false)
                    onRefresh()
                })
            }
        }
        card.addView(actions)
        return card
    }

    private fun smallActionBtn(
        activity: FragmentActivity,
        label: String,
        bgColor: Int,
        txtColor: Int,
        onClick: () -> Unit
    ): TextView = TextView(activity).apply {
        text = label
        setTextColor(txtColor)
        textSize = 11.5f
        background = roundedBg(bgColor, 8, activity)
        setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(activity, 6) }
        setOnClickListener { onClick() }
    }

    /**
     * Play-Protect-Safe file opener: never launches APK package-installer intents!
     */
    private fun openSafeCompletedFile(activity: FragmentActivity, rec: DownloadRecord) {
        val lowerName = rec.fileName.lowercase(Locale.US)
        if (lowerName.endsWith(".apk") || rec.mimeType.contains("package-archive", true)) {
            Toast.makeText(
                activity,
                "فایل در پوشه Downloads ذخیره شده است؛ از مدیریت فایل گوشی باز کنید.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val f = File(rec.stagingPath.ifBlank { rec.localUriOrPath })
        if (!f.exists()) {
            Toast.makeText(activity, "فایل در پوشه Downloads گوشی موجود است", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val contentUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                f
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, rec.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(Intent.createChooser(viewIntent, rec.fileName))
        }.onFailure {
            Toast.makeText(activity, "برنامه‌ای برای باز کردن این فرمت یافت نشد (فایل در پوشه Downloads ذخیره است)", Toast.LENGTH_LONG).show()
        }
    }
}
