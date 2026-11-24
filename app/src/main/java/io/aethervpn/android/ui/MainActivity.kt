package io.aethervpn.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.aethervpn.android.AetherApplication
import io.aethervpn.android.R
import io.aethervpn.android.core.AetherProtocol
import io.aethervpn.android.core.AppThemeMode
import io.aethervpn.android.core.DiagnosticsLog
import io.aethervpn.android.core.HevMode
import io.aethervpn.android.core.IdentityRepository
import io.aethervpn.android.core.IpFamily
import io.aethervpn.android.core.NoizeProfile
import io.aethervpn.android.core.PerfProfile
import io.aethervpn.android.core.ProfileLevel
import io.aethervpn.android.core.PsiphonMode
import io.aethervpn.android.core.ScanMode
import io.aethervpn.android.core.TunnelPhase
import io.aethervpn.android.core.TunnelSnapshot
import io.aethervpn.android.core.TunnelValidator
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.core.VpnStateStore
import io.aethervpn.android.databinding.ActivityMainBinding
import io.aethervpn.android.vpn.AetherVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Clean, organized, battery-first native Kotlin XML UI structured into 3 tabs:
 * 1. Home / Connect: Status card, primary Connect/Disconnect button, active mode summary.
 * 2. Settings: Neatly grouped configuration cards (Protocol, Psiphon, Routing, Anti-Censorship, App Settings).
 * 3. Logs & Diagnostics: 60fps RecyclerView live auto-refreshing log console with smart auto-scroll + component controls.
 *
 * Battery & performance discipline:
 * - Lightweight native XML RecyclerView with ListAdapter (diffing new entries only).
 * - Zero Compose runtime overhead, zero FragmentTransaction churn.
 * - Foreground stats loop runs ONLY when Home tab is visible AND VPN is CONNECTED.
 * - Reactive StateFlow log collection with pause on manual scroll up.
 * - All SharedPreferences and file I/O offloaded to Dispatchers.IO.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var statsJob: Job? = null
    private var logsJob: Job? = null
    private var currentTabId: Int = R.id.nav_home

    @Volatile
    private var userAutoScrollEnabled = true

    @Volatile
    private var cachedConfig: VpnConfig? = null

    private val statsIntervalsMs = longArrayOf(5_000L, 3_000L, 10_000L)
    private val statsIntervalLabels = listOf(
        "5 seconds (Battery Efficient)",
        "3 seconds (Balanced)",
        "10 seconds (Ultra Low)"
    )

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            VpnStateStore.reportError(getString(R.string.error_vpn_permission_denied))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setupNavigation()
        setupSpinners()
        setupLogRecyclerView()
        bindActions()

        // Load configuration asynchronously off the main thread
        uiScope.launch {
            val cfg = withContext(Dispatchers.IO) { VpnConfig.load(this@MainActivity) }
            cachedConfig = cfg
            loadConfigIntoUi(cfg)
        }
    }

    override fun onStart() {
        super.onStart()
        stateJob = uiScope.launch {
            VpnStateStore.state.collectLatest { snapshot ->
                renderHomeState(snapshot)
                updateStatsLoop(snapshot)
            }
        }
        logsJob = uiScope.launch {
            DiagnosticsLog.logsFlow.collectLatest { lines ->
                renderLogs(lines)
            }
        }
    }

    override fun onStop() {
        stateJob?.cancel()
        stateJob = null
        statsJob?.cancel()
        statsJob = null
        logsJob?.cancel()
        logsJob = null
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            DiagnosticsLog.trim(keepCount = 30)
        }
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            currentTabId = item.itemId
            binding.sectionHome.visibility = if (currentTabId == R.id.nav_home) View.VISIBLE else View.GONE
            binding.sectionSettings.visibility = if (currentTabId == R.id.nav_settings) View.VISIBLE else View.GONE
            binding.sectionLogs.visibility = if (currentTabId == R.id.nav_logs) View.VISIBLE else View.GONE

            if (currentTabId == R.id.nav_logs) {
                refreshIdentityAndDiagnostics()
                if (userAutoScrollEnabled && logAdapter.itemCount > 0) {
                    binding.rvLogs.post {
                        binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
                    }
                }
            }
            updateStatsLoop(VpnStateStore.state.value)
            true
        }
    }

    private fun setupLogRecyclerView() {
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvLogs.layoutManager = layoutManager
        logAdapter = LogAdapter()
        binding.rvLogs.adapter = logAdapter

        binding.rvLogs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val canScrollDown = recyclerView.canScrollVertically(1)
                if (!canScrollDown) {
                    // Reached the bottom -> re-enable auto-scroll
                    userAutoScrollEnabled = true
                } else if (dy < 0) {
                    // User scrolled up -> pause auto-scrolling
                    userAutoScrollEnabled = false
                }
            }
        })
    }

    private fun renderLogs(lines: List<String>) {
        logAdapter.submitList(lines) {
            if (userAutoScrollEnabled && lines.isNotEmpty()) {
                binding.rvLogs.scrollToPosition(lines.size - 1)
            }
        }
    }

    private fun setupSpinners() {
        fun simpleAdapter(items: List<String>) =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

        binding.spinnerProfile.adapter = simpleAdapter(ProfileLevel.entries.map { it.label })
        binding.spinnerPerfProfile.adapter = simpleAdapter(PerfProfile.entries.map { it.label })
        binding.spinnerHevMode.adapter = simpleAdapter(HevMode.entries.map { it.label })
        binding.spinnerProtocol.adapter = simpleAdapter(AetherProtocol.entries.map { it.label })
        binding.spinnerPsiphonMode.adapter = simpleAdapter(PsiphonMode.entries.map { it.label })
        binding.spinnerPsiphonRegion.adapter = simpleAdapter(VpnConfig.PSIPHON_REGIONS.map { it.second })
        binding.spinnerScanMode.adapter = simpleAdapter(ScanMode.entries.map { it.label })
        binding.spinnerIpFamily.adapter = simpleAdapter(IpFamily.entries.map { it.label })
        binding.spinnerNoize.adapter = simpleAdapter(NoizeProfile.entries.map { it.label })
        binding.spinnerTheme.adapter = simpleAdapter(AppThemeMode.entries.map { it.label })
        binding.spinnerStatsInterval.adapter = simpleAdapter(statsIntervalLabels)

        binding.spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val level = ProfileLevel.entries[pos]
                uiScope.launch {
                    val current = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@MainActivity) }
                    if (current.profileLevel != level) {
                        val updated = current.applyPreset(level)
                        cachedConfig = updated
                        withContext(Dispatchers.IO) { VpnConfig.save(this@MainActivity, updated) }
                        loadConfigIntoUi(updated)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadConfigIntoUi(cfg: VpnConfig) {
        val psiphonSummary = when (cfg.psiphonMode) {
            PsiphonMode.OFF -> "Off"
            PsiphonMode.WARP_TO_PSIPHON -> "WARP → Psiphon" + if (cfg.psiphonRegion.isNotBlank()) " (${cfg.psiphonRegion})" else ""
            PsiphonMode.PSIPHON_TO_WARP -> "Psiphon → WARP" + if (cfg.psiphonRegion.isNotBlank()) " (${cfg.psiphonRegion})" else ""
            PsiphonMode.PSIPHON_ONLY -> "Psiphon Only" + if (cfg.psiphonRegion.isNotBlank()) " (${cfg.psiphonRegion})" else ""
        }
        binding.tvActiveProfileBadge.text =
            "Profile: ${cfg.profileLevel.label} • Protocol: ${cfg.protocol.label} • Psiphon: $psiphonSummary • Perf: ${cfg.perfProfile.label}"

        binding.spinnerProfile.setSelection(cfg.profileLevel.ordinal, false)
        binding.spinnerPerfProfile.setSelection(cfg.perfProfile.ordinal, false)
        binding.spinnerHevMode.setSelection(cfg.hevMode.ordinal, false)
        binding.spinnerProtocol.setSelection(cfg.protocol.ordinal, false)
        binding.switchAutoH2Fallback.isChecked = cfg.autoFallbackToH2
        binding.switchQuickReconnect.isChecked = cfg.quickReconnect
        binding.etManualPeer.setText(cfg.manualPeer)

        binding.spinnerPsiphonMode.setSelection(cfg.psiphonMode.ordinal, false)
        val regionIdx = VpnConfig.PSIPHON_REGIONS.indexOfFirst {
            it.first.equals(cfg.psiphonRegion, ignoreCase = true)
        }.coerceAtLeast(0)
        binding.spinnerPsiphonRegion.setSelection(regionIdx, false)

        binding.etExitLocation.setText(cfg.exitLocation)
        binding.spinnerIpFamily.setSelection(cfg.ipFamily.ordinal, false)
        binding.etCustomDns.setText(cfg.customDns)
        binding.etRouteDirect.setText(cfg.routeDirectRules)
        binding.etRouteBlock.setText(cfg.routeBlockRules)
        binding.etUpstreamProxy.setText(cfg.upstreamProxy)

        binding.spinnerScanMode.setSelection(cfg.scanMode.ordinal, false)
        binding.spinnerNoize.setSelection(cfg.noizeProfile.ordinal, false)
        binding.switchTlsFragment.isChecked = cfg.tlsFragmentation
        binding.etMasqueSni.setText(cfg.masqueSni)
        binding.switchEch.isChecked = cfg.echEnabled
        binding.switchTlsVerify.isChecked = cfg.tlsVerify
        binding.etWgKeepalive.setText(if (cfg.wgKeepaliveSecs > 0) cfg.wgKeepaliveSecs.toString() else "")

        binding.etZeroTrustTeam.setText(cfg.zeroTrustTeam)
        binding.spinnerTheme.setSelection(cfg.themeMode.ordinal, false)
        val intervalIdx = statsIntervalsMs.indexOf(cfg.statsRefreshIntervalMs).takeIf { it >= 0 } ?: 0
        binding.spinnerStatsInterval.setSelection(intervalIdx, false)
    }

    private fun bindActions() {
        binding.btnToggleVpn.setOnClickListener {
            val phase = VpnStateStore.state.value.phase
            if (phase == TunnelPhase.VPN_CONNECTED || phase.isWorking) {
                stopVpnService()
            } else {
                requestVpnAndConnect()
            }
        }

        binding.btnToggleCore.setOnClickListener {
            val snap = VpnStateStore.state.value
            if (snap.coreActive) {
                AetherVpnService.stopCoreOnly(this)
            } else {
                AetherVpnService.startCoreOnly(this)
            }
        }

        binding.btnToggleTun.setOnClickListener {
            val snap = VpnStateStore.state.value
            if (snap.tunActive) {
                AetherVpnService.stopTunOnly(this)
            } else {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    AetherVpnService.startTunOnly(this)
                }
            }
        }

        binding.btnSplitTunnel.setOnClickListener {
            startActivity(Intent(this, AppSplitTunnelActivity::class.java))
        }

        binding.btnRunProbe.setOnClickListener {
            val egressPort = cachedConfig?.effectiveSocksPort ?: VpnConfig.DEFAULT_SOCKS_PORT
            binding.tvDiagnosticsSummary.text = "Running on-demand tunnel probe..."
            uiScope.launch {
                val res = withContext(Dispatchers.IO) {
                    TunnelValidator.verifyThroughSocks(egressPort)
                }
                res.onSuccess { v ->
                    DiagnosticsLog.record("Probe", "OK: IP=${v.publicIp}, WARP=${v.warpStatus}, RTT=${v.latencyMs}ms")
                }.onFailure { e ->
                    DiagnosticsLog.record("Probe", "Failed: ${e.message}")
                }
                refreshIdentityAndDiagnostics()
            }
        }

        binding.btnCopyLogs.setOnClickListener {
            val logs = DiagnosticsLog.snapshot()
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("AetherLogs", logs))
            toast("Diagnostic logs copied to clipboard")
        }

        binding.btnClearLogs.setOnClickListener {
            DiagnosticsLog.clear()
            userAutoScrollEnabled = true
            refreshIdentityAndDiagnostics()
        }

        binding.btnResetIdentity.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset WARP Identity?")
                .setMessage("Deletes local device identity and cached gateways. A fresh identity will be provisioned on next connect.")
                .setPositiveButton("Reset") { _, _ ->
                    uiScope.launch {
                        withContext(Dispatchers.IO) {
                            IdentityRepository.resetIdentity(filesDir)
                        }
                        DiagnosticsLog.record("Identity", "Local WARP identity reset by user")
                        refreshIdentityAndDiagnostics()
                        toast("Identity cleared")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnExportConfig.setOnClickListener {
            uiScope.launch {
                val cfg = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@MainActivity) }
                val json = cfg.toSafeBackupJson()
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("AetherConfigBackup", json))
                toast("Backup JSON copied to clipboard (secrets excluded)")
            }
        }

        binding.btnImportConfig.setOnClickListener {
            val input = EditText(this).apply { hint = "Paste backup JSON here" }
            AlertDialog.Builder(this)
                .setTitle("Restore Configuration")
                .setView(input)
                .setPositiveButton("Restore") { _, _ ->
                    VpnConfig.fromBackupJson(input.text.toString()).onSuccess { restored ->
                        cachedConfig = restored
                        uiScope.launch(Dispatchers.IO) {
                            VpnConfig.save(this@MainActivity, restored)
                        }
                        AetherApplication.applyTheme(restored.themeMode)
                        loadConfigIntoUi(restored)
                        toast("Configuration restored")
                    }.onFailure { e ->
                        toast("Invalid JSON: ${e.message}")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnSaveSettings.setOnClickListener {
            uiScope.launch {
                val cur = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@MainActivity) }
                val keepalive = binding.etWgKeepalive.text.toString().trim().toIntOrNull()?.coerceIn(0, 300) ?: 0
                val theme = AppThemeMode.entries[binding.spinnerTheme.selectedItemPosition]
                val interval = statsIntervalsMs[binding.spinnerStatsInterval.selectedItemPosition]
                val selectedRegionCode = VpnConfig.PSIPHON_REGIONS.getOrNull(
                    binding.spinnerPsiphonRegion.selectedItemPosition
                )?.first.orEmpty()

                val updated = cur.copy(
                    profileLevel = ProfileLevel.entries[binding.spinnerProfile.selectedItemPosition],
                    perfProfile = PerfProfile.entries[binding.spinnerPerfProfile.selectedItemPosition],
                    hevMode = HevMode.entries[binding.spinnerHevMode.selectedItemPosition],
                    protocol = AetherProtocol.entries[binding.spinnerProtocol.selectedItemPosition],
                    autoFallbackToH2 = binding.switchAutoH2Fallback.isChecked,
                    quickReconnect = binding.switchQuickReconnect.isChecked,
                    manualPeer = binding.etManualPeer.text.toString().trim(),
                    psiphonMode = PsiphonMode.entries[binding.spinnerPsiphonMode.selectedItemPosition],
                    psiphonRegion = selectedRegionCode,
                    exitLocation = binding.etExitLocation.text.toString().trim(),
                    ipFamily = IpFamily.entries[binding.spinnerIpFamily.selectedItemPosition],
                    customDns = binding.etCustomDns.text.toString().trim(),
                    routeDirectRules = binding.etRouteDirect.text.toString().trim(),
                    routeBlockRules = binding.etRouteBlock.text.toString().trim(),
                    upstreamProxy = binding.etUpstreamProxy.text.toString().trim(),
                    scanMode = ScanMode.entries[binding.spinnerScanMode.selectedItemPosition],
                    noizeProfile = NoizeProfile.entries[binding.spinnerNoize.selectedItemPosition],
                    tlsFragmentation = binding.switchTlsFragment.isChecked,
                    masqueSni = binding.etMasqueSni.text.toString().trim(),
                    echEnabled = binding.switchEch.isChecked,
                    tlsVerify = binding.switchTlsVerify.isChecked,
                    wgKeepaliveSecs = keepalive,
                    zeroTrustTeam = binding.etZeroTrustTeam.text.toString().trim(),
                    themeMode = theme,
                    statsRefreshIntervalMs = interval
                )
                cachedConfig = updated
                withContext(Dispatchers.IO) {
                    VpnConfig.save(this@MainActivity, updated)
                }
                AetherApplication.applyTheme(theme)
                loadConfigIntoUi(updated)
                toast(getString(R.string.saved_confirmation))
            }
        }
    }

    private fun renderHomeState(snapshot: TunnelSnapshot) {
        val statusText = when (snapshot.phase) {
            TunnelPhase.STOPPED -> getString(R.string.status_disconnected)
            TunnelPhase.WAITING_FOR_INTERNET -> "Waiting for Internet"
            TunnelPhase.STARTING_CORE -> "Starting Aether Core"
            TunnelPhase.STARTING_TUN -> "Establishing TUN Interface"
            TunnelPhase.VALIDATING_EGRESS -> "Validating WARP Egress"
            TunnelPhase.VPN_CONNECTED -> getString(R.string.status_connected)
            TunnelPhase.ERROR -> "Error: ${snapshot.lastErrorMessage ?: "Unknown"}"
            TunnelPhase.DEGRADED -> "Degraded Connection"
            TunnelPhase.RECONNECTING -> "Reconnecting..."
        }
        binding.tvConnectionStatus.text = statusText
        binding.tvConnectedProtocolBadge.text =
            if (snapshot.phase == TunnelPhase.VPN_CONNECTED) "Active: ${snapshot.protocolLabel}" else "Protocol: ${snapshot.protocolLabel}"

        when (snapshot.phase) {
            TunnelPhase.VPN_CONNECTED -> {
                binding.btnToggleVpn.text = getString(R.string.action_disconnect)
                binding.btnToggleVpn.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_disconnect))
                binding.btnToggleVpn.isEnabled = true
            }
            TunnelPhase.STOPPED, TunnelPhase.ERROR -> {
                binding.btnToggleVpn.text = getString(R.string.action_connect)
                binding.btnToggleVpn.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_primary))
                binding.btnToggleVpn.isEnabled = true
            }
            else -> {
                binding.btnToggleVpn.text = "Connecting..."
                binding.btnToggleVpn.isEnabled = true
            }
        }

        val pubIp = snapshot.egressPublicIp ?: "—"
        val latency = if (snapshot.lastLatencyMs != null) "${snapshot.lastLatencyMs} ms" else "—"
        binding.tvEgressInfo.text = "Public IP: $pubIp  •  Latency: $latency"

        if (snapshot.phase != TunnelPhase.VPN_CONNECTED && !snapshot.tunActive) {
            binding.tvTrafficStats.text = getString(R.string.label_stats_idle)
            binding.tvDuration.text = getString(R.string.label_duration_idle)
        }

        // Diagnostics component badges & toggles
        val egressPort = cachedConfig?.effectiveSocksPort ?: VpnConfig.DEFAULT_SOCKS_PORT
        binding.tvComponentStatusSummary.text =
            "Core: ${if (snapshot.coreActive) "Ready (127.0.0.1:$egressPort)" else "Stopped"} • TUN: ${if (snapshot.tunActive) "Active (/dev/tun)" else "Stopped"}"
        binding.btnToggleCore.text = if (snapshot.coreActive) getString(R.string.action_stop_core) else getString(R.string.action_start_core)
        binding.btnToggleTun.text = if (snapshot.tunActive) getString(R.string.action_stop_tun) else getString(R.string.action_start_tun)
    }

    private fun updateStatsLoop(snapshot: TunnelSnapshot) {
        statsJob?.cancel()
        if ((snapshot.phase != TunnelPhase.VPN_CONNECTED && !snapshot.tunActive) || currentTabId != R.id.nav_home) return
        val intervalMs = cachedConfig?.statsRefreshIntervalMs ?: 5_000L
        statsJob = uiScope.launch {
            while (true) {
                val stats = AetherVpnService.readLiveStats()
                val elapsedSec = if (snapshot.connectedSinceMs > 0L) {
                    ((System.currentTimeMillis() - snapshot.connectedSinceMs) / 1000L).coerceAtLeast(0L)
                } else 0L
                binding.tvDuration.text = "Duration: ${formatDuration(elapsedSec)}"
                binding.tvTrafficStats.text =
                    "TX: ${formatBytes(stats.txBytes)} (${stats.txPackets} pkts)  •  " +
                    "RX: ${formatBytes(stats.rxBytes)} (${stats.rxPackets} pkts)"
                delay(intervalMs)
            }
        }
    }

    private fun refreshIdentityAndDiagnostics() {
        uiScope.launch {
            val (id, cfg) = withContext(Dispatchers.IO) {
                Pair(IdentityRepository.load(filesDir), cachedConfig ?: VpnConfig.load(this@MainActivity))
            }
            if (!isActive) return@launch
            cachedConfig = cfg
            binding.tvIdentitySummary.text = buildString {
                append("Identity: ").append(if (id.provisioned) "Provisioned" else "Not provisioned").append(" | ")
                if (id.deviceId.isNotBlank()) append("Device: ").append(id.deviceId.take(12)).append("... | ")
                if (id.ipv4.isNotBlank()) append("IPv4: ").append(id.ipv4).append(" | ")
                append("Gateway: ").append(id.cachedGateway.ifBlank { "Auto" })
            }

            val snap = VpnStateStore.state.value
            val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
            val jvmUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0)
            binding.tvDiagnosticsSummary.text = buildString {
                append("Phase: ").append(snap.phase.label).append(" • ")
                append("DNS: ").append(cfg.customDns.ifBlank { "1.1.1.1 (TUN forced)" }).append(" • ")
                append("Heap: ").append(String.format(Locale.US, "%.1fM JVM / %.1fM Native", jvmUsedMb, nativeHeapMb))
            }

            val egressPort = cfg.effectiveSocksPort
            binding.tvComponentStatusSummary.text =
                "Core: ${if (snap.coreActive) "Ready (127.0.0.1:$egressPort)" else "Stopped"} • TUN: ${if (snap.tunActive) "Active (/dev/tun)" else "Stopped"}"
            binding.btnToggleCore.text = if (snap.coreActive) getString(R.string.action_stop_core) else getString(R.string.action_start_core)
            binding.btnToggleTun.text = if (snap.tunActive) getString(R.string.action_stop_tun) else getString(R.string.action_start_tun)
        }
    }

    private fun requestVpnAndConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        AetherVpnService.startVpn(this)
    }

    private fun stopVpnService() {
        AetherVpnService.stopVpn(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun formatDuration(seconds: Long): String {
        val s = seconds % 60L
        val m = (seconds / 60L) % 60L
        val h = seconds / 3600L
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
