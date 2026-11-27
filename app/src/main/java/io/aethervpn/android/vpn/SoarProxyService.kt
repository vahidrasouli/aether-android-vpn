package io.aethervpn.android.vpn

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.aethervpn.android.R
import io.aethervpn.android.core.DiagnosticsLog
import io.aethervpn.android.core.SoarConnectionMode
import io.aethervpn.android.core.SoarEngineManager
import io.aethervpn.android.core.SoarPhase
import io.aethervpn.android.core.SoarProfileStore
import io.aethervpn.android.core.SoarRuntimeState
import io.aethervpn.android.core.SoarStateStore
import io.aethervpn.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lightweight Foreground Service for Soar `Proxy Only` mode.
 * Runs `SoarEngineManager` on 127.0.0.1:1829 without starting VpnService or HEV TUN.
 */
class SoarProxyService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private lateinit var soarEngine: SoarEngineManager
    private var startJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        soarEngine = SoarEngineManager(filesDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                startJob?.cancel()
                serviceScope.launch {
                    stopInternal()
                }
            }
            ACTION_START, null -> {
                promoteForeground("Starting Soar SOCKS5 Proxy…")
                startJob?.cancel()
                startJob = serviceScope.launch {
                    startInternal()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startInternal() = lifecycleMutex.withLock {
        val state = SoarProfileStore.load(filesDir)
        val profile = state.activeProfile
        if (profile == null) {
            SoarStateStore.reportError("No active Soar profile selected", SoarConnectionMode.PROXY_ONLY)
            stopSelfCleanly()
            return
        }

        SoarStateStore.update(
            SoarRuntimeState(
                phase = SoarPhase.STARTING,
                mode = SoarConnectionMode.PROXY_ONLY,
                profileName = profile.name
            )
        )

        val result = soarEngine.start(
            context = this@SoarProxyService,
            profile = profile,
            mode = SoarConnectionMode.PROXY_ONLY
        )
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "Failed to start Soar proxy"
            SoarStateStore.reportError(msg, SoarConnectionMode.PROXY_ONLY)
            stopSelfCleanly()
            return
        }

        val listenAddr = "${SoarEngineManager.DEFAULT_LISTEN_HOST}:${SoarEngineManager.DEFAULT_LISTEN_PORT}"
        promoteForeground("Soar SOCKS5 Proxy active on $listenAddr")
        SoarStateStore.update(
            SoarRuntimeState(
                phase = SoarPhase.CONNECTED,
                mode = SoarConnectionMode.PROXY_ONLY,
                profileName = profile.name,
                listenAddress = listenAddr
            )
        )
    }

    private suspend fun stopInternal() = lifecycleMutex.withLock {
        SoarStateStore.update(
            SoarStateStore.state.value.copy(phase = SoarPhase.DISCONNECTING)
        )
        soarEngine.stop()
        SoarStateStore.resetStopped(SoarConnectionMode.PROXY_ONLY)
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteForeground(subtitle: String) {
        val notification = buildNotification(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SOAR_TAB, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, SoarProxyService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.soar_notification_title))
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_stop),
                stopIntent
            )
            .build()
    }

    override fun onDestroy() {
        startJob?.cancel()
        soarEngine.stop()
        if (SoarStateStore.state.value.mode == SoarConnectionMode.PROXY_ONLY &&
            SoarStateStore.state.value.phase != SoarPhase.ERROR
        ) {
            SoarStateStore.resetStopped(SoarConnectionMode.PROXY_ONLY)
        }
        serviceScope.cancel()
        DiagnosticsLog.record("Soar", "SoarProxyService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "io.aethervpn.android.SOAR_PROXY_START"
        const val ACTION_STOP = "io.aethervpn.android.SOAR_PROXY_STOP"
        const val EXTRA_OPEN_SOAR_TAB = "extra_open_soar_tab"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, SoarProxyService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SoarProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
