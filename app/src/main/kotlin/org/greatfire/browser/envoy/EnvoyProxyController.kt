package org.greatfire.browser.envoy

import IEnvoyProxy.Controller
import IEnvoyProxy.IEnvoyProxy
import android.content.Context
import android.util.Log
import org.greatfire.browser.vpn.LegacySslTrustBridge
import org.greatfire.browser.vpn.ProxyProfile
import org.greatfire.browser.vpn.TransportKind
import java.io.File

/**
 * Manages GreatFire's IEnvoyProxy (e3.5.0) multi-transport Go controller:
 * - V2Ray (VMess WebSocket, HTTP, SRTP, WeChat + uTLS + Domain Fronting)
 * - XRay (XHTTP)
 * - Hysteria2 (QUIC)
 * - MASQUE (HTTP/2 CONNECT Relay)
 * - Envoy ECH (Encrypted Client Hello)
 * - Tor Pluggable Transports (`Snowflake`, `MeekLite`, `Obfs4`, `Webtunnel`) — chained behind
 *   Guardian Project's `TorService` via `TorBridgeRunner`!
 */
class EnvoyProxyController(private val context: Context) {

    @Volatile
    private var controller: Controller? = null

    @Volatile
    private var activeMethod: String? = null

    data class StartedEndpoint(
        val upstreamMode: String, // "socks5", "http_proxy", or "envoy_http"
        val localAddr: String,    // e.g. "127.0.0.1:47000" or envoy URL
        val methodName: String
    )

    @Synchronized
    private fun ensureController(): Controller {
        controller?.let { return it }

        LegacySslTrustBridge.ensureCaBundlePath(context)

        val stateDir = File(context.cacheDir, "iep-state")
        if (!stateDir.exists()) {
            stateDir.mkdirs()
        }

        // Use Google/Quad9 encrypted DoH bootstrap (higher reachability in Iran than 1.1.1.1)
        runCatching {
            IEnvoyProxy.setDOHServer("8.8.8.8")
        }.onFailure {
            Log.w(TAG, "setDOHServer warning: ${it.message}")
        }

        val created = IEnvoyProxy.newController(
            stateDir.absolutePath,
            true,
            false,
            "WARN",
            null
        ) ?: throw IllegalStateException("Failed to initialize GreatFire IEnvoyProxy Controller")

        controller = created
        return created
    }

    /**
     * Starts a GreatFire `IEnvoyProxy` Pluggable Transport (`MeekLite`, `Snowflake`, `Obfs4`, `Webtunnel`)
     * and returns its local PT-SOCKS5 address (`127.0.0.1:<port>`) to be consumed by `TorBridgeRunner`.
     */
    @Synchronized
    fun startPluggableTransport(
        ptMethod: String,
        iceServers: String = DEFAULT_SNOWFLAKE_STUNS,
        brokerUrl: String = DEFAULT_SNOWFLAKE_BROKER_CDN77,
        frontDomains: String = DEFAULT_SNOWFLAKE_FRONTS_CDN77,
        ampCacheUrl: String = ""
    ): String {
        stopActive()
        val ctrl = ensureController()

        if (ptMethod == IEnvoyProxy.Snowflake) {
            ctrl.snowflakeIceServers = iceServers.ifBlank { DEFAULT_SNOWFLAKE_STUNS }
            ctrl.snowflakeBrokerUrl = brokerUrl.ifBlank { DEFAULT_SNOWFLAKE_BROKER_CDN77 }
            ctrl.snowflakeFrontDomains = frontDomains.ifBlank { DEFAULT_SNOWFLAKE_FRONTS_CDN77 }
            ctrl.snowflakeAmpCacheUrl = ampCacheUrl
            ctrl.snowflakeMaxPeers = 3L
        }

        ctrl.start(ptMethod, "")
        activeMethod = ptMethod
        val addr = ctrl.localAddress(ptMethod)
        awaitEndpoint(addr, ptMethod)
        Log.i(TAG, "GreatFire IEnvoyProxy Pluggable Transport $ptMethod listening on $addr")
        return addr
    }

