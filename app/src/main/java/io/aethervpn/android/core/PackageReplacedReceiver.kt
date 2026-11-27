package io.aethervpn.android.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.aethervpn.android.BuildConfig
import io.aethervpn.android.R
import io.aethervpn.android.ui.MainActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Automatically relaunches [MainActivity] cleanly after the app updates itself
 * (`android.intent.action.MY_PACKAGE_REPLACED`), with a safe fallback notification
 * if Android blocks background activity launch.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appCacheDir = context.applicationContext.cacheDir
        CoroutineScope(Dispatchers.IO).launch {
            UpdateManager.cleanupInstalledApks(appCacheDir)
        }
        DiagnosticsLog.record("Updater", "App updated to v${BuildConfig.VERSION_NAME}; relaunching MainActivity")

        val launchIntent = buildRelaunchIntent(context)
        val launched = runCatching {
            context.startActivity(launchIntent)
        }.isSuccess

        if (!launched) {
            showFallbackNotification(context, launchIntent)
        }
    }

    private fun showFallbackNotification(context: Context, launchIntent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when Aether VPN finishes updating"
            }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Updated to v${BuildConfig.VERSION_NAME} — tap to open")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            nm.notify(UPDATE_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UPDATE_CHANNEL_ID = "aether_update_channel"
        const val UPDATE_NOTIFICATION_ID = 1002

        fun buildRelaunchIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
    }
}
