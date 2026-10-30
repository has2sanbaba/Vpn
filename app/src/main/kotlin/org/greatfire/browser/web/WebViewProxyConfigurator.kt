package org.greatfire.browser.web

import android.annotation.SuppressLint
import android.content.Context
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
 * Routes Android WebView traffic cleanly through our local loopback gateway (`127.0.0.1:<port>`)
 * using official public AndroidX `ProxyController` APIs (zero hidden-API reflection).
 * On factory-old Android 5.0–6.0 WebViews lacking `PROXY_OVERRIDE`, `EnvoyWebViewClient` falls
 * back to `requiresClientInterception` cleanly.
 */
object WebViewProxyConfigurator {
    private const val TAG = "WebViewProxyConfig"

    @Volatile
    var requiresClientInterception: Boolean = false
        private set

    @Volatile
    private var lastAppliedPort: Int = -1

    @SuppressLint("RequiresFeature")
    suspend fun applyProxyToWebView(@Suppress("UNUSED_PARAMETER") context: Context, localHttpProxyPort: Int): Boolean {
        if (localHttpProxyPort <= 0) return false

        val host = "127.0.0.1"
        val proxyUrl = "http://$host:$localHttpProxyPort"

        System.setProperty("http.proxyHost", host)
        System.setProperty("http.proxyPort", localHttpProxyPort.toString())
        System.setProperty("https.proxyHost", host)
        System.setProperty("https.proxyPort", localHttpProxyPort.toString())

        if (lastAppliedPort == localHttpProxyPort) {
            return true
        }

        return withContext(Dispatchers.Main.immediate) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                try {
                    val configBuilder = ProxyConfig.Builder()
                        .addProxyRule(proxyUrl)

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
                    Log.i(TAG, "WebView ProxyController configured to $proxyUrl (applied=$applied)")
                    return@withContext true
                } catch (t: Throwable) {
                    Log.w(TAG, "ProxyController warning, enabling client interception: ${t.message}")
                }
            }

            requiresClientInterception = true
            false
        }
    }
}