    @Synchronized
    fun startTransport(profile: ProxyProfile): StartedEndpoint {
        stopActive()
        val ctrl = ensureController()

        return when (profile.kind) {
            TransportKind.V2RAY_WS -> {
                ctrl.v2RayServerAddress = profile.host
                ctrl.v2RayServerPort = profile.port.toString()
                ctrl.v2RayWsPath = profile.path.ifBlank { "/" }
                ctrl.v2RayId = profile.uuid
                ctrl.v2RayAllowInsecure = profile.allowInsecure
                ctrl.v2RayServerName = profile.sni.ifBlank { profile.host }
                ctrl.v2RayHostname = profile.frontHost
                ctrl.v2RayUtlsFingerprint = profile.utlsFingerprint.ifBlank { "chrome" }

                ctrl.start(IEnvoyProxy.V2RayWs, "")
                activeMethod = IEnvoyProxy.V2RayWs
                val addr = ctrl.localAddress(IEnvoyProxy.V2RayWs)
                awaitEndpoint(addr, IEnvoyProxy.V2RayWs)
                StartedEndpoint("socks5", addr, IEnvoyProxy.V2RayWs)
            }

            TransportKind.V2RAY_HTTP -> {
                ctrl.v2RayServerAddress = profile.host
                ctrl.v2RayServerPort = profile.port.toString()
                ctrl.v2RayHttpPath = profile.path.ifBlank { "/" }
                ctrl.v2RayId = profile.uuid
                ctrl.v2RayAllowInsecure = profile.allowInsecure
                ctrl.v2RayServerName = profile.sni.ifBlank { profile.host }
                ctrl.v2RayHostname = profile.frontHost
                ctrl.v2RayUtlsFingerprint = profile.utlsFingerprint.ifBlank { "chrome" }

                ctrl.start(IEnvoyProxy.V2RayHttp, "")
                activeMethod = IEnvoyProxy.V2RayHttp
                val addr = ctrl.localAddress(IEnvoyProxy.V2RayHttp)
                awaitEndpoint(addr, IEnvoyProxy.V2RayHttp)
                StartedEndpoint("socks5", addr, IEnvoyProxy.V2RayHttp)
            }

            TransportKind.V2RAY_SRTP -> {
                ctrl.v2RayServerAddress = profile.host
                ctrl.v2RayServerPort = profile.port.toString()
                ctrl.v2RayId = profile.uuid

                ctrl.start(IEnvoyProxy.V2RaySrtp, "")
                activeMethod = IEnvoyProxy.V2RaySrtp
                val addr = ctrl.localAddress(IEnvoyProxy.V2RaySrtp)
                awaitEndpoint(addr, IEnvoyProxy.V2RaySrtp)
                StartedEndpoint("socks5", addr, IEnvoyProxy.V2RaySrtp)
            }

            TransportKind.V2RAY_WECHAT -> {
                ctrl.v2RayServerAddress = profile.host
                ctrl.v2RayServerPort = profile.port.toString()
                ctrl.v2RayId = profile.uuid

                ctrl.start(IEnvoyProxy.V2RayWechat, "")
                activeMethod = IEnvoyProxy.V2RayWechat
                val addr = ctrl.localAddress(IEnvoyProxy.V2RayWechat)
                awaitEndpoint(addr, IEnvoyProxy.V2RayWechat)
                StartedEndpoint("socks5", addr, IEnvoyProxy.V2RayWechat)
            }

            TransportKind.XRAY_XHTTP -> {
                ctrl.setXRayServerAddress(profile.host)
                ctrl.setXRayServerPort(profile.port.toString())
                ctrl.setXRayXhttpPath(profile.path.ifBlank { "/" })
                ctrl.setXRayId(profile.uuid)
                ctrl.setXRayAllowInsecure(profile.allowInsecure)
                ctrl.setXRayServerName(profile.sni.ifBlank { profile.host })
                ctrl.setXRayHostname(profile.frontHost)

                ctrl.start(IEnvoyProxy.XRayXhttp, "")
                activeMethod = IEnvoyProxy.XRayXhttp
                val addr = ctrl.localAddress(IEnvoyProxy.XRayXhttp)
                awaitEndpoint(addr, IEnvoyProxy.XRayXhttp)
                StartedEndpoint("socks5", addr, IEnvoyProxy.XRayXhttp)
            }

            TransportKind.HYSTERIA2 -> {
                ctrl.hysteria2Server = profile.rawUri
                ctrl.hysteria2BandwidthUp = profile.extra["up"].orEmpty()
                ctrl.hysteria2BandwidthDown = profile.extra["down"].orEmpty()

                ctrl.start(IEnvoyProxy.Hysteria2, "")
                activeMethod = IEnvoyProxy.Hysteria2
                val addr = ctrl.localAddress(IEnvoyProxy.Hysteria2)
                awaitEndpoint(addr, IEnvoyProxy.Hysteria2)
                StartedEndpoint("socks5", addr, IEnvoyProxy.Hysteria2)
            }

            TransportKind.MASQUE -> {
                ctrl.masqueHost = profile.host
                ctrl.masquePort = (if (profile.port > 0) profile.port else 443).toLong()
                ctrl.masqueProxyToken = profile.password.ifBlank { "envoy" }

                ctrl.start(IEnvoyProxy.Masque, "")
                activeMethod = IEnvoyProxy.Masque
                val addr = ctrl.localAddress(IEnvoyProxy.Masque)
                awaitEndpoint(addr, IEnvoyProxy.Masque)
                StartedEndpoint("http_proxy", addr, IEnvoyProxy.Masque)
            }

            TransportKind.ENVOY_ECH -> {
                ctrl.setEnvoyUrl(profile.envoyUrl.ifBlank { profile.rawUri }, profile.extra["ech"].orEmpty())
                ctrl.start(IEnvoyProxy.EnvoyEch, "")
                activeMethod = IEnvoyProxy.EnvoyEch
                val echUrl = ctrl.echProxyUrl.ifBlank {
                    "http://" + ctrl.localAddress(IEnvoyProxy.EnvoyEch) + "/envoy"
                }
                StartedEndpoint("envoy_http", echUrl, IEnvoyProxy.EnvoyEch)
            }

            else -> throw IllegalArgumentException("Unsupported direct IEnvoyProxy transport: ${profile.kind}")
        }
    }

