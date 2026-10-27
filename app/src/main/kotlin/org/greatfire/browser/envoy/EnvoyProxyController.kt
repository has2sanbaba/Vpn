package org.greatfire.browser.envoy

import IEnvoyProxy.Controller
import IEnvoyProxy.IEnvoyProxy
import android.content.Context
import android.util.Log
import org.greatfire.browser.vpn.ProxyProfile
import org.greatfire.browser.vpn.TransportKind
import java.io.File

/**
 * Manages GreatFire's IEnvoyProxy (e3.5.0) multi-transport Go controller:
 * - V2Ray (WebSocket, HTTP, SRTP, WeChat + uTLS + Domain Fronting)
 * - XRay (XHTTP)
 * - Hysteria2 (QUIC)
 * - Tor Snowflake (WebRTC + Domain Fronting / AMP Cache)
 * - Lyrebird Pluggable Transports (Obfs4, MeekLite, WebTunnel)
 * - MASQUE (HTTP/2 CONNECT Relay)
 * - Envoy ECH (Encrypted Client Hello)
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

        val stateDir = File(context.cacheDir, "iep-state")
        if (!stateDir.exists()) {
            stateDir.mkdirs()
        }

        // Configure Go runtime DNS inside IEnvoyProxy to use encrypted DoH
        runCatching {
            IEnvoyProxy.setDOHServer("1.1.1.1")
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

            TransportKind.SNOWFLAKE -> {
                ctrl.snowflakeIceServers = profile.extra["ice"]?.ifBlank { null }
                    ?: "stun:stun.l.google.com:19302,stun:stun.voip.blackberry.com:3478,stun:stun.sonetel.com:3478"
                ctrl.snowflakeBrokerUrl = profile.extra["broker"]?.ifBlank { null }
                    ?: "https://snowflake-broker.torproject.net.global.prod.fastly.net/"
                ctrl.snowflakeFrontDomains = profile.frontHost.ifBlank {
                    "www.cdn77.com,www.phpmyadmin.net"
                }
                ctrl.snowflakeAmpCacheUrl = profile.extra["ampcache"].orEmpty()
                ctrl.snowflakeMaxPeers = 2L

                ctrl.start(IEnvoyProxy.Snowflake, "")
                activeMethod = IEnvoyProxy.Snowflake
                val addr = ctrl.localAddress(IEnvoyProxy.Snowflake)
                awaitEndpoint(addr, IEnvoyProxy.Snowflake)
                StartedEndpoint("socks5", addr, IEnvoyProxy.Snowflake)
            }

            TransportKind.LYREBIRD_PT -> {
                val ptMethod = when (profile.extra["pt"]?.lowercase()) {
                    "meek", "meek_lite" -> IEnvoyProxy.MeekLite
                    "webtunnel" -> IEnvoyProxy.Webtunnel
                    else -> IEnvoyProxy.Obfs4
                }
                ctrl.start(ptMethod, "")
                activeMethod = ptMethod
                val addr = ctrl.localAddress(ptMethod)
                awaitEndpoint(addr, ptMethod)
                StartedEndpoint("socks5", addr, ptMethod)
            }

            else -> throw IllegalArgumentException("Unsupported IEnvoyProxy transport: ${profile.kind}")
        }
    }

    private fun awaitEndpoint(addr: String, method: String) {
        if (addr.isBlank() || !addr.contains(':')) {
            throw IllegalStateException("IEnvoyProxy $method returned empty local address")
        }
        val host = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toIntOrNull()
            ?: throw IllegalStateException("Invalid port from IEnvoyProxy: $addr")
        if (!ShadowsocksRunner.waitForTcpPort(host, port, 5500L)) {
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
    }
}
