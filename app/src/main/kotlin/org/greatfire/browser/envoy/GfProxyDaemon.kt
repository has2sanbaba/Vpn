package org.greatfire.browser.envoy

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Supervises our native Go GreatFire FreeBrowser/Anti-DPI + Unified Gateway binary (`libgfproxy.so`).
 * All WebView traffic points permanently to `127.0.0.1:gatewayPort`, allowing 0ms leak-free
 * switching between Anti-DPI, IEnvoyProxy (V2Ray/XRay/Hysteria2/Snowflake/MASQUE/ECH),
 * Envoy/Cronet, Shadowsocks, custom proxies, and Kill-Switch mode.
 */
class GfProxyDaemon(private val context: Context) {

    data class GatewayStatus(
        val ok: Boolean,
        val bytesUp: Long,
        val bytesDown: Long,
        val activeConnections: Long,
        val blockedRequests: Long,
        val dohQueries: Long,
        val dohCacheHits: Long,
        val mode: String
    )

    data class ProbeResult(
        val ok: Boolean,
        val statusCode: Int,
        val latencyMs: Long,
        val error: String? = null
    )

    @Volatile
    private var process: Process? = null

    @Volatile
    var gatewayPort: Int = 0
        private set

    @Volatile
    private var controlPort: Int = 0

    private val authToken: String = buildRandomHex(16)

    private val controlClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun ensureStarted(): Int {
        val current = process
        if (current != null && isAliveCompat(current) && gatewayPort > 0) {
            return gatewayPort
        }

        stop()

        val gwPort = ShadowsocksRunner.findFreePort(18080)
        val ctrlPort = ShadowsocksRunner.findFreePort(gwPort + 1)
        val fbConfigFile = File(context.filesDir, "fb.config")

        val exe = File(context.applicationInfo.nativeLibraryDir, "libgfproxy.so")
        if (!exe.exists()) {
            throw IllegalStateException("Native engine libgfproxy.so missing at ${exe.absolutePath}")
        }

        val cmd = listOf(
            exe.absolutePath,
            "-listen", "127.0.0.1:$gwPort",
            "-control", "127.0.0.1:$ctrlPort",
            "-token", authToken,
            "-mode", "anti_dpi",
            "-fbconfig", fbConfigFile.absolutePath
        )

        val started = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        process = started
        gatewayPort = gwPort
        controlPort = ctrlPort

        Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.d(TAG, line) }
                }
            }
        }, "gfproxy-log").apply { isDaemon = true }.start()

        if (!ShadowsocksRunner.waitForTcpPort("127.0.0.1", gwPort, 5000L)) {
            stop()
            throw IllegalStateException("libgfproxy.so failed to listen on 127.0.0.1:$gwPort")
        }
        ShadowsocksRunner.waitForTcpPort("127.0.0.1", ctrlPort, 2500L)

        Log.i(TAG, "GreatFire unified gateway active on 127.0.0.1:$gwPort (control :$ctrlPort)")
        return gwPort
    }

    fun updateRouting(
        mode: String,
        upstreamAddr: String = "",
        upstreamUser: String = "",
        upstreamPass: String = "",
        envoyUrl: String = "",
        envoyAddress: String = "",
        envoyHostHdr: String = "",
        dohServer: String = "",
        fragEnabled: Boolean = true,
        fragSize: Int = 24,
        fragDelayMs: Int = 4,
        profileName: String = ""
    ): Boolean {
        ensureStarted()
        val payload = JSONObject().apply {
            put("mode", mode)
            put("upstream_addr", upstreamAddr)
            put("upstream_user", upstreamUser)
            put("upstream_pass", upstreamPass)
            put("envoy_url", envoyUrl)
            put("envoy_address", envoyAddress)
            put("envoy_host_hdr", envoyHostHdr)
            put("doh_server", dohServer)
            put("frag_enabled", fragEnabled)
            put("frag_size", fragSize)
            put("frag_delay_ms", fragDelayMs)
            put("fb_config_path", File(context.filesDir, "fb.config").absolutePath)
            put("profile_name", profileName)
        }

        val req = Request.Builder()
            .url("http://127.0.0.1:$controlPort/config")
            .header("X-GF-Token", authToken)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return runCatching {
            controlClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun fetchStatus(): GatewayStatus? {
        if (controlPort <= 0) return null
        val req = Request.Builder()
            .url("http://127.0.0.1:$controlPort/status")
            .header("X-GF-Token", authToken)
            .get()
            .build()

        return runCatching {
            controlClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val obj = JSONObject(resp.body?.string().orEmpty())
                val cfg = obj.optJSONObject("config")
                GatewayStatus(
                    ok = obj.optBoolean("ok", false),
                    bytesUp = obj.optLong("bytes_up", 0L),
                    bytesDown = obj.optLong("bytes_down", 0L),
                    activeConnections = obj.optLong("active_connections", 0L),
                    blockedRequests = obj.optLong("blocked_requests", 0L),
                    dohQueries = obj.optLong("doh_queries", 0L),
                    dohCacheHits = obj.optLong("doh_cache_hits", 0L),
                    mode = cfg?.optString("mode").orEmpty()
                )
            }
        }.getOrNull()
    }

    fun probe(url: String = "https://www.google.com/generate_204"): ProbeResult {
        ensureStarted()
        val encoded = URLEncoder.encode(url, "UTF-8")
        val req = Request.Builder()
            .url("http://127.0.0.1:$controlPort/probe?url=$encoded")
            .header("X-GF-Token", authToken)
            .get()
            .build()

        return runCatching {
            controlClient.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string().orEmpty())
                ProbeResult(
                    ok = obj.optBoolean("ok", false),
                    statusCode = obj.optInt("status", 0),
                    latencyMs = obj.optLong("latency_ms", -1L),
                    error = obj.optString("error").takeIf { it.isNotBlank() }
                )
            }
        }.getOrElse { err ->
            ProbeResult(ok = false, statusCode = 0, latencyMs = -1L, error = err.message)
        }
    }

    @Synchronized
    fun stop() {
        runCatching { process?.destroy() }
        process = null
        gatewayPort = 0
        controlPort = 0
    }

    private fun isAliveCompat(p: Process): Boolean {
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    companion object {
        private const val TAG = "GfProxyDaemon"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun buildRandomHex(numBytes: Int): String {
            val bytes = ByteArray(numBytes)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
