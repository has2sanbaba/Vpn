package org.greatfire.browser.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mgr = tabManager ?: run {
            dismissAllowingStateLoss()
            return
        }

        val adapter = TabsAdapter(
            mgr = mgr,
            onSelect = { idx ->
                mgr.selectTab(idx)
                mgr.activeTab?.let { mgr.ensureTabLoaded(it) }
                dismissAllowingStateLoss()
            },
            onClose = { idx ->
                val closed = mgr.closeTab(idx)
                binding.rvTabs.adapter?.notifyDataSetChanged()
                if (closed != null) {
                    Snackbar.make(binding.root, "تب بسته شد", Snackbar.LENGTH_SHORT)
                        .setAction("واگرد") {
                            mainActivity?.openUrlInNewTab(closed.second, incognito = false, selectImmediately = true)
                            binding.rvTabs.adapter?.notifyDataSetChanged()
                        }
                        .show()
                }
            }
        )

        binding.rvTabs.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvTabs.adapter = adapter

        binding.btnNewTab.setOnClickListener {
            mgr.createTab(BrowserTabManager.HOME_URL, incognito = false)
            dismissAllowingStateLoss()
        }

        binding.btnNewIncognitoTab.setOnClickListener {
            mgr.createTab(BrowserTabManager.HOME_URL, incognito = true)
            dismissAllowingStateLoss()
        }

        binding.btnCloseAllTabs.setOnClickListener {
            mgr.closeAllTabs(incognitoOnly = false)
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class TabsAdapter(
        private val mgr: BrowserTabManager,
        private val onSelect: (Int) -> Unit,
        private val onClose: (Int) -> Unit
    ) : RecyclerView.Adapter<TabsAdapter.VH>() {

        inner class VH(val b: ItemBrowserTabBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemBrowserTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = mgr.tabs.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tab: BrowserTab = mgr.tabs[position]
            val isActive = position == mgr.activeIndex

            holder.b.tabItemRoot.setBackgroundResource(
                if (isActive) R.drawable.bg_active_tab_card else R.drawable.bg_card_dark
            )

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
            holder.b.tvTabUrl.text = if (tab.currentUrl == BrowserTabManager.HOME_URL) {
                if (tab.incognito) "تب ناشناس جدید" else "صفحه تب جدید"
            } else {
                host.ifBlank { tab.currentUrl }
            }

            holder.b.tabItemRoot.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSelect(pos)
            }
            holder.b.btnCloseTab.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClose(pos)
            }
        }
    }
}
