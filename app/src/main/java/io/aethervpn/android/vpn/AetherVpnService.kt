package io.aethervpn.android.vpn

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import io.aethervpn.android.R
import io.aethervpn.android.core.AetherEngineManager
import io.aethervpn.android.core.AetherProtocol
import io.aethervpn.android.core.DiagnosticsLog
import io.aethervpn.android.core.HevTunnelManager
import io.aethervpn.android.core.IdentityRepository
import io.aethervpn.android.core.SocketProtectorCallback
import io.aethervpn.android.core.SplitTunnelMode
import io.aethervpn.android.core.TrafficStats
import io.aethervpn.android.core.TunnelPhase
import io.aethervpn.android.core.TunnelSnapshot
import io.aethervpn.android.core.TunnelValidator
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.core.VpnStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

/**
 * Battery-first native Android full-device VpnService:
 *   Android TUN -> hev-socks5-tunnel (JNI) -> 127.0.0.1:1819 -> Aether Rust Engine (JNI) -> WARP
 *
 * Battery guarantees:
 * - Zero wake locks, zero busy-wait loops, zero periodic background polling while idle.
 * - Single managed Aether engine + single managed HEV tunnel.
 * - 1-shot smart fallback from MASQUE HTTP/3 -> MASQUE HTTP/2 if QUIC is blocked on the network.
 * - Health-checked network transitions: avoids tearing down and reconnecting if the tunnel is still healthy.
 * - Exponential backoff reconnect (2s -> 4s -> 8s -> 16s -> 30s cap).
 */
class AetherVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "io.aethervpn.android.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "io.aethervpn.android.ACTION_DISCONNECT"

        @Volatile
        private var instanceRef: WeakReference<AetherVpnService>? = null

        fun readLiveStats(): TrafficStats {
            return instanceRef?.get()?.hevManager?.readStats() ?: TrafficStats()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private lateinit var engineManager: AetherEngineManager
    private lateinit var hevManager: HevTunnelManager

    private var tunInterface: ParcelFileDescriptor? = null
    private var activeJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetworkId: Long = -1L
    private var networkLostDuringSession: Boolean = false

    @Volatile
    private var userRequestedStop: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        NotificationHelper.ensureChannel(this)
        engineManager = AetherEngineManager(filesDir)
        hevManager = HevTunnelManager(cacheDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                userRequestedStop = true
                DiagnosticsLog.record("VPN", "User requested disconnect")
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    teardownAll(clearError = true)
                    stopForegroundCompat()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_CONNECT, null -> {
                userRequestedStop = false
                startForegroundCompat(
                    getString(R.string.app_name),
                    getString(R.string.status_connecting)
                )
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    connectPipeline(isReconnect = false)
                }
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        userRequestedStop = true
        DiagnosticsLog.record("VPN", "VPN permission revoked by system")
        activeJob?.cancel()
        serviceScope.launch {
            teardownAll(clearError = true)
            stopForegroundCompat()
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        userRequestedStop = true
        unregisterNetworkCallback()
        hevManager.stop()
        closeTun()
        engineManager.stop()
        serviceScope.cancel()
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        super.onDestroy()
    }

    private fun formatException(t: Throwable?): String {
        if (t == null) return "Unknown error"
        val cls = t.javaClass.simpleName
        val msg = t.message ?: "No error message"
        val topFrame = t.stackTrace.firstOrNull()?.let { " (${it.fileName}:${it.lineNumber})" } ?: ""
        return "$cls: $msg$topFrame"
    }

    private suspend fun connectPipeline(isReconnect: Boolean) {
        val config = VpnConfig.load(this)
        val primaryProtocol = config.protocol

        val firstAttempt = attemptSingleProtocol(config, primaryProtocol, isReconnect)
        if (firstAttempt.isSuccess) return

        // Smart 1-shot battery-saving fallback: MASQUE HTTP/3 -> MASQUE HTTP/2 when QUIC is blocked
        if (!userRequestedStop &&
            primaryProtocol == AetherProtocol.MASQUE_H3 &&
            config.autoFallbackToH2
        ) {
            val firstErr = formatException(firstAttempt.exceptionOrNull())
            DiagnosticsLog.record("VPN", "MASQUE HTTP/3 failed ($firstErr); trying 1-shot HTTP/2 fallback")
            val fallbackAttempt = attemptSingleProtocol(config, AetherProtocol.MASQUE_H2, isReconnect)
            if (fallbackAttempt.isSuccess) return
            failClosed(
                formatException(fallbackAttempt.exceptionOrNull()),
                AetherProtocol.MASQUE_H2.label
            )
            return
        }

        failClosed(
            formatException(firstAttempt.exceptionOrNull()),
            primaryProtocol.label
        )
    }

    private suspend fun attemptSingleProtocol(
        config: VpnConfig,
        protocol: AetherProtocol,
        isReconnect: Boolean
    ): Result<Unit> = lifecycleMutex.withLock {
        try {
            VpnStateStore.update(
                TunnelSnapshot(
                    phase = if (isReconnect) TunnelPhase.RECONNECTING else TunnelPhase.CONNECTING,
                    protocolLabel = protocol.label
                )
            )
            updateNotification(
                getString(R.string.app_name),
                if (isReconnect) getString(R.string.status_reconnecting)
                else getString(R.string.status_connecting)
            )

            hevManager.stop()
            closeTun()
            engineManager.stop()

            // 1. Start single managed Aether Rust engine with VpnService.protect(fd) callback
            val protector = SocketProtectorCallback { fd -> protect(fd) }
            val engineResult = engineManager.start(config, protector, overrideProtocol = protocol)
            if (engineResult.isFailure) {
                return@withLock Result.failure(
                    engineResult.exceptionOrNull() ?: IllegalStateException("Aether engine failed")
                )
            }

            // 2. Establish dual-stack Android TUN interface
            val pfd = establishTun(config)
                ?: return@withLock Result.failure(
                    IllegalStateException("VpnService.Builder.establish() returned null")
                )
            tunInterface = pfd

            // 3. Start single managed HEV SOCKS5 Tunnel bridging TUN fd -> 127.0.0.1:1819
            val hevResult = hevManager.start(config, pfd.fd)
            if (hevResult.isFailure) {
                return@withLock Result.failure(
                    hevResult.exceptionOrNull() ?: IllegalStateException("HEV tunnel failed")
                )
            }

            // 4. End-to-end WARP verification before ever reporting CONNECTED
            VpnStateStore.update(
                TunnelSnapshot(
                    phase = TunnelPhase.VALIDATING,
                    protocolLabel = protocol.label
                )
            )
            updateNotification(getString(R.string.app_name), getString(R.string.status_validating))

            val validation = TunnelValidator.verifyThroughSocks(config.socksPort)
            if (validation.isFailure) {
                return@withLock Result.failure(
                    validation.exceptionOrNull() ?: IllegalStateException("End-to-end validation failed")
                )
            }

            val verified = validation.getOrThrow()
            val identity = IdentityRepository.load(filesDir)
            val gateway = config.manualPeer.ifBlank {
                identity.cachedGateway.ifBlank { identity.assignedEndpoint.ifBlank { "Auto (WARP)" } }
            }

            registerNetworkCallback(config.socksPort)
            DiagnosticsLog.record(
                "VPN",
                "Connected via ${protocol.label} | IP=${verified.publicIp} | WARP=${verified.warpStatus} | ${verified.latencyMs}ms"
            )

            VpnStateStore.update(
                TunnelSnapshot(
                    phase = TunnelPhase.CONNECTED,
                    protocolLabel = protocol.label,
                    gatewayLabel = gateway,
                    verifiedPublicIp = verified.publicIp,
                    warpStatus = verified.warpStatus,
                    colo = verified.colo,
                    latencyMs = verified.latencyMs,
                    connectedSinceMs = System.currentTimeMillis()
                )
            )
            updateNotification(
                getString(R.string.status_connected),
                "${verified.publicIp} • ${protocol.label}"
            )
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun establishTun(config: VpnConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Aether VPN")
            .setMtu(config.tunMtu)
            .addAddress(VpnConfig.TUN_IPV4_ADDRESS, VpnConfig.TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .setBlocking(false)

        val rawDns = config.customDns.trim()
        val parsedDns = when {
            rawDns.startsWith("[") -> rawDns.substringAfter("[").substringBefore("]")
            rawDns.count { it == ':' } == 1 -> rawDns.substringBefore(':')
            else -> rawDns
        }
        val isCustomV4 = parsedDns.count { it == '.' } == 3
        val isCustomV6 = parsedDns.contains(':') && !isCustomV4
        var dnsAdded = false
        if (isCustomV4 || (isCustomV6 && config.ipv6Enabled)) {
            val res = runCatching { builder.addDnsServer(parsedDns) }
            if (res.isSuccess) {
                dnsAdded = true
            } else {
                DiagnosticsLog.record("VPN", "Custom DNS ($parsedDns) rejected: ${res.exceptionOrNull()?.message}; using fallback")
            }
        }
        if (!dnsAdded) {
            builder.addDnsServer(VpnConfig.DNS_IPV4_PRIMARY)
            builder.addDnsServer(VpnConfig.DNS_IPV4_SECONDARY)
        }

        // Always route ::/0 into TUN (routes IPv6 when enabled, blackholes IPv6 when disabled to prevent leaks)
        builder
            .addAddress(VpnConfig.TUN_IPV6_ADDRESS, VpnConfig.TUN_IPV6_PREFIX)
            .addRoute("::", 0)
        if (config.ipv6Enabled) {
            builder.addDnsServer(VpnConfig.DNS_IPV6_PRIMARY)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        applyPerAppRouting(builder, config)
        return builder.establish()
    }

    private fun applyPerAppRouting(builder: Builder, config: VpnConfig) {
        // Enforce true kernel-level TUN bypass for Aether itself
        val bypassRes = runCatching { builder.addDisallowedApplication(packageName) }
        if (bypassRes.isSuccess) {
            DiagnosticsLog.record("VPN", "Kernel TUN bypass active ($packageName excluded from /dev/tun)")
        } else {
            DiagnosticsLog.record("VPN", "Warning: could not add disallowed app $packageName: ${bypassRes.exceptionOrNull()?.message}")
        }

        when (config.splitTunnelMode) {
            SplitTunnelMode.ALL_APPS -> Unit

            SplitTunnelMode.EXCLUDE_SELECTED -> {
                config.selectedPackages.forEach { pkg ->
                    if (pkg != packageName) runCatching { builder.addDisallowedApplication(pkg) }
                }
            }

            SplitTunnelMode.INCLUDE_ONLY_SELECTED -> {
                val targets = config.selectedPackages.filter { it != packageName }
                targets.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
            }
        }
    }

    private fun registerNetworkCallback(socksPort: Int) {
        unregisterNetworkCallback()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val netId = network.networkHandle
                if (activeNetworkId == -1L && !networkLostDuringSession) {
                    activeNetworkId = netId
                    return
                }
                val recoveringFromLoss = networkLostDuringSession
                networkLostDuringSession = false
                if ((recoveringFromLoss || netId != activeNetworkId) && !userRequestedStop) {
                    activeNetworkId = netId
                    // Battery-first check: avoid tearing down a healthy tunnel on transient network events
                    serviceScope.launch {
                        if (!recoveringFromLoss && TunnelValidator.isTunnelHealthy(socksPort)) {
                            DiagnosticsLog.record("Net", "Network changed but tunnel remains healthy; skipping reconnect")
                        } else {
                            DiagnosticsLog.record("Net", "Network transition requires reconnect; scheduling backoff")
                            scheduleReconnectWithBackoff()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                if (network.networkHandle == activeNetworkId && !userRequestedStop) {
                    activeNetworkId = -1L
                    networkLostDuringSession = true
                    DiagnosticsLog.record("Net", "Underlying network lost; awaiting new network")
                    VpnStateStore.update(
                        VpnStateStore.state.value.copy(phase = TunnelPhase.RECONNECTING)
                    )
                    updateNotification(
                        getString(R.string.app_name),
                        getString(R.string.status_reconnecting)
                    )
                }
            }
        }

        networkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        activeNetworkId = -1L
        networkLostDuringSession = false
        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }

    private fun scheduleReconnectWithBackoff() {
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            var delayMs = 2_000L
            val maxDelayMs = 30_000L
            for (attempt in 1..5) {
                if (userRequestedStop) return@launch
                delay(delayMs)
                if (userRequestedStop) return@launch
                DiagnosticsLog.record("VPN", "Reconnect attempt $attempt/5")
                connectPipeline(isReconnect = true)
                if (VpnStateStore.state.value.phase == TunnelPhase.CONNECTED) return@launch
                delayMs = (delayMs * 2L).coerceAtMost(maxDelayMs)
            }
        }
    }

    private suspend fun failClosed(reason: String, protocolLabel: String) {
        unregisterNetworkCallback()
        hevManager.stop()
        closeTun()
        engineManager.stop()
        VpnStateStore.reportError(reason, protocolLabel)
        stopForegroundCompat()
        stopSelf()
    }

    private suspend fun teardownAll(clearError: Boolean) {
        lifecycleMutex.withLock {
            unregisterNetworkCallback()
            hevManager.stop()
            closeTun()
            engineManager.stop()
            if (clearError) {
                VpnStateStore.resetDisconnected()
            }
        }
    }

    private fun closeTun() {
        val pfd = tunInterface
        tunInterface = null
        runCatching { pfd?.close() }
    }

    private fun startForegroundCompat(title: String, subtitle: String) {
        val notification = NotificationHelper.build(this, title, subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, subtitle: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm?.notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, title, subtitle))
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
