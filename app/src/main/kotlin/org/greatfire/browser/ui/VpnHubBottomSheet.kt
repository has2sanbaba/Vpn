package org.greatfire.browser.ui

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import org.greatfire.browser.databinding.DialogAddProfileBinding
import org.greatfire.browser.databinding.ItemProxyProfileBinding
import org.greatfire.browser.databinding.SheetVpnHubBinding
import org.greatfire.browser.vpn.ProxyProfile
import org.greatfire.browser.vpn.ProxyProfileParser
import org.greatfire.browser.vpn.TunnelManager

class VpnHubBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetVpnHubBinding? = null
    private val binding get() = _binding!!

    private lateinit var tunnelManager: TunnelManager
    private lateinit var adapter: ProfileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetVpnHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tunnelManager = TunnelManager.getInstance(requireContext())

        val repo = tunnelManager.repository
        binding.swKillSwitch.isChecked = repo.killSwitchEnabled
        binding.swAdBlock.isChecked = repo.adBlockEnabled
        binding.swWebRtcShield.isChecked = repo.webRtcShieldEnabled

        binding.swKillSwitch.setOnCheckedChangeListener { _, checked ->
            tunnelManager.setKillSwitch(checked)
        }
        binding.swAdBlock.setOnCheckedChangeListener { _, checked ->
            repo.adBlockEnabled = checked
        }
        binding.swWebRtcShield.setOnCheckedChangeListener { _, checked ->
            repo.webRtcShieldEnabled = checked
        }

        adapter = ProfileAdapter(
            onSelect = { profile ->
                tunnelManager.connect(profile.id)
                refreshProfiles()
                Toast.makeText(requireContext(), "اتصال به ${profile.name}", Toast.LENGTH_SHORT).show()
            },
            onDelete = { profile ->
                repo.deleteCustomProfile(profile.id)
                refreshProfiles()
            }
        )

        binding.rvProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProfiles.adapter = adapter
        refreshProfiles()

        binding.btnHubImportClipboard.setOnClickListener {
            importFromClipboard()
        }

        binding.btnHubAddCustom.setOnClickListener {
            showAddCustomDialog()
        }

        binding.btnHubFetchFree.setOnClickListener {
            binding.btnHubFetchFree.isEnabled = false
            Toast.makeText(requireContext(), "درحال دریافت سرورهای رایگان جدید از مسیر ضد DPI…", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch {
                val res = tunnelManager.fetchPublicFreeServers()
                binding.btnHubFetchFree.isEnabled = true
                res.onSuccess { added ->
                    refreshProfiles()
                    Toast.makeText(
                        requireContext(),
                        "✅ تعداد $added سرور جدید (VLESS-Reality / Trojan / Hy2) اضافه شد",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { err ->
                    Toast.makeText(
                        requireContext(),
                        "⚠ ${err.message ?: "خطا در دریافت سرورها"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnHubPingAll.setOnClickListener {
            binding.btnHubPingAll.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val res = tunnelManager.runActiveProbe()
                binding.btnHubPingAll.isEnabled = true
                refreshProfiles()
                val msg = if (res.ok) {
                    "✅ پینگ تونل فعال: ${res.latencyMs}ms"
                } else {
                    "⚠ خطای تست پینگ: ${res.error ?: "نامشخص"}"
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshProfiles() {
        val list = tunnelManager.repository.loadAllProfiles()
        val selectedId = tunnelManager.repository.selectedProfileId
        adapter.submit(list, selectedId)
    }

    private fun importFromClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()?.trim()
        if (text.isNullOrBlank()) {
            Toast.makeText(requireContext(), "کلیپ‌بورد خالی است", Toast.LENGTH_SHORT).show()
            return
        }
        handleRawImport(text, "")
    }

    private fun showAddCustomDialog() {
        val dlgBinding = DialogAddProfileBinding.inflate(layoutInflater)
        AlertDialog.Builder(requireContext())
            .setView(dlgBinding.root)
            .setPositiveButton("افزودن و اتصال") { _, _ ->
                val raw = dlgBinding.etConfigUri.text?.toString()?.trim().orEmpty()
                val name = dlgBinding.etProfileName.text?.toString()?.trim().orEmpty()
                if (raw.isNotEmpty()) {
                    handleRawImport(raw, name)
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun handleRawImport(raw: String, customName: String) {
        // Subscription URL (https://...) vs Direct Proxy Config URI(s)
        if (raw.lines().size == 1 && raw.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(requireContext(), "درحال دریافت لینک اشتراک از طریق تونل ضد فیلتر…", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch {
                val res = tunnelManager.importSubscriptionUrl(raw, customName)
                res.onSuccess { count ->
                    refreshProfiles()
                    Toast.makeText(requireContext(), "✅ تعداد $count سرور از اشتراک اضافه شد", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), "خطا در دریافت اشتراک: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        val parsed = ProxyProfileParser.parseMany(raw, customName)
        if (parsed.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "فرمت لینک شناسایی نشد (پشتیبانی: vless, trojan, tuic, vmess, hy2, ss, wg, envoy, masque, snowflake, socks5)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val added = tunnelManager.repository.addCustomProfiles(parsed)
        val first = tunnelManager.repository.loadCustomProfiles().firstOrNull()
        if (first != null) {
            tunnelManager.connect(first.id)
        }
        refreshProfiles()
        Toast.makeText(requireContext(), "✅ $added پروفایل VPN اضافه و فعال شد", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ProfileAdapter(
        private val onSelect: (ProxyProfile) -> Unit,
        private val onDelete: (ProxyProfile) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.VH>() {

        private var items: List<ProxyProfile> = emptyList()
        private var selectedId: String = ""

        fun submit(newItems: List<ProxyProfile>, currentSelectedId: String) {
            items = newItems
            selectedId = currentSelectedId
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemProxyProfileBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemProxyProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.rbSelected.isChecked = item.id == selectedId
            holder.b.tvProfileName.text = item.name
            val hostDetail = if (item.host.isNotBlank()) " • ${item.host}:${item.port}" else ""
            holder.b.tvProfileSubtitle.text = "${item.kind.badge}$hostDetail"
            holder.b.tvLatency.text = if (item.lastLatencyMs > 0) "${item.lastLatencyMs} ms" else ""

            holder.b.btnDeleteProfile.visibility = if (item.builtIn) View.GONE else View.VISIBLE
            holder.b.btnDeleteProfile.setOnClickListener { onDelete(item) }
            holder.b.itemRoot.setOnClickListener { onSelect(item) }
        }
    }
}
