package org.greatfire.browser.envoy

import android.content.Context
import android.util.Log
import org.greatfire.browser.vpn.LegacySslTrustBridge
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Zero-Config Anti-Censorship Engine #1:
 * Supervises `libwarpplus.so` (`bepass-org/warp-plus` v1.2.6) compiled for Android API 21+ (all 4 ABIs).
 *
 * Automatically registers an anonymous Cloudflare WARP WireGuard identity, scans clean reachable
 * Cloudflare IPv4 endpoints with Anti-DPI WireGuard noise packets, and optionally chains:
 *  - `CLEAN_SCAN`: Fast single-hop Clean-IP WireGuard WARP tunnel
 *  - `WARP_IN_WARP_GOOL`: Double-hop WARP-inside-WARP (`--gool`) to defeat deep WireGuard DPI
 *  - `PSIPHON_CFON`: Multi-country Psiphon tunnel over Clean-IP WARP (`--cfon --country DE`)
 */
class WarpPlusRunner(private val context: Context) {

    enum class WarpMode {
        CLEAN_SCAN,
        WARP_IN_WARP_GOOL,
        PSIPHON_CFON
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    var activePort: Int = 0
        private set

    @Synchronized
    fun start(
        mode: WarpMode,
        countryCode: String = "DE",
        customEndpoint: String = "",
        onStatusLine: ((String) -> Unit)? = null
    ): String {
        stop()

        val exe = File(context.applicationInfo.nativeLibraryDir, "libwarpplus.so")
        if (!exe.exists()) {
            throw IllegalStateException("Native engine libwarpplus.so missing at ${exe.absolutePath}")
        }

        val port = ShadowsocksRunner.findFreePort(28080)
        val cacheDir = File(context.filesDir, "warp-plus-cache").apply {
            if (!exists()) mkdirs()
        }

        val cmd = mutableListOf(
            exe.absolutePath,
            "-4",
            "-b", "127.0.0.1:$port",
            "--cache-dir", cacheDir.absolutePath
        )

        if (customEndpoint.isNotBlank()) {
            cmd.addAll(listOf("-e", customEndpoint.trim()))
        } else {
            val maxRtt = if (mode == WarpMode.CLEAN_SCAN) "1500ms" else "1800ms"
            cmd.addAll(listOf("--scan", "--rtt", maxRtt))
        }

        when (mode) {
            WarpMode.CLEAN_SCAN -> Unit
            WarpMode.WARP_IN_WARP_GOOL -> cmd.add("--gool")
            WarpMode.PSIPHON_CFON -> {
                cmd.add("--cfon")
                cmd.add("--country")
                cmd.add(countryCode.ifBlank { "DE" }.uppercase())
            }
        }

        val pb = ProcessBuilder(cmd)
            .directory(cacheDir)
            .redirectErrorStream(true)
        LegacySslTrustBridge.applyGoTlsEnv(context, pb.environment())
        pb.environment()["HOME"] = context.filesDir.absolutePath

        val started = pb.start()
        process = started
        activePort = port

        @Volatile
        var boundSignal = false

        Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Log.d(TAG, line)
                        when {
                            line.contains("scanning", ignoreCase = true) ->
                                onStatusLine?.invoke("درحال اسکن آی‌پی تمیز Cloudflare WARP…")
                            line.contains("selected endpoint", ignoreCase = true) ||
                                line.contains("best endpoint", ignoreCase = true) ->
                                onStatusLine?.invoke("آی‌پی تمیز پیدا شد • درحال برقراری تونل WireGuard…")
                            line.contains("psiphon", ignoreCase = true) ->
                                onStatusLine?.invoke("درحال برقراری تونل Psiphon از مسیر WARP…")
                            line.contains("serving proxy", ignoreCase = true) ||
                                line.contains("127.0.0.1:$port") ->
                                boundSignal = true
                        }
                    }
                }
            }
        }, "warpplus-log").apply { isDaemon = true }.start()

        val maxWaitMs = if (mode == WarpMode.PSIPHON_CFON) 34_000L else 26_000L
        val deadline = System.currentTimeMillis() + maxWaitMs
        var ready = false

        while (System.currentTimeMillis() < deadline) {
            if (!isAliveCompat(started)) {
                stop()
                throw IllegalStateException("موتور WARP+ متوقف شد (کد خروج ${runCatching { started.exitValue() }.getOrDefault(-1)})")
            }
            if (boundSignal || canConnectLocalPort(port)) {
                ready = true
                break
            }
            Thread.sleep(220L)
        }

        if (!ready) {
            stop()
            throw IllegalStateException("اسکن آی‌پی تمیز WARP+ روی این شبکه پاسخ نداد")
        }

        Log.i(TAG, "warp-plus ($mode) listening on 127.0.0.1:$port")
        return "127.0.0.1:$port"
    }

    private fun canConnectLocalPort(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 200)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isAliveCompat(p: Process): Boolean {
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    @Synchronized
    fun stop() {
        runCatching { process?.destroy() }
        process = null
        activePort = 0
    }

    companion object {
        private const val TAG = "WarpPlusRunner"
    }
}
