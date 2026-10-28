package org.greatfire.browser.envoy

import android.content.Context
import android.util.Log
import org.greatfire.browser.vpn.LegacySslTrustBridge
import org.greatfire.browser.vpn.ProxyProfile
import org.greatfire.browser.vpn.TransportKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Multi-Protocol Anti-Censorship Engine #3:
 * Supervises `libsingbox.so` (`SagerNet/sing-box` v1.14.4, API 21+, all 4 ABIs) with TLS Record
 * Fragmentation (`record_fragment: true`), uTLS Chrome/Firefox/Safari fingerprints, and full support for:
 *  - VLESS (`xtls-rprx-vision`, `Reality`, `WebSocket`, `gRPC`, `HTTPUpgrade`, `HTTP/2`, `TCP`)
 *  - Trojan (`TLS` / `Reality` + `WS` / `gRPC` / `TCP`)
 *  - TUIC v5 (`QUIC` + `BBR`)
 *  - Hysteria2 (`QUIC` + Salamander obfs)
 *  - VMess (`WS`, `gRPC`, `HTTPUpgrade`, `TCP`, `TLS`, `uTLS`)
 *  - Shadowsocks (AEAD + Shadowsocks-2022 `2022-blake3-*`)
 *  - WireGuard (`wg://` / `wireguard://`)
 */
class SingBoxRunner(private val context: Context) {

    @Volatile
    private var process: Process? = null

    @Volatile
    var activePort: Int = 0
        private set

