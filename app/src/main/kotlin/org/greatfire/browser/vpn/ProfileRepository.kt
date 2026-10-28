package org.greatfire.browser.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProfileRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun builtInProfiles(): List<ProxyProfile> = listOf(
        ProxyProfile(
            id = ID_SMART_AUTO,
            name = "⚡ اتصال هوشمند خودکار (تضمینی • چندموتوره)",
            kind = TransportKind.SMART_AUTO,
            builtIn = true,
            genevaStrategy = 1,
            fragSize = 16,
            fragDelayMs = 4
        ),
        ProxyProfile(
            id = ID_WARP_CLEAN,
            name = "🚀 کلودفلر WARP+ (اسکن خودکار آی‌پی تمیز)",
            kind = TransportKind.WARP_CLEAN_SCAN,
            builtIn = true
        ),
        ProxyProfile(
            id = ID_WARP_GOOL,
            name = "🔥 وارپ در وارپ (Gool • عبور از فیلترینگ شدید)",
            kind = TransportKind.WARP_IN_WARP_GOOL,
            builtIn = true
        ),
        ProxyProfile(
            id = ID_WARP_PSIPHON,
            name = "🌍 سایفون + WARP (آی‌پی آلمان • بدون سرور)",
            kind = TransportKind.WARP_PSIPHON_CFON,
            builtIn = true,
            extra = mapOf("country" to "DE")
        ),
        ProxyProfile(
            id = ID_TOR_MEEK,
            name = "🌉 پل Tor + GreatFire Meek (CDN77 HTTPS • ضد قطع UDP)",
            kind = TransportKind.TOR_MEEK_BRIDGE,
            builtIn = true
        ),
        ProxyProfile(
            id = ID_TOR_SNOWFLAKE,
            name = "❄ پل Tor + GreatFire Snowflake (CDN77 WebRTC)",
            kind = TransportKind.SNOWFLAKE,
            builtIn = true,
            frontHost = "app.datapacket.com,www.datapacket.com"
        ),
        ProxyProfile(
            id = ID_TOR_OBFS4,
            name = "🛡 پل Tor + GreatFire Obfs4 (Lyrebird)",
            kind = TransportKind.TOR_OBFS4_BRIDGE,
            builtIn = true
        ),
        ProxyProfile(
            id = ID_CRONET_GENEVA,
            name = "🌐 موتور Envoy Cronet + استراتژی Geneva",
            kind = TransportKind.CRONET_GENEVA,
            builtIn = true,
            genevaStrategy = 2,
            fragSize = 16,
            fragDelayMs = 4
        ),
        ProxyProfile(
            id = ID_FREEBROWSER_STRICT,
            name = "🔧 موتور FreeBrowser (ضد SNI/DNS مستقیم)",
            kind = TransportKind.FREEBROWSER_ANTI_DPI,
            builtIn = true,
            fragSize = 12,
            fragDelayMs = 5
        )
    )

    fun loadAllProfiles(): List<ProxyProfile> {
        val latencyOverrides = loadLatencyMap()
        val defaults = builtInProfiles().map { p ->
            latencyOverrides[p.id]?.let { p.copy(lastLatencyMs = it) } ?: p
        }
        val custom = loadCustomProfiles().map { p ->
            latencyOverrides[p.id]?.let { p.copy(lastLatencyMs = it) } ?: p
        }
        return defaults + custom
    }

    fun loadCustomProfiles(): List<ProxyProfile> {
        val raw = prefs.getString(KEY_CUSTOM_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    ProxyProfile.fromJson(arr.getJSONObject(i))?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addCustomProfiles(newItems: List<ProxyProfile>, maxTotalCustom: Int = 120): Int {
        if (newItems.isEmpty()) return 0
        val current = loadCustomProfiles().toMutableList()
        val existingUris = current.map { it.rawUri }.filter { it.isNotBlank() }.toMutableSet()
        var added = 0
        for (item in newItems) {
            if (item.rawUri.isNotBlank() && !existingUris.add(item.rawUri)) continue
            current.add(0, item.copy(builtIn = false))
            added++
        }
        val trimmed = if (current.size > maxTotalCustom) current.take(maxTotalCustom) else current
        saveCustomProfiles(trimmed)
        return added
    }

    fun deleteCustomProfile(profileId: String) {
        val filtered = loadCustomProfiles().filterNot { it.id == profileId }
        saveCustomProfiles(filtered)
        if (selectedProfileId == profileId) {
            selectedProfileId = ID_SMART_AUTO
        }
    }

    fun updateLatency(profileId: String, latencyMs: Long) {
        val map = loadLatencyMap().toMutableMap()
        map[profileId] = latencyMs
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_LATENCIES, json.toString()).apply()
    }

    private fun loadLatencyMap(): Map<String, Long> {
        val raw = prefs.getString(KEY_LATENCIES, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, Long>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optLong(k, -1L)
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun saveCustomProfiles(list: List<ProxyProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_CUSTOM_PROFILES, arr.toString()).apply()
    }

    var selectedProfileId: String
        get() = prefs.getString(KEY_SELECTED_PROFILE, ID_SMART_AUTO) ?: ID_SMART_AUTO
        set(value) = prefs.edit().putString(KEY_SELECTED_PROFILE, value).apply()

    var killSwitchEnabled: Boolean
        get() = prefs.getBoolean(KEY_KILL_SWITCH, true)
        set(value) = prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply()

    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_BLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_AD_BLOCK, value).apply()

    var webRtcShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEBRTC_SHIELD, true)
        set(value) = prefs.edit().putBoolean(KEY_WEBRTC_SHIELD, value).apply()

    companion object {
        private const val PREFS_NAME = "gf_vpn_browser_prefs"
        private const val KEY_CUSTOM_PROFILES = "custom_profiles_v1"
        private const val KEY_SELECTED_PROFILE = "selected_profile_id"
        private const val KEY_KILL_SWITCH = "kill_switch_enabled"
        private const val KEY_AD_BLOCK = "ad_block_enabled"
        private const val KEY_WEBRTC_SHIELD = "webrtc_shield_enabled"
        private const val KEY_LATENCIES = "profile_latencies"

        const val ID_SMART_AUTO = "builtin-smart-auto"
        const val ID_WARP_CLEAN = "builtin-warp-clean"
        const val ID_WARP_GOOL = "builtin-warp-gool"
        const val ID_WARP_PSIPHON = "builtin-warp-psiphon"
        const val ID_TOR_MEEK = "builtin-tor-meek"
        const val ID_TOR_SNOWFLAKE = "builtin-tor-snowflake"
        const val ID_TOR_OBFS4 = "builtin-tor-obfs4"
        const val ID_FREEBROWSER_STRICT = "builtin-freebrowser-strict"
        const val ID_CRONET_GENEVA = "builtin-cronet-geneva"
    }
}
