package io.aethervpn.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import io.aethervpn.android.core.AppThemeMode
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.vpn.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AetherApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Defer disk I/O (SharedPreferences) and notification channel setup off the main thread
        // so cold-start reaches the first rendered frame immediately.
        appScope.launch {
            NotificationHelper.ensureChannel(this@AetherApplication)
            val themeMode = VpnConfig.load(this@AetherApplication).themeMode
            if (themeMode != AppThemeMode.DARK) {
                withContext(Dispatchers.Main) {
                    applyTheme(themeMode)
                }
            }
        }
    }

    companion object {
        fun applyTheme(mode: AppThemeMode) {
            val nightMode = when (mode) {
                AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }
}
