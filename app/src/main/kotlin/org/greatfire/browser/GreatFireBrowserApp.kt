package org.greatfire.browser

import android.os.Build
import android.webkit.WebView
import androidx.multidex.MultiDexApplication
import org.greatfire.browser.vpn.LegacySslTrustBridge
import org.greatfire.browser.vpn.TunnelManager
import java.io.File

class GreatFireBrowserApp : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val proc = getProcessNameSafe()
            if (proc != null && proc != packageName) {
                runCatching { WebView.setDataDirectorySuffix(proc.substringAfterLast(':')) }
            }
        }

        // Copy default GreatFire FreeBrowser fb.config template to app filesDir if absent
        ensureDefaultFbConfig()

        // Initialize Let's Encrypt ISRG Root X1/X2 trust bridge for legacy Android 5.0 - 7.0
        LegacySslTrustBridge.initialize(this)

        // Boot GreatFire Unified Anti-Censorship Gateway immediately so zero traffic leaks at startup
        TunnelManager.getInstance(this).initializeAutoStart()
    }

    private fun ensureDefaultFbConfig() {
        runCatching {
            val out = File(filesDir, "fb.config")
            if (!out.exists()) {
                assets.open("fb.config").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun getProcessNameSafe(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            null
        }
    }

    companion object {
        @Volatile
        lateinit var instance: GreatFireBrowserApp
            private set
    }
}
