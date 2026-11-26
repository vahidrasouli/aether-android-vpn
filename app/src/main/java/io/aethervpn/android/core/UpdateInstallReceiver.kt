package io.aethervpn.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives [PackageInstaller] session commit callbacks.
 * Launches the single system confirmation prompt when [PackageInstaller.STATUS_PENDING_USER_ACTION]
 * is returned, and records completion/failure diagnostics.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != UpdateManager.ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching {
                        context.startActivity(confirmIntent)
                    }.onFailure { err ->
                        DiagnosticsLog.record("Updater", "Failed to launch install confirmation: ${err.message}")
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                DiagnosticsLog.record("Updater", "PackageInstaller update committed successfully")
                UpdateManager.cleanupInstalledApks(context.cacheDir)
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status=$status"
                DiagnosticsLog.record("Updater", "PackageInstaller failed ($status): $msg")
            }
        }
    }
}
