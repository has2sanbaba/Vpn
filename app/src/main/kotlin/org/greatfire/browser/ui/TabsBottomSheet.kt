package org.greatfire.browser.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import org.greatfire.browser.R
import org.greatfire.browser.data.BrowserDataStore
import org.greatfire.browser.databinding.ItemBrowserTabBinding
import org.greatfire.browser.databinding.SheetTabsBinding
import org.greatfire.browser.web.BrowserTab
import org.greatfire.browser.web.BrowserTabManager

class TabsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetTabsBinding? = null
    private val binding get() = _binding!!

    private var incognitoOnlyFilter: Boolean = false

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    private val tabManager: BrowserTabManager?
        get() = mainActivity?.tabManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetTabsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mgr = tabManager ?: run {
            dismissAllowingStateLoss()
            return
        }

        mgr.captureActiveTabPreview()

        lateinit var adapter: TabsAdapter
        fun currentVisibleTabs(): List<IndexedValue<BrowserTab>> {
            return mgr.tabs.withIndex().filter { (_, t) ->
                if (incognitoOnlyFilter) t.incognito else true
            }
        }

        fun updateFilterLabel() {
            val incCount = mgr.tabs.count { it.incognito }
            binding.btnFilterIncognito.text = if (incognitoOnlyFilter) {
                "نمایش همه (${mgr.tabs.size})"
            } else if (incCount > 0) {
                "فقط ناشناس ($incCount)"
            } else {
                "تب‌ها (${mgr.tabs.size})"
            }
        }

        adapter = TabsAdapter(
            itemsProvider = { currentVisibleTabs() },
            activeIndexProvider = { mgr.activeIndex },
            onSelect = { realIdx ->
                mgr.selectTab(realIdx)
                mgr.activeTab?.let { mainActivity?.ensureTabRestoredAndLoaded(it) }
                dismissAllowingStateLoss()
            },
            onClose = { realIdx ->
                val closed = mgr.closeTab(realIdx)
                updateFilterLabel()
                adapter.notifyDataSetChanged()
                if (closed != null) {
                    val snackAnchor = mainActivity?.rootViewForSnackbar() ?: binding.root
                    Snackbar.make(snackAnchor, "تب بسته شد", Snackbar.LENGTH_SHORT)
                        .setAction("واگرد") {
                            mainActivity?.openUrlInNewTab(closed.second, incognito = false, selectImmediately = true)
                            _binding?.rvTabs?.adapter?.notifyDataSetChanged()
                        }
                        .show()
                }
            },
            onLongPressCard = { anchor, tab, realIdx ->
                showChromeTabContextMenu(anchor, mgr, tab, realIdx) {
                    updateFilterLabel()
                    adapter.notifyDataSetChanged()
                }
            }
        )

        binding.rvTabs.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvTabs.adapter = adapter
        updateFilterLabel()

        binding.btnNewTab.setOnClickListener {
            mgr.createTab(BrowserTabManager.HOME_URL, incognito = false)
            dismissAllowingStateLoss()
        }

        binding.btnNewIncognitoTab.setOnClickListener {
            mgr.createTab(BrowserTabManager.HOME_URL, incognito = true)
            dismissAllowingStateLoss()
        }

        binding.btnFilterIncognito.setOnClickListener {
            incognitoOnlyFilter = !incognitoOnlyFilter
            updateFilterLabel()
            adapter.notifyDataSetChanged()
        }

        binding.btnCloseAllTabs.setOnClickListener {
            mgr.closeAllTabs(incognitoOnly = incognitoOnlyFilter, keepPinned = true)
            dismissAllowingStateLoss()
        }
    }

    private fun showChromeTabContextMenu(
        anchor: View,
        mgr: BrowserTabManager,
        tab: BrowserTab,
        realIdx: Int,
        onChanged: () -> Unit
    ) {
        val act = mainActivity ?: return
        val popup = PopupMenu(requireContext(), anchor)
        val isHome = tab.currentUrl == BrowserTabManager.HOME_URL
        if (!isHome) {
            popup.menu.add(0, 1, 1, "🔗 اشتراک‌گذاری تب")
            popup.menu.add(0, 2, 2, if (act.dataStore.isBookmarked(tab.currentUrl)) "★ حذف نشانک" else "☆ افزودن به نشانک‌ها")
        }
        popup.menu.add(0, 3, 3, if (tab.pinned) "📌 برداشتن پین تب" else "📌 پین کردن تب")
        popup.menu.add(0, 4, 4, "📑 تکثیر تب (Duplicate)")
        popup.menu.add(0, 5, 5, "✕ بستن تب")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> ChromeFeaturesDialogs.sharePlainText(act, "${tab.title}\n${tab.currentUrl}", "اشتراک‌گذاری تب")
                2 -> {
                    val added = act.dataStore.toggleBookmark(tab.title, tab.currentUrl)
                    Toast.makeText(act, if (added) "★ به نشانک‌ها اضافه شد" else "از نشانک‌ها حذف شد", Toast.LENGTH_SHORT).show()
                }
                3 -> {
                    val pinned = mgr.togglePinTab(tab)
                    Toast.makeText(act, if (pinned) "📌 تب پین شد (در بستن همه بسته نمی‌شود)" else "پین تب برداشته شد", Toast.LENGTH_SHORT).show()
                    onChanged()
                }
                4 -> {
                    mgr.duplicateTab(tab)
                    onChanged()
                }
                5 -> {
                    mgr.closeTab(realIdx)
                    onChanged()
                }
            }
            true
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class TabsAdapter(
        private val itemsProvider: () -> List<IndexedValue<BrowserTab>>,
        private val activeIndexProvider: () -> Int,
        private val onSelect: (Int) -> Unit,
        private val onClose: (Int) -> Unit,
        private val onLongPressCard: (View, BrowserTab, Int) -> Unit
    ) : RecyclerView.Adapter<TabsAdapter.VH>() {

        inner class VH(val b: ItemBrowserTabBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemBrowserTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = itemsProvider().size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val indexed = itemsProvider().getOrNull(position) ?: return
            val realIdx = indexed.index
            val tab: BrowserTab = indexed.value
            val isActive = realIdx == activeIndexProvider()

            holder.b.tabItemRoot.setBackgroundResource(
                if (isActive) R.drawable.bg_active_tab_card else R.drawable.bg_card_dark
            )
            holder.b.ivTabPinned.visibility = if (tab.pinned) View.VISIBLE else View.GONE

            val host = runCatching { Uri.parse(tab.currentUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
            val badgeChar = when {
                tab.incognito -> "🕶"
                tab.currentUrl == BrowserTabManager.HOME_URL -> "G"
                host.isNotEmpty() -> host.first().uppercaseChar().toString()
                else -> "W"
            }
            val badgeColor = when {
                tab.incognito -> Color.parseColor("#35363A")
                tab.currentUrl == BrowserTabManager.HOME_URL -> Color.parseColor("#4285F4")
                else -> Color.parseColor(BrowserDataStore.pickBadgeColor(host))
            }
            holder.b.tvTabBadge.text = badgeChar
            holder.b.tvTabBadge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(badgeColor)
            }

            holder.b.tvTabTitle.text = tab.title

            val bmp = tab.previewBitmap
            if (bmp != null && !bmp.isRecycled && tab.currentUrl != BrowserTabManager.HOME_URL) {
                holder.b.ivTabPreview.setImageBitmap(bmp)
                holder.b.ivTabPreview.visibility = View.VISIBLE
                holder.b.tvTabUrl.visibility = View.GONE
            } else {
                holder.b.ivTabPreview.setImageDrawable(null)
                holder.b.ivTabPreview.visibility = View.GONE
                holder.b.tvTabUrl.visibility = View.VISIBLE
                if (tab.currentUrl == BrowserTabManager.HOME_URL) {
                    holder.b.tvTabUrl.textDirection = View.TEXT_DIRECTION_LOCALE
                    holder.b.tvTabUrl.text = if (tab.incognito) "🕶\nتب ناشناس جدید" else "Google\nصفحه تب جدید"
                } else {
                    holder.b.tvTabUrl.textDirection = View.TEXT_DIRECTION_LTR
                    holder.b.tvTabUrl.text = host.ifBlank { tab.currentUrl }
                }
            }

            holder.b.tabItemRoot.setOnClickListener {
                onSelect(realIdx)
            }
            holder.b.tabItemRoot.setOnLongClickListener { v ->
                onLongPressCard(v, tab, realIdx)
                true
            }
            holder.b.btnCloseTab.setOnClickListener {
                onClose(realIdx)
            }
        }
    }
}
