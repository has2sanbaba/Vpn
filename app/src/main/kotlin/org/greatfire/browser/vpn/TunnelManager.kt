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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.greatfire.browser.web.WebViewProxyConfigurator
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GatewayStatus(
    val ready: Boolean = false,
    val gatewayPort: Int = 0,
    val readyNodes: Int = 0,
    val bestNode: String = "",
    val bestProto: String = "",
    val bestRttMs: Long = 0L,
    val directWins: Long = 0L,
    val tunnelWins: Long = 0L,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
    val customUri: String = ""
)

data class ProbeResult(
    val ok: Boolean,
    val latencyMs: Long = 0L,
    val bestNode: String = "",
    val error: String? = null
)

/**
 * Lightweight, zero-wait controller for the native GreatFire FreeBrowser/Envoy Go engine (`libgfproxy.so`).
 * Boots in <25ms in the background and routes WebView requests immediately without ever blocking navigation.
 */
class TunnelManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("gf_chrome_prefs", Context.MODE_PRIVATE)

    private val controlToken = UUID.randomUUID().toString()

    @Volatile
    private var proxyProcess: Process? = null

    @Volatile
    var gatewayPort: Int = 0
        private set

    @Volatile
    private var controlPort: Int = 0

    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_ADBLOCK, value).apply()

    var webRtcShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEBRTC_SHIELD, true)
        set(value) = prefs.edit().putBoolean(KEY_WEBRTC_SHIELD, value).apply()

    var customProxyUri: String
        get() = prefs.getString(KEY_CUSTOM_URI, "").orEmpty()
        private set(value) = prefs.edit().putString(KEY_CUSTOM_URI, value.trim()).apply()

    private val controlHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()

    fun initializeAutoStart() {
        scope.launch {
            LegacySslTrustBridge.initialize(appContext)
            ensureDefaultFbConfig()
            ensureRunning()
            startWatchdogLoop()
        }
    }

    suspend fun ensureRunning(): Boolean = startMutex.withLock {
        val currentProc = proxyProcess
        if (currentProc != null && isProcessAliveCompat(currentProc) && gatewayPort > 0) {
            return@withLock true
        }

        runCatching { currentProc?.destroy() }
        proxyProcess = null

        val binFile = findGfProxyBinary() ?: run {
            Log.e(TAG, "libgfproxy.so not found in nativeLibraryDir")
            return@withLock false
        }

        val gwPort = findAvailableLocalPort(18080)
        val ctrlPort = findAvailableLocalPort(18081)
        val dataDir = File(appContext.filesDir, "gfproxy").apply { mkdirs() }
        val fbConfigFile = File(appContext.filesDir, "fb.config")

        val cmd = mutableListOf(
            binFile.absolutePath,
            "-listen", "127.0.0.1:$gwPort",
            "-control", "127.0.0.1:$ctrlPort",
            "-token", controlToken,
            "-data-dir", dataDir.absolutePath,
            "-fbconfig", fbConfigFile.absolutePath
        )
        val savedCustom = customProxyUri
        if (savedCustom.isNotBlank()) {
            cmd.add("-custom-uri")
            cmd.add(savedCustom)
        }

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.directory(dataDir)
        LegacySslTrustBridge.caBundlePemPath?.let { pemPath ->
            pb.environment()["SSL_CERT_FILE"] = pemPath
        }
        pb.environment()["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed starting libgfproxy.so: ${t.message}")
            return@withLock false
        }

        // Drain stdout/stderr asynchronously so pipe buffer never blocks Go runtime
        scope.launch {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.d(TAG, line) }
                }
            }
        }

        // Wait up to 400ms (polling every 12ms) for TCP bind — usually ready in <20ms!
        var listening = false
        repeat(34) {
            if (isLocalPortOpen(gwPort)) {
                listening = true
                return@repeat
            }
            delay(12)
        }

        if (!listening) {
            Log.w(TAG, "libgfproxy port $gwPort slow to bind, continuing...")
        }

        proxyProcess = proc
        gatewayPort = gwPort
        controlPort = ctrlPort

        WebViewProxyConfigurator.applyProxyToWebView(appContext, gwPort)
        _status.value = _status.value.copy(
            ready = true,
            gatewayPort = gwPort,
            customUri = savedCustom
        )
        true
    }

    private fun startWatchdogLoop() {
        scope.launch {
            while (true) {
                delay(4000)
                val p = proxyProcess
                if (p == null || !isProcessAliveCompat(p)) {
                    ensureRunning()
                }
                pollStatusOnce()
            }
        }
    }

    suspend fun pollStatusOnce(): GatewayStatus? = withContext(Dispatchers.IO) {
        val cPort = controlPort
        if (cPort <= 0) return@withContext null
        return@withContext try {
            val req = Request.Builder()
                .url("http://127.0.0.1:$cPort/status")
                .header("X-GF-Token", controlToken)
                .build()
            controlHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val st = GatewayStatus(
                    ready = json.optBoolean("ok", true),
                    gatewayPort = gatewayPort,
                    readyNodes = json.optInt("ready_nodes", 0),
                    bestNode = json.optString("best_node", ""),
                    bestProto = json.optString("best_proto", ""),
                    bestRttMs = json.optLong("best_rtt_ms", 0L),
                    directWins = json.optLong("direct_wins", 0L),
                    tunnelWins = json.optLong("tunnel_wins", 0L),
                    bytesUp = json.optLong("bytes_up", 0L),
                    bytesDown = json.optLong("bytes_down", 0L),
                    customUri = customProxyUri
                )
                _status.value = st
                st
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun updateCustomProxy(uriInput: String): Result<Unit> = withContext(Dispatchers.IO) {
        ensureRunning()
        val cPort = controlPort
        if (cPort <= 0) return@withContext Result.failure(IllegalStateException("موتور پروکسی آماده نیست"))

        val cleanUri = uriInput.trim()
        val payload = JSONObject().apply {
            put("mode", "anti_dpi")
            put("frag_enabled", true)
            put("frag_size", 24)
            put("frag_delay_ms", 3)
            put("custom_uri", cleanUri)
        }

        return@withContext try {
            val req = Request.Builder()
                .url("http://127.0.0.1:$cPort/config")
                .header("X-GF-Token", controlToken)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            controlHttp.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use Result.failure(IllegalArgumentException(bodyStr.ifBlank { "لینک نامعتبر است" }))
                }
                customProxyUri = cleanUri
                pollStatusOnce()
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun refreshNodesNow() = withContext(Dispatchers.IO) {
        val cPort = controlPort
        if (cPort <= 0) return@withContext
        runCatching {
            val req = Request.Builder()
                .url("http://127.0.0.1:$cPort/refresh")
                .header("X-GF-Token", controlToken)
                .post("".toRequestBody(null))
                .build()
            controlHttp.newCall(req).execute().close()
        }
    }

    suspend fun runActiveProbe(): ProbeResult = withContext(Dispatchers.IO) {
        ensureRunning()
        val cPort = controlPort
        if (cPort <= 0) return@withContext ProbeResult(false, error = "پروکسی داخلی آماده نیست")

        return@withContext try {
            val req = Request.Builder()
                .url("http://127.0.0.1:$cPort/probe?timeout_ms=8000")
                .header("X-GF-Token", controlToken)
                .build()
            controlHttp.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string().orEmpty())
                ProbeResult(
                    ok = obj.optBoolean("ok", false),
                    latencyMs = obj.optLong("latency_ms", 0L),
                    bestNode = obj.optString("best_node", ""),
                    error = obj.optString("error", "").ifBlank { null }
                )
            }
        } catch (t: Throwable) {
            ProbeResult(false, error = t.message)
        }
    }

    fun getTunneledOkHttpClient(): OkHttpClient {
        val port = gatewayPort
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (port > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            val sf = LegacySslTrustBridge.sslSocketFactory
            val tm = LegacySslTrustBridge.trustManager
            if (sf != null && tm != null) {
                builder.sslSocketFactory(sf, tm)
            }
        }
        return builder.build()
    }

    private fun findGfProxyBinary(): File? {
        val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
        val candidate = File(nativeDir, "libgfproxy.so")
        if (candidate.exists()) {
            candidate.setExecutable(true)
            return candidate
        }
        return null
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

    private fun findAvailableLocalPort(preferred: Int): Int {
        runCatching {
            ServerSocket(preferred).use { return preferred }
        }
        return ServerSocket(0).use { it.localPort }
    }

    private fun isLocalPortOpen(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 60)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isProcessAliveCompat(proc: Process): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            proc.isAlive
        } else {
            try {
                proc.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }
    }

    companion object {
        private const val TAG = "GFProxyEngine"
        private const val KEY_ADBLOCK = "adblock_enabled"
        private const val KEY_WEBRTC_SHIELD = "webrtc_shield_enabled"
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
