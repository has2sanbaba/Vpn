package org.greatfire.browser.vpn

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.greatfire.browser.envoy.CronetInterceptor
import org.greatfire.browser.envoy.CronetNetworking
import org.greatfire.browser.envoy.EnvoyProxyController
import org.greatfire.browser.envoy.GfProxyDaemon
import org.greatfire.browser.envoy.ShadowsocksRunner
import org.greatfire.browser.web.WebViewProxyConfigurator
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

enum class VpnConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

data class TunnelUiState(
    val state: VpnConnectionState = VpnConnectionState.CONNECTING,
    val activeProfile: ProxyProfile? = null,
    val gatewayPort: Int = 0,
    val latencyMs: Long = -1L,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
    val dohQueries: Long = 0L,
    val blockedLeaks: Long = 0L,
    val killSwitchEnabled: Boolean = true,
    val statusDetail: String = "درحال راه‌اندازی موتور ضد فیلتر GreatFire…"
)

class TunnelManager private constructor(private val appContext: Context) {

    val repository = ProfileRepository(appContext)
    private val gfDaemon = GfProxyDaemon(appContext)
    private val iepController = EnvoyProxyController(appContext)
    private val ssRunner = ShadowsocksRunner(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val switchMutex = Mutex()

    private val _uiState = MutableStateFlow(
        TunnelUiState(
            killSwitchEnabled = repository.killSwitchEnabled,
            activeProfile = repository.loadAllProfiles().firstOrNull { it.id == repository.selectedProfileId }
                ?: repository.builtInProfiles().first()
        )
    )
    val uiState: StateFlow<TunnelUiState> = _uiState.asStateFlow()

    @Volatile
    private var proxyClientCache: Pair<Int, OkHttpClient>? = null

    fun initializeAutoStart() {
        scope.launch {
            runCatching {
                val gwPort = gfDaemon.ensureStarted()
                WebViewProxyConfigurator.applyProxyToWebView(appContext, gwPort)
                connectSelectedProfileInternal()
                startTelemetryLoop()
            }.onFailure { err ->
                Log.e(TAG, "Auto-start tunnel error", err)
                _uiState.value = _uiState.value.copy(
                    state = VpnConnectionState.DISCONNECTED,
                    statusDetail = "خطا در راه‌اندازی اولیه: ${err.message}"
                )
            }
        }
    }

    fun connect(profileId: String = repository.selectedProfileId) {
        repository.selectedProfileId = profileId
        scope.launch {
            connectSelectedProfileInternal()
        }
    }

    fun disconnect() {
        scope.launch {
            switchMutex.withLock {
                iepController.stopActive()
                ssRunner.stop()

                val killSwitch = repository.killSwitchEnabled
                val gwPort = runCatching { gfDaemon.ensureStarted() }.getOrDefault(0)
                if (gwPort > 0) {
                    val mode = if (killSwitch) "blocked" else "direct"
                    gfDaemon.updateRouting(
                        mode = mode,
                        profileName = if (killSwitch) "Kill-Switch Blocked" else "Direct"
                    )
                    WebViewProxyConfigurator.applyProxyToWebView(appContext, gwPort)
                }

                GreatFireTunnelService.stop(appContext)

                _uiState.value = _uiState.value.copy(
                    state = VpnConnectionState.DISCONNECTED,
                    latencyMs = -1L,
                    killSwitchEnabled = killSwitch,
                    statusDetail = if (killSwitch) {
                        "قطع شد • قفل Kill-Switch فعال (هیچ سایتی بدون VPN باز نمی‌شود)"
                    } else {
                        "VPN خاموش است (اتصال مستقیم)"
                    }
                )
            }
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        repository.killSwitchEnabled = enabled
        _uiState.value = _uiState.value.copy(killSwitchEnabled = enabled)
        if (_uiState.value.state == VpnConnectionState.DISCONNECTED) {
            disconnect()
        }
    }

    private suspend fun connectSelectedProfileInternal() = switchMutex.withLock {
        val all = repository.loadAllProfiles()
        val chosen = all.firstOrNull { it.id == repository.selectedProfileId } ?: all.first()

        _uiState.value = _uiState.value.copy(
            state = VpnConnectionState.CONNECTING,
            activeProfile = chosen,
            killSwitchEnabled = repository.killSwitchEnabled,
            statusDetail = "درحال اتصال به ${chosen.name}…"
        )

        try {
            val gwPort = gfDaemon.ensureStarted()
            WebViewProxyConfigurator.applyProxyToWebView(appContext, gwPort)

            // Stop previous external sub-transports cleanly before activating new profile
            iepController.stopActive()
            ssRunner.stop()

            when (chosen.kind) {
                TransportKind.SMART_AUTO -> {
                    CronetNetworking.initializeEngine(
                        context = appContext,
                        envoyUrl = "socks5://127.0.0.1:$gwPort",
                        genevaStrategy = chosen.genevaStrategy
                    )
                    gfDaemon.updateRouting(
                        mode = "anti_dpi",
                        fragEnabled = true,
                        fragSize = chosen.fragSize,
                        fragDelayMs = chosen.fragDelayMs,
                        dohServer = chosen.dohServer,
                        profileName = chosen.name
                    )
                }

                TransportKind.FREEBROWSER_ANTI_DPI -> {
                    CronetNetworking.initializeEngine(
                        context = appContext,
                        envoyUrl = "socks5://127.0.0.1:$gwPort",
                        genevaStrategy = 0
                    )
                    gfDaemon.updateRouting(
                        mode = "anti_dpi",
                        fragEnabled = true,
                        fragSize = chosen.fragSize,
                        fragDelayMs = chosen.fragDelayMs,
                        dohServer = chosen.dohServer,
                        profileName = chosen.name
                    )
                }

                TransportKind.CRONET_GENEVA -> {
                    CronetNetworking.initializeEngine(
                        context = appContext,
                        envoyUrl = chosen.envoyUrl.ifBlank { "socks5://127.0.0.1:$gwPort" },
                        genevaStrategy = if (chosen.genevaStrategy > 0) chosen.genevaStrategy else 2
                    )
                    gfDaemon.updateRouting(
                        mode = "anti_dpi",
                        fragEnabled = true,
                        fragSize = chosen.fragSize,
                        fragDelayMs = chosen.fragDelayMs,
                        profileName = chosen.name
                    )
                }

                TransportKind.SHADOWSOCKS -> {
                    val localSocks = ssRunner.start(chosen.rawUri)
                    CronetNetworking.initializeEngine(appContext, "socks5://$localSocks")
                    gfDaemon.updateRouting(
                        mode = "socks5",
                        upstreamAddr = localSocks,
                        profileName = chosen.name
                    )
                }

                TransportKind.ENVOY_URL -> {
                    CronetNetworking.initializeEngine(appContext, chosen.rawUri)
                    gfDaemon.updateRouting(
                        mode = "envoy_http",
                        envoyUrl = chosen.envoyUrl,
                        envoyAddress = chosen.envoyAddress,
                        envoyHostHdr = chosen.frontHost,
                        fragEnabled = true,
                        profileName = chosen.name
                    )
                }

                TransportKind.SOCKS5 -> {
                    val addr = "${chosen.host}:${chosen.port}"
                    gfDaemon.updateRouting(
                        mode = "socks5",
                        upstreamAddr = addr,
                        upstreamUser = chosen.username,
                        upstreamPass = chosen.password,
                        profileName = chosen.name
                    )
                    CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
                }

                TransportKind.HTTP_PROXY -> {
                    val addr = "${chosen.host}:${chosen.port}"
                    gfDaemon.updateRouting(
                        mode = "http_proxy",
                        upstreamAddr = addr,
                        upstreamUser = chosen.username,
                        upstreamPass = chosen.password,
                        profileName = chosen.name
                    )
                    CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
                }

                TransportKind.V2RAY_WS,
                TransportKind.V2RAY_HTTP,
                TransportKind.V2RAY_SRTP,
                TransportKind.V2RAY_WECHAT,
                TransportKind.XRAY_XHTTP,
                TransportKind.HYSTERIA2,
                TransportKind.MASQUE,
                TransportKind.ENVOY_ECH,
                TransportKind.SNOWFLAKE,
                TransportKind.LYREBIRD_PT -> {
                    val started = iepController.startTransport(chosen)
                    if (started.upstreamMode == "envoy_http") {
                        gfDaemon.updateRouting(
                            mode = "envoy_http",
                            envoyUrl = started.localAddr,
                            profileName = chosen.name
                        )
                    } else {
                        gfDaemon.updateRouting(
                            mode = started.upstreamMode,
                            upstreamAddr = started.localAddr,
                            profileName = chosen.name
                        )
                    }
                    CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
                }
            }

            GreatFireTunnelService.start(appContext, chosen.name)

            _uiState.value = _uiState.value.copy(
                state = VpnConnectionState.CONNECTED,
                activeProfile = chosen,
                gatewayPort = gwPort,
                statusDetail = "متصل • ${chosen.kind.badge}"
            )

            // Non-blocking real connectivity latency measurement in background
            scope.launch {
                val probe = gfDaemon.probe()
                if (probe.ok && probe.latencyMs > 0) {
                    repository.updateLatency(chosen.id, probe.latencyMs)
                    if (_uiState.value.activeProfile?.id == chosen.id &&
                        _uiState.value.state == VpnConnectionState.CONNECTED
                    ) {
                        _uiState.value = _uiState.value.copy(latencyMs = probe.latencyMs)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed connecting profile ${chosen.name}", t)
            // Keep Kill-Switch active if connection fails so browser never leaks!
            if (repository.killSwitchEnabled) {
                runCatching { gfDaemon.updateRouting(mode = "blocked", profileName = "Error Kill-Switch") }
            }
            _uiState.value = _uiState.value.copy(
                state = VpnConnectionState.DISCONNECTED,
                latencyMs = -1L,
                statusDetail = "خطا در اتصال (${chosen.name}): ${t.message}"
            )
        }
    }

    suspend fun runActiveProbe(): GfProxyDaemon.ProbeResult = withContext(Dispatchers.IO) {
        val result = gfDaemon.probe()
        val activeId = _uiState.value.activeProfile?.id
        if (result.ok && result.latencyMs >= 0 && activeId != null) {
            repository.updateLatency(activeId, result.latencyMs)
            _uiState.value = _uiState.value.copy(latencyMs = result.latencyMs)
        }
        result
    }

    /**
     * Fetches a remote subscription URL THROUGH our active Anti-DPI/Tunnel gateway so even
     * blocked GitHub raw or Cloudflare Worker links download without external VPN!
     */
    suspend fun importSubscriptionUrl(subUrl: String, labelPrefix: String = ""): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = getTunneledOkHttpClient()
                val req = Request.Builder()
                    .url(subUrl.trim())
                    .header("User-Agent", "GreatFireEnvoyBrowser/1.0")
                    .build()
                val body = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code} while fetching subscription")
                    }
                    resp.body?.string().orEmpty()
                }
                val parsed = ProxyProfileParser.parseMany(body, labelPrefix)
                if (parsed.isEmpty()) {
                    throw IllegalArgumentException("هیچ کانفیگ معتبری در لینک اشتراک یافت نشد")
                }
                repository.addCustomProfiles(parsed)
            }
        }

