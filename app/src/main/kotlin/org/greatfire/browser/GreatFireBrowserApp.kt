package org.greatfire.browser

import android.os.Build
import android.webkit.WebView
import androidx.multidex.MultiDexApplication
import org.greatfire.browser.vpn.TunnelManager

class GreatFireBrowserApp : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val proc = getProcessName()
            if (proc != null && proc != packageName) {
                runCatching { WebView.setDataDirectorySuffix(proc.substringAfterLast(':')) }
            }
        }

        // Start lightweight GreatFire FreeBrowser/Envoy local engine asynchronously in <25ms (0ms UI block!)
        TunnelManager.getInstance(this).initializeAutoStart()
    }

    companion object {
        @Volatile
        lateinit var instance: GreatFireBrowserApp
            private set
    }
}
