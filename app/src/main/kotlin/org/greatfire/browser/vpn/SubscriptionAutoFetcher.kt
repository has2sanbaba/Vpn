package org.greatfire.browser.vpn

import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Fetches fresh public anti-censorship servers (VLESS-Reality, VLESS-WS-CDN, Trojan, Hysteria2, SS)
 * from open GitHub community aggregators OVER our local Anti-DPI + DoH + TLS-Fragmentation gateway
 * (`libgfproxy.so`), so `raw.githubusercontent.com` succeeds even when SNI/DNS-filtered by the ISP!
 */
object SubscriptionAutoFetcher {
    private const val TAG = "SubscriptionAutoFetcher"

    private val PUBLIC_SUBSCRIPTION_FEEDS = listOf(
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt",
        "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/Eternity",
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_base64_Sub.txt"
    )

    fun fetchFreshPublicProfiles(gatewayPort: Int, maxProfiles: Int = 25): List<ProxyProfile> {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        if (gatewayPort > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", gatewayPort)))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            val sf = LegacySslTrustBridge.sslSocketFactory
            val tm = LegacySslTrustBridge.trustManager
            if (sf != null && tm != null) {
                builder.sslSocketFactory(sf, tm)
            }
        }

        val client = builder.build()
        val collected = LinkedHashMap<String, ProxyProfile>()

        for (feedUrl in PUBLIC_SUBSCRIPTION_FEEDS) {
            try {
                val req = Request.Builder()
                    .url(feedUrl)
                    .header("User-Agent", "GreatFireEnvoyBrowser/2.0")
                    .build()

                val text = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use ""
                    resp.body?.string().orEmpty()
                }
                if (text.isBlank()) continue

                val parsed = ProxyProfileParser.parseMany(text)
                // Prioritize VLESS-Reality, Hysteria2, Trojan-TLS, and port 443 CDN WebSockets
                val prioritized = parsed.sortedBy { scoreProfileForCensorship(it) }
                for (p in prioritized) {
                    val key = "${p.kind}:${p.host}:${p.port}:${p.uuid.ifBlank { p.password }}"
                    if (!collected.containsKey(key)) {
                        collected[key] = p.copy(name = "🌐 ${p.name.take(42)}")
                    }
                    if (collected.size >= maxProfiles) {
                        return collected.values.toList()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Feed fetch failed ($feedUrl): ${t.message}")
            }
        }

        return collected.values.toList()
    }

    private fun scoreProfileForCensorship(p: ProxyProfile): Int {
        val sec = p.extra["security"].orEmpty()
        val net = p.extra["type"].orEmpty()
        return when {
            p.kind == TransportKind.VLESS && sec == "reality" -> 1
            p.kind == TransportKind.HYSTERIA2 -> 2
            p.kind == TransportKind.VLESS && net == "ws" && p.port == 443 -> 3
            p.kind == TransportKind.TROJAN && p.port == 443 -> 4
            p.kind == TransportKind.TUIC -> 5
            p.kind == TransportKind.V2RAY_WS && p.port == 443 -> 6
            p.kind == TransportKind.SHADOWSOCKS -> 7
            else -> 10
        }
    }
}
