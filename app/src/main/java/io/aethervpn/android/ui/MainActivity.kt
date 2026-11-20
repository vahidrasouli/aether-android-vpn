package io.aethervpn.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import io.aethervpn.android.AetherApplication
import io.aethervpn.android.R
import io.aethervpn.android.core.AetherProtocol
import io.aethervpn.android.core.AppThemeMode
import io.aethervpn.android.core.DiagnosticsLog
import io.aethervpn.android.core.IdentityRepository
import io.aethervpn.android.core.IpFamily
import io.aethervpn.android.core.NoizeProfile
import io.aethervpn.android.core.ProfileLevel
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal, organized, battery-first native Kotlin XML UI covering all 7 sections:
 * Home, Connection, Advanced Networking, Application Routing, WARP & Identity, Diagnostics, Settings.
 *
 * Battery discipline:
 * - Zero decorative animations, zero Compose runtime, zero FragmentTransaction overhead.
 * - Foreground stats/duration loop runs ONLY when Home tab is visible AND VPN is CONNECTED.
 * - Diagnostics probe and identity/memory refresh run strictly on-demand.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var statsJob: Job? = null
    private var currentTabId: Int = R.id.nav_home

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
        loadConfigIntoUi(VpnConfig.load(this))
        bindActions()
    }

    override fun onStart() {
        super.onStart()
        stateJob = uiScope.launch {
            VpnStateStore.state.collectLatest { snapshot ->
                renderHomeState(snapshot)
                updateStatsLoop(snapshot)
            }
        }
    }

    override fun onStop() {
        stateJob?.cancel()
        stateJob = null
        statsJob?.cancel()
        statsJob = null
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            currentTabId = item.itemId
            binding.sectionHome.visibility = if (currentTabId == R.id.nav_home) View.VISIBLE else View.GONE
            binding.sectionConnection.visibility = if (currentTabId == R.id.nav_connection) View.VISIBLE else View.GONE
            binding.sectionNetwork.visibility = if (currentTabId == R.id.nav_network) View.VISIBLE else View.GONE
            binding.sectionDiagnostics.visibility = if (currentTabId == R.id.nav_diagnostics) View.VISIBLE else View.GONE
            binding.sectionSettings.visibility = if (currentTabId == R.id.nav_settings) View.VISIBLE else View.GONE

            if (currentTabId == R.id.nav_diagnostics) {
                refreshIdentityAndDiagnostics()
            }
            updateStatsLoop(VpnStateStore.state.value)
            true
        }
    }

    private fun setupSpinners() {
        fun simpleAdapter(items: List<String>) =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

        binding.spinnerProfile.adapter = simpleAdapter(ProfileLevel.entries.map { it.label })
        binding.spinnerProtocol.adapter = simpleAdapter(AetherProtocol.entries.map { it.label })
        binding.spinnerScanMode.adapter = simpleAdapter(ScanMode.entries.map { it.label })
        binding.spinnerIpFamily.adapter = simpleAdapter(IpFamily.entries.map { it.label })
        binding.spinnerNoize.adapter = simpleAdapter(NoizeProfile.entries.map { it.label })
        binding.spinnerTheme.adapter = simpleAdapter(AppThemeMode.entries.map { it.label })
        binding.spinnerStatsInterval.adapter = simpleAdapter(statsIntervalLabels)

        binding.spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val level = ProfileLevel.entries[pos]
                val current = VpnConfig.load(this@MainActivity)
                if (current.profileLevel != level) {
                    val updated = current.applyPreset(level)
                    VpnConfig.save(this@MainActivity, updated)
                    loadConfigIntoUi(updated)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadConfigIntoUi(cfg: VpnConfig) {
        binding.tvActiveProfileBadge.text =
            "Profile: ${cfg.profileLevel.label} • Protocol: ${cfg.protocol.label} • Quick Reconnect: ${if (cfg.quickReconnect) "On" else "Off"}"

        binding.spinnerProfile.setSelection(cfg.profileLevel.ordinal, false)
        binding.spinnerProtocol.setSelection(cfg.protocol.ordinal, false)
        binding.switchAutoH2Fallback.isChecked = cfg.autoFallbackToH2
        binding.switchQuickReconnect.isChecked = cfg.quickReconnect
        binding.spinnerScanMode.setSelection(cfg.scanMode.ordinal, false)
        binding.etManualPeer.setText(cfg.manualPeer)

        binding.spinnerIpFamily.setSelection(cfg.ipFamily.ordinal, false)
        binding.etCustomDns.setText(cfg.customDns)
        binding.etRouteDirect.setText(cfg.routeDirectRules)
        binding.etRouteBlock.setText(cfg.routeBlockRules)
        binding.spinnerNoize.setSelection(cfg.noizeProfile.ordinal, false)
        binding.switchTlsFragment.isChecked = cfg.tlsFragmentation
        binding.etMasqueSni.setText(cfg.masqueSni)
        binding.switchEch.isChecked = cfg.echEnabled
        binding.switchTlsVerify.isChecked = cfg.tlsVerify
        binding.etUpstreamProxy.setText(cfg.upstreamProxy)
        binding.etWgKeepalive.setText(if (cfg.wgKeepaliveSecs > 0) cfg.wgKeepaliveSecs.toString() else "")

        binding.etZeroTrustTeam.setText(cfg.zeroTrustTeam)
        binding.spinnerTheme.setSelection(cfg.themeMode.ordinal, false)
        val intervalIdx = statsIntervalsMs.indexOf(cfg.statsRefreshIntervalMs).takeIf { it >= 0 } ?: 0
        binding.spinnerStatsInterval.setSelection(intervalIdx, false)
    }

    private fun bindActions() {
        binding.btnToggleVpn.setOnClickListener {
            val phase = VpnStateStore.state.value.phase
            if (phase == TunnelPhase.CONNECTED ||
                phase == TunnelPhase.CONNECTING ||
                phase == TunnelPhase.VALIDATING ||
                phase == TunnelPhase.RECONNECTING
            ) {
                stopVpnService()
            } else {
                requestVpnAndConnect()
            }
        }

        binding.btnSplitTunnel.setOnClickListener {
            startActivity(Intent(this, AppSplitTunnelActivity::class.java))
        }

        binding.btnSaveConnection.setOnClickListener {
            val cur = VpnConfig.load(this)
            val updated = cur.copy(
                profileLevel = ProfileLevel.entries[binding.spinnerProfile.selectedItemPosition],
                protocol = AetherProtocol.entries[binding.spinnerProtocol.selectedItemPosition],
                autoFallbackToH2 = binding.switchAutoH2Fallback.isChecked,
                quickReconnect = binding.switchQuickReconnect.isChecked,
                scanMode = ScanMode.entries[binding.spinnerScanMode.selectedItemPosition],
                manualPeer = binding.etManualPeer.text.toString().trim()
            )
            VpnConfig.save(this, updated)
            loadConfigIntoUi(updated)
            toast(getString(R.string.saved_confirmation))
        }

        binding.btnSaveNetwork.setOnClickListener {
            val cur = VpnConfig.load(this)
            val keepalive = binding.etWgKeepalive.text.toString().trim().toIntOrNull()?.coerceIn(0, 300) ?: 0
            val updated = cur.copy(
                ipFamily = IpFamily.entries[binding.spinnerIpFamily.selectedItemPosition],
                customDns = binding.etCustomDns.text.toString().trim(),
                routeDirectRules = binding.etRouteDirect.text.toString().trim(),
                routeBlockRules = binding.etRouteBlock.text.toString().trim(),
                noizeProfile = NoizeProfile.entries[binding.spinnerNoize.selectedItemPosition],
                tlsFragmentation = binding.switchTlsFragment.isChecked,
                masqueSni = binding.etMasqueSni.text.toString().trim(),
                echEnabled = binding.switchEch.isChecked,
                tlsVerify = binding.switchTlsVerify.isChecked,
                upstreamProxy = binding.etUpstreamProxy.text.toString().trim(),
                wgKeepaliveSecs = keepalive
            )
            VpnConfig.save(this, updated)
            toast(getString(R.string.saved_confirmation))
        }

        binding.btnSaveTeam.setOnClickListener {
            val cur = VpnConfig.load(this)
            VpnConfig.save(this, cur.copy(zeroTrustTeam = binding.etZeroTrustTeam.text.toString().trim()))
            refreshIdentityAndDiagnostics()
            toast(getString(R.string.saved_confirmation))
        }

        binding.btnResetIdentity.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset WARP Identity?")
                .setMessage("Deletes local device identity and cached gateways. A fresh identity will be provisioned on next connect.")
                .setPositiveButton("Reset") { _, _ ->
                    IdentityRepository.resetIdentity(filesDir)
                    DiagnosticsLog.record("Identity", "Local WARP identity reset by user")
                    refreshIdentityAndDiagnostics()
                    toast("Identity cleared")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnRunProbe.setOnClickListener {
            val cfg = VpnConfig.load(this)
            binding.tvDiagnosticsSummary.text = "Running on-demand tunnel probe..."
            uiScope.launch {
                val res = withContext(Dispatchers.IO) {
                    TunnelValidator.verifyThroughSocks(cfg.socksPort)
                }
                res.onSuccess { v ->
                    DiagnosticsLog.record("Probe", "OK: IP=${v.publicIp}, WARP=${v.warpStatus}, RTT=${v.latencyMs}ms")
                }.onFailure { e ->
                    DiagnosticsLog.record("Probe", "Failed: ${e.message}")
                }
                refreshIdentityAndDiagnostics()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            DiagnosticsLog.clear()
            refreshIdentityAndDiagnostics()
        }

        binding.btnExportConfig.setOnClickListener {
            val json = VpnConfig.load(this).toSafeBackupJson()
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("AetherConfigBackup", json))
            toast("Backup JSON copied to clipboard (secrets excluded)")
        }

        binding.btnImportConfig.setOnClickListener {
            val input = EditText(this).apply { hint = "Paste backup JSON here" }
            AlertDialog.Builder(this)
                .setTitle("Restore Configuration")
                .setView(input)
                .setPositiveButton("Restore") { _, _ ->
                    VpnConfig.fromBackupJson(input.text.toString()).onSuccess { restored ->
                        VpnConfig.save(this, restored)
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
            val cur = VpnConfig.load(this)
            val theme = AppThemeMode.entries[binding.spinnerTheme.selectedItemPosition]
            val interval = statsIntervalsMs[binding.spinnerStatsInterval.selectedItemPosition]
            val updated = cur.copy(themeMode = theme, statsRefreshIntervalMs = interval)
            VpnConfig.save(this, updated)
            AetherApplication.applyTheme(theme)
            toast("Settings applied")
        }
    }

    private fun renderHomeState(snapshot: TunnelSnapshot) {
        val statusText = when (snapshot.phase) {
            TunnelPhase.DISCONNECTED -> getString(R.string.status_disconnected)
            TunnelPhase.CONNECTING -> getString(R.string.status_connecting)
            TunnelPhase.VALIDATING -> getString(R.string.status_validating)
            TunnelPhase.CONNECTED -> getString(R.string.status_connected)
            TunnelPhase.RECONNECTING -> getString(R.string.status_reconnecting)
            TunnelPhase.ERROR -> getString(R.string.status_error)
        }
        binding.tvStatus.text = statusText
        binding.tvProtocol.text = "Protocol: ${snapshot.protocolLabel}"
        binding.tvGateway.text = "Gateway: ${snapshot.gatewayLabel.ifBlank { "Auto (WARP)" }}"

        val isActive = snapshot.phase == TunnelPhase.CONNECTED ||
            snapshot.phase == TunnelPhase.CONNECTING ||
            snapshot.phase == TunnelPhase.VALIDATING ||
            snapshot.phase == TunnelPhase.RECONNECTING

        binding.btnToggleVpn.text = if (isActive) {
            getString(R.string.action_disconnect)
        } else {
            getString(R.string.action_connect)
        }

        binding.tvPublicIp.text = if (snapshot.verifiedPublicIp != null) {
            val colo = snapshot.colo?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            "Verified IP: ${snapshot.verifiedPublicIp}$colo • WARP: ${snapshot.warpStatus} • ${snapshot.latencyMs}ms"
        } else {
            getString(R.string.label_ip_unverified)
        }

        binding.tvError.text = snapshot.errorMessage.orEmpty()
        binding.tvError.visibility = if (snapshot.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE

        if (snapshot.phase != TunnelPhase.CONNECTED) {
            binding.tvTrafficStats.text = getString(R.string.label_stats_idle)
            binding.tvDuration.text = getString(R.string.label_duration_idle)
        }
    }

    private fun updateStatsLoop(snapshot: TunnelSnapshot) {
        statsJob?.cancel()
        if (snapshot.phase != TunnelPhase.CONNECTED || currentTabId != R.id.nav_home) return
        val intervalMs = VpnConfig.load(this).statsRefreshIntervalMs
        statsJob = uiScope.launch {
            while (true) {
                val stats = AetherVpnService.readLiveStats()
                val elapsedSec = ((System.currentTimeMillis() - snapshot.connectedSinceMs) / 1000L).coerceAtLeast(0L)
                binding.tvDuration.text = "Duration: ${formatDuration(elapsedSec)}"
                binding.tvTrafficStats.text =
                    "TX: ${formatBytes(stats.txBytes)} (${stats.txPackets} pkts)  •  " +
                    "RX: ${formatBytes(stats.rxBytes)} (${stats.rxPackets} pkts)"
                delay(intervalMs)
            }
        }
    }

    private fun refreshIdentityAndDiagnostics() {
        val id = IdentityRepository.load(filesDir)
        val cfg = VpnConfig.load(this)
        binding.tvIdentitySummary.text = buildString {
            append("Status: ").append(if (id.provisioned) "Provisioned" else "Not provisioned yet").append('\n')
            if (id.deviceId.isNotBlank()) append("Device ID: ").append(id.deviceId).append('\n')
            if (id.ipv4.isNotBlank()) append("WARP IPv4: ").append(id.ipv4).append('\n')
            if (id.ipv6.isNotBlank()) append("WARP IPv6: ").append(id.ipv6).append('\n')
            if (id.organization.isNotBlank()) append("Organization: ").append(id.organization).append('\n')
            append("Cached Gateway: ").append(id.cachedGateway.ifBlank { "None" })
            if (id.cachedCarrier.isNotBlank()) append(" (").append(id.cachedCarrier).append(')')
        }

        val snap = VpnStateStore.state.value
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
        val jvmUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0)
        binding.tvDiagnosticsSummary.text = buildString {
            append("Tunnel Phase: ").append(snap.phase.name).append('\n')
            append("DNS Mode: ").append(cfg.customDns.ifBlank { "1.1.1.1 / 1.0.0.1 (TUN forced)" }).append('\n')
            append("Last Verified RTT: ").append(if (snap.latencyMs > 0) "${snap.latencyMs} ms" else "N/A").append('\n')
            append(String.format("App Heap: %.1f MiB JVM | %.1f MiB Native", jvmUsedMb, nativeHeapMb))
        }
        binding.tvRedactedLogs.text = DiagnosticsLog.snapshot()
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
        val intent = Intent(this, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024.0) return String.format("%.1f KiB", kib)
        val mib = kib / 1024.0
        if (mib < 1024.0) return String.format("%.1f MiB", mib)
        return String.format("%.2f GiB", mib / 1024.0)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
