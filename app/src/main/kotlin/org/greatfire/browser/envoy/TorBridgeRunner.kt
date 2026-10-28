package org.greatfire.browser.envoy

import IEnvoyProxy.IEnvoyProxy
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.greatfire.browser.vpn.ProxyProfile
import org.greatfire.browser.vpn.TransportKind
import org.torproject.jni.TorService
import java.util.concurrent.atomic.AtomicReference

/**
 * Zero-Config Anti-Censorship Engine #2:
 * Chains GreatFire's `IEnvoyProxy` Pluggable Transports (`MeekLite` CDN77 domain fronting,
 * `Snowflake` CDN77/DataPacket WebRTC, and `Obfs4` Lyrebird) behind Guardian Project's official
 * `org.torproject.jni.TorService` (`tor-android:0.4.8.12`, `minSdk 19`, all 4 ABIs).
 *
 * Why both are required together:
 * `IEnvoyProxy`'s `MeekLite`, `Snowflake`, and `Obfs4` speak the Tor Pluggable Transport SOCKS
 * protocol (`pt.ListenSocks`), NOT plain HTTP/SOCKS5 to arbitrary websites. `TorService` runs
 * the real Tor onion client over `IEnvoyProxy`'s PT listener and exposes a genuine multi-hop
 * IP-hiding SOCKS5 proxy at `127.0.0.1:${TorService.socksPort}`!
 */
