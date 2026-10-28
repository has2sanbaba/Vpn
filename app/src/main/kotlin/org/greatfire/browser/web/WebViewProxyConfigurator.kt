package org.greatfire.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Proxy
import android.os.Build
import android.os.Parcelable
import android.util.ArrayMap
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Configures Android WebView so 100% of web traffic routes through our local GreatFire Gateway:
 * 1. Modern WebViews (Android 5.0..16 with updated Chromium WebView): AndroidX `ProxyController`
 *    engine-level proxy override (covers GET, POST, Fetch, WebSockets, Media, DNS).
 * 2. Ancient factory Android 5.0..7.1 WebViews lacking `PROXY_OVERRIDE`: JVM proxy properties +
 *    Guardian-Project style `PROXY_CHANGE` reflection + `EnvoyWebViewClient` OkHttp/Cronet interception!
 */
object WebViewProxyConfigurator {
    private const val TAG = "WebViewProxyConfig"
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    var activeGatewayPort: Int = 0
        private set

    @Volatile
    var requiresClientInterception: Boolean = false
        private set

    fun applyProxyToWebView(context: Context, port: Int): Boolean {
        if (port <= 0) return false
        activeGatewayPort = port

        // Always set JVM proxy properties as an additional safety net for HttpURLConnection callers
        System.setProperty("http.proxyHost", "127.0.0.1")
        System.setProperty("http.proxyPort", port.toString())
        System.setProperty("https.proxyHost", "127.0.0.1")
        System.setProperty("https.proxyPort", port.toString())
        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", port.toString())

        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val latch = CountDownLatch(1)
            val builder = ProxyConfig.Builder()
                .addProxyRule("http://127.0.0.1:$port")
                .addProxyRule("socks5://127.0.0.1:$port")

            try {
                ProxyController.getInstance().setProxyOverride(
                    builder.build(),
                    executor
                ) {
                    latch.countDown()
                }
                latch.await(2500L, TimeUnit.MILLISECONDS)
                requiresClientInterception = false
                Log.i(TAG, "ProxyController.setProxyOverride applied -> 127.0.0.1:$port")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "ProxyController failed, activating legacy fallback", t)
            }
        }

        // Legacy fallback for very old Android 5.0 - 7.1 factory WebViews
        requiresClientInterception = true
        val reflected = applyLegacyReflectionProxy(context.applicationContext, "127.0.0.1", port)
        Log.i(TAG, "Legacy WebView proxy fallback active (reflection=$reflected, clientIntercept=true)")
        return true
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun applyLegacyReflectionProxy(appContext: Context, host: String, port: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return try {
            val appCls = Class.forName("android.app.Application")
            val mLoadedApkField = appCls.getDeclaredField("mLoadedApk")
            mLoadedApkField.isAccessible = true
            val loadedApk = mLoadedApkField.get(appContext)

            val loadedApkCls = Class.forName("android.app.LoadedApk")
            val mReceiversField = loadedApkCls.getDeclaredField("mReceivers")
            mReceiversField.isAccessible = true
            val receivers = mReceiversField.get(loadedApk) as? ArrayMap<*, *> ?: return false

            val proxyInfoCls = Class.forName("android.net.ProxyInfo")
            val buildDirectProxy = proxyInfoCls.getMethod(
                "buildDirectProxy",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            val proxyInfoObj = buildDirectProxy.invoke(null, host, port) as Parcelable

            for (receiverMap in receivers.values) {
                val map = receiverMap as? ArrayMap<*, *> ?: continue
                for (rec in map.keys) {
                    val recCls = rec?.javaClass ?: continue
                    if (recCls.name.contains("ProxyChangeListener")) {
                        val onReceive = recCls.getDeclaredMethod(
                            "onReceive",
                            Context::class.java,
                            Intent::class.java
                        )
                        val intent = Intent(Proxy.PROXY_CHANGE_ACTION).apply {
                            putExtra("android.intent.extra.PROXY_INFO", proxyInfoObj)
                        }
                        onReceive.invoke(rec, appContext, intent)
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
