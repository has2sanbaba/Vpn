package acr.browser.lightning.browser.proxy

import acr.browser.lightning.browser.BrowserActivity
import acr.browser.lightning.utils.ProxyUtils
import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.greatfire.browser.vpn.TunnelManager
import org.greatfire.browser.web.WebViewProxyConfigurator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates WebView proxy binding & foreground/background power-saving lifecycle
 * for the in-process JNI anti-censorship engine.
 */
@Singleton
class ProxyAdapter @Inject constructor(
    private val proxyUtils: ProxyUtils
) : Application.ActivityLifecycleCallbacks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startedBrowserActivities = 0

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        if (activity !is BrowserActivity) return
        startedBrowserActivities++
        val tm = TunnelManager.getInstance(activity)
        tm.setForeground(true)
        val port = tm.gatewayPort
        if (port > 0) {
            scope.launch {
                WebViewProxyConfigurator.applyProxyToWebView(activity.applicationContext, port)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is BrowserActivity) return
        proxyUtils.checkForProxy(activity)
        proxyUtils.updateProxySettings(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        if (activity !is BrowserActivity) return
        startedBrowserActivities = (startedBrowserActivities - 1).coerceAtLeast(0)
        if (startedBrowserActivities == 0) {
            TunnelManager.getInstance(activity).setForeground(false)
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