    @Synchronized
    fun start(profile: ProxyProfile): String {
        stop()

        val exe = File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")
        if (!exe.exists()) {
            throw IllegalStateException("Native engine libsingbox.so missing at ${exe.absolutePath}")
        }

        val port = ShadowsocksRunner.findFreePort(29080)
        val workDir = File(context.cacheDir, "sing-box-runtime").apply {
            if (!exists()) mkdirs()
        }
        val configFile = File(workDir, "config.json")
        val configJson = buildSingBoxConfig(profile, port)
        configFile.writeText(configJson.toString(2), Charsets.UTF_8)

        val cmd = listOf(
            exe.absolutePath,
            "run",
            "-D", workDir.absolutePath,
            "-c", configFile.absolutePath
        )

        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
        LegacySslTrustBridge.applyGoTlsEnv(context, pb.environment())

        val started = pb.start()
        process = started
        activePort = port

        Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.d(TAG, line) }
                }
            }
        }, "singbox-log").apply { isDaemon = true }.start()

        val deadline = System.currentTimeMillis() + 9_000L
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            if (!isAliveCompat(started)) {
                stop()
                throw IllegalStateException("کانفیگ Sing-Box معتبر نبود یا اتصال اولیه رد شد")
            }
            if (canConnectLocalPort(port)) {
                ready = true
                break
            }
            Thread.sleep(150L)
        }

        if (!ready) {
            stop()
            throw IllegalStateException("Sing-Box failed to bind 127.0.0.1:$port")
        }

        Log.i(TAG, "sing-box (${profile.kind}) listening on 127.0.0.1:$port")
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
        private const val TAG = "SingBoxRunner"

        internal fun buildSingBoxConfig(profile: ProxyProfile, localMixedPort: Int): JSONObject {
        val outbound = buildProxyOutbound(profile)

        val dnsServers = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("tag", "dns-remote")
                    put("type", "https")
                    put("server", "8.8.8.8")
                    put("detour", "proxy")
                    put(
                        "tls",
                        JSONObject().apply {
                            put("enabled", true)
                            put("server_name", "dns.google")
                        }
                    )
                }
            )
            put(
                JSONObject().apply {
                    put("tag", "dns-bootstrap")
                    put("type", "https")
                    put("server", "8.8.8.8")
                    put(
                        "tls",
                        JSONObject().apply {
                            put("enabled", true)
                            put("server_name", "dns.google")
                            put("record_fragment", true)
                        }
                    )
                }
            )
        }

        val inbounds = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("type", "mixed")
                    put("tag", "mixed-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", localMixedPort)
                }
            )
        }

        val outbounds = JSONArray().apply {
            put(outbound)
            put(
                JSONObject().apply {
                    put("type", "direct")
                    put("tag", "direct")
                }
            )
        }

        return JSONObject().apply {
            put("log", JSONObject().put("level", "warn"))
            put(
                "dns",
                JSONObject().apply {
                    put("servers", dnsServers)
                    put("final", "dns-remote")
                }
            )
            put("inbounds", inbounds)
            put("outbounds", outbounds)
            put(
                "route",
                JSONObject().apply {
                    put("default_domain_resolver", "dns-bootstrap")
                    put("final", "proxy")
                }
            )
        }
    }

    private fun buildProxyOutbound(profile: ProxyProfile): JSONObject {
        return when (profile.kind) {
            TransportKind.VLESS -> JSONObject().apply {
                put("type", "vless")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                val flow = profile.extra["flow"].orEmpty()
                if (flow.isNotBlank()) {
                    put("flow", flow)
                }
                put("packet_encoding", "xudp")
                attachTlsIfNeeded(this, profile, defaultTls = false)
                attachV2RayTransportIfNeeded(this, profile)
            }

            TransportKind.TROJAN -> JSONObject().apply {
                put("type", "trojan")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", profile.port)
                put("password", profile.password.ifBlank { profile.uuid })
                attachTlsIfNeeded(this, profile, defaultTls = true)
                attachV2RayTransportIfNeeded(this, profile)
            }

            TransportKind.TUIC -> JSONObject().apply {
                put("type", "tuic")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                put("password", profile.password)
                put("congestion_control", profile.extra["congestion_control"]?.ifBlank { null } ?: "bbr")
                put("udp_relay_mode", profile.extra["udp_relay_mode"]?.ifBlank { null } ?: "native")
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", profile.sni.ifBlank { profile.host })
                        put("insecure", profile.allowInsecure)
                        val alpn = profile.extra["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        if (!alpn.isNullOrEmpty()) {
                            put("alpn", JSONArray(alpn))
                        } else {
                            put("alpn", JSONArray(listOf("h3")))
                        }
                    }
                )
            }

            TransportKind.HYSTERIA2 -> JSONObject().apply {
                put("type", "hysteria2")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", profile.port)
                put("password", profile.password)
                val obfsType = profile.extra["obfs"].orEmpty()
                val obfsPassword = profile.extra["obfs-password"].orEmpty()
                if (obfsType.equals("salamander", ignoreCase = true) && obfsPassword.isNotEmpty()) {
                    put(
                        "obfs",
                        JSONObject().apply {
                            put("type", "salamander")
                            put("password", obfsPassword)
                        }
                    )
                }
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", profile.sni.ifBlank { profile.host })
                        put("insecure", profile.allowInsecure || profile.extra["insecure"] == "1")
                        put("alpn", JSONArray(listOf("h3")))
                    }
                )
            }

            TransportKind.SHADOWSOCKS -> JSONObject().apply {
                put("type", "shadowsocks")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", profile.port)
                put("method", profile.username.ifBlank { "chacha20-ietf-poly1305" })
                put("password", profile.password)
            }

            TransportKind.WIREGUARD -> JSONObject().apply {
                put("type", "wireguard")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", if (profile.port > 0) profile.port else 2408)
                put("private_key", profile.password)
                put("peer_public_key", profile.extra["publickey"].orEmpty())
                val localAddr = profile.extra["address"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: listOf("172.16.0.2/32")
                put("local_address", JSONArray(localAddr))
                profile.extra["reserved"]?.takeIf { it.isNotBlank() }?.let { resStr ->
                    val nums = resStr.split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (nums.size == 3) {
                        put("reserved", JSONArray(nums))
                    }
                }
                put("mtu", profile.extra["mtu"]?.toIntOrNull() ?: 1280)
            }

            TransportKind.V2RAY_WS, TransportKind.V2RAY_HTTP -> JSONObject().apply {
                put("type", "vmess")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                put("security", profile.extra["scy"]?.ifBlank { null } ?: "auto")
                put("alter_id", profile.extra["aid"]?.toIntOrNull() ?: 0)
                attachTlsIfNeeded(this, profile, defaultTls = profile.port == 443 || profile.extra["tls"] == "tls")
                attachV2RayTransportIfNeeded(
                    this,
                    if (profile.extra["type"].isNullOrBlank()) {
                        profile.copy(extra = profile.extra + ("type" to if (profile.kind == TransportKind.V2RAY_HTTP) "http" else "ws"))
                    } else profile
                )
            }

            else -> throw IllegalArgumentException("Unsupported SingBox profile kind: ${profile.kind}")
        }
    }

    private fun attachTlsIfNeeded(out: JSONObject, profile: ProxyProfile, defaultTls: Boolean) {
        val security = profile.extra["security"]?.lowercase()
            ?: profile.extra["tls"]?.lowercase()
            ?: if (defaultTls) "tls" else ""

        if (security != "tls" && security != "reality") return

        val tlsObj = JSONObject().apply {
            put("enabled", true)
            val sni = profile.sni.ifBlank { profile.frontHost.ifBlank { profile.host } }
            if (sni.isNotBlank()) {
                put("server_name", sni)
            }
            put("insecure", profile.allowInsecure)
            // Fragment TLS ClientHello records to defeat Iranian ISP SNI DPI middleboxes!
            if (security != "reality") {
                put("record_fragment", true)
            }

            val alpnRaw = profile.extra["alpn"].orEmpty()
            if (alpnRaw.isNotBlank()) {
                val alpnList = alpnRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (alpnList.isNotEmpty()) {
                    put("alpn", JSONArray(alpnList))
                }
            }

            val fp = profile.utlsFingerprint.ifBlank { "chrome" }
            put(
                "utls",
                JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", fp)
                }
            )

            if (security == "reality") {
                put(
                    "reality",
                    JSONObject().apply {
                        put("enabled", true)
                        put("public_key", profile.extra["pbk"].orEmpty())
                        val sid = profile.extra["sid"].orEmpty()
                        if (sid.isNotBlank()) {
                            put("short_id", sid)
                        }
                    }
                )
            }
        }
        out.put("tls", tlsObj)
    }

    private fun attachV2RayTransportIfNeeded(out: JSONObject, profile: ProxyProfile) {
        val netType = profile.extra["type"]?.lowercase().orEmpty()
        when (netType) {
            "ws", "websocket" -> {
                var cleanPath = profile.path.ifBlank { "/" }
                var maxEarlyData = 0
                if (cleanPath.contains("?ed=")) {
                    maxEarlyData = cleanPath.substringAfter("?ed=").substringBefore('&').toIntOrNull() ?: 2048
                    cleanPath = cleanPath.substringBefore("?ed=").ifBlank { "/" }
                }
                val tr = JSONObject().apply {
                    put("type", "ws")
                    put("path", cleanPath)
                    if (profile.frontHost.isNotBlank()) {
                        put("headers", JSONObject().put("Host", profile.frontHost))
                    }
                    if (maxEarlyData > 0) {
                        put("max_early_data", maxEarlyData)
                        put("early_data_header_name", "Sec-WebSocket-Protocol")
                    }
                }
                out.put("transport", tr)
            }

            "grpc" -> {
                val serviceName = profile.extra["serviceName"]
                    ?: profile.extra["servicename"]
                    ?: profile.path.trimStart('/')
                out.put(
                    "transport",
                    JSONObject().apply {
                        put("type", "grpc")
                        if (serviceName.isNotBlank()) {
                            put("service_name", serviceName)
                        }
                    }
                )
            }

            "httpupgrade" -> {
                out.put(
                    "transport",
                    JSONObject().apply {
                        put("type", "httpupgrade")
                        put("path", profile.path.ifBlank { "/" })
                        if (profile.frontHost.isNotBlank()) {
                            put("host", profile.frontHost)
                        }
                    }
                )
            }

            "http", "h2" -> {
                out.put(
                    "transport",
                    JSONObject().apply {
                        put("type", "http")
                        put("path", profile.path.ifBlank { "/" })
                        if (profile.frontHost.isNotBlank()) {
                            put("host", JSONArray(listOf(profile.frontHost)))
                        }
                    }
                )
            }
        }
    }
    }
}
