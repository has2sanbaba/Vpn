package org.greatfire.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.greatfire.browser.databinding.ItemBrowserTabBinding
import org.greatfire.browser.databinding.SheetTabsBinding
import org.greatfire.browser.web.BrowserTab
import org.greatfire.browser.web.BrowserTabManager

class TabsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetTabsBinding? = null
    private val binding get() = _binding!!

    private val tabManager: BrowserTabManager?
        get() = (activity as? MainActivity)?.tabManager

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
                dismissAllowingStateLoss()
            },
            onClose = { idx ->
                mgr.closeTab(idx)
                binding.rvTabs.adapter?.notifyDataSetChanged()
            }
        )

        binding.rvTabs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTabs.adapter = adapter

        binding.btnNewTab.setOnClickListener {
            mgr.createTab(BrowserTabManager.HOME_URL, incognito = false)
            dismissAllowingStateLoss()
        }

        binding.btnNewIncognitoTab.setOnClickListener {
            mgr.createTab(BrowserTabManager.HOME_URL, incognito = true)
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
            val activeMark = if (position == mgr.activeIndex) "● " else ""
            val incog = if (tab.incognito) "🕶 " else ""
            holder.b.tvTabTitle.text = "$activeMark$incog${tab.title}"
            holder.b.tvTabUrl.text = if (tab.currentUrl == BrowserTabManager.HOME_URL) {
                "صفحه شروع امن GreatFire"
            } else {
                tab.currentUrl
            }
            holder.b.tabItemRoot.setOnClickListener { onSelect(position) }
            holder.b.btnCloseTab.setOnClickListener { onClose(position) }
        }
    }
}
