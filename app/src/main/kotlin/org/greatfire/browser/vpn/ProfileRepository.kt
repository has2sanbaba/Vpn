package org.greatfire.browser.vpn

import android.content.Context
import org.json.JSONArray

class ProfileRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun builtInProfiles(): List<ProxyProfile> = listOf(
        ProxyProfile(
            id = ID_SMART_AUTO,
            name = "⚡ اتصال هوشمند خودکار (GreatFire Smart Auto)",
            kind = TransportKind.SMART_AUTO,
            builtIn = true,
            genevaStrategy = 1,
            fragSize = 24,
            fragDelayMs = 4
        ),
        ProxyProfile(
            id = ID_FREEBROWSER_STRICT,
            name = "🛡 موتور FreeBrowser (ضد DPI شدید + DoH)",
            kind = TransportKind.FREEBROWSER_ANTI_DPI,
            builtIn = true,
            fragSize = 12,
            fragDelayMs = 6
        ),
        ProxyProfile(
            id = ID_CRONET_GENEVA,
            name = "🌐 موتور Envoy Cronet + استراتژی Geneva",
            kind = TransportKind.CRONET_GENEVA,
            builtIn = true,
            genevaStrategy = 2,
            fragSize = 20,
            fragDelayMs = 4
        ),
        ProxyProfile(
            id = ID_TOR_SNOWFLAKE,
            name = "❄ پل Tor Snowflake (WebRTC بدون سرور)",
            kind = TransportKind.SNOWFLAKE,
            builtIn = true,
            frontHost = "www.cdn77.com,www.phpmyadmin.net"
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

    fun addCustomProfiles(newItems: List<ProxyProfile>): Int {
        if (newItems.isEmpty()) return 0
        val current = loadCustomProfiles().toMutableList()
        val existingUris = current.map { it.rawUri }.filter { it.isNotBlank() }.toMutableSet()
        var added = 0
        for (item in newItems) {
            if (item.rawUri.isNotBlank() && !existingUris.add(item.rawUri)) continue
            current.add(item.copy(builtIn = false))
            added++
        }
        saveCustomProfiles(current)
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
        val json = org.json.JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_LATENCIES, json.toString()).apply()
    }

    private fun loadLatencyMap(): Map<String, Long> {
        val raw = prefs.getString(KEY_LATENCIES, null) ?: return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
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
        const val ID_FREEBROWSER_STRICT = "builtin-freebrowser-strict"
        const val ID_CRONET_GENEVA = "builtin-cronet-geneva"
        const val ID_TOR_SNOWFLAKE = "builtin-tor-snowflake"
    }
}