    fun getTunneledOkHttpClient(): OkHttpClient {
        val gwPort = runCatching { gfDaemon.ensureStarted() }.getOrDefault(0)
        proxyClientCache?.let { (cachedPort, cachedClient) ->
            if (cachedPort == gwPort) return cachedClient
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        if (gwPort > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", gwPort)))
        } else {
            builder.addInterceptor(CronetInterceptor())
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            val sf = LegacySslTrustBridge.sslSocketFactory
            val tm = LegacySslTrustBridge.trustManager
            if (sf != null && tm != null) {
                builder.sslSocketFactory(sf, tm)
            }
        }

        val client = builder.build()
        proxyClientCache = gwPort to client
        return client
    }

    private fun startTelemetryLoop() {
        scope.launch {
            while (isActive) {
                delay(2000L)
                val status = gfDaemon.fetchStatus() ?: continue
                val cur = _uiState.value
                _uiState.value = cur.copy(
                    bytesUp = status.bytesUp,
                    bytesDown = status.bytesDown,
                    dohQueries = status.dohQueries,
                    blockedLeaks = status.blockedRequests
                )
            }
        }
    }

    companion object {
        private const val TAG = "TunnelManager"

        @Volatile
        private var instance: TunnelManager? = null

        fun getInstance(context: Context): TunnelManager {
            return instance ?: synchronized(this) {
                instance ?: TunnelManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
