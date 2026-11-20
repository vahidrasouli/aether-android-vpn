package io.aethervpn.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import io.aethervpn.android.core.AppThemeMode
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.vpn.NotificationHelper

class AetherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        applyTheme(VpnConfig.load(this).themeMode)
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
