package org.greatfire.browser.vpn

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import org.greatfire.browser.envoy.SingBoxRunner
import org.greatfire.browser.envoy.TorBridgeRunner
import org.greatfire.browser.envoy.WarpPlusRunner
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
    private val torBridgeRunner = TorBridgeRunner(appContext, iepController)
    private val warpRunner = WarpPlusRunner(appContext)
    private val singBoxRunner = SingBoxRunner(appContext)
    private val ssRunner = ShadowsocksRunner(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val switchMutex = Mutex()
    private var activeConnectJob: Job? = null

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
                val initMode = if (repository.killSwitchEnabled) "blocked" else "direct"
                val gwPort = gfDaemon.ensureStarted(initialMode = initMode)
                WebViewProxyConfigurator.applyProxyToWebView(appContext, gwPort)
                startTelemetryLoop()
                connect(repository.selectedProfileId)
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
        activeConnectJob?.cancel()
        activeConnectJob = scope.launch {
            connectSelectedProfileInternal()
        }
    }

    fun disconnect() {
        activeConnectJob?.cancel()
        scope.launch {
            switchMutex.withLock {
                stopAllSubEngines()

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

    private suspend fun stopAllSubEngines() {
        runCatching { warpRunner.stop() }
        runCatching { singBoxRunner.stop() }
        runCatching { torBridgeRunner.stop() }
        runCatching { iepController.stopActive() }
        runCatching { ssRunner.stop() }
    }

    private suspend fun connectSelectedProfileInternal() = switchMutex.withLock {
        val all = repository.loadAllProfiles()
        val chosen = all.firstOrNull { it.id == repository.selectedProfileId } ?: all.first()

        _uiState.value = _uiState.value.copy(
            state = VpnConnectionState.CONNECTING,
            activeProfile = chosen,
            latencyMs = -1L,
            killSwitchEnabled = repository.killSwitchEnabled,
            statusDetail = "درحال اتصال به ${chosen.name}…"
        )

        try {
            val initMode = if (repository.killSwitchEnabled) "blocked" else "direct"
            val gwPort = gfDaemon.ensureStarted(initialMode = initMode)
            if (repository.killSwitchEnabled) {
                gfDaemon.updateRouting(mode = "blocked", profileName = "Connecting Kill-Switch")
            }
            WebViewProxyConfigurator.applyProxyToWebView(appContext, gwPort)

            stopAllSubEngines()

            if (chosen.kind == TransportKind.SMART_AUTO) {
                connectSmartAutoCascade(chosen, gwPort)
                return@withLock
            }

            activateSingleProfile(chosen, gwPort)

            updateStatusDetail("درحال تست باز شدن واقعی یوتیوب و سایت‌های فیلتر…")
            val probeTimeout = when (chosen.kind) {
                TransportKind.TOR_MEEK_BRIDGE,
                TransportKind.SNOWFLAKE,
                TransportKind.TOR_OBFS4_BRIDGE,
                TransportKind.LYREBIRD_PT -> 18_000
                else -> 12_000
            }
            val probe = gfDaemon.verifyRealCensorshipBypass(timeoutMs = probeTimeout)
            if (!probe.ok && chosen.kind != TransportKind.FREEBROWSER_ANTI_DPI && chosen.kind != TransportKind.CRONET_GENEVA) {
                throw IllegalStateException(
                    probe.error ?: "سرور پاسخ نداد یا قادر به عبور از فیلترینگ نبود (HTTP ${probe.statusCode})"
                )
            }

            GreatFireTunnelService.start(appContext, chosen.name)

            if (probe.ok && probe.latencyMs > 0) {
                repository.updateLatency(chosen.id, probe.latencyMs)
            }

            _uiState.value = _uiState.value.copy(
                state = VpnConnectionState.CONNECTED,
                activeProfile = chosen,
                gatewayPort = gwPort,
                latencyMs = if (probe.ok) probe.latencyMs else -1L,
                statusDetail = "متصل • ${chosen.kind.badge}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed connecting profile ${chosen.name}", t)
            stopAllSubEngines()
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

    /**
     * Multi-Engine Zero-Config Failover Cascade (`SMART_AUTO`):
     * Automatically tests real remote IP-hiding anti-censorship tunnels in fastest-first order
     * and ONLY transitions to `CONNECTED` once `https://www.youtube.com/generate_204` (or `x.com`)
     * genuinely succeeds through the tunnel!
     */
    private suspend fun connectSmartAutoCascade(smartProfile: ProxyProfile, gwPort: Int) {
        val builtIns = repository.builtInProfiles().associateBy { it.id }
        val customVerified = repository.loadCustomProfiles()
            .filter { it.lastLatencyMs in 1..2800 }
            .sortedBy { it.lastLatencyMs }
            .take(2)

        val primaryStages = buildList {
            addAll(customVerified)
            builtIns[ProfileRepository.ID_WARP_CLEAN]?.let { add(it) }
            builtIns[ProfileRepository.ID_WARP_GOOL]?.let { add(it) }
            builtIns[ProfileRepository.ID_WARP_PSIPHON]?.let { add(it) }
        }

        for ((idx, stage) in primaryStages.withIndex()) {
            val ok = tryCandidateStage(
                smartProfile = smartProfile,
                candidate = stage,
                gwPort = gwPort,
                stepLabel = "[${idx + 1}/${primaryStages.size + 3}]"
            )
            if (ok) return
        }

        // Stage: Auto-fetch fresh VLESS-Reality / Trojan / Hysteria2 community servers over Anti-DPI + DoH!
        updateStatusDetail("درحال دریافت خودکار سرورهای جدید VLESS-Reality / Trojan…")
        val autoServers = runCatching {
            gfDaemon.updateRouting(
                mode = "anti_dpi",
                fragEnabled = true,
                fragSize = 14,
                fragDelayMs = 4,
                profileName = "AutoFetch Bootstrap"
            )
            val fetched = SubscriptionAutoFetcher.fetchFreshPublicProfiles(gwPort, maxProfiles = 12)
            if (repository.killSwitchEnabled) {
                gfDaemon.updateRouting(mode = "blocked", profileName = "Connecting Kill-Switch")
            }
            if (fetched.isNotEmpty()) {
                repository.addCustomProfiles(fetched)
            }
            fetched.take(4)
        }.getOrDefault(emptyList())

        for (srv in autoServers) {
            if (tryCandidateStage(smartProfile, srv, gwPort, "[سرور خودکار]")) {
                return
            }
        }

        // Stage: TCP/443 HTTPS Domain-Fronted & WebRTC Tor + GreatFire Pluggable Transports (works even when ALL UDP is blocked!)
        val torFallbacks = listOfNotNull(
            builtIns[ProfileRepository.ID_TOR_MEEK],
            builtIns[ProfileRepository.ID_TOR_SNOWFLAKE],
            builtIns[ProfileRepository.ID_TOR_OBFS4]
        )
        for (torStage in torFallbacks) {
            if (tryCandidateStage(smartProfile, torStage, gwPort, "[پل اضطراری Tor]", probeTimeoutMs = 18_000)) {
                return
            }
        }

        stopAllSubEngines()
        if (repository.killSwitchEnabled) {
            gfDaemon.updateRouting(mode = "blocked", profileName = "Error Kill-Switch")
        }
        _uiState.value = _uiState.value.copy(
            state = VpnConnectionState.DISCONNECTED,
            latencyMs = -1L,
            statusDetail = "همه موتورهای خودکار روی این اینترنت مسدود بودند • از بخش VPN یک کانفیگ VLESS/Trojan وارد کنید"
        )
    }

    private suspend fun tryCandidateStage(
        smartProfile: ProxyProfile,
        candidate: ProxyProfile,
        gwPort: Int,
        stepLabel: String,
        probeTimeoutMs: Int = 11_000
    ): Boolean {
        return try {
            stopAllSubEngines()
            updateStatusDetail("$stepLabel تلاش با ${candidate.name}…")
            activateSingleProfile(candidate, gwPort)

            updateStatusDetail("$stepLabel تست باز شدن واقعی یوتیوب با ${candidate.name}…")
            val probe = gfDaemon.verifyRealCensorshipBypass(timeoutMs = probeTimeoutMs)
            if (!probe.ok) {
                Log.w(TAG, "SmartAuto stage ${candidate.name} failed real censorship probe: ${probe.error}")
                false
            } else {
                repository.updateLatency(smartProfile.id, probe.latencyMs)
                repository.updateLatency(candidate.id, probe.latencyMs)
                GreatFireTunnelService.start(appContext, candidate.name)

                _uiState.value = _uiState.value.copy(
                    state = VpnConnectionState.CONNECTED,
                    activeProfile = smartProfile.copy(lastLatencyMs = probe.latencyMs),
                    gatewayPort = gwPort,
                    latencyMs = probe.latencyMs,
                    statusDetail = "متصل • ${candidate.name}"
                )
                Log.i(TAG, "SmartAuto connected via ${candidate.name} (${probe.latencyMs}ms)")
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SmartAuto stage ${candidate.name} error: ${t.message}")
            false
        }
    }

    private suspend fun activateSingleProfile(chosen: ProxyProfile, gwPort: Int) {
        when (chosen.kind) {
            TransportKind.WARP_CLEAN_SCAN -> {
                val localProxy = withContext(Dispatchers.IO) {
                    warpRunner.start(
                        mode = WarpPlusRunner.WarpMode.CLEAN_SCAN,
                        customEndpoint = chosen.extra["endpoint"].orEmpty(),
                        onStatusLine = { updateStatusDetail(it) }
                    )
                }
                gfDaemon.updateRouting(
                    mode = "socks5",
                    upstreamAddr = localProxy,
                    profileName = chosen.name
                )
                CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
            }

            TransportKind.WARP_IN_WARP_GOOL -> {
                val localProxy = withContext(Dispatchers.IO) {
                    warpRunner.start(
                        mode = WarpPlusRunner.WarpMode.WARP_IN_WARP_GOOL,
                        customEndpoint = chosen.extra["endpoint"].orEmpty(),
                        onStatusLine = { updateStatusDetail(it) }
                    )
                }
                gfDaemon.updateRouting(
                    mode = "socks5",
                    upstreamAddr = localProxy,
                    profileName = chosen.name
                )
                CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
            }

            TransportKind.WARP_PSIPHON_CFON -> {
                val cc = chosen.extra["country"]?.ifBlank { null } ?: "DE"
                val localProxy = withContext(Dispatchers.IO) {
                    warpRunner.start(
                        mode = WarpPlusRunner.WarpMode.PSIPHON_CFON,
                        countryCode = cc,
                        customEndpoint = chosen.extra["endpoint"].orEmpty(),
                        onStatusLine = { updateStatusDetail(it) }
                    )
                }
                gfDaemon.updateRouting(
                    mode = "socks5",
                    upstreamAddr = localProxy,
                    profileName = chosen.name
                )
                CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
            }

            TransportKind.TOR_MEEK_BRIDGE,
            TransportKind.SNOWFLAKE,
            TransportKind.TOR_OBFS4_BRIDGE,
            TransportKind.LYREBIRD_PT -> {
                val torSocksAddr = torBridgeRunner.start(
                    profile = chosen,
                    onStatusLine = { updateStatusDetail(it) }
                )
                gfDaemon.updateRouting(
                    mode = "socks5",
                    upstreamAddr = torSocksAddr,
                    profileName = chosen.name
                )
                CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
            }

            TransportKind.VLESS,
            TransportKind.TROJAN,
            TransportKind.TUIC,
            TransportKind.WIREGUARD -> {
                val sbAddr = withContext(Dispatchers.IO) { singBoxRunner.start(chosen) }
                gfDaemon.updateRouting(
                    mode = "socks5",
                    upstreamAddr = sbAddr,
                    profileName = chosen.name
                )
                CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
            }

            TransportKind.HYSTERIA2 -> {
                val addr = withContext(Dispatchers.IO) {
                    if (chosen.password.isNotBlank()) {
                        runCatching { singBoxRunner.start(chosen) }
                            .getOrElse { iepController.startTransport(chosen).localAddr }
                    } else {
                        iepController.startTransport(chosen).localAddr
                    }
                }
                gfDaemon.updateRouting(
                    mode = "socks5",
                    upstreamAddr = addr,
                    profileName = chosen.name
                )
                CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
            }

            TransportKind.V2RAY_WS,
            TransportKind.V2RAY_HTTP -> {
                val addr = withContext(Dispatchers.IO) {
                    if (chosen.extra["engine"] == "iep") {
                        iepController.startTransport(chosen).localAddr
                    } else {
                        runCatching { singBoxRunner.start(chosen) }
                            .getOrElse { iepController.startTransport(chosen).localAddr }
                    }
                }
                gfDaemon.updateRouting(
                    mode = "socks5",
                    upstreamAddr = addr,
                    profileName = chosen.name
                )
                CronetNetworking.initializeEngine(appContext, "socks5://127.0.0.1:$gwPort")
            }

            TransportKind.SHADOWSOCKS -> {
                val localSocks = withContext(Dispatchers.IO) {
                    if (chosen.username.startsWith("2022-")) {
                        singBoxRunner.start(chosen)
                    } else {
                        runCatching { ssRunner.start(chosen.rawUri) }
                            .getOrElse { singBoxRunner.start(chosen) }
                    }
                }
                CronetNetworking.initializeEngine(appContext, "socks5://$localSocks")
                gfDaemon.updateRouting(
                    mode = "socks5",
                    upstreamAddr = localSocks,
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

            TransportKind.V2RAY_SRTP,
            TransportKind.V2RAY_WECHAT,
            TransportKind.XRAY_XHTTP,
            TransportKind.MASQUE,
            TransportKind.ENVOY_ECH -> {
                val started = withContext(Dispatchers.IO) { iepController.startTransport(chosen) }
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

            TransportKind.SMART_AUTO -> Unit
        }
    }

    private fun updateStatusDetail(msg: String) {
        if (_uiState.value.state == VpnConnectionState.CONNECTING) {
            _uiState.value = _uiState.value.copy(statusDetail = msg)
        }
    }

    suspend fun runActiveProbe(): GfProxyDaemon.ProbeResult = withContext(Dispatchers.IO) {
        val result = gfDaemon.verifyRealCensorshipBypass(timeoutMs = 12_000)
        val activeId = _uiState.value.activeProfile?.id
        if (result.ok && result.latencyMs >= 0 && activeId != null) {
            repository.updateLatency(activeId, result.latencyMs)
            _uiState.value = _uiState.value.copy(latencyMs = result.latencyMs)
        }
        result
    }

    /**
     * One-tap fetch of fresh public VLESS-Reality, Trojan, and Hysteria2 servers over our
     * Anti-DPI / Tunnel gateway.
     */
    suspend fun fetchPublicFreeServers(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val gwPort = gfDaemon.ensureStarted()
            val wasDisconnected = _uiState.value.state != VpnConnectionState.CONNECTED
            if (wasDisconnected) {
                gfDaemon.updateRouting(
                    mode = "anti_dpi",
                    fragEnabled = true,
                    fragSize = 14,
                    fragDelayMs = 4,
                    profileName = "AutoFetch Anti-DPI"
                )
            }
            val fetched = try {
                SubscriptionAutoFetcher.fetchFreshPublicProfiles(gwPort, maxProfiles = 25)
            } finally {
                if (wasDisconnected && repository.killSwitchEnabled) {
                    gfDaemon.updateRouting(mode = "blocked", profileName = "Kill-Switch Blocked")
                }
            }
            if (fetched.isEmpty()) {
                throw IllegalStateException("دریافت سرورهای عمومی ناموفق بود؛ اتصال اینترنت را بررسی کنید")
            }
            repository.addCustomProfiles(fetched)
        }
    }

    /**
     * Fetches a remote subscription URL THROUGH our active Anti-DPI/Tunnel gateway so even
     * blocked GitHub raw or Cloudflare Worker links download without external VPN!
     */
    suspend fun importSubscriptionUrl(subUrl: String, labelPrefix: String = ""): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val gwPort = gfDaemon.ensureStarted()
                val wasDisconnected = _uiState.value.state != VpnConnectionState.CONNECTED
                if (wasDisconnected) {
                    gfDaemon.updateRouting(
                        mode = "anti_dpi",
                        fragEnabled = true,
                        fragSize = 14,
                        fragDelayMs = 4,
                        profileName = "SubImport Anti-DPI"
                    )
                }
                val body = try {
                    val client = getTunneledOkHttpClient()
                    val req = Request.Builder()
                        .url(subUrl.trim())
                        .header("User-Agent", "GreatFireEnvoyBrowser/2.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("HTTP ${resp.code} while fetching subscription")
                        }
                        resp.body?.string().orEmpty()
                    }
                } finally {
                    if (wasDisconnected && repository.killSwitchEnabled) {
                        gfDaemon.updateRouting(mode = "blocked", profileName = "Kill-Switch Blocked")
                    }
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
