package org.greatfire.browser.vpn

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.greatfire.browser.web.WebViewProxyConfigurator
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit

data class GatewayStatus(
    val ready: Boolean = false,
    val gatewayPort: Int = 0,
    val readyNodes: Int = 0,
    val activeSources: Int = 0,
    val bestNode: String = "",
    val bestSource: String = "",
    val bestProto: String = "",
    val bestRttMs: Long = 0L,
    val bestSpeedKbps: Long = 0L,
    val directWins: Long = 0L,
    val tunnelWins: Long = 0L,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
    val customUri: String = ""
) {
    fun formattedSpeed(): String {
        if (bestSpeedKbps <= 0L) return ""
        return if (bestSpeedKbps >= 1000L) {
            String.format(Locale.US, "%.1f Mbps", bestSpeedKbps / 1000.0)
        } else {
            "${bestSpeedKbps} Kbps"
        }
    }
}

data class ProbeResult(
    val ok: Boolean,
    val latencyMs: Long = 0L,
    val speedKbps: Long = 0L,
    val bestNode: String = "",
    val error: String? = null
)

data class VerifiedNodeItem(
    val id: String,
    val name: String,
    val source: String = "",
    val protocol: String,
    val rttMs: Long,
    val speedKbps: Long,
    val active: Boolean,
    val custom: Boolean = false
)

data class SwitchProxyResult(
    val ok: Boolean,
    val switchedFrom: String = "",
    val bestId: String = "",
    val bestNode: String = "",
    val bestSource: String = "",
    val bestProto: String = "",
    val bestRttMs: Long = 0L,
    val bestSpeedKbps: Long = 0L,
    val readyNodes: Int = 0,
    val activeSources: Int = 0,
    val error: String? = null
)

data class CustomAddResponse(
    val ok: Boolean,
    val addedCount: Int = 0,
    val workingCount: Int = 0,
    val isSub: Boolean = false,
    val bestId: String = "",
    val bestNode: String = "",
    val bestProto: String = "",
    val bestRttMs: Long = 0L,
    val message: String = "",
    val error: String? = null
)

class TunnelManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val clientCacheLock = Any()
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("gf_chrome_prefs", Context.MODE_PRIVATE)

    @Volatile
    var gatewayPort: Int = 0
        private set

    private var cachedClientPort: Int = -1
    private var cachedTunneledClient: OkHttpClient? = null
    private var cachedDownloadClient: OkHttpClient? = null

    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_ADBLOCK, value).apply()

    var customProxyUri: String
        get() = prefs.getString(KEY_CUSTOM_URI, "").orEmpty()
        private set(value) = prefs.edit().putString(KEY_CUSTOM_URI, value.trim()).apply()

    fun initializeAutoStart() {
        scope.launch {
            LegacySslTrustBridge.initialize(appContext)
            ensureDefaultFbConfig()
            ensureRunning()
            val fastPollsMs = longArrayOf(280L, 550L, 900L, 1400L, 2000L)
            for (waitMs in fastPollsMs) {
                delay(waitMs)
                val st = pollStatusOnce()
                if (st != null && st.readyNodes > 0) break
            }
            startWatchdogLoop()
        }
    }

    suspend fun ensureRunning(): Boolean = startMutex.withLock {
        if (gatewayPort > 0) {
            return@withLock true
        }

        val dataDir = File(appContext.filesDir, "gfproxy").apply { mkdirs() }
        val fbConfigFile = File(appContext.filesDir, "fb.config")
        val caPemPath = LegacySslTrustBridge.ensureCaBundlePath(appContext).orEmpty()
        val savedCustom = customProxyUri

        val port = NativeProxyBridge.startEngine(
            dataDir = dataDir.absolutePath,
            caPemPath = caPemPath,
            fbConfigPath = fbConfigFile.absolutePath,
            customUri = savedCustom
        )
        if (port <= 0) {
            Log.e(TAG, "Failed starting in-process JNI libgfproxy.so (port=$port)")
            return@withLock false
        }

        gatewayPort = port
        WebViewProxyConfigurator.applyProxyToWebView(appContext, port)
        _status.value = _status.value.copy(
            ready = true,
            gatewayPort = port,
            customUri = savedCustom
        )
        true
    }

    private fun startWatchdogLoop() {
        scope.launch {
            while (true) {
                delay(4000)
                if (gatewayPort <= 0) {
                    ensureRunning()
                }
                pollStatusOnce()
            }
        }
    }

    suspend fun pollStatusOnce(): GatewayStatus? = withContext(Dispatchers.IO) {
        if (gatewayPort <= 0) return@withContext null
        return@withContext try {
            val raw = NativeProxyBridge.controlRequest("GET", "/status")
            val json = JSONObject(raw.ifBlank { "{}" })
            if (!json.optBoolean("ok", false)) return@withContext null
            val st = GatewayStatus(
                ready = true,
                gatewayPort = gatewayPort,
                readyNodes = json.optInt("ready_nodes", 0),
                activeSources = json.optInt("active_sources", 0),
                bestNode = json.optString("best_node", ""),
                bestSource = json.optString("best_source", ""),
                bestProto = json.optString("best_proto", ""),
                bestRttMs = json.optLong("best_rtt_ms", 0L),
                bestSpeedKbps = json.optLong("best_speed_kbps", 0L),
                directWins = json.optLong("direct_wins", 0L),
                tunnelWins = json.optLong("tunnel_wins", 0L),
                bytesUp = json.optLong("bytes_up", 0L),
                bytesDown = json.optLong("bytes_down", 0L),
                customUri = customProxyUri
            )
            _status.value = st
            st
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun switchToBestFastProxy(): SwitchProxyResult = withContext(Dispatchers.IO) {
        if (!ensureRunning()) {
            return@withContext SwitchProxyResult(false, error = "موتور اتصال آماده نیست")
        }
        return@withContext try {
            val raw = NativeProxyBridge.controlRequest("POST", "/switch-best", "{}")
            val obj = JSONObject(raw.ifBlank { "{}" })
            val ok = obj.optBoolean("ok", false)
            if (ok) {
                customProxyUri = ""
                pollStatusOnce()
            }
            SwitchProxyResult(
                ok = ok,
                switchedFrom = obj.optString("switched_from", ""),
                bestId = obj.optString("best_id", ""),
                bestNode = obj.optString("best_node", ""),
                bestSource = obj.optString("best_source", ""),
                bestProto = obj.optString("best_proto", ""),
                bestRttMs = obj.optLong("best_rtt_ms", 0L),
                bestSpeedKbps = obj.optLong("best_speed_kbps", 0L),
                readyNodes = obj.optInt("ready_nodes", 0),
                activeSources = obj.optInt("active_sources", 0),
                error = obj.optString("error", "").ifBlank { null }
            )
        } catch (t: Throwable) {
            SwitchProxyResult(false, error = t.message)
        }
    }

    suspend fun fetchVerifiedNodes(): List<VerifiedNodeItem> = withContext(Dispatchers.IO) {
        if (!ensureRunning()) return@withContext emptyList()
        return@withContext try {
            val raw = NativeProxyBridge.controlRequest("GET", "/nodes")
            val obj = JSONObject(raw.ifBlank { "{}" })
            val arr = obj.optJSONArray("nodes") ?: return@withContext emptyList()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(
                        VerifiedNodeItem(
                            id = item.optString("id", ""),
                            name = item.optString("name", "Proxy"),
                            source = item.optString("source", ""),
                            protocol = item.optString("protocol", "TLS"),
                            rttMs = item.optLong("rtt_ms", 0L),
                            speedKbps = item.optLong("speed_kbps", 0L),
                            active = item.optBoolean("active", false),
                            custom = item.optBoolean("custom", false)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun selectSpecificNode(nodeId: String): SwitchProxyResult = withContext(Dispatchers.IO) {
        if (!ensureRunning()) return@withContext SwitchProxyResult(false, error = "موتور اتصال آماده نیست")
        val payload = JSONObject().put("id", nodeId).toString()
        return@withContext try {
            val raw = NativeProxyBridge.controlRequest("POST", "/select-node", payload)
            val obj = JSONObject(raw.ifBlank { "{}" })
            val ok = obj.optBoolean("ok", false)
            if (ok) {
                customProxyUri = ""
                pollStatusOnce()
            }
            SwitchProxyResult(
                ok = ok,
                switchedFrom = obj.optString("switched_from", ""),
                bestId = obj.optString("best_id", ""),
                bestNode = obj.optString("best_node", ""),
                bestSource = obj.optString("best_source", ""),
                bestProto = obj.optString("best_proto", ""),
                bestRttMs = obj.optLong("best_rtt_ms", 0L),
                bestSpeedKbps = obj.optLong("best_speed_kbps", 0L),
                readyNodes = obj.optInt("ready_nodes", 0),
                activeSources = obj.optInt("active_sources", 0),
                error = obj.optString("error", "").ifBlank { null }
            )
        } catch (t: Throwable) {
            SwitchProxyResult(false, error = t.message)
        }
    }

    suspend fun addCustomConfigsOrSub(rawInput: String): CustomAddResponse = withContext(Dispatchers.IO) {
        if (!ensureRunning()) return@withContext CustomAddResponse(false, error = "موتور اتصال آماده نیست")
        val payload = JSONObject().put("input", rawInput.trim()).toString()
        return@withContext try {
            val raw = NativeProxyBridge.controlRequest("POST", "/custom/add", payload)
            val obj = JSONObject(raw.ifBlank { "{}" })
            val ok = obj.optBoolean("ok", false)
            if (ok) {
                customProxyUri = ""
                pollStatusOnce()
            }
            CustomAddResponse(
                ok = ok,
                addedCount = obj.optInt("added_count", 0),
                workingCount = obj.optInt("working_count", 0),
                isSub = obj.optBoolean("is_sub", false),
                bestId = obj.optString("best_id", ""),
                bestNode = obj.optString("best_node", ""),
                bestProto = obj.optString("best_proto", ""),
                bestRttMs = obj.optLong("best_rtt_ms", 0L),
                message = obj.optString("message", ""),
                error = obj.optString("error", "").ifBlank { null }
            )
        } catch (t: Throwable) {
            CustomAddResponse(false, error = t.message)
        }
    }

    suspend fun fetchCustomConfigsAndSubs(): Pair<List<VerifiedNodeItem>, List<String>> = withContext(Dispatchers.IO) {
        if (!ensureRunning()) return@withContext emptyList<VerifiedNodeItem>() to emptyList()
        return@withContext try {
            val raw = NativeProxyBridge.controlRequest("GET", "/custom/list")
            val obj = JSONObject(raw.ifBlank { "{}" })
            val nodesArr = obj.optJSONArray("custom_nodes")
            val subsArr = obj.optJSONArray("custom_subs")
            val nodes = buildList {
                if (nodesArr != null) {
                    for (i in 0 until nodesArr.length()) {
                        val item = nodesArr.optJSONObject(i) ?: continue
                        add(
                            VerifiedNodeItem(
                                id = item.optString("id", ""),
                                name = item.optString("name", "Custom"),
                                source = item.optString("source", "اختصاصی کاربر"),
                                protocol = item.optString("protocol", "TLS"),
                                rttMs = item.optLong("rtt_ms", 0L),
                                speedKbps = item.optLong("speed_kbps", 0L),
                                active = item.optBoolean("active", false),
                                custom = true
                            )
                        )
                    }
                }
            }
            val subs = buildList {
                if (subsArr != null) {
                    for (i in 0 until subsArr.length()) {
                        val s = subsArr.optString(i, "").trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }
            nodes to subs
        } catch (_: Throwable) {
            emptyList<VerifiedNodeItem>() to emptyList()
        }
    }

    suspend fun deleteCustomConfigOrSub(
        nodeId: String = "",
        subUrl: String = "",
        clearAll: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ensureRunning()) return@withContext false
        val payload = JSONObject().apply {
            put("id", nodeId)
            put("sub_url", subUrl)
            put("clear_all", clearAll)
        }.toString()
        return@withContext try {
            val raw = NativeProxyBridge.controlRequest("POST", "/custom/delete", payload)
            val ok = JSONObject(raw.ifBlank { "{}" }).optBoolean("ok", false)
            if (clearAll) customProxyUri = ""
            pollStatusOnce()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun updateCustomProxy(uriInput: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanUri = uriInput.trim()
        if (cleanUri.isEmpty()) {
            if (ensureRunning()) {
                val payload = JSONObject().apply {
                    put("mode", "anti_dpi")
                    put("frag_enabled", true)
                    put("frag_size", 24)
                    put("frag_delay_ms", 3)
                    put("custom_uri", "")
                }.toString()
                runCatching { NativeProxyBridge.controlRequest("POST", "/config", payload) }
            }
            customProxyUri = ""
            pollStatusOnce()
            return@withContext Result.success(Unit)
        }
        val res = addCustomConfigsOrSub(cleanUri)
        if (res.ok) Result.success(Unit)
        else Result.failure(IllegalArgumentException(res.error ?: "کانفیگ معتبر نیست"))
    }

    suspend fun refreshNodesNow() = withContext(Dispatchers.IO) {
        if (gatewayPort <= 0) return@withContext
        runCatching { NativeProxyBridge.controlRequest("POST", "/refresh") }
    }

    suspend fun runActiveProbe(): ProbeResult = withContext(Dispatchers.IO) {
        if (!ensureRunning()) return@withContext ProbeResult(false, error = "پروکسی داخلی آماده نیست")
        return@withContext try {
            val raw = NativeProxyBridge.controlRequest("GET", "/probe?timeout_ms=8000")
            val obj = JSONObject(raw.ifBlank { "{}" })
            ProbeResult(
                ok = obj.optBoolean("ok", false),
                latencyMs = obj.optLong("latency_ms", 0L),
                speedKbps = obj.optLong("speed_kbps", 0L),
                bestNode = obj.optString("best_node", ""),
                error = obj.optString("error", "").ifBlank { null }
            )
        } catch (t: Throwable) {
            ProbeResult(false, error = t.message)
        }
    }

    fun getTunneledOkHttpClient(): OkHttpClient = synchronized(clientCacheLock) {
        val port = gatewayPort
        val existing = cachedTunneledClient
        if (existing != null && cachedClientPort == port) {
            return existing
        }
        rebuildClientsLocked(port)
        return cachedTunneledClient!!
    }

    fun getStreamingDownloadOkHttpClient(): OkHttpClient = synchronized(clientCacheLock) {
        val port = gatewayPort
        val existing = cachedDownloadClient
        if (existing != null && cachedClientPort == port) {
            return existing
        }
        rebuildClientsLocked(port)
        return cachedDownloadClient!!
    }

    private fun rebuildClientsLocked(port: Int) {
        fun applyBase(b: OkHttpClient.Builder): OkHttpClient.Builder {
            b.followRedirects(true).followSslRedirects(true)
            if (port > 0) {
                b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                val sf = LegacySslTrustBridge.sslSocketFactory
                val tm = LegacySslTrustBridge.trustManager
                if (sf != null && tm != null) {
                    b.sslSocketFactory(sf, tm)
                }
            }
            return b
        }

        cachedClientPort = port
        cachedTunneledClient = applyBase(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
        ).build()

        cachedDownloadClient = applyBase(
            OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(65, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
        ).build()
    }

    private fun ensureDefaultFbConfig() {
        runCatching {
            val out = File(appContext.filesDir, "fb.config")
            if (!out.exists()) {
                appContext.assets.open("fb.config").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "GFProxyEngine"
        private const val KEY_ADBLOCK = "adblock_enabled"
        private const val KEY_CUSTOM_URI = "custom_proxy_uri"

        @Volatile
        private var INSTANCE: TunnelManager? = null

        fun getInstance(context: Context): TunnelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TunnelManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
