package org.greatfire.browser.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.greatfire.browser.R
import org.greatfire.browser.ui.MainActivity

class GreatFireTunnelService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            TunnelManager.getInstance(applicationContext).disconnect()
            stopSelf()
            return START_NOT_STICKY
        }

        val profileName = intent?.getStringExtra(EXTRA_PROFILE_NAME)
            ?: getString(R.string.app_name)

        ensureChannel()
        val notification = buildNotification(profileName)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            // Graceful fallback on restricted background start contexts
        }

        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(profileName: String): Notification {
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            pendingFlags
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, GreatFireTunnelService::class.java).apply { action = ACTION_DISCONNECT },
            pendingFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_ok)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(profileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_disconnect), disconnectIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gf_envoy_tunnel"
        private const val NOTIFICATION_ID = 4401
        private const val EXTRA_PROFILE_NAME = "profile_name"
        private const val ACTION_DISCONNECT = "org.greatfire.browser.DISCONNECT_TUNNEL"

        fun start(context: Context, profileName: String) {
            val intent = Intent(context, GreatFireTunnelService::class.java).apply {
                putExtra(EXTRA_PROFILE_NAME, profileName)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, GreatFireTunnelService::class.java))
            }
        }
    }
}
