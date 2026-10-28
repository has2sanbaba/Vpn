package org.greatfire.browser

import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication
import org.greatfire.browser.data.BrowserDataStore
import org.greatfire.browser.vpn.TunnelManager

class GreatFireBrowserApp : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        runCatching {
            val theme = BrowserDataStore.getInstance(this).themeMode
            AppCompatDelegate.setDefaultNightMode(theme.nightMode)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val proc = getProcessName()
            if (proc != null && proc != packageName) {
                runCatching { WebView.setDataDirectorySuffix(proc.substringAfterLast(':')) }
            }
        }

        // Boot lightweight native proxy & auto-connect to fastest server for user's ISP in background
        TunnelManager.getInstance(this).initializeAutoStart()
    }

    companion object {
        @Volatile
        lateinit var instance: GreatFireBrowserApp
            private set
    }
}