class TorBridgeRunner(
    private val appContext: Context,
    private val iepController: EnvoyProxyController
) {

    enum class BridgeMode {
        MEEK_CDN77,
        SNOWFLAKE_CDN77,
        OBFS4_LYREBIRD,
        CUSTOM_PT
    }

    @Volatile
    private var serviceConnection: ServiceConnection? = null

    @Volatile
    private var isBound: Boolean = false

    suspend fun start(
        profile: ProxyProfile,
        onStatusLine: ((String) -> Unit)? = null
    ): String {
        stop()

        val bridgeMode = when (profile.kind) {
            TransportKind.TOR_MEEK_BRIDGE -> BridgeMode.MEEK_CDN77
            TransportKind.SNOWFLAKE -> BridgeMode.SNOWFLAKE_CDN77
            TransportKind.TOR_OBFS4_BRIDGE -> BridgeMode.OBFS4_LYREBIRD
            TransportKind.LYREBIRD_PT -> BridgeMode.CUSTOM_PT
            else -> BridgeMode.MEEK_CDN77
        }

        val (ptName, ptMethod) = when (bridgeMode) {
            BridgeMode.MEEK_CDN77 -> "meek_lite" to IEnvoyProxy.MeekLite
            BridgeMode.SNOWFLAKE_CDN77 -> "snowflake" to IEnvoyProxy.Snowflake
            BridgeMode.OBFS4_LYREBIRD -> "obfs4" to IEnvoyProxy.Obfs4
            BridgeMode.CUSTOM_PT -> {
                when (profile.extra["pt"]?.lowercase()) {
                    "meek", "meek_lite" -> "meek_lite" to IEnvoyProxy.MeekLite
                    "webtunnel" -> "webtunnel" to IEnvoyProxy.Webtunnel
                    else -> "obfs4" to IEnvoyProxy.Obfs4
                }
            }
        }

        onStatusLine?.invoke("درحال راه‌اندازی پل ضد فیلتر GreatFire ($ptName)…")

        val ptLocalAddr = withContext(Dispatchers.IO) {
            iepController.startPluggableTransport(
                ptMethod = ptMethod,
                iceServers = profile.extra["ice"].orEmpty(),
                brokerUrl = profile.extra["broker"].orEmpty(),
                frontDomains = profile.frontHost,
                ampCacheUrl = profile.extra["ampcache"].orEmpty()
            )
        }

        val bridgeLines = buildBridgeLines(bridgeMode, ptName, profile)
        val torrcContent = buildString {
            appendLine("UseBridges 1")
            appendLine("ClientTransportPlugin $ptName socks5 $ptLocalAddr")
            bridgeLines.forEach { line ->
                appendLine("Bridge $line")
            }
            appendLine("AvoidDiskWrites 1")
            appendLine("SafeSocks 0")
            appendLine("ClientOnly 1")
        }

        val torrcFile = TorService.getTorrc(appContext)
        torrcFile.parentFile?.mkdirs()
        torrcFile.writeText(torrcContent, Charsets.UTF_8)

        onStatusLine?.invoke("درحال ساخت مدار رمزنگاری‌شده Tor از مسیر $ptName…")

        val latestStatus = AtomicReference("")

        val statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == TorService.ACTION_STATUS) {
                    val st = intent.getStringExtra(TorService.EXTRA_STATUS).orEmpty()
                    latestStatus.set(st)
                    Log.i(TAG, "TorService broadcast status=$st socksPort=${TorService.socksPort}")
                }
            }
        }

        val filter = IntentFilter(TorService.ACTION_STATUS)
        withContext(Dispatchers.Main.immediate) {
            LocalBroadcastManager.getInstance(appContext).registerReceiver(statusReceiver, filter)
            ContextCompat.registerReceiver(
                appContext,
                statusReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    Log.i(TAG, "Bound to org.torproject.jni.TorService")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "Disconnected from org.torproject.jni.TorService")
                }
            }
            serviceConnection = conn
            val bound = appContext.bindService(
                Intent(appContext, TorService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
            isBound = bound
            if (!bound) {
                throw IllegalStateException("خطا در اتصال به سرویس داخلی TorService")
            }
        }

        try {
            val timeoutMs = if (bridgeMode == BridgeMode.SNOWFLAKE_CDN77) 45_000L else 38_000L
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                val port = TorService.socksPort
                if (port > 0 && (latestStatus.get() == TorService.STATUS_ON || ShadowsocksRunner.waitForTcpPort("127.0.0.1", port, 250L))) {
                    if (latestStatus.get() == TorService.STATUS_ON) {
                        Log.i(TAG, "Tor + GreatFire $ptName circuit READY on 127.0.0.1:$port")
                        return "127.0.0.1:$port"
                    }
                }
                delay(350L)
            }

            // If Tor SOCKS port opened right as circuit completed even if broadcast lagged
            if (TorService.socksPort > 0 && ShadowsocksRunner.waitForTcpPort("127.0.0.1", TorService.socksPort, 500L)) {
                return "127.0.0.1:${TorService.socksPort}"
            }

            stop()
            throw IllegalStateException("زمان ساخت مدار Tor ($ptName) به پایان رسید")
        } finally {
            withContext(Dispatchers.Main.immediate) {
                runCatching { LocalBroadcastManager.getInstance(appContext).unregisterReceiver(statusReceiver) }
                runCatching { appContext.unregisterReceiver(statusReceiver) }
            }
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.Main.immediate) {
            serviceConnection?.let { conn ->
                if (isBound) {
                    runCatching { appContext.unbindService(conn) }
                }
            }
            serviceConnection = null
            isBound = false
            runCatching { appContext.stopService(Intent(appContext, TorService::class.java)) }
        }
        iepController.stopActive()

        // Allow TorService native thread a moment to release its control/SOCKS sockets cleanly
        var waited = 0
        while (TorService.socksPort > 0 && waited < 1500) {
            delay(150L)
            waited += 150
        }
    }

    private fun buildBridgeLines(mode: BridgeMode, ptName: String, profile: ProxyProfile): List<String> {
        // Allow user-supplied custom bridge line in rawUri if present
        val customLine = profile.extra["bridge"]?.trim().orEmpty()
        if (customLine.isNotEmpty()) {
            return listOf(customLine.removePrefix("Bridge ").trim())
        }

        return when (mode) {
            BridgeMode.MEEK_CDN77 -> listOf(
                "meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN"
            )

            BridgeMode.SNOWFLAKE_CDN77 -> listOf(
                "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
                    "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
                    "url=https://1098762253.rsc.cdn77.org/ " +
                    "fronts=app.datapacket.com,www.datapacket.com " +
                    "ice=${EnvoyProxyController.DEFAULT_SNOWFLAKE_STUNS} " +
                    "utls-imitate=hellorandomizedalpn",
                "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
                    "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
                    "url=https://1098762253.rsc.cdn77.org/ " +
                    "fronts=app.datapacket.com,www.datapacket.com " +
                    "ice=${EnvoyProxyController.DEFAULT_SNOWFLAKE_STUNS} " +
                    "utls-imitate=hellorandomizedalpn"
            )

            BridgeMode.OBFS4_LYREBIRD, BridgeMode.CUSTOM_PT -> if (ptName == "meek_lite") {
                listOf("meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN")
            } else {
                ORBOT_OBFS4_BRIDGES
            }
        }
    }

    companion object {
        private const val TAG = "TorBridgeRunner"

        // Official live Guardian Project Orbot 2026 built-in Obfs4 bridges
        private val ORBOT_OBFS4_BRIDGES = listOf(
            "obfs4 192.95.36.142:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 cert=qUVQ0srL1JI/vO6V6m/24anYXiJD3QP2HgzUKQtQ7GRqqUvs7P+tG43RtAqdhLOALP7DJQ iat-mode=1",
            "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0",
            "obfs4 85.31.186.98:443 011F2599C0E9B27EE74B353155E244813763C3E5 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=0",
            "obfs4 85.31.186.26:443 91A6354697E6B02A386312F68D82CF86824D3606 cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=0",
            "obfs4 193.11.166.194:27015 2D82C2E354D531A68469ADF7F878FA6060C6BACA cert=4TLQPJrTSaDffMK7Nbao6LC7G9OW/NHkUwIdjLSS3KYf0Nv4/nQiiI8dY2TcsQx01NniOg iat-mode=0",
            "obfs4 193.11.166.194:27020 86AC7B8D430DAC4117E9F42C9EAED18133863AAF cert=0LDeJH4JzMDtkJJrFphJCiPqKx7loozKN7VNfuukMGfHO0Z8OGdzHVkhVAOfo1mUdv9cMg iat-mode=0",
            "obfs4 193.11.166.194:27025 1AE2C08904527FEA90C4C4F8C1083EA59FBC6FAF cert=ItvYZzW5tn6v3G4UnQa6Qz04Npro6e81AP70YujmK/KXwDFPTs3aHXcHp4n8Vt6w/bv8cA iat-mode=0"
        )
    }
}