    private fun awaitEndpoint(addr: String, method: String) {
        if (addr.isBlank() || !addr.contains(':')) {
            throw IllegalStateException("IEnvoyProxy $method returned empty local address")
        }
        val host = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toIntOrNull()
            ?: throw IllegalStateException("Invalid port from IEnvoyProxy: $addr")
        if (!ShadowsocksRunner.waitForTcpPort(host, port, 6000L)) {
            Log.w(TAG, "Transport $method port $addr slow to respond; continuing")
        }
    }

    @Synchronized
    fun stopActive() {
        val method = activeMethod ?: return
        activeMethod = null
        runCatching {
            controller?.stop(method)
        }.onFailure {
            Log.w(TAG, "Error stopping $method: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "EnvoyProxyController"

        // Live 2026 Orbot CDN77 / DataPacket Snowflake Broker, Fronts, and non-Google STUN servers
        const val DEFAULT_SNOWFLAKE_BROKER_CDN77 = "https://1098762253.rsc.cdn77.org/"
        const val DEFAULT_SNOWFLAKE_FRONTS_CDN77 = "app.datapacket.com,www.datapacket.com,www.phpmyadmin.net"
        const val DEFAULT_SNOWFLAKE_STUNS =
            "stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
                "stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478," +
                "stun:stun.m-online.net:3478"
    }
}
