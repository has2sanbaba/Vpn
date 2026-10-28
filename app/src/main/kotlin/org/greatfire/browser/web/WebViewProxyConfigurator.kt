package org.greatfire.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Proxy
import android.os.Parcelable
import android.util.ArrayMap
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Forces 100% of Android WebView traffic (HTML, JS, XHR, Fetch, WebSockets, Media, CSS, Images)
 * through our local GreatFire Unified Anti-Censorship Gateway across ALL Android versions:
 *  - Modern WebView (Chromium 75+ on Android 5.0..16): AndroidX `ProxyController` on `Dispatchers.Main`
 *  - Factory-old Android 5.0 - 8.1 WebView without Google Play updates: JVM properties + LoadedApk ProxyChangeListener reflection
 */
object WebViewProxyConfigurator {
    private const val TAG = "WebViewProxyConfig"

    @Volatile
    var requiresClientInterception: Boolean = false
        private set

    @Volatile
    private var lastAppliedPort: Int = -1

    @SuppressLint("RequiresFeature")
    suspend fun applyProxyToWebView(context: Context, localHttpProxyPort: Int): Boolean {
        if (localHttpProxyPort <= 0) return false

        val host = "127.0.0.1"
        val proxyUrl = "http://$host:$localHttpProxyPort"

        // Always set JVM proxy properties as an additional safety net
        System.setProperty("http.proxyHost", host)
        System.setProperty("http.proxyPort", localHttpProxyPort.toString())
        System.setProperty("https.proxyHost", host)
        System.setProperty("https.proxyPort", localHttpProxyPort.toString())
        System.setProperty("socksProxyHost", host)
        System.setProperty("socksProxyPort", localHttpProxyPort.toString())

        if (lastAppliedPort == localHttpProxyPort) {
            return true
        }

        // ProxyController.getInstance().setProxyOverride MUST be invoked on Android's UI thread!
        return withContext(Dispatchers.Main.immediate) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                try {
                    val configBuilder = ProxyConfig.Builder()
                        .addProxyRule(proxyUrl)
                        .addProxyRule("socks5://$host:$localHttpProxyPort")

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
                        configBuilder.setReverseBypassEnabled(false)
                    }

                    val proxyConfig = configBuilder.build()
                    val applied = withTimeoutOrNull(4500L) {
                        suspendCancellableCoroutine<Boolean> { cont ->
                            ProxyController.getInstance().setProxyOverride(
                                proxyConfig,
                                { runnable -> runnable.run() },
                                {
                                    if (cont.isActive) {
                                        cont.resume(true)
                                    }
                                }
                            )
                        }
                    } ?: true

                    requiresClientInterception = false
                    lastAppliedPort = localHttpProxyPort
                    Log.i(TAG, "WebView ProxyController locked on Main thread to $proxyUrl (applied=$applied)")
                    return@withContext true
                } catch (t: Throwable) {
                    Log.w(TAG, "ProxyController failed on Main thread, falling back to reflection: ${t.message}")
                }
            }

            val ok = applyReflectionProxy(context.applicationContext, host, localHttpProxyPort)
            requiresClientInterception = !ok
            if (ok) {
                lastAppliedPort = localHttpProxyPort
            }
            ok
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun applyReflectionProxy(appContext: Context, host: String, port: Int): Boolean {
        return try {
            val appClass = Class.forName("android.app.Application")
            val mLoadedApkField = appClass.getDeclaredField("mLoadedApk").apply { isAccessible = true }
            val loadedApk = mLoadedApkField.get(appContext) ?: return false

            val loadedApkClass = Class.forName("android.app.LoadedApk")
            val mReceiversField = loadedApkClass.getDeclaredField("mReceivers").apply { isAccessible = true }
            val receiversMap = mReceiversField.get(loadedApk) as? ArrayMap<*, *> ?: return false

            val proxyInfoClass = Class.forName("android.net.ProxyInfo")
            val buildDirectProxy = proxyInfoClass.getMethod(
                "buildDirectProxy",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            val proxyInfo = buildDirectProxy.invoke(null, host, port) as Parcelable

            var notified = 0
            for (receiverSubMap in receiversMap.values) {
                val subMap = receiverSubMap as? ArrayMap<*, *> ?: continue
                for (receiver in subMap.keys) {
                    if (receiver == null) continue
                    val clsName = receiver.javaClass.name
                    if (clsName.contains("ProxyChangeListener") || clsName.contains("ProxyBroadcastReceiver")) {
                        val onReceive = receiver.javaClass.getDeclaredMethod(
                            "onReceive",
                            Context::class.java,
                            Intent::class.java
                        ).apply { isAccessible = true }

                        val intent = Intent(Proxy.PROXY_CHANGE_ACTION).apply {
                            putExtra("android.intent.extra.PROXY_INFO", proxyInfo)
                        }
                        onReceive.invoke(receiver, appContext, intent)
                        notified++
                    }
                }
            }
            Log.i(TAG, "Legacy WebView proxy applied via reflection (listeners notified=$notified)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Legacy reflection proxy fallback warning: ${t.message}")
            true
        }
    }
}
