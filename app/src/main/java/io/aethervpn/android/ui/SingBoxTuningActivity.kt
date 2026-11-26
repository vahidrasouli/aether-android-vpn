package io.aethervpn.android.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import io.aethervpn.android.R
import io.aethervpn.android.core.SingBoxBlockQuicMode
import io.aethervpn.android.core.SingBoxTuningPreset
import io.aethervpn.android.core.TunnelPhase
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.core.VpnStateStore
import io.aethervpn.android.databinding.ActivitySingboxTuningBinding
import io.aethervpn.android.vpn.AetherVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Material 3 Sing-box Engine & Tuning Lab screen for configuring
 * Sing-box Go runtime caps, sing-box 1.12 DNS/routing, and dialer/logging controls.
 */
class SingBoxTuningActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySingboxTuningBinding

    @Volatile
    private var isBindingUi = false

    @Volatile
    private var cachedConfig: VpnConfig? = null

    private var activeSnackbar: Snackbar? = null
    private var bypassDomainsDebounceJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySingboxTuningBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarSingBoxTuning.setNavigationOnClickListener { finish() }
        binding.btnResetSingBoxDefaults.setOnClickListener {
            lifecycleScope.launch {
                val base = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@SingBoxTuningActivity) }
                val reset = base.resetSingBoxTuningDefaults()
                persistConfigAsync(reset)
                bindConfigToUi(reset)
            }
        }

        setupTabs()
        setupListeners()

        lifecycleScope.launch {
            val cfg = withContext(Dispatchers.IO) { VpnConfig.load(this@SingBoxTuningActivity) }
            cachedConfig = cfg
            bindConfigToUi(cfg)
        }
    }

    private fun setupTabs() {
        binding.tabLayoutSingBoxTuning.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val isCoreTab = (tab?.position ?: 0) == 0
                binding.layoutSectionCoreEngine.visibility = if (isCoreTab) View.VISIBLE else View.GONE
                binding.cardSbEngineDialerLogging.visibility = if (isCoreTab) View.VISIBLE else View.GONE
                binding.layoutSectionConfigRouting.visibility = if (isCoreTab) View.GONE else View.VISIBLE
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    private fun setupListeners() {
        binding.chipGroupSbPreset.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val preset = when (checkedIds.firstOrNull()) {
                R.id.chipSbPresetEco -> SingBoxTuningPreset.ECO
                R.id.chipSbPresetBalanced -> SingBoxTuningPreset.BALANCED
                R.id.chipSbPresetTurbo -> SingBoxTuningPreset.TURBO
                else -> SingBoxTuningPreset.CUSTOM
            }
            if (preset != SingBoxTuningPreset.CUSTOM) {
                lifecycleScope.launch {
                    val base = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@SingBoxTuningActivity) }
                    val updated = base.applySingBoxTuningPreset(preset)
                    if (updated != base) {
                        persistConfigAsync(updated)
                        bindConfigToUi(updated)
                    }
                }
            }
        }

        binding.chipGroupSbGoMem.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val mem = when (checkedIds.firstOrNull()) {
                R.id.chipSbGoMem24 -> "24MiB"
                R.id.chipSbGoMem32 -> "32MiB"
                R.id.chipSbGoMem48 -> "48MiB"
                R.id.chipSbGoMem64 -> "64MiB"
                R.id.chipSbGoMemAuto -> "Auto"
                else -> "96MiB"
            }
            updateConfig { it.copy(sbGoMemLimit = mem) }
        }

        binding.chipGroupSbGoProcs.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val procs = when (checkedIds.firstOrNull()) {
                R.id.chipSbGoProcs1 -> "1"
                R.id.chipSbGoProcs4 -> "4"
                R.id.chipSbGoProcsAuto -> "Auto"
                else -> "2"
            }
            updateConfig { it.copy(sbGoMaxProcs = procs) }
        }

        binding.chipGroupSbGoGc.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val gc = when (checkedIds.firstOrNull()) {
                R.id.chipSbGoGc25 -> "25"
                R.id.chipSbGoGc100 -> "100"
                else -> "50"
            }
            updateConfig { it.copy(sbGoGc = gc) }
        }

        binding.chipGroupSbRemoteDns.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val remote = when (checkedIds.firstOrNull()) {
                R.id.chipSbRemoteDnsCfDoh -> "https://1.1.1.1/dns-query"
                R.id.chipSbRemoteDnsGoogleDoh -> "https://8.8.8.8/dns-query"
                R.id.chipSbRemoteDnsGoogleUdp -> "8.8.8.8"
                R.id.chipSbRemoteDnsQuad9Udp -> "9.9.9.9"
                else -> "1.1.1.1"
            }
            updateConfig { it.copy(sbRemoteDns = remote) }
        }

        binding.chipGroupSbDirectDns.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val direct = when (checkedIds.firstOrNull()) {
                R.id.chipSbDirectDnsGoogle -> "8.8.8.8"
                R.id.chipSbDirectDnsAli -> "223.5.5.5"
                else -> "1.1.1.1"
            }
            updateConfig { it.copy(sbDirectDns = direct) }
        }

        binding.chipGroupSbDnsStrategy.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val strategy = when (checkedIds.firstOrNull()) {
                R.id.chipSbDnsV4Only -> "ipv4_only"
                R.id.chipSbDnsPreferV6 -> "prefer_ipv6"
                else -> "prefer_ipv4"
            }
            updateConfig { it.copy(sbDnsStrategy = strategy) }
        }

        binding.switchSbSniff.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            updateConfig { it.copy(sbSniffEnabled = isChecked) }
        }

        binding.chipGroupSbBlockQuic.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chipSbQuicAlways -> SingBoxBlockQuicMode.ALWAYS
                R.id.chipSbQuicOff -> SingBoxBlockQuicMode.OFF
                else -> SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS
            }
            updateConfig { it.copy(sbBlockQuic = mode) }
        }

        binding.switchSbBypassPrivateIp.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            updateConfig { it.copy(sbBypassPrivateIp = isChecked) }
        }

        binding.switchSbBypassIrDomains.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            updateConfig { it.copy(sbBypassIrDomains = isChecked) }
        }

        binding.etSbCustomBypassDomains.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isBindingUi) return
                val nextValue = s?.toString()?.trim().orEmpty()
                bypassDomainsDebounceJob?.cancel()
                bypassDomainsDebounceJob = lifecycleScope.launch {
                    delay(450L)
                    updateConfig { it.copy(sbCustomBypassDomains = nextValue) }
                }
            }
        })

        binding.chipGroupSbConnectTimeout.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val timeoutSec = when (checkedIds.firstOrNull()) {
                R.id.chipSbTimeout5s -> 5
                R.id.chipSbTimeout15s -> 15
                else -> 10
            }
            updateConfig { it.copy(sbConnectTimeoutSec = timeoutSec) }
        }

        binding.switchSbTcpFastOpen.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            updateConfig { it.copy(sbTcpFastOpen = isChecked) }
        }

        binding.switchSbMultiplex.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            updateConfig { it.copy(sbMultiplexEnabled = isChecked) }
        }

        binding.chipGroupSbLogLevel.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val level = when (checkedIds.firstOrNull()) {
                R.id.chipSbLogError -> "error"
                R.id.chipSbLogInfo -> "info"
                else -> "warn"
            }
            updateConfig { it.copy(sbLogLevel = level) }
        }
    }

    private fun updateConfig(transform: (VpnConfig) -> VpnConfig) {
        lifecycleScope.launch {
            val base = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@SingBoxTuningActivity) }
            val updated = transform(base)
            if (updated == base) return@launch
            persistConfigAsync(updated)
            syncPresetHighlight(updated)
        }
    }

    private suspend fun persistConfigAsync(updated: VpnConfig) {
        cachedConfig = updated
        binding.tvSbAutoSaveStatus.text = "Saving…"
        withContext(Dispatchers.IO) {
            VpnConfig.save(this@SingBoxTuningActivity, updated)
        }
        binding.tvSbAutoSaveStatus.text = "Saved"
        notifyReconnectIfVpnActive()
    }

    private fun bindConfigToUi(cfg: VpnConfig) {
        isBindingUi = true
        try {
            syncPresetHighlight(cfg)

            binding.chipGroupSbGoMem.check(
                when {
                    cfg.sbGoMemLimit.equals("24MiB", true) -> R.id.chipSbGoMem24
                    cfg.sbGoMemLimit.equals("32MiB", true) -> R.id.chipSbGoMem32
                    cfg.sbGoMemLimit.equals("48MiB", true) -> R.id.chipSbGoMem48
                    cfg.sbGoMemLimit.equals("64MiB", true) -> R.id.chipSbGoMem64
                    cfg.sbGoMemLimit.equals("Auto", true) -> R.id.chipSbGoMemAuto
                    else -> R.id.chipSbGoMem96
                }
            )
            binding.chipGroupSbGoProcs.check(
                when {
                    cfg.sbGoMaxProcs == "1" -> R.id.chipSbGoProcs1
                    cfg.sbGoMaxProcs == "4" -> R.id.chipSbGoProcs4
                    cfg.sbGoMaxProcs.equals("Auto", true) -> R.id.chipSbGoProcsAuto
                    else -> R.id.chipSbGoProcs2
                }
            )
            binding.chipGroupSbGoGc.check(
                when (cfg.sbGoGc) {
                    "25" -> R.id.chipSbGoGc25
                    "100" -> R.id.chipSbGoGc100
                    else -> R.id.chipSbGoGc50
                }
            )

            binding.chipGroupSbRemoteDns.check(
                when (cfg.sbRemoteDns) {
                    "https://1.1.1.1/dns-query" -> R.id.chipSbRemoteDnsCfDoh
                    "https://8.8.8.8/dns-query" -> R.id.chipSbRemoteDnsGoogleDoh
                    "8.8.8.8" -> R.id.chipSbRemoteDnsGoogleUdp
                    "9.9.9.9" -> R.id.chipSbRemoteDnsQuad9Udp
                    else -> R.id.chipSbRemoteDnsCfUdp
                }
            )
            binding.chipGroupSbDirectDns.check(
                when (cfg.sbDirectDns) {
                    "8.8.8.8" -> R.id.chipSbDirectDnsGoogle
                    "223.5.5.5" -> R.id.chipSbDirectDnsAli
                    else -> R.id.chipSbDirectDnsCf
                }
            )
            binding.chipGroupSbDnsStrategy.check(
                when (cfg.sbDnsStrategy) {
                    "ipv4_only" -> R.id.chipSbDnsV4Only
                    "prefer_ipv6" -> R.id.chipSbDnsPreferV6
                    else -> R.id.chipSbDnsPreferV4
                }
            )

            binding.switchSbSniff.isChecked = cfg.sbSniffEnabled
            binding.chipGroupSbBlockQuic.check(
                when (cfg.sbBlockQuic) {
                    SingBoxBlockQuicMode.ALWAYS -> R.id.chipSbQuicAlways
                    SingBoxBlockQuicMode.OFF -> R.id.chipSbQuicOff
                    SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS -> R.id.chipSbQuicAuto
                }
            )
            binding.switchSbBypassPrivateIp.isChecked = cfg.sbBypassPrivateIp
            binding.switchSbBypassIrDomains.isChecked = cfg.sbBypassIrDomains
            if (binding.etSbCustomBypassDomains.text?.toString().orEmpty() != cfg.sbCustomBypassDomains) {
                binding.etSbCustomBypassDomains.setText(cfg.sbCustomBypassDomains)
            }

            binding.chipGroupSbConnectTimeout.check(
                when (cfg.sbConnectTimeoutSec) {
                    5 -> R.id.chipSbTimeout5s
                    15 -> R.id.chipSbTimeout15s
                    else -> R.id.chipSbTimeout10s
                }
            )
            binding.switchSbTcpFastOpen.isChecked = cfg.sbTcpFastOpen
            binding.switchSbMultiplex.isChecked = cfg.sbMultiplexEnabled
            binding.chipGroupSbLogLevel.check(
                when (cfg.sbLogLevel) {
                    "error" -> R.id.chipSbLogError
                    "info" -> R.id.chipSbLogInfo
                    else -> R.id.chipSbLogWarn
                }
            )
        } finally {
            isBindingUi = false
        }
    }

    private fun syncPresetHighlight(cfg: VpnConfig) {
        val preset = cfg.detectSingBoxTuningPreset()
        val prev = isBindingUi
        isBindingUi = true
        try {
            binding.chipGroupSbPreset.check(
                when (preset) {
                    SingBoxTuningPreset.ECO -> R.id.chipSbPresetEco
                    SingBoxTuningPreset.BALANCED -> R.id.chipSbPresetBalanced
                    SingBoxTuningPreset.TURBO -> R.id.chipSbPresetTurbo
                    SingBoxTuningPreset.CUSTOM -> R.id.chipSbPresetCustom
                }
            )
            binding.tvSbPresetSummary.text = when (preset) {
                SingBoxTuningPreset.ECO -> "Eco: 48MiB, 2 cores, GOGC=50, Error log, QUIC=Always, IPv4 Only, 15s timeout."
                SingBoxTuningPreset.BALANCED -> "Balanced: 96MiB, Auto cores, GOGC=100, Warn log, QUIC=Auto, IPv4 Only, 15s timeout."
                SingBoxTuningPreset.TURBO -> "Turbo: Auto mem, Auto cores, GOGC=100, Warn log, QUIC=Auto, IPv4 Only, TFO=On, 15s timeout."
                SingBoxTuningPreset.CUSTOM -> "Custom: ${cfg.sbGoMemLimit} • ${cfg.sbGoMaxProcs} cores • ${cfg.sbDnsStrategy} • ${cfg.sbConnectTimeoutSec}s dial"
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
            binding.singBoxTuningRoot,
            getString(R.string.saved_confirmation),
            Snackbar.LENGTH_LONG
        ).apply {
            setBackgroundTint(ContextCompat.getColor(this@SingBoxTuningActivity, R.color.surface_elevated))
            setTextColor(ContextCompat.getColor(this@SingBoxTuningActivity, R.color.text_primary))
            setActionTextColor(ContextCompat.getColor(this@SingBoxTuningActivity, R.color.accent_primary))
            setAction(R.string.action_reconnect) {
                AetherVpnService.startVpn(this@SingBoxTuningActivity)
            }
            show()
        }
    }
}
