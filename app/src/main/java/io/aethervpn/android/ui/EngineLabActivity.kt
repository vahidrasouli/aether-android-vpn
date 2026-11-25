package io.aethervpn.android.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import io.aethervpn.android.R
import io.aethervpn.android.core.EngineLabPreset
import io.aethervpn.android.core.NoizeProfile
import io.aethervpn.android.core.PsiphonProtocol
import io.aethervpn.android.core.ScanMode
import io.aethervpn.android.core.TunnelPhase
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.core.VpnStateStore
import io.aethervpn.android.databinding.ActivityEngineLabBinding
import io.aethervpn.android.vpn.AetherVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Material 3 Engine & Performance Lab screen for tuning
 * Aether Rust core, Psiphon Go runtime, HEV tunnel buffers, and protocol controls.
 */
class EngineLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEngineLabBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var isBindingUi = false

    @Volatile
    private var cachedConfig: VpnConfig? = null

    private var activeSnackbar: Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEngineLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarEngineLab.setNavigationOnClickListener { finish() }
        binding.btnResetLabDefaults.setOnClickListener {
            uiScope.launch {
                val base = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@EngineLabActivity) }
                val reset = base.resetEngineLabDefaults()
                persistConfigAsync(reset)
                bindConfigToUi(reset)
            }
        }

        setupProtocolSpinners()
        setupListeners()

        uiScope.launch {
            val cfg = withContext(Dispatchers.IO) { VpnConfig.load(this@EngineLabActivity) }
            cachedConfig = cfg
            bindConfigToUi(cfg)
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun setupProtocolSpinners() {
        fun adapter(items: List<String>) =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

        binding.spinnerLabNoize.adapter = adapter(NoizeProfile.entries.map { it.label })
        binding.spinnerLabScanMode.adapter = adapter(ScanMode.entries.map { it.label })
        binding.spinnerLabPsiphonProtocol.adapter = adapter(PsiphonProtocol.entries.map { it.label })
    }

    private fun setupListeners() {
        binding.chipGroupPreset.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val preset = when (checkedIds.firstOrNull()) {
                R.id.chipPresetEco -> EngineLabPreset.ECO
                R.id.chipPresetBalanced -> EngineLabPreset.BALANCED
                R.id.chipPresetTurbo -> EngineLabPreset.TURBO
                else -> EngineLabPreset.CUSTOM
            }
            if (preset != EngineLabPreset.CUSTOM) {
                uiScope.launch {
                    val base = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@EngineLabActivity) }
                    val updated = base.applyEngineLabPreset(preset)
                    if (updated != base) {
                        persistConfigAsync(updated)
                        bindConfigToUi(updated)
                    }
                }
            }
        }

        binding.chipGroupMaxClients.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val clients = when (checkedIds.firstOrNull()) {
                R.id.chipClients128 -> 128
                R.id.chipClients256 -> 256
                R.id.chipClients1024 -> 1024
                else -> 512
            }
            updateConfig { it.copy(aetherMaxClients = clients) }
        }

        binding.chipGroupH2Keepalive.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val secs = when (checkedIds.firstOrNull()) {
                R.id.chipH2Ka30 -> 30
                R.id.chipH2Ka60 -> 60
                R.id.chipH2Ka90 -> 90
                else -> 45
            }
            updateConfig { it.copy(aetherMasqueH2KeepaliveSecs = secs) }
        }

        binding.chipGroupTcpKeepalive.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val secs = when (checkedIds.firstOrNull()) {
                R.id.chipTcpKa30 -> 30
                R.id.chipTcpKa45 -> 45
                R.id.chipTcpKa90 -> 90
                R.id.chipTcpKa120 -> 120
                else -> 60
            }
            updateConfig { it.copy(aetherTcpKeepaliveSecs = secs) }
        }

        binding.chipGroupHalfClose.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val secs = when (checkedIds.firstOrNull()) {
                R.id.chipHalfClose15 -> 15
                R.id.chipHalfClose60 -> 60
                else -> 30
            }
            updateConfig { it.copy(aetherHalfCloseSecs = secs) }
        }

        binding.chipGroupGoMemLimit.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val mem = when (checkedIds.firstOrNull()) {
                R.id.chipGoMem24 -> "24MiB"
                R.id.chipGoMem32 -> "32MiB"
                R.id.chipGoMem64 -> "64MiB"
                R.id.chipGoMemAuto -> "Auto"
                else -> "48MiB"
            }
            updateConfig { it.copy(goMemLimit = mem) }
        }

        binding.chipGroupGoMaxProcs.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val procs = when (checkedIds.firstOrNull()) {
                R.id.chipGoProcs1 -> "1"
                R.id.chipGoProcs4 -> "4"
                R.id.chipGoProcsAuto -> "Auto"
                else -> "2"
            }
            updateConfig { it.copy(goMaxProcs = procs) }
        }

        binding.chipGroupGoGc.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val gc = when (checkedIds.firstOrNull()) {
                R.id.chipGoGc25 -> "25"
                R.id.chipGoGc100 -> "100"
                else -> "50"
            }
            updateConfig { it.copy(goGc = gc) }
        }

        binding.chipGroupTunMtu.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val mtu = when (checkedIds.firstOrNull()) {
                R.id.chipMtu1360 -> 1360
                R.id.chipMtu1400 -> 1400
                R.id.chipMtu1420 -> 1420
                else -> 1280
            }
            updateConfig { it.copy(tunMtu = mtu) }
        }

        binding.chipGroupTaskStack.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val stack = when (checkedIds.firstOrNull()) {
                R.id.chipStack24k -> 24576
                R.id.chipStack32k -> 32768
                else -> 16384
            }
            updateConfig { it.copy(taskStackSize = stack) }
        }

        binding.chipGroupUdpTimeout.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val timeoutMs = when (checkedIds.firstOrNull()) {
                R.id.chipUdp15s -> 15000
                R.id.chipUdp30s -> 30000
                else -> 5000
            }
            updateConfig { it.copy(udpTimeoutMs = timeoutMs) }
        }

        binding.switchHevMapDns.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            updateConfig { it.copy(mapDnsEnabled = isChecked) }
        }

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isBindingUi) return
                updateConfig { cfg ->
                    cfg.copy(
                        noizeProfile = NoizeProfile.entries[binding.spinnerLabNoize.selectedItemPosition],
                        scanMode = ScanMode.entries[binding.spinnerLabScanMode.selectedItemPosition],
                        psiphonProtocol = PsiphonProtocol.entries[binding.spinnerLabPsiphonProtocol.selectedItemPosition]
                    )
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spinnerLabNoize.onItemSelectedListener = spinnerListener
        binding.spinnerLabScanMode.onItemSelectedListener = spinnerListener
        binding.spinnerLabPsiphonProtocol.onItemSelectedListener = spinnerListener
    }

    private fun updateConfig(transform: (VpnConfig) -> VpnConfig) {
        uiScope.launch {
            val base = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@EngineLabActivity) }
            val updated = transform(base)
            if (updated == base) return@launch
            persistConfigAsync(updated)
            syncPresetHighlight(updated)
        }
    }

    private suspend fun persistConfigAsync(updated: VpnConfig) {
        cachedConfig = updated
        binding.tvLabAutoSaveStatus.text = "Saving…"
        withContext(Dispatchers.IO) {
            VpnConfig.save(this@EngineLabActivity, updated)
        }
        binding.tvLabAutoSaveStatus.text = "Saved"
        notifyReconnectIfVpnActive()
    }

    private fun bindConfigToUi(cfg: VpnConfig) {
        isBindingUi = true
        try {
            syncPresetHighlight(cfg)

            binding.chipGroupMaxClients.check(
                when (cfg.aetherMaxClients) {
                    128 -> R.id.chipClients128
                    256 -> R.id.chipClients256
                    1024 -> R.id.chipClients1024
                    else -> R.id.chipClients512
                }
            )
            binding.chipGroupH2Keepalive.check(
                when (cfg.aetherMasqueH2KeepaliveSecs) {
                    30 -> R.id.chipH2Ka30
                    60 -> R.id.chipH2Ka60
                    90 -> R.id.chipH2Ka90
                    else -> R.id.chipH2Ka45
                }
            )
            binding.chipGroupTcpKeepalive.check(
                when (cfg.aetherTcpKeepaliveSecs) {
                    30 -> R.id.chipTcpKa30
                    45 -> R.id.chipTcpKa45
                    90 -> R.id.chipTcpKa90
                    120 -> R.id.chipTcpKa120
                    else -> R.id.chipTcpKa60
                }
            )
            binding.chipGroupHalfClose.check(
                when (cfg.aetherHalfCloseSecs) {
                    15 -> R.id.chipHalfClose15
                    60 -> R.id.chipHalfClose60
                    else -> R.id.chipHalfClose30
                }
            )

            binding.chipGroupGoMemLimit.check(
                when {
                    cfg.goMemLimit.equals("24MiB", true) -> R.id.chipGoMem24
                    cfg.goMemLimit.equals("32MiB", true) -> R.id.chipGoMem32
                    cfg.goMemLimit.equals("64MiB", true) -> R.id.chipGoMem64
                    cfg.goMemLimit.equals("Auto", true) -> R.id.chipGoMemAuto
                    else -> R.id.chipGoMem48
                }
            )
            binding.chipGroupGoMaxProcs.check(
                when {
                    cfg.goMaxProcs == "1" -> R.id.chipGoProcs1
                    cfg.goMaxProcs == "4" -> R.id.chipGoProcs4
                    cfg.goMaxProcs.equals("Auto", true) -> R.id.chipGoProcsAuto
                    else -> R.id.chipGoProcs2
                }
            )
            binding.chipGroupGoGc.check(
                when (cfg.goGc) {
                    "25" -> R.id.chipGoGc25
                    "100" -> R.id.chipGoGc100
                    else -> R.id.chipGoGc50
                }
            )

            binding.chipGroupTunMtu.check(
                when (cfg.tunMtu) {
                    1360 -> R.id.chipMtu1360
                    1400 -> R.id.chipMtu1400
                    1420 -> R.id.chipMtu1420
                    else -> R.id.chipMtu1280
                }
            )
            binding.chipGroupTaskStack.check(
                when (cfg.taskStackSize) {
                    24576 -> R.id.chipStack24k
                    32768 -> R.id.chipStack32k
                    else -> R.id.chipStack16k
                }
            )
            binding.chipGroupUdpTimeout.check(
                when (cfg.udpTimeoutMs) {
                    15000 -> R.id.chipUdp15s
                    30000 -> R.id.chipUdp30s
                    else -> R.id.chipUdp5s
                }
            )
            binding.switchHevMapDns.isChecked = cfg.mapDnsEnabled

            binding.spinnerLabNoize.setSelection(cfg.noizeProfile.ordinal, false)
            binding.spinnerLabScanMode.setSelection(cfg.scanMode.ordinal, false)
            binding.spinnerLabPsiphonProtocol.setSelection(cfg.psiphonProtocol.ordinal, false)
        } finally {
            isBindingUi = false
        }
    }

    private fun syncPresetHighlight(cfg: VpnConfig) {
        val preset = cfg.detectEngineLabPreset()
        val prev = isBindingUi
        isBindingUi = true
        try {
            binding.chipGroupPreset.check(
                when (preset) {
                    EngineLabPreset.ECO -> R.id.chipPresetEco
                    EngineLabPreset.BALANCED -> R.id.chipPresetBalanced
                    EngineLabPreset.TURBO -> R.id.chipPresetTurbo
                    EngineLabPreset.CUSTOM -> R.id.chipPresetCustom
                }
            )
            binding.chipTcpKa45.visibility = if (cfg.aetherTcpKeepaliveSecs == 45) View.VISIBLE else View.GONE
            binding.tvPresetSummary.text = when (preset) {
                EngineLabPreset.ECO -> "Eco: 32MiB Go cap, 1 core, 256 clients, longer keepalives for max battery."
                EngineLabPreset.BALANCED -> "Balanced: 48MiB Go cap, 2 cores, 512 clients, 16KB HEV stack (safe default)."
                EngineLabPreset.TURBO -> "Turbo: 64MiB Go cap, 4 cores, 1024 clients, 24KB HEV stack for media."
                EngineLabPreset.CUSTOM -> "Custom: ${cfg.goMemLimit} Go • ${cfg.aetherMaxClients} clients • ${cfg.tunMtu} MTU • ${cfg.taskStackSize / 1024}KB stack"
            }
        } finally {
            isBindingUi = prev
        }
    }

    private fun notifyReconnectIfVpnActive() {
        val snap = VpnStateStore.state.value
        val vpnActive = snap.phase == TunnelPhase.VPN_CONNECTED ||
            snap.phase.isWorking ||
            snap.tunActive ||
            snap.coreActive
        if (!vpnActive) return

        activeSnackbar?.dismiss()
        activeSnackbar = Snackbar.make(
            binding.engineLabRoot,
            getString(R.string.saved_confirmation),
            Snackbar.LENGTH_LONG
        ).apply {
            setBackgroundTint(ContextCompat.getColor(this@EngineLabActivity, R.color.surface_elevated))
            setTextColor(ContextCompat.getColor(this@EngineLabActivity, R.color.text_primary))
            setActionTextColor(ContextCompat.getColor(this@EngineLabActivity, R.color.accent_primary))
            setAction(R.string.action_reconnect) {
                AetherVpnService.startVpn(this@EngineLabActivity)
            }
            show()
        }
    }
}
