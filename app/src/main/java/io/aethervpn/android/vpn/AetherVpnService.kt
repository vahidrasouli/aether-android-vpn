package io.aethervpn.android.vpn

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import io.aethervpn.android.R
import io.aethervpn.android.core.AetherEngineManager
import io.aethervpn.android.core.AetherProtocol
import io.aethervpn.android.core.ChainMode
import io.aethervpn.android.core.ChainOrchestrator
import io.aethervpn.android.core.CidrRoutePlanner
import io.aethervpn.android.core.DiagnosticsLog
import io.aethervpn.android.core.HevTunnelManager
import io.aethervpn.android.core.IdentityRepository
import io.aethervpn.android.core.PsiphonMode
import io.aethervpn.android.core.SingBoxEngineManager
import io.aethervpn.android.core.SingBoxProfileRepository
import io.aethervpn.android.core.SoarConnectionMode
import io.aethervpn.android.core.SoarEngineManager
import io.aethervpn.android.core.SoarPhase
import io.aethervpn.android.core.SoarProfileStore
import io.aethervpn.android.core.SoarResolverPackStore
import io.aethervpn.android.core.SoarRuntimeState
import io.aethervpn.android.core.SoarStateStore
import io.aethervpn.android.core.SocketProtectorCallback
import io.aethervpn.android.core.SplitTunnelMode
import io.aethervpn.android.core.SplitTunnelPlanner
import io.aethervpn.android.core.StructuredUpstreamProxy
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        const val ACTION_START_CORE = "io.aethervpn.android.ACTION_START_CORE"
        const val ACTION_STOP_CORE = "io.aethervpn.android.ACTION_STOP_CORE"
        const val ACTION_START_TUN = "io.aethervpn.android.ACTION_START_TUN"
        const val ACTION_STOP_TUN = "io.aethervpn.android.ACTION_STOP_TUN"
        const val ACTION_START_SOAR_VPN = "io.aethervpn.android.ACTION_START_SOAR_VPN"
        const val ACTION_STOP_SOAR_VPN = "io.aethervpn.android.ACTION_STOP_SOAR_VPN"

        @Volatile
        private var instanceRef: WeakReference<AetherVpnService>? = null

        internal fun shouldFallbackToMasqueH2(
            config: VpnConfig,
            primaryProtocol: AetherProtocol,
            userRequestedStop: Boolean
        ): Boolean {
            if (userRequestedStop) return false
            if (config.singBoxEnabled) {
                if (!config.chainMode.requiresWarp) return false
            } else if (config.psiphonMode == PsiphonMode.PSIPHON_ONLY) {
                return false
            }
            if (primaryProtocol == AetherProtocol.MASQUE_H2) return false
            return config.upstreamProxy.isNotBlank() ||
                (primaryProtocol == AetherProtocol.MASQUE_H3 && config.autoFallbackToH2)
        }

        fun readLiveStats(): TrafficStats {
            return instanceRef?.get()?.hevManager?.readStats() ?: TrafficStats()
        }

        internal fun resolveIpv4DnsServers(config: VpnConfig): List<String> {
            if (config.useMapDns) {
                return listOf(config.effectiveMapDnsAddress)
            }
            val rawDns = config.customDns.trim()
            val parsedDns = when {
                rawDns.startsWith("[") -> rawDns.substringAfter("[").substringBefore("]")
                rawDns.count { it == ':' } == 1 -> rawDns.substringBefore(':')
                else -> rawDns
            }
            val isCustomV4 = parsedDns.count { it == '.' } == 3
            val isCustomV6 = parsedDns.contains(':') && !isCustomV4
            if (isCustomV4 || (isCustomV6 && config.ipv6Enabled)) {
                return listOf(parsedDns)
            }
            return if (config.isSingBoxOnlyActive) {
                listOf(VpnConfig.DNS_IPV4_PRIMARY, "8.8.8.8")
            } else {
                listOf(VpnConfig.DNS_IPV4_PRIMARY, VpnConfig.DNS_IPV4_SECONDARY)
            }
        }

        fun startVpn(context: android.content.Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_CONNECT }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopVpn(context: android.content.Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_DISCONNECT }
            context.startService(intent)
        }

        fun startCoreOnly(context: android.content.Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_START_CORE }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopCoreOnly(context: android.content.Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_STOP_CORE }
            context.startService(intent)
        }

        fun startTunOnly(context: android.content.Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_START_TUN }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopTunOnly(context: android.content.Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_STOP_TUN }
            context.startService(intent)
        }

        fun startSoarVpn(context: android.content.Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_START_SOAR_VPN }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopSoarVpn(context: android.content.Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_STOP_SOAR_VPN }
            context.startService(intent)
        }

        internal fun soarDisallowedPackages(config: VpnConfig, selfPackageName: String): Set<String> {
            val out = linkedSetOf(selfPackageName)
            if (config.splitTunnelMode == SplitTunnelMode.BYPASS_SELECTED) {
                config.selectedPackages.map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
            }
            config.proxyBypassApps.map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
            return out
        }

        internal fun generateSoarHevYaml(hevManager: HevTunnelManager, config: VpnConfig): String {
            return hevManager.generateYamlConfig(
                config = config,
                tunIpv4 = SoarEngineManager.TUN_IPV4_ADDRESS,
                socksPort = SoarEngineManager.DEFAULT_LISTEN_PORT,
                mapDnsAddress = SoarEngineManager.TUN_MAPDNS_ADDRESS,
                mapDnsNetwork = SoarEngineManager.TUN_MAPDNS_NETWORK,
                mapDnsNetmask = SoarEngineManager.TUN_MAPDNS_NETMASK,
                forceMapDns = true
            )
        }

        /**
         * Executes an instant, non-blocking disconnect sequence:
         * 1. Cancels all active connect/reconnect/speed-test coroutines immediately.
         * 2. Closes the TUN ParcelFileDescriptor first so device traffic stops routing immediately.
         * 3. Resets UI/notification state to Disconnected without waiting on engine shutdown.
         * 4. Signals termination across all engine managers in parallel.
         */
        internal suspend fun performInstantDisconnect(
            jobsToCancel: List<Job?>,
            closeTunFirst: () -> Unit,
            onImmediateStateReset: () -> Unit,
            engineStoppers: List<() -> Unit>
        ) {
            jobsToCancel.forEach { it?.cancel() }
            runCatching { closeTunFirst() }
            runCatching { onImmediateStateReset() }
            coroutineScope {
                engineStoppers.map { stopFn ->
                    async(Dispatchers.IO) {
                        runCatching { stopFn() }
                    }
                }.awaitAll()
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private lateinit var engineManager: AetherEngineManager
    private lateinit var singBoxManager: SingBoxEngineManager
    private lateinit var soarManager: SoarEngineManager
    private lateinit var hevManager: HevTunnelManager

    private var tunInterface: ParcelFileDescriptor? = null
    private var activeJob: Job? = null
    private var reconnectJob: Job? = null
    private val backoffDelaysMs = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetworkId: Long = -1L
    private var networkLostDuringSession: Boolean = false
    private var upstreamWatchdog: UpstreamProxyWatchdog? = null
    private var watchdogListener: ((String, String) -> Unit)? = null

    @Volatile
    private var isCoreRunning: Boolean = false

    @Volatile
    private var isTunRunning: Boolean = false

    @Volatile
    private var userRequestedStop: Boolean = false

    @Volatile
    private var lastSingBoxStartedFirst: Boolean = true

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        NotificationHelper.ensureChannel(this)
        engineManager = AetherEngineManager(filesDir)
        singBoxManager = SingBoxEngineManager(filesDir)
        soarManager = SoarEngineManager(filesDir)
        hevManager = HevTunnelManager(cacheDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                userRequestedStop = true
                DiagnosticsLog.record("System", "User requested disconnect")
                val pendingJobs = listOf(activeJob, reconnectJob)
                activeJob = null
                reconnectJob = null
                pendingJobs.forEach { it?.cancel() }
                closeTun()
                stopUpstreamWatchdog()
                unregisterNetworkCallback()
                VpnStateStore.resetDisconnected()
                if (SoarStateStore.state.value.mode == SoarConnectionMode.VPN_FULL_DEVICE) {
                    SoarStateStore.resetStopped(SoarConnectionMode.VPN_FULL_DEVICE)
                }
                stopForegroundCompat()
                activeJob = serviceScope.launch {
                    teardownAll(clearError = true)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_START_CORE -> {
                userRequestedStop = false
                val cfg = VpnConfig.load(this)
                startForegroundCompat("Aether Diagnostic Core", "Listening on 127.0.0.1:${cfg.effectiveSocksPort}")
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    startCoreStandalone()
                }
                return START_STICKY
            }

            ACTION_STOP_CORE -> {
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    stopCoreStandalone()
                }
                return START_NOT_STICKY
            }

            ACTION_START_TUN -> {
                userRequestedStop = false
                val cfg = VpnConfig.load(this)
                startForegroundCompat("Aether Diagnostic TUN", "Relaying to 127.0.0.1:${cfg.effectiveSocksPort}")
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    startTunStandalone()
                }
                return START_STICKY
            }

            ACTION_STOP_TUN -> {
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    stopTunStandalone()
                }
                return START_NOT_STICKY
            }

            ACTION_START_SOAR_VPN -> {
                userRequestedStop = false
                SoarStateStore.update(
                    SoarRuntimeState(
                        phase = SoarPhase.STARTING,
                        mode = SoarConnectionMode.VPN_FULL_DEVICE
                    )
                )
                startForegroundCompat("Soar DNS Tunnel VPN", "Starting TUN + Soar…")
                activeJob?.cancel()
                reconnectJob?.cancel()
                activeJob = serviceScope.launch {
                    connectSoarVpnPipeline()
                }
                return START_STICKY
            }

            ACTION_STOP_SOAR_VPN -> {
                userRequestedStop = true
                val pendingJobs = listOf(activeJob, reconnectJob)
                activeJob = null
                reconnectJob = null
                pendingJobs.forEach { it?.cancel() }
                closeTun()
                stopUpstreamWatchdog()
                unregisterNetworkCallback()
                SoarStateStore.resetStopped(SoarConnectionMode.VPN_FULL_DEVICE)
                VpnStateStore.resetDisconnected()
                stopForegroundCompat()
                activeJob = serviceScope.launch {
                    teardownAll(clearError = true)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_CONNECT, null -> {
                userRequestedStop = false
                VpnStateStore.update(
                    VpnStateStore.state.value.copy(
                        phase = TunnelPhase.STARTING_CORE,
                        errorMessage = null
                    )
                )
                startForegroundCompat(
                    getString(R.string.app_name),
                    getString(R.string.status_connecting)
                )
                activeJob?.cancel()
                reconnectJob?.cancel()
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
        DiagnosticsLog.record("System", "VPN permission revoked by system")
        activeJob?.cancel()
        serviceScope.launch {
            teardownAll(clearError = true)
            stopForegroundCompat()
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                DiagnosticsLog.record("System", "Memory pressure (level=$level); trimming log buffer to protect VPN service")
                DiagnosticsLog.trim(keepCount = 25)
            }
        }
    }

    override fun onDestroy() {
        userRequestedStop = true
        activeJob?.cancel()
        reconnectJob?.cancel()
        stopUpstreamWatchdog()
        unregisterNetworkCallback()
        hevManager.stop()
        closeTun()
        stopEnginesInReverseOrder()
        isCoreRunning = false
        isTunRunning = false
        runCatching { setUnderlyingNetworks(null) }
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
        val activeProfile = if (config.singBoxEnabled) {
            SingBoxProfileRepository.load(filesDir).activeProfile
        } else {
            null
        }
        DiagnosticsLog.record("Chain", ChainOrchestrator.formatTopology(config, activeProfile))

        if (config.singBoxEnabled) {
            val sbAttempt = attemptSingBoxChain(config, primaryProtocol, isReconnect)
            if (sbAttempt.isSuccess) return
            if (shouldFallbackToMasqueH2(config, primaryProtocol, userRequestedStop)) {
                val firstErr = formatException(sbAttempt.exceptionOrNull())
                DiagnosticsLog.record("Aether", "Chain ${config.chainMode.label} failed with ${primaryProtocol.label} ($firstErr); trying 1-shot MASQUE HTTP/2 fallback")
                val fallbackAttempt = attemptSingBoxChain(config, AetherProtocol.MASQUE_H2, isReconnect)
                if (fallbackAttempt.isSuccess) return
                failClosed(
                    formatException(fallbackAttempt.exceptionOrNull()),
                    "${config.chainMode.label} (${AetherProtocol.MASQUE_H2.label})"
                )
                return
            }
            failClosed(
                formatException(sbAttempt.exceptionOrNull()),
                config.chainMode.label
            )
            return
        }

        val firstAttempt = attemptSingleProtocol(config, primaryProtocol, isReconnect)
        if (firstAttempt.isSuccess) return

        // Smart 1-shot fallback to MASQUE HTTP/2 (TCP):
        // - When an upstream proxy is enabled and any UDP-dependent WARP protocol fails/times out.
        // - Or when MASQUE HTTP/3 fails and autoFallbackToH2 is enabled.
        // (Skipped in PSIPHON_ONLY mode where WARP/MASQUE is not used)
        if (shouldFallbackToMasqueH2(config, primaryProtocol, userRequestedStop)) {
            val firstErr = formatException(firstAttempt.exceptionOrNull())
            val notice = if (config.upstreamProxy.isNotBlank()) {
                "${primaryProtocol.label} failed/timed out over upstream proxy ($firstErr); falling back to MASQUE (HTTP/2 TCP)"
            } else {
                "MASQUE HTTP/3 failed ($firstErr); trying 1-shot HTTP/2 fallback"
            }
            DiagnosticsLog.record("Aether", notice)
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

    private suspend fun attemptSingBoxChain(
        config: VpnConfig,
        protocol: AetherProtocol,
        isReconnect: Boolean
    ): Result<Unit> = lifecycleMutex.withLock {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                DiagnosticsLog.record("System", "No internet connection detected; awaiting network")
                VpnStateStore.update(
                    TunnelSnapshot(
                        phase = TunnelPhase.WAITING_FOR_INTERNET,
                        protocolLabel = config.chainMode.label
                    )
                )
                updateNotification(getString(R.string.app_name), "Waiting for Internet")
                return@withLock Result.failure(IllegalStateException("No internet connection available"))
            }

            val activeProfile = SingBoxProfileRepository.load(filesDir).activeProfile
                ?: return@withLock Result.failure(
                    IllegalStateException("No Sing-box profile selected. Add or select a profile in Sing-box Profiles.")
                )
            val plan = ChainOrchestrator.plan(config, activeProfile.protocol, activeProfile.rawUriOrJson)
            if (plan.autoBumpedPerfProfile) {
                DiagnosticsLog.record(
                    "Chain",
                    "QUIC outbound detected — bumping Aether UDP buffer profile for stability"
                )
            }

            val psiphonBin = if (plan.chainMode.requiresPsiphon) {
                val bin = AetherEngineManager.findPsiphonBinary(this@AetherVpnService)
                if (bin == null) {
                    val msg = "Psiphon binary (libpsiphon-tunnel-core.so) not found in nativeLibraryDir. External ARM64 binary required for Psiphon chaining."
                    DiagnosticsLog.record("Psiphon", msg)
                    VpnStateStore.reportError(msg, plan.protocolLabel)
                    return@withLock Result.failure(IllegalStateException(msg))
                }
                bin.absolutePath
            } else null

            VpnStateStore.update(
                TunnelSnapshot(
                    phase = if (isReconnect) TunnelPhase.RECONNECTING else TunnelPhase.STARTING_CORE,
                    protocolLabel = plan.protocolLabel,
                    coreActive = false,
                    tunActive = isTunRunning
                )
            )
            updateNotification(
                getString(R.string.app_name),
                if (isReconnect) getString(R.string.status_reconnecting) else "Starting ${plan.chainMode.label}"
            )

            hevManager.stop()
            closeTun()
            stopUpstreamWatchdog()
            stopEnginesInReverseOrder()
            isCoreRunning = false
            isTunRunning = false

            lastSingBoxStartedFirst = plan.startSingBoxFirst
            val protector = SocketProtectorCallback { fd -> protect(fd) }

            if (plan.startSingBoxFirst) {
                // Upstream Sing-box starts first on 127.0.0.1:1825
                val sbResult = singBoxManager.start(
                    context = this@AetherVpnService,
                    config = config,
                    rawUriOrJson = activeProfile.rawUriOrJson,
                    listenPort = VpnConfig.SING_BOX_SOCKS_PORT,
                    upstreamSocksPort = null
                )
                if (sbResult.isFailure) {
                    return@withLock Result.failure(
                        sbResult.exceptionOrNull() ?: IllegalStateException("Sing-box core failed")
                    )
                }

                // Downstream Aether/WARP/Psiphon starts after Sing-box is ready
                if (plan.startAetherEngine && plan.aetherEffectiveConfig != null) {
                    val engineResult = engineManager.start(
                        config = plan.aetherEffectiveConfig,
                        protector = protector,
                        overrideProtocol = protocol,
                        psiphonBinaryPath = psiphonBin
                    )
                    if (engineResult.isFailure) {
                        singBoxManager.stop()
                        return@withLock Result.failure(
                            engineResult.exceptionOrNull() ?: IllegalStateException("Aether engine failed")
                        )
                    }
                }
            } else {
                // Upstream Aether/WARP or Psiphon starts first (1819 or 1821)
                if (plan.startAetherEngine && plan.aetherEffectiveConfig != null) {
                    val engineResult = engineManager.start(
                        config = plan.aetherEffectiveConfig,
                        protector = protector,
                        overrideProtocol = protocol,
                        psiphonBinaryPath = psiphonBin
                    )
                    if (engineResult.isFailure) {
                        return@withLock Result.failure(
                            engineResult.exceptionOrNull() ?: IllegalStateException("Aether engine failed")
                        )
                    }
                }

                // Downstream Sing-box starts after upstream SOCKS5 is ready, with detour -> upstream-socks
                val sbResult = singBoxManager.start(
                    context = this@AetherVpnService,
                    config = config,
                    rawUriOrJson = activeProfile.rawUriOrJson,
                    listenPort = VpnConfig.SING_BOX_SOCKS_PORT,
                    upstreamSocksPort = plan.singBoxUpstreamSocksPort
                )
                if (sbResult.isFailure) {
                    engineManager.stop()
                    return@withLock Result.failure(
                        sbResult.exceptionOrNull() ?: IllegalStateException("Sing-box core failed")
                    )
                }
            }
            isCoreRunning = true

            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = TunnelPhase.SOCKS5_READY,
                    coreActive = true
                )
            )

            val psiphonMode = plan.aetherEffectiveConfig?.psiphonMode ?: PsiphonMode.OFF
            val validateBeforeTun = config.upstreamProxy.isNotBlank() ||
                plan.aetherEffectiveConfig?.upstreamProxy?.isNotBlank() == true ||
                plan.chainMode.requiresPsiphon
            val hasLoopbackUpstream = SplitTunnelPlanner.isLoopbackUpstream(config.upstreamProxy) ||
                SplitTunnelPlanner.isLoopbackUpstream(plan.aetherEffectiveConfig?.upstreamProxy.orEmpty())

            var preTunVerified = if (validateBeforeTun) {
                updateNotification(getString(R.string.app_name), getString(R.string.status_validating))
                val preValidation = TunnelValidator.verifyThroughSocks(
                    socksPort = plan.tunTargetPort,
                    psiphonMode = psiphonMode,
                    chainMode = plan.chainMode
                )
                if (preValidation.isFailure) {
                    return@withLock Result.failure(
                        preValidation.exceptionOrNull() ?: IllegalStateException("Chain end-to-end validation failed")
                    )
                }
                preValidation.getOrThrow()
            } else {
                null
            }

            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = TunnelPhase.STARTING_HEV
                )
            )
            val pfd = establishTun(config)
                ?: return@withLock Result.failure(
                    IllegalStateException("VpnService.Builder.establish() returned null")
                )
            tunInterface = pfd

            DiagnosticsLog.record(
                "Tunnel",
                "HEV config (${plan.chainMode.label}): mtu=${config.effectiveTunMtu}, tcpBuf=${HevTunnelManager.TCP_BUFFER_SIZE_BYTES}, socks=127.0.0.1:${plan.tunTargetPort}, mapdns=${config.useMapDns}"
            )
            val hevResult = hevManager.start(config, pfd.fd)
            if (hevResult.isFailure) {
                return@withLock Result.failure(
                    hevResult.exceptionOrNull() ?: IllegalStateException("HEV tunnel failed")
                )
            }
            isTunRunning = true

            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = TunnelPhase.TUN_ACTIVE,
                    tunActive = true
                )
            )

            val verified = if (preTunVerified != null) {
                preTunVerified
            } else {
                updateNotification(getString(R.string.app_name), getString(R.string.status_validating))
                val validation = TunnelValidator.verifyThroughSocks(
                    socksPort = plan.tunTargetPort,
                    psiphonMode = psiphonMode,
                    chainMode = plan.chainMode
                )
                if (validation.isFailure) {
                    return@withLock Result.failure(
                        validation.exceptionOrNull() ?: IllegalStateException("Chain end-to-end validation failed")
                    )
                }
                validation.getOrThrow()
            }
            registerNetworkCallback(
                socksPort = plan.tunTargetPort,
                psiphonMode = psiphonMode,
                chainMode = plan.chainMode,
                hasLoopbackUpstream = hasLoopbackUpstream
            )
            startUpstreamWatchdog(config)
            DiagnosticsLog.record(
                "SingBox",
                "Connected (${plan.chainMode.label} • ${activeProfile.name}) | IP=${verified.publicIp} | WARP=${verified.warpStatus} | Colo=${verified.colo} | ${verified.latencyMs}ms"
            )

            VpnStateStore.update(
                TunnelSnapshot(
                    phase = TunnelPhase.VPN_CONNECTED,
                    protocolLabel = plan.protocolLabel,
                    gatewayLabel = activeProfile.name,
                    verifiedPublicIp = verified.publicIp,
                    warpStatus = if (plan.requireWarpValidation) verified.warpStatus else "sing-box",
                    colo = verified.colo,
                    latencyMs = verified.latencyMs,
                    connectedSinceMs = System.currentTimeMillis(),
                    coreActive = true,
                    tunActive = true
                )
            )
            updateNotification(
                getString(R.string.status_connected),
                "${verified.publicIp} • ${plan.chainMode.label}"
            )
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun attemptSingleProtocol(
        config: VpnConfig,
        protocol: AetherProtocol,
        isReconnect: Boolean
    ): Result<Unit> = lifecycleMutex.withLock {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                DiagnosticsLog.record("System", "No internet connection detected; awaiting network")
                VpnStateStore.update(
                    TunnelSnapshot(
                        phase = TunnelPhase.WAITING_FOR_INTERNET,
                        protocolLabel = protocol.label
                    )
                )
                updateNotification(getString(R.string.app_name), "Waiting for Internet")
                return@withLock Result.failure(IllegalStateException("No internet connection available"))
            }

            VpnStateStore.update(
                TunnelSnapshot(
                    phase = if (isReconnect) TunnelPhase.RECONNECTING else TunnelPhase.STARTING_CORE,
                    protocolLabel = protocol.label,
                    coreActive = false,
                    tunActive = isTunRunning
                )
            )
            updateNotification(
                getString(R.string.app_name),
                if (isReconnect) getString(R.string.status_reconnecting)
                else "Starting Aether Core"
            )

            hevManager.stop()
            closeTun()
            stopUpstreamWatchdog()
            engineManager.stop()
            singBoxManager.stop()
            isCoreRunning = false
            isTunRunning = false

            // Psiphon external binary requirement check
            val psiphonBin = if (config.psiphonMode != PsiphonMode.OFF) {
                val bin = AetherEngineManager.findPsiphonBinary(this@AetherVpnService)
                if (bin == null) {
                    val msg = "Psiphon binary (libpsiphon-tunnel-core.so) not found in nativeLibraryDir. External ARM64 binary required for Psiphon chaining."
                    DiagnosticsLog.record("Psiphon", msg)
                    VpnStateStore.reportError(msg, protocol.label)
                    return@withLock Result.failure(IllegalStateException(msg))
                }
                bin.absolutePath
            } else null

            // 1. Start single managed Aether Rust engine with VpnService.protect(fd) callback
            val protector = SocketProtectorCallback { fd -> protect(fd) }
            val engineResult = engineManager.start(
                config = config,
                protector = protector,
                overrideProtocol = protocol,
                psiphonBinaryPath = psiphonBin
            )
            if (engineResult.isFailure) {
                return@withLock Result.failure(
                    engineResult.exceptionOrNull() ?: IllegalStateException("Aether engine failed")
                )
            }
            isCoreRunning = true

            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = TunnelPhase.SOCKS5_READY,
                    coreActive = true
                )
            )

            val validateBeforeTun = config.upstreamProxy.isNotBlank() || config.psiphonMode != PsiphonMode.OFF
            val hasLoopbackUpstream = SplitTunnelPlanner.isLoopbackUpstream(config.upstreamProxy)

            val preTunVerified = if (validateBeforeTun) {
                updateNotification(getString(R.string.app_name), getString(R.string.status_validating))
                val preValidation = TunnelValidator.verifyThroughSocks(
                    socksPort = config.effectiveSocksPort,
                    psiphonMode = config.psiphonMode
                )
                if (preValidation.isFailure) {
                    return@withLock Result.failure(
                        preValidation.exceptionOrNull() ?: IllegalStateException("End-to-end validation failed")
                    )
                }
                preValidation.getOrThrow()
            } else {
                null
            }

            // 2. Establish dual-stack Android TUN interface
            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = TunnelPhase.STARTING_HEV
                )
            )
            val pfd = establishTun(config)
                ?: return@withLock Result.failure(
                    IllegalStateException("VpnService.Builder.establish() returned null")
                )
            tunInterface = pfd

            // 3. Start single managed HEV SOCKS5 Tunnel bridging TUN fd -> egress SOCKS5 port (1819 or 1821)
            DiagnosticsLog.record(
                "Tunnel",
                "HEV config: mtu=${config.effectiveTunMtu}, tcpBuf=${HevTunnelManager.TCP_BUFFER_SIZE_BYTES}, stack=${config.taskStackSize}, udpTimeout=${config.udpTimeoutMs}ms, mapdns=${config.useMapDns}"
            )
            val hevResult = hevManager.start(config, pfd.fd)
            if (hevResult.isFailure) {
                return@withLock Result.failure(
                    hevResult.exceptionOrNull() ?: IllegalStateException("HEV tunnel failed")
                )
            }
            isTunRunning = true

            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = TunnelPhase.TUN_ACTIVE,
                    tunActive = true
                )
            )

            // 4. End-to-end tunnel verification before ever reporting VPN_CONNECTED
            val verified = if (preTunVerified != null) {
                preTunVerified
            } else {
                updateNotification(getString(R.string.app_name), getString(R.string.status_validating))
                val validation = TunnelValidator.verifyThroughSocks(
                    socksPort = config.effectiveSocksPort,
                    psiphonMode = config.psiphonMode
                )
                if (validation.isFailure) {
                    return@withLock Result.failure(
                        validation.exceptionOrNull() ?: IllegalStateException("End-to-end validation failed")
                    )
                }
                validation.getOrThrow()
            }
            val identity = IdentityRepository.load(filesDir)
            val gateway = config.manualPeer.ifBlank {
                identity.cachedGateway.ifBlank { identity.assignedEndpoint.ifBlank { "Auto (WARP)" } }
            }

            registerNetworkCallback(
                socksPort = config.effectiveSocksPort,
                psiphonMode = config.psiphonMode,
                hasLoopbackUpstream = hasLoopbackUpstream
            )
            startUpstreamWatchdog(config)
            DiagnosticsLog.record(
                "System",
                "Connected via ${protocol.label} | IP=${verified.publicIp} | WARP=${verified.warpStatus} | ${verified.latencyMs}ms"
            )

            VpnStateStore.update(
                TunnelSnapshot(
                    phase = TunnelPhase.VPN_CONNECTED,
                    protocolLabel = protocol.label,
                    gatewayLabel = gateway,
                    verifiedPublicIp = verified.publicIp,
                    warpStatus = verified.warpStatus,
                    colo = verified.colo,
                    latencyMs = verified.latencyMs,
                    connectedSinceMs = System.currentTimeMillis(),
                    coreActive = true,
                    tunActive = true
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

    private suspend fun startCoreStandalone() = lifecycleMutex.withLock {
        try {
            val config = VpnConfig.load(this@AetherVpnService)
            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = TunnelPhase.STARTING_CORE,
                    protocolLabel = config.protocol.label,
                    coreActive = false
                )
            )
            stopEnginesInReverseOrder()
            if (config.singBoxEnabled) {
                val activeProfile = SingBoxProfileRepository.load(filesDir).activeProfile
                if (activeProfile == null) {
                    val msg = "No Sing-box profile selected"
                    DiagnosticsLog.record("SingBox", msg)
                    VpnStateStore.reportError(msg, config.chainMode.label)
                    if (!isTunRunning) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                    return@withLock
                }
                val plan = ChainOrchestrator.plan(config, activeProfile.protocol, activeProfile.rawUriOrJson)
            if (plan.autoBumpedPerfProfile) {
                DiagnosticsLog.record(
                    "Chain",
                    "QUIC outbound detected — bumping Aether UDP buffer profile for stability"
                )
            }
                val psiphonBin = if (plan.chainMode.requiresPsiphon) {
                    val bin = AetherEngineManager.findPsiphonBinary(this@AetherVpnService)
                    if (bin == null) {
                        val msg = "Psiphon binary (libpsiphon-tunnel-core.so) not found in nativeLibraryDir."
                        DiagnosticsLog.record("Psiphon", msg)
                        VpnStateStore.reportError(msg, plan.protocolLabel)
                        if (!isTunRunning) {
                            stopForegroundCompat()
                            stopSelf()
                        }
                        return@withLock
                    }
                    bin.absolutePath
                } else null
                lastSingBoxStartedFirst = plan.startSingBoxFirst
                val protector = SocketProtectorCallback { fd -> protect(fd) }
                val chainError: Throwable? = if (plan.startSingBoxFirst) {
                    val sbRes = singBoxManager.start(
                        context = this@AetherVpnService,
                        config = config,
                        rawUriOrJson = activeProfile.rawUriOrJson,
                        listenPort = VpnConfig.SING_BOX_SOCKS_PORT,
                        upstreamSocksPort = null
                    )
                    if (sbRes.isFailure) {
                        sbRes.exceptionOrNull()
                    } else if (plan.startAetherEngine && plan.aetherEffectiveConfig != null) {
                        engineManager.start(
                            config = plan.aetherEffectiveConfig,
                            protector = protector,
                            psiphonBinaryPath = psiphonBin
                        ).exceptionOrNull()
                    } else null
                } else {
                    val engRes = if (plan.startAetherEngine && plan.aetherEffectiveConfig != null) {
                        engineManager.start(
                            config = plan.aetherEffectiveConfig,
                            protector = protector,
                            psiphonBinaryPath = psiphonBin
                        )
                    } else Result.success(0L)
                    if (engRes.isFailure) {
                        engRes.exceptionOrNull()
                    } else {
                        singBoxManager.start(
                            context = this@AetherVpnService,
                            config = config,
                            rawUriOrJson = activeProfile.rawUriOrJson,
                            listenPort = VpnConfig.SING_BOX_SOCKS_PORT,
                            upstreamSocksPort = plan.singBoxUpstreamSocksPort
                        ).exceptionOrNull()
                    }
                }
                if (chainError != null) {
                    stopEnginesInReverseOrder()
                    val err = formatException(chainError)
                    DiagnosticsLog.record("SingBox", "Standalone Core failed: $err")
                    VpnStateStore.reportError(err, plan.protocolLabel)
                    if (!isTunRunning) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                    return@withLock
                }
                isCoreRunning = true
                VpnStateStore.update(
                    VpnStateStore.state.value.copy(
                        phase = if (isTunRunning) TunnelPhase.TUN_ACTIVE else TunnelPhase.SOCKS5_READY,
                        protocolLabel = plan.protocolLabel,
                        coreActive = true
                    )
                )
                updateNotification("${plan.chainMode.label} Ready", "127.0.0.1:${plan.tunTargetPort}")
                return@withLock
            }
            val psiphonBin = if (config.psiphonMode != PsiphonMode.OFF) {
                val bin = AetherEngineManager.findPsiphonBinary(this@AetherVpnService)
                if (bin == null) {
                    val msg = "Psiphon binary (libpsiphon-tunnel-core.so) not found in nativeLibraryDir. External ARM64 binary required for Psiphon chaining."
                    DiagnosticsLog.record("Psiphon", msg)
                    VpnStateStore.reportError(msg, config.protocol.label)
                    if (!isTunRunning) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                    return@withLock
                }
                bin.absolutePath
            } else null

            val protector = SocketProtectorCallback { fd -> protect(fd) }
            val engineResult = engineManager.start(
                config = config,
                protector = protector,
                psiphonBinaryPath = psiphonBin
            )
            if (engineResult.isFailure) {
                val err = formatException(engineResult.exceptionOrNull())
                DiagnosticsLog.record("Aether", "Standalone Core failed: $err")
                VpnStateStore.reportError(err, config.protocol.label)
                if (!isTunRunning) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return@withLock
            }
            isCoreRunning = true
            DiagnosticsLog.record("Aether", "Standalone Core ready on 127.0.0.1:${config.effectiveSocksPort}")
            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = if (isTunRunning) TunnelPhase.TUN_ACTIVE else TunnelPhase.SOCKS5_READY,
                    protocolLabel = config.protocol.label,
                    coreActive = true
                )
            )
            updateNotification("Aether Core Ready", "127.0.0.1:${config.effectiveSocksPort}")
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val err = formatException(t)
            VpnStateStore.reportError(err)
            if (!isTunRunning) {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private suspend fun stopCoreStandalone() = lifecycleMutex.withLock {
        stopEnginesInReverseOrder()
        isCoreRunning = false
        DiagnosticsLog.record("Aether", "Standalone Core stopped")
        val newPhase = if (isTunRunning) TunnelPhase.TUN_ACTIVE else TunnelPhase.STOPPED
        VpnStateStore.update(
            VpnStateStore.state.value.copy(
                phase = newPhase,
                coreActive = false
            )
        )
        if (!isTunRunning) {
            stopForegroundCompat()
            stopSelf()
        } else {
            updateNotification("Aether Diagnostic TUN", "TUN Active (Core stopped)")
        }
    }

    private suspend fun startTunStandalone() = lifecycleMutex.withLock {
        try {
            val config = VpnConfig.load(this@AetherVpnService)
            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = TunnelPhase.STARTING_HEV,
                    tunActive = false
                )
            )
            hevManager.stop()
            closeTun()

            val pfd = establishTun(config)
            if (pfd == null) {
                val err = "VpnService.Builder.establish() returned null"
                DiagnosticsLog.record("Tunnel", "Standalone TUN failed: $err")
                VpnStateStore.reportError(err, config.protocol.label)
                if (!isCoreRunning) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return@withLock
            }
            tunInterface = pfd

            val hevResult = hevManager.start(config, pfd.fd)
            if (hevResult.isFailure) {
                val err = formatException(hevResult.exceptionOrNull())
                DiagnosticsLog.record("Tunnel", "Standalone HEV failed: $err")
                closeTun()
                VpnStateStore.reportError(err, config.protocol.label)
                if (!isCoreRunning) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return@withLock
            }
            isTunRunning = true
            registerNetworkCallback(config.effectiveSocksPort, config.psiphonMode)
            DiagnosticsLog.record("Tunnel", "Standalone TUN active (/dev/tun -> 127.0.0.1:${config.effectiveSocksPort})")
            VpnStateStore.update(
                VpnStateStore.state.value.copy(
                    phase = TunnelPhase.TUN_ACTIVE,
                    tunActive = true
                )
            )
            updateNotification("Aether TUN Active", "Relaying to 127.0.0.1:${config.effectiveSocksPort}")
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val err = formatException(t)
            VpnStateStore.reportError(err)
            if (!isCoreRunning) {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private suspend fun stopTunStandalone() = lifecycleMutex.withLock {
        unregisterNetworkCallback()
        hevManager.stop()
        closeTun()
        isTunRunning = false
        DiagnosticsLog.record("Tunnel", "Standalone TUN stopped")
        val newPhase = if (isCoreRunning) TunnelPhase.SOCKS5_READY else TunnelPhase.STOPPED
        val cfg = VpnConfig.load(this@AetherVpnService)
        VpnStateStore.update(
            VpnStateStore.state.value.copy(
                phase = newPhase,
                tunActive = false
            )
        )
        if (!isCoreRunning) {
            stopForegroundCompat()
            stopSelf()
        } else {
            updateNotification("Aether Diagnostic Core", "127.0.0.1:${cfg.effectiveSocksPort}")
        }
    }

    private fun establishTun(config: VpnConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Aether VPN")
            .setMtu(config.effectiveTunMtu)
            .addAddress(VpnConfig.TUN_IPV4_ADDRESS, VpnConfig.TUN_IPV4_PREFIX)
            .setBlocking(false)

        if (config.bypassIran) {
            val iranCidrs = runCatching {
                resources.openRawResource(R.raw.iran_cidrs).use { CidrRoutePlanner.parseCidrs(it) }
            }.getOrDefault(emptyList())

            if (iranCidrs.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.addRoute("0.0.0.0", 0)
                iranCidrs.forEach { cidr ->
                    runCatching { builder.excludeRoute(IpPrefix(cidr.toInetAddress(), cidr.prefix)) }
                }
                DiagnosticsLog.record("Tunnel", "Iran Direct active (API 33+ excludeRoute: ${iranCidrs.size} CIDRs)")
            } else if (iranCidrs.isNotEmpty()) {
                val allowedRoutes = CidrRoutePlanner.invertCidrs(iranCidrs)
                allowedRoutes.forEach { block ->
                    builder.addRoute(block.addressString, block.prefix)
                }
                DiagnosticsLog.record("Tunnel", "Iran Direct active (inverted IPv4 routes: ${allowedRoutes.size} blocks)")
            } else {
                builder.addRoute("0.0.0.0", 0)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (config.useMapDns) {
            val mapDnsAddress = config.effectiveMapDnsAddress
            builder.addDnsServer(mapDnsAddress)
            DiagnosticsLog.record("Tunnel", "HEV mapdns active ($mapDnsAddress:53)")
        } else {
            val resolvedDns = resolveIpv4DnsServers(config)
            var dnsAdded = false
            for (dns in resolvedDns) {
                val res = runCatching { builder.addDnsServer(dns) }
                if (res.isSuccess) {
                    dnsAdded = true
                } else {
                    DiagnosticsLog.record("System", "DNS ($dns) rejected: ${res.exceptionOrNull()?.message}; using fallback")
                }
            }
            if (!dnsAdded) {
                builder.addDnsServer(VpnConfig.DNS_IPV4_PRIMARY)
                builder.addDnsServer(
                    if (config.isSingBoxOnlyActive) "8.8.8.8" else VpnConfig.DNS_IPV4_SECONDARY
                )
            }
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

    private suspend fun connectSoarVpnPipeline() {
        val profile = SoarProfileStore.load(filesDir).activeProfile
        if (profile == null) {
            SoarStateStore.reportError("No active Soar profile selected", SoarConnectionMode.VPN_FULL_DEVICE)
            teardownAll(clearError = false)
            stopForegroundCompat()
            stopSelf()
            return
        }

        val config = VpnConfig.load(this)
        lifecycleMutex.withLock {
            hevManager.stop()
            closeTun()
            stopEnginesInReverseOrder()

            SoarStateStore.update(
                SoarRuntimeState(
                    phase = SoarPhase.STARTING,
                    mode = SoarConnectionMode.VPN_FULL_DEVICE,
                    profileName = profile.name
                )
            )
            VpnStateStore.update(
                TunnelSnapshot(
                    phase = TunnelPhase.STARTING_CORE,
                    protocolLabel = "Soar DNS Tunnel",
                    gatewayLabel = profile.domain
                )
            )

            val activePack = SoarResolverPackStore.load(filesDir).activePack
            val soarResult = soarManager.start(
                context = this@AetherVpnService,
                profile = profile,
                mode = SoarConnectionMode.VPN_FULL_DEVICE,
                activePack = activePack
            )
            if (soarResult.isFailure) {
                val msg = soarResult.exceptionOrNull()?.message ?: "Soar failed to start"
                SoarStateStore.reportError(msg, SoarConnectionMode.VPN_FULL_DEVICE)
                VpnStateStore.reportError(msg, "Soar DNS Tunnel")
                stopForegroundCompat()
                stopSelf()
                return
            }
            isCoreRunning = true

            val pfd = establishSoarTun(config) ?: run {
                val msg = "Soar TUN interface establish() returned null"
                soarManager.stop()
                isCoreRunning = false
                SoarStateStore.reportError(msg, SoarConnectionMode.VPN_FULL_DEVICE)
                VpnStateStore.reportError(msg, "Soar DNS Tunnel")
                stopForegroundCompat()
                stopSelf()
                return
            }
            tunInterface = pfd

            DiagnosticsLog.record(
                "Tunnel",
                "Soar HEV mapdns: ${SoarEngineManager.TUN_MAPDNS_ADDRESS}:53 (${SoarEngineManager.TUN_MAPDNS_NETWORK}/${SoarEngineManager.TUN_MAPDNS_NETMASK}) -> 127.0.0.1:${SoarEngineManager.DEFAULT_LISTEN_PORT}"
            )
            val hevResult = hevManager.start(
                config = config,
                tunFd = pfd.fd,
                tunIpv4 = SoarEngineManager.TUN_IPV4_ADDRESS,
                socksPort = SoarEngineManager.DEFAULT_LISTEN_PORT,
                mapDnsAddress = SoarEngineManager.TUN_MAPDNS_ADDRESS,
                mapDnsNetwork = SoarEngineManager.TUN_MAPDNS_NETWORK,
                mapDnsNetmask = SoarEngineManager.TUN_MAPDNS_NETMASK,
                forceMapDns = true
            )
            if (hevResult.isFailure) {
                val msg = hevResult.exceptionOrNull()?.message ?: "HEV tunnel failed for Soar"
                closeTun()
                soarManager.stop()
                isCoreRunning = false
                SoarStateStore.reportError(msg, SoarConnectionMode.VPN_FULL_DEVICE)
                VpnStateStore.reportError(msg, "Soar DNS Tunnel")
                stopForegroundCompat()
                stopSelf()
                return
            }

            isTunRunning = true
            val listenAddr = "${SoarEngineManager.DEFAULT_LISTEN_HOST}:${SoarEngineManager.DEFAULT_LISTEN_PORT}"
            SoarStateStore.update(
                SoarRuntimeState(
                    phase = SoarPhase.CONNECTED,
                    mode = SoarConnectionMode.VPN_FULL_DEVICE,
                    profileName = profile.name,
                    listenAddress = listenAddr
                )
            )
            VpnStateStore.update(
                TunnelSnapshot(
                    phase = TunnelPhase.VPN_CONNECTED,
                    protocolLabel = "Soar DNS (${profile.soarSettings.transport.uppercase()})",
                    gatewayLabel = profile.domain,
                    connectedSinceMs = System.currentTimeMillis(),
                    coreActive = true,
                    tunActive = true
                )
            )
            startForegroundCompat(
                "Soar VPN Active",
                "TUN (${SoarEngineManager.TUN_IPV4_ADDRESS}) -> Soar ($listenAddr)"
            )
        }
    }

    private fun establishSoarTun(config: VpnConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Aether Soar VPN")
            .setMtu(config.effectiveTunMtu)
            .addAddress(SoarEngineManager.TUN_IPV4_ADDRESS, SoarEngineManager.TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(SoarEngineManager.TUN_MAPDNS_ADDRESS)
            .setBlocking(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Explicitly disallow selfPackageName (io.aethervpn.android) so libsoar.so's outbound
        // UDP/TCP port 53 traffic to DNS resolvers bypasses TUN and avoids any routing loop.
        soarDisallowedPackages(config, packageName).forEach { pkg ->
            val res = runCatching { builder.addDisallowedApplication(pkg) }
            if (res.isFailure && pkg == packageName) {
                DiagnosticsLog.record("Tunnel", "Warning: could not exclude $pkg: ${res.exceptionOrNull()?.message}")
            }
        }
        DiagnosticsLog.record(
            "Tunnel",
            "Soar TUN bypass: addDisallowedApplication($packageName) applied"
        )
        return builder.establish()
    }

    private fun applyPerAppRouting(builder: Builder, config: VpnConfig) {
        val plan = SplitTunnelPlanner.plan(
            mode = config.splitTunnelMode,
            selectedPackages = config.selectedPackages,
            selfPackageName = packageName,
            upstreamProxy = config.upstreamProxy,
            proxyBypassApps = config.proxyBypassApps
        )

        if (SplitTunnelPlanner.isLoopbackUpstream(config.upstreamProxy)) {
            DiagnosticsLog.record("Tunnel", "Auto-excluding local proxy apps for loopback upstream")
        }
        if (config.proxyBypassApps.isNotEmpty()) {
            DiagnosticsLog.record("Tunnel", "Bypassing user-selected proxy app(s): ${config.proxyBypassApps.joinToString(", ")}")
        }

        plan.disallowedPackages.forEach { pkg ->
            val res = runCatching { builder.addDisallowedApplication(pkg) }
            if (res.isFailure && pkg == packageName) {
                DiagnosticsLog.record("Tunnel", "Warning: could not exclude $pkg: ${res.exceptionOrNull()?.message}")
            }
        }

        plan.allowedPackages.forEach { pkg ->
            val res = runCatching { builder.addAllowedApplication(pkg) }
            if (res.isFailure) {
                DiagnosticsLog.record("Tunnel", "Warning: could not allow $pkg: ${res.exceptionOrNull()?.message}")
            }
        }

        DiagnosticsLog.record(
            "Tunnel",
            "Per-app routing before establish(): mode=${config.splitTunnelMode}, selfExcluded=${packageName !in plan.allowedPackages}"
        )

        val summary = when (config.splitTunnelMode) {
            SplitTunnelMode.ALL_APPS -> "All apps (self $packageName excluded)"
            SplitTunnelMode.BYPASS_SELECTED -> "Bypass ${plan.disallowedPackages.size - 1} app(s) + self excluded"
            SplitTunnelMode.ONLY_SELECTED -> if (plan.allowedPackages.isNotEmpty()) {
                "Only ${plan.allowedPackages.size} selected app(s) routed"
            } else {
                "Only-selected mode with 0 apps -> fallback to All apps"
            }
        }
        DiagnosticsLog.record("Tunnel", "Split tunnel: $summary")
    }

    private fun registerNetworkCallback(
        socksPort: Int,
        psiphonMode: PsiphonMode,
        requireWarpOverride: Boolean? = null,
        chainMode: ChainMode? = null,
        hasLoopbackUpstream: Boolean = false
    ) {
        unregisterNetworkCallback()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val netId = network.networkHandle
                runCatching { setUnderlyingNetworks(arrayOf(network)) }

                if (activeNetworkId == -1L && !networkLostDuringSession) {
                    activeNetworkId = netId
                    return
                }
                val recoveringFromLoss = networkLostDuringSession
                networkLostDuringSession = false
                if (recoveringFromLoss) {
                    DiagnosticsLog.onNetworkRestored()
                }
                if ((recoveringFromLoss || netId != activeNetworkId) && !userRequestedStop) {
                    activeNetworkId = netId
                    // Battery-first check: avoid tearing down a healthy tunnel on transient network events
                    serviceScope.launch {
                        if (!recoveringFromLoss) {
                            val health = TunnelValidator.probeHealth(
                                socksPort = socksPort,
                                psiphonMode = psiphonMode,
                                requireWarpOverride = requireWarpOverride,
                                chainMode = chainMode
                            )
                            if (health.isSuccess) {
                                DiagnosticsLog.record("System", "Network changed but tunnel remains healthy; skipping reconnect")
                                return@launch
                            }
                            if (hasLoopbackUpstream && TunnelValidator.isTransientSocksFailure(health.exceptionOrNull())) {
                                DiagnosticsLog.record(
                                    "System",
                                    "Transient SOCKS5 0x01 health-check blip on local proxy chain; allowing upstream auto-reconnect to recover"
                                )
                                delay(4_000)
                                if (userRequestedStop) return@launch
                                val recovered = TunnelValidator.verifyThroughSocks(
                                    socksPort = socksPort,
                                    timeoutMs = 6_000,
                                    psiphonMode = psiphonMode,
                                    requireWarpOverride = requireWarpOverride,
                                    chainMode = chainMode
                                )
                                if (recovered.isSuccess) {
                                    DiagnosticsLog.record("System", "Local proxy chain recovered after transient 0x01; keeping session active")
                                    return@launch
                                }
                            }
                        }
                        DiagnosticsLog.record("System", "Network transition requires reconnect; scheduling backoff")
                        scheduleReconnectWithBackoff()
                    }
                }
            }

            override fun onLost(network: Network) {
                if (network.networkHandle == activeNetworkId && !userRequestedStop) {
                    activeNetworkId = -1L
                    networkLostDuringSession = true
                    runCatching { setUnderlyingNetworks(null) }
                    DiagnosticsLog.onNetworkLost()
                    VpnStateStore.update(
                        VpnStateStore.state.value.copy(phase = TunnelPhase.WAITING_FOR_INTERNET)
                    )
                    updateNotification(
                        getString(R.string.app_name),
                        "Waiting for Internet"
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
        DiagnosticsLog.resetNetworkState()
        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }

    private fun scheduleReconnectWithBackoff() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            for ((index, delayMs) in backoffDelaysMs.withIndex()) {
                if (userRequestedStop) return@launch
                delay(delayMs)
                if (userRequestedStop) return@launch
                DiagnosticsLog.record("System", "Reconnect attempt ${index + 1}/${backoffDelaysMs.size} (backoff ${delayMs / 1000}s)")
                connectPipeline(isReconnect = true)
                if (VpnStateStore.state.value.phase == TunnelPhase.VPN_CONNECTED) {
                    DiagnosticsLog.record("System", "Reconnect successful; backoff reset")
                    return@launch
                }
            }
            DiagnosticsLog.record("System", "All reconnect attempts exhausted; waiting for network event")
        }
    }

    private suspend fun failClosed(reason: String, protocolLabel: String) {
        reconnectJob?.cancel()
        stopUpstreamWatchdog()
        unregisterNetworkCallback()
        hevManager.stop()
        closeTun()
        stopEnginesInReverseOrder()
        isCoreRunning = false
        isTunRunning = false
        runCatching { setUnderlyingNetworks(null) }
        VpnStateStore.reportError(reason, protocolLabel)
        stopForegroundCompat()
        stopSelf()
    }

    private suspend fun teardownAll(clearError: Boolean) {
        performInstantDisconnect(
            jobsToCancel = listOf(reconnectJob),
            closeTunFirst = { closeTun() },
            onImmediateStateReset = {
                stopUpstreamWatchdog()
                unregisterNetworkCallback()
                runCatching { setUnderlyingNetworks(null) }
                if (clearError) {
                    VpnStateStore.resetDisconnected()
                    if (SoarStateStore.state.value.mode == SoarConnectionMode.VPN_FULL_DEVICE) {
                        SoarStateStore.resetStopped(SoarConnectionMode.VPN_FULL_DEVICE)
                    }
                }
            },
            engineStoppers = listOf(
                { hevManager.stop() },
                { engineManager.stop() },
                { singBoxManager.stop() },
                { soarManager.stop() }
            )
        )
        isCoreRunning = false
        isTunRunning = false
    }

    private fun stopEnginesInReverseOrder() {
        soarManager.stop()
        if (lastSingBoxStartedFirst) {
            // Sing-box was upstream, Aether was downstream -> stop downstream Aether first
            engineManager.stop()
            singBoxManager.stop()
        } else {
            // Aether was upstream, Sing-box was downstream -> stop downstream Sing-box first
            singBoxManager.stop()
            engineManager.stop()
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

    private fun startUpstreamWatchdog(config: VpnConfig) {
        stopUpstreamWatchdog()
        if (!SplitTunnelPlanner.isLoopbackUpstream(config.upstreamProxy)) return
        val parsed = StructuredUpstreamProxy.parse(config.upstreamProxy)
        if (!parsed.enabled || parsed.port !in 1..65535) return
        val displayHost = if (parsed.host.equals("localhost", ignoreCase = true)) {
            "127.0.0.1"
        } else {
            parsed.host
        }
        val watchdog = UpstreamProxyWatchdog(
            proxyHost = displayHost,
            proxyPort = parsed.port,
            scope = serviceScope,
            onUnreachable = { host, port ->
                DiagnosticsLog.record("System", "Upstream proxy $host:$port appears stopped or unreachable")
            }
        )
        val listener: (String, String) -> Unit = { tag, message ->
            if (isCoreRunning &&
                isTunRunning &&
                !userRequestedStop &&
                VpnStateStore.state.value.phase == TunnelPhase.VPN_CONNECTED
            ) {
                watchdog.onEngineLog(tag, message)
            }
        }
        upstreamWatchdog = watchdog
        watchdogListener = listener
        DiagnosticsLog.addListener(listener)
    }

    private fun stopUpstreamWatchdog() {
        watchdogListener?.let { DiagnosticsLog.removeListener(it) }
        watchdogListener = null
        upstreamWatchdog = null
    }
}

/**
 * Watches engine error logs when a local loopback upstream proxy is active.
 * After repeated engine errors, probes the local proxy port once and emits a
 * single clear [System] warning if the upstream proxy refuses connections.
 */
internal class UpstreamProxyWatchdog(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val scope: CoroutineScope,
    private val portProbe: (String, Int, Int) -> Boolean = ::isTcpPortReachable,
    private val onUnreachable: (String, Int) -> Unit
) {
    private val consecutiveErrors = AtomicInteger(0)
    private val warnedForOutage = AtomicBoolean(false)
    private val probeInFlight = AtomicBoolean(false)

    fun onEngineLog(tag: String, message: String) {
        if (tag !in ENGINE_TAGS) return
        if (!isEngineErrorLine(message)) return
        if (consecutiveErrors.incrementAndGet() < ERROR_THRESHOLD) return
        if (warnedForOutage.get()) return
        if (!probeInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                val reachable = portProbe(proxyHost, proxyPort, PROBE_TIMEOUT_MS)
                if (!reachable) {
                    if (warnedForOutage.compareAndSet(false, true)) {
                        onUnreachable(proxyHost, proxyPort)
                    }
                } else {
                    consecutiveErrors.set(0)
                    warnedForOutage.set(false)
                }
            } finally {
                probeInFlight.set(false)
            }
        }
    }

    companion object {
        private const val ERROR_THRESHOLD = 3
        private const val PROBE_TIMEOUT_MS = 800
        private val ENGINE_TAGS = setOf("Aether", "Psiphon", "SingBox")
        private val ERROR_KEYWORDS = listOf(
            "error",
            "fail",
            "refused",
            "reset",
            "eof",
            "timeout",
            "timed out",
            "closed",
            "unreachable",
            "broken pipe",
            "socks"
        )

        internal fun isEngineErrorLine(message: String): Boolean {
            val lower = message.lowercase()
            return ERROR_KEYWORDS.any { lower.contains(it) }
        }

        internal fun isTcpPortReachable(host: String, port: Int, timeoutMs: Int): Boolean {
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
