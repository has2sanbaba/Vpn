package org.greatfire.browser.envoy

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import org.greatfire.browser.vpn.ProxyProfileParser
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder

/**
 * Launches and supervises GreatFire's bundled gfw-report/shadowsocks-rust binary (`libsslocal.so`)
 * packaged inside cronet-108.0.5359.195.aar across armeabi-v7a, arm64-v8a, x86, and x86_64.
 */
class ShadowsocksRunner(private val context: Context) {

    @Volatile
    private var process: Process? = null

    @Volatile
    var activeLocalPort: Int = 0
        private set

    @Synchronized
    fun start(ssUri: String): String {
        stop()

        val parsed = parseShadowsocksUri(ssUri)
            ?: throw IllegalArgumentException("Invalid Shadowsocks URI")

        val localPort = findFreePort(11080)
        parsed.put("local_address", "127.0.0.1")
        parsed.put("local_port", localPort)
        parsed.put("timeout", 300)

        val dir = ContextCompat.getNoBackupFilesDir(context) ?: context.filesDir
        val confFile = File(dir, "gf_shadowsocks.json")
        confFile.writeText(parsed.toString())

        val exeFile = File(context.applicationInfo.nativeLibraryDir, "libsslocal.so")
        if (!exeFile.exists()) {
            throw IllegalStateException("libsslocal.so not found in ${exeFile.parent}")
        }

        val pb = ProcessBuilder(exeFile.absolutePath, "-c", confFile.absolutePath)
            .redirectErrorStream(true)
        val started = pb.start()
        process = started
        activeLocalPort = localPort

        Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.d(TAG, line) }
                }
            }
        }, "sslocal-log").apply { isDaemon = true }.start()

        if (!waitForTcpPort("127.0.0.1", localPort, 4500L)) {
            stop()
            throw IllegalStateException("Shadowsocks local SOCKS5 failed to bind on port $localPort")
        }

        return "127.0.0.1:$localPort"
    }

    @Synchronized
    fun stop() {
        runCatching { process?.destroy() }
        process = null
        activeLocalPort = 0
    }

    companion object {
        private const val TAG = "ShadowsocksRunner"

        /**
         * Supports both SIP002 (`ss://BASE64(method:pass)@host:port#tag`)
         * and Legacy (`ss://BASE64(method:pass@host:port)#tag`) URIs.
         */
        fun parseShadowsocksUri(rawUri: String): JSONObject? {
            val trimmed = rawUri.trim()
            if (!trimmed.startsWith("ss://", ignoreCase = true)) return null

            val withoutScheme = trimmed.substring(5)
            val remarks = withoutScheme.substringAfter('#', "").let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            val mainPart = withoutScheme.substringBefore('#')
            val queryPart = mainPart.substringAfter('?', "")
            val authorityPart = mainPart.substringBefore('?')

            val method: String
            val password: String
            val host: String
            val port: Int

            if (authorityPart.contains('@')) {
                // SIP002 format: userInfo@host:port
                val userInfoRaw = authorityPart.substringBeforeLast('@')
                val hostPort = authorityPart.substringAfterLast('@')
                val decodedUser = decodeBase64Flexible(userInfoRaw) ?: runCatching {
                    URLDecoder.decode(userInfoRaw, "UTF-8")
                }.getOrDefault(userInfoRaw)

                if (!decodedUser.contains(':')) return null
                method = decodedUser.substringBefore(':')
                password = decodedUser.substringAfter(':')

                val hp = splitHostPort(hostPort) ?: return null
                host = hp.first
                port = hp.second
            } else {
                // Legacy full-base64 format: BASE64(method:password@host:port)
                val decoded = decodeBase64Flexible(authorityPart) ?: return null
                if (!decoded.contains('@') || !decoded.contains(':')) return null
                val creds = decoded.substringBeforeLast('@')
                val hostPort = decoded.substringAfterLast('@')
                method = creds.substringBefore(':')
                password = creds.substringAfter(':')
                val hp = splitHostPort(hostPort) ?: return null
                host = hp.first
                port = hp.second
            }

            if (host.isBlank() || port !in 1..65535 || method.isBlank()) return null

            return JSONObject().apply {
                put("server", host)
                put("server_port", port)
                put("method", method)
                put("password", password)
                if (remarks.isNotBlank()) put("remarks", remarks)
                if (queryPart.isNotBlank()) {
                    val uri = runCatching { URI("ss://dummy?$queryPart") }.getOrNull()
                    uri?.rawQuery?.split('&')?.forEach { kv ->
                        val k = kv.substringBefore('=')
                        val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                        if (k == "plugin" && v.isNotBlank()) {
                            put("plugin", v.substringBefore(';'))
                            if (v.contains(';')) {
                                put("plugin_opts", v.substringAfter(';'))
                            }
                        }
                    }
                }
            }
        }

        private fun decodeBase64Flexible(input: String): String? =
            ProxyProfileParser.decodeBase64Utf8(input)

        private fun splitHostPort(hostPort: String): Pair<String, Int>? {
            val h: String
            val pStr: String
            if (hostPort.startsWith('[')) {
                val close = hostPort.indexOf(']')
                if (close < 0 || close + 2 > hostPort.length) return null
                h = hostPort.substring(1, close)
                pStr = hostPort.substring(close + 2)
            } else {
                val colon = hostPort.lastIndexOf(':')
                if (colon <= 0) return null
                h = hostPort.substring(0, colon)
                pStr = hostPort.substring(colon + 1)
            }
            val p = pStr.toIntOrNull() ?: return null
            return h to p
        }

        fun findFreePort(preferred: Int): Int {
            for (candidate in preferred..(preferred + 100)) {
                runCatching {
                    ServerSocket(candidate).use { return candidate }
                }
            }
            ServerSocket(0).use { return it.localPort }
        }

        fun waitForTcpPort(host: String, port: Int, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(host, port), 350)
                        return true
                    }
                } catch (_: Exception) {
                    Thread.sleep(120)
                }
            }
            return false
        }
    }
}
