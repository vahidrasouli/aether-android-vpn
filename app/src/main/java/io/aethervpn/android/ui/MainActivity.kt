package io.aethervpn.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.aethervpn.android.AetherApplication
import io.aethervpn.android.BuildConfig
import io.aethervpn.android.R
import io.aethervpn.android.core.AetherProtocol
import io.aethervpn.android.core.AppThemeMode
import io.aethervpn.android.core.CacheHygiene
import io.aethervpn.android.core.ChainMode
import io.aethervpn.android.core.DiagnosticsLog
import io.aethervpn.android.core.HevMode
import io.aethervpn.android.core.IdentityRepository
import io.aethervpn.android.core.IpFamily
import io.aethervpn.android.core.NoizeProfile
import io.aethervpn.android.core.PerfProfile
import io.aethervpn.android.core.ProfileLevel
import io.aethervpn.android.core.ProxyTestResult
import io.aethervpn.android.core.PsiphonMode
import io.aethervpn.android.core.SingBoxEngineManager
import io.aethervpn.android.core.SingBoxProfile
import io.aethervpn.android.core.SingBoxProfileRepository
import io.aethervpn.android.core.PsiphonProtocol
import io.aethervpn.android.core.ScanMode
import io.aethervpn.android.core.SplitTunnelMode
import io.aethervpn.android.core.StructuredUpstreamProxy
import io.aethervpn.android.core.TunnelPhase
import io.aethervpn.android.core.TunnelSnapshot
import io.aethervpn.android.core.TunnelValidator
import io.aethervpn.android.core.UpdateCheckResult
import io.aethervpn.android.core.UpdateManager
import io.aethervpn.android.core.UpstreamProxyPreset
import io.aethervpn.android.core.UpstreamProxyProtocol
import io.aethervpn.android.core.UpstreamProxyTester
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.core.VpnStateStore
import io.aethervpn.android.core.VpnUiAction
import io.aethervpn.android.core.VpnUiState
import io.aethervpn.android.core.VpnUiStateMachine
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
 * Lightweight, dark-first Material 3 native Kotlin XML UI structured into 3 tabs:
 * 1. Home / Connect: Hero status card, live exit/latency/chain card, minimal 60fps telemetry.
 * 2. Settings: Grouped Material 3 cards with instant auto-save (switches/spinners/App Picker)
 *    and 400ms debounced auto-save for text inputs — zero manual Save button friction.
 * 3. Logs & Diagnostics: Collapsible Diagnostic Controls + dark monospace terminal RecyclerView.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var statsJob: Job? = null
    private var logsJob: Job? = null
    private var debounceSaveJob: Job? = null
    private var currentTabId: Int = R.id.nav_home
    private lateinit var soarTabController: SoarTabController

    private val soarVpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            AetherVpnService.startSoarVpn(this)
        } else {
            Toast.makeText(this, R.string.error_vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }
    private var diagnosticsExpanded = false

    @Volatile
    private var isBindingUi = false

    @Volatile
    private var userAutoScrollEnabled = true

    @Volatile
    private var cachedConfig: VpnConfig? = null

    @Volatile
    private var cachedActiveSingBoxProfile: SingBoxProfile? = null

    private var activeSnackbar: Snackbar? = null
    private val vpnUiStateMachine = VpnUiStateMachine()

    @Volatile
    private var awaitingVpnPermission = false

    @Volatile
    private var initialLoadCompleted = false

    private val statsIntervalsMs = longArrayOf(5_000L, 3_000L, 10_000L)
    private val statsIntervalLabels = listOf(
        "5 seconds (Battery Efficient)",
        "3 seconds (Balanced)",
        "10 seconds (Ultra Low)"
    )

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        awaitingVpnPermission = false
        if (result.resultCode == RESULT_OK) {
            vpnUiStateMachine.tryStartConnect()
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

        setupNavigation()
        soarTabController = SoarTabController(this, binding) { prepIntent ->
            soarVpnPermissionLauncher.launch(prepIntent)
        }
        soarTabController.init()
        setupSpinners()
        setupAutoSaveListeners()
        setupLogRecyclerView()
        setupAboutAndUpdateEntry()
        bindActions()

        // Defer all disk I/O (SharedPreferences, profile parsing, cache cleanup, update check)
        // until after the first frame renders so cold-start is instantaneous.
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            requestNotificationPermissionIfNeeded()

            lifecycleScope.launch {
                val (cfg, activeProfile) = withContext(Dispatchers.IO) {
                    val prefs = getSharedPreferences(CacheHygiene.PREFS_NAME, MODE_PRIVATE)
                    val purged = CacheHygiene.purgeStaleCacheOnVersionChange(
                        prefs = prefs,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        cacheDir = cacheDir
                    )
                    if (purged) {
                        DiagnosticsLog.record(
                            "System",
                            "Updated to v${BuildConfig.VERSION_NAME} — cleared stale cache"
                        )
                    } else {
                        UpdateManager.cleanupInstalledApks(cacheDir)
                    }
                    VpnConfig.load(this@MainActivity) to SingBoxProfileRepository.load(filesDir).activeProfile
                }
                cachedConfig = cfg
                cachedActiveSingBoxProfile = activeProfile
                initialLoadCompleted = true
                loadConfigIntoUi(cfg)
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val result = UpdateManager.checkForUpdates(BuildConfig.VERSION_NAME)
                if (result is UpdateCheckResult.UpdateAvailable) {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            binding.tvUpdateAvailableBadge.visibility = View.VISIBLE
                            binding.tvSoftwareUpdateSummary.text =
                                "v${result.release.versionName} available — tap to update"
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!initialLoadCompleted) return
        lifecycleScope.launch {
            val (cfg, activeProfile) = withContext(Dispatchers.IO) {
                VpnConfig.load(this@MainActivity) to SingBoxProfileRepository.load(filesDir).activeProfile
            }
            cachedActiveSingBoxProfile = activeProfile
            if (cfg != cachedConfig) {
                cachedConfig = cfg
                loadConfigIntoUi(cfg)
            } else {
                refreshSingBoxSummary(cfg, activeProfile)
                updateHomeBadges(cfg, activeProfile)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        stateJob = uiScope.launch {
            VpnStateStore.state.collectLatest { snapshot ->
                if (!awaitingVpnPermission) {
                    vpnUiStateMachine.onServiceSnapshot(snapshot)
                }
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
        debounceSaveJob?.cancel()
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
            binding.sectionSoar.visibility = if (currentTabId == R.id.nav_soar) View.VISIBLE else View.GONE
            binding.sectionSettings.visibility = if (currentTabId == R.id.nav_settings) View.VISIBLE else View.GONE
            binding.sectionLogs.visibility = if (currentTabId == R.id.nav_logs) View.VISIBLE else View.GONE

            if (currentTabId == R.id.nav_soar) {
                soarTabController.reloadStore()
            } else if (currentTabId == R.id.nav_logs) {
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
        binding.rvLogs.setHasFixedSize(true)
        logAdapter = LogAdapter()
        binding.rvLogs.adapter = logAdapter

        binding.rvLogs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val canScrollDown = recyclerView.canScrollVertically(1)
                if (!canScrollDown) {
                    userAutoScrollEnabled = true
                } else if (dy < 0) {
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
        binding.spinnerChainMode.adapter = simpleAdapter(ChainMode.entries.map { it.label })
        binding.spinnerPsiphonMode.adapter = simpleAdapter(PsiphonMode.entries.map { it.label })
        binding.spinnerPsiphonRegion.adapter = simpleAdapter(VpnConfig.PSIPHON_REGIONS.map { it.second })
        binding.spinnerPsiphonProtocol.adapter = simpleAdapter(PsiphonProtocol.entries.map { it.label })
        binding.spinnerSplitMode.adapter = simpleAdapter(SplitTunnelMode.entries.map { it.label })
        binding.spinnerIpFamily.adapter = simpleAdapter(IpFamily.entries.map { it.label })
        binding.spinnerScanMode.adapter = simpleAdapter(ScanMode.entries.map { it.label })
        binding.spinnerNoize.adapter = simpleAdapter(NoizeProfile.entries.map { it.label })
        binding.spinnerTheme.adapter = simpleAdapter(AppThemeMode.entries.map { it.label })
        binding.spinnerStatsInterval.adapter = simpleAdapter(statsIntervalLabels)
        binding.spinnerUpstreamProtocol.adapter = simpleAdapter(UpstreamProxyProtocol.entries.map { it.label })
    }

    private fun setupAutoSaveListeners() {
        binding.spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (isBindingUi) return
                val level = ProfileLevel.entries[pos]
                uiScope.launch {
                    val current = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@MainActivity) }
                    if (current.profileLevel != level) {
                        val updated = current.applyPreset(level)
                        persistConfigAsync(updated)
                        loadConfigIntoUi(updated)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val immediateSpinners: List<Spinner> = listOf(
            binding.spinnerPerfProfile,
            binding.spinnerHevMode,
            binding.spinnerProtocol,
            binding.spinnerChainMode,
            binding.spinnerPsiphonMode,
            binding.spinnerPsiphonRegion,
            binding.spinnerPsiphonProtocol,
            binding.spinnerSplitMode,
            binding.spinnerIpFamily,
            binding.spinnerScanMode,
            binding.spinnerNoize,
            binding.spinnerTheme,
            binding.spinnerStatsInterval,
            binding.spinnerUpstreamProtocol
        )
        immediateSpinners.forEach { spinner ->
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (isBindingUi) return
                    if (spinner === binding.spinnerUpstreamProtocol) {
                        syncUpstreamPreviewAndPreset()
                    }
                    saveFromUiImmediate()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val immediateSwitches = listOf(
            binding.switchAutoH2Fallback,
            binding.switchQuickReconnect,
            binding.switchBypassIran,
            binding.switchTlsFragment,
            binding.switchSbTlsFragment,
            binding.switchEch,
            binding.switchTlsVerify,
            binding.switchUpstreamProxy
        )
        immediateSwitches.forEach { sw ->
            sw.setOnCheckedChangeListener { _, isChecked ->
                if (isBindingUi) return@setOnCheckedChangeListener
                if (sw === binding.switchUpstreamProxy) {
                    binding.layoutUpstreamProxyFields.visibility = if (isChecked) View.VISIBLE else View.GONE
                    syncUpstreamPreviewAndPreset()
                }
                saveFromUiImmediate()
            }
        }

        binding.chipGroupUpstreamPreset.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingUi) return@setOnCheckedStateChangeListener
            val preset = when (checkedIds.firstOrNull()) {
                R.id.chipPresetV2rayNg -> UpstreamProxyPreset.V2RAYNG
                R.id.chipPresetSingbox -> UpstreamProxyPreset.SINGBOX_CLASH
                else -> UpstreamProxyPreset.CUSTOM
            }
            if (preset != UpstreamProxyPreset.CUSTOM) {
                isBindingUi = true
                try {
                    binding.spinnerUpstreamProtocol.setSelection(preset.protocol.ordinal, false)
                    binding.etUpstreamHost.setText(preset.host)
                    binding.etUpstreamPort.setText(preset.port.toString())
                    binding.etUpstreamUsername.setText("")
                    binding.etUpstreamPassword.setText("")
                } finally {
                    isBindingUi = false
                }
                syncUpstreamPreviewAndPreset()
                saveFromUiImmediate()
            }
        }

        binding.btnTestProxy.setOnClickListener {
            runUpstreamProxyTest()
        }

        val debouncedFields = listOf(
            binding.etManualPeer,
            binding.etExitLocation,
            binding.etCustomDns,
            binding.etRouteDirect,
            binding.etRouteBlock,
            binding.etUpstreamHost,
            binding.etUpstreamPort,
            binding.etUpstreamUsername,
            binding.etUpstreamPassword,
            binding.etMasqueSni,
            binding.etWgKeepalive,
            binding.etZeroTrustTeam
        )
        val debouncedWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isBindingUi) return
                syncUpstreamPreviewAndPreset()
                scheduleDebouncedTextSave()
            }
        }
        debouncedFields.forEach { it.addTextChangedListener(debouncedWatcher) }
    }

    private fun scheduleDebouncedTextSave() {
        debounceSaveJob?.cancel()
        binding.tvAutoSaveStatus.text = "Saving…"
        debounceSaveJob = uiScope.launch {
            delay(400L)
            saveFromUiImmediate()
        }
    }

    private fun currentStructuredProxyFromUi(): StructuredUpstreamProxy {
        val protocol = UpstreamProxyProtocol.entries[
            binding.spinnerUpstreamProtocol.selectedItemPosition.coerceIn(0, UpstreamProxyProtocol.entries.lastIndex)
        ]
        val host = binding.etUpstreamHost.text.toString().trim()
        val port = binding.etUpstreamPort.text.toString().trim().toIntOrNull() ?: 0
        val username = binding.etUpstreamUsername.text?.toString().orEmpty()
        val password = binding.etUpstreamPassword.text?.toString().orEmpty()
        return StructuredUpstreamProxy(
            enabled = binding.switchUpstreamProxy.isChecked,
            protocol = protocol,
            host = host,
            port = port,
            username = username,
            password = password
        )
    }

    private fun syncUpstreamPreviewAndPreset() {
        val structured = currentStructuredProxyFromUi()
        val url = structured.copy(enabled = true).buildUrl(maskPassword = true)
        binding.tvUpstreamUrlPreview.text = url.ifEmpty { "Enter a valid host and port (1–65535)" }

        val presetId = when (structured.detectedPreset()) {
            UpstreamProxyPreset.V2RAYNG -> R.id.chipPresetV2rayNg
            UpstreamProxyPreset.SINGBOX_CLASH -> R.id.chipPresetSingbox
            UpstreamProxyPreset.CUSTOM -> R.id.chipPresetCustom
        }
        if (binding.chipGroupUpstreamPreset.checkedChipId != presetId) {
            val prev = isBindingUi
            isBindingUi = true
            try {
                binding.chipGroupUpstreamPreset.check(presetId)
            } finally {
                isBindingUi = prev
            }
        }
    }

    private fun runUpstreamProxyTest() {
        val structured = currentStructuredProxyFromUi()
        binding.btnTestProxy.isEnabled = false
        binding.tvProxyTestResult.visibility = View.VISIBLE
        binding.tvProxyTestResult.text = "Testing…"
        binding.tvProxyTestResult.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_elevated))
        binding.tvProxyTestResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        uiScope.launch {
            val result = UpstreamProxyTester.test(
                protocol = structured.protocol,
                host = structured.host,
                port = structured.port,
                username = structured.username,
                password = structured.password
            )
            binding.btnTestProxy.isEnabled = true
            when (result) {
                is ProxyTestResult.Success -> {
                    binding.tvProxyTestResult.text = result.formatSummary()
                    binding.tvProxyTestResult.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent_primary_container))
                    binding.tvProxyTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.log_success))
                }
                is ProxyTestResult.Failure -> {
                    binding.tvProxyTestResult.text = result.error
                    binding.tvProxyTestResult.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.error_container))
                    binding.tvProxyTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error_text))
                }
            }
        }
    }

    private fun buildConfigFromUi(base: VpnConfig): VpnConfig {
        val keepalive = binding.etWgKeepalive.text.toString().trim().toIntOrNull()?.coerceIn(0, 300) ?: 0
        val theme = AppThemeMode.entries[binding.spinnerTheme.selectedItemPosition.coerceIn(0, AppThemeMode.entries.lastIndex)]
        val interval = statsIntervalsMs[binding.spinnerStatsInterval.selectedItemPosition.coerceIn(0, statsIntervalsMs.lastIndex)]
        val selectedRegionCode = VpnConfig.PSIPHON_REGIONS.getOrNull(
            binding.spinnerPsiphonRegion.selectedItemPosition
        )?.first.orEmpty()

        return base.copy(
            profileLevel = ProfileLevel.entries[binding.spinnerProfile.selectedItemPosition],
            perfProfile = PerfProfile.entries[binding.spinnerPerfProfile.selectedItemPosition],
            hevMode = HevMode.entries[binding.spinnerHevMode.selectedItemPosition],
            protocol = AetherProtocol.entries[binding.spinnerProtocol.selectedItemPosition],
            autoFallbackToH2 = binding.switchAutoH2Fallback.isChecked,
            quickReconnect = binding.switchQuickReconnect.isChecked,
            manualPeer = binding.etManualPeer.text.toString().trim(),
            psiphonMode = PsiphonMode.entries[binding.spinnerPsiphonMode.selectedItemPosition],
            psiphonRegion = selectedRegionCode,
            psiphonProtocol = PsiphonProtocol.entries[
                binding.spinnerPsiphonProtocol.selectedItemPosition.coerceIn(0, PsiphonProtocol.entries.lastIndex)
            ],
            bypassIran = binding.switchBypassIran.isChecked,
            splitTunnelMode = SplitTunnelMode.entries[binding.spinnerSplitMode.selectedItemPosition],
            exitLocation = binding.etExitLocation.text.toString().trim(),
            ipFamily = IpFamily.entries[binding.spinnerIpFamily.selectedItemPosition],
            customDns = binding.etCustomDns.text.toString().trim(),
            routeDirectRules = binding.etRouteDirect.text.toString().trim(),
            routeBlockRules = binding.etRouteBlock.text.toString().trim(),
            upstreamProxy = currentStructuredProxyFromUi().buildUrl(),
            scanMode = ScanMode.entries[binding.spinnerScanMode.selectedItemPosition],
            noizeProfile = NoizeProfile.entries[binding.spinnerNoize.selectedItemPosition],
            tlsFragmentation = binding.switchTlsFragment.isChecked,
            sbTlsFragment = binding.switchSbTlsFragment.isChecked,
            masqueSni = binding.etMasqueSni.text.toString().trim(),
            echEnabled = binding.switchEch.isChecked,
            tlsVerify = binding.switchTlsVerify.isChecked,
            singBoxEnabled = binding.switchSingBoxEnabled.isChecked,
            chainMode = ChainMode.entries[
                binding.spinnerChainMode.selectedItemPosition.coerceIn(0, ChainMode.entries.lastIndex)
            ],
            singBoxOnly = if (binding.switchSingBoxEnabled.isChecked) {
                ChainMode.entries[
                    binding.spinnerChainMode.selectedItemPosition.coerceIn(0, ChainMode.entries.lastIndex)
                ] == ChainMode.SINGBOX_ONLY
            } else {
                false
            },
            wgKeepaliveSecs = keepalive,
            zeroTrustTeam = binding.etZeroTrustTeam.text.toString().trim(),
            themeMode = theme,
            statsRefreshIntervalMs = interval
        )
    }

    private fun saveFromUiImmediate() {
        uiScope.launch {
            val base = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@MainActivity) }
            val updated = buildConfigFromUi(base)
            if (updated == base) {
                binding.tvAutoSaveStatus.text = "Auto-save on"
                return@launch
            }
            val themeChanged = updated.themeMode != base.themeMode
            persistConfigAsync(updated)
            if (themeChanged) {
                AetherApplication.applyTheme(updated.themeMode)
            }
            updateHomeBadges(updated)
        }
    }

    private suspend fun persistConfigAsync(updated: VpnConfig) {
        cachedConfig = updated
        withContext(Dispatchers.IO) {
            VpnConfig.save(this@MainActivity, updated)
        }
        binding.tvAutoSaveStatus.text = "Saved"
        notifyReconnectIfVpnActive()
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
            binding.mainContentRoot,
            getString(R.string.saved_confirmation),
            Snackbar.LENGTH_SHORT
        ).apply {
            setBackgroundTint(ContextCompat.getColor(this@MainActivity, R.color.surface_elevated))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            show()
        }
    }

    private fun loadConfigIntoUi(cfg: VpnConfig) {
        isBindingUi = true
        try {
            updateHomeBadges(cfg)
            binding.switchSingBoxEnabled.isChecked = cfg.singBoxEnabled
            binding.switchSbTlsFragment.isChecked = cfg.sbTlsFragment
            binding.spinnerChainMode.setSelection(cfg.chainMode.ordinal, false)
            refreshSingBoxSummary(cfg)
            binding.tvEngineLabSummary.text =
                "${cfg.detectEngineLabPreset().label} • ${cfg.aetherMaxClients} clients • ${cfg.tunMtu} MTU"

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
            binding.spinnerPsiphonProtocol.setSelection(cfg.psiphonProtocol.ordinal, false)

            binding.switchBypassIran.isChecked = cfg.bypassIran
            binding.spinnerSplitMode.setSelection(cfg.splitTunnelMode.ordinal, false)
            updateSplitButtonLabel(cfg)
            binding.etExitLocation.setText(cfg.exitLocation)
            binding.spinnerIpFamily.setSelection(cfg.ipFamily.ordinal, false)
            binding.etCustomDns.setText(cfg.customDns)
            binding.etRouteDirect.setText(cfg.routeDirectRules)
            binding.etRouteBlock.setText(cfg.routeBlockRules)

            val parsedProxy = StructuredUpstreamProxy.parse(cfg.upstreamProxy)
            binding.switchUpstreamProxy.isChecked = parsedProxy.enabled
            binding.layoutUpstreamProxyFields.visibility = if (parsedProxy.enabled) View.VISIBLE else View.GONE
            binding.spinnerUpstreamProtocol.setSelection(parsedProxy.protocol.ordinal, false)
            binding.etUpstreamHost.setText(parsedProxy.host)
            binding.etUpstreamPort.setText(parsedProxy.port.toString())
            binding.etUpstreamUsername.setText(parsedProxy.username)
            binding.etUpstreamPassword.setText(parsedProxy.password)
            updateProxyBypassButtonLabel(cfg)
            syncUpstreamPreviewAndPreset()

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
        } finally {
            isBindingUi = false
        }
    }

    private fun updateSplitButtonLabel(cfg: VpnConfig) {
        val count = cfg.selectedPackages.size
        binding.btnSplitTunnel.text = if (count > 0) {
            "Select Apps ($count selected)"
        } else {
            getString(R.string.action_pick_apps)
        }
    }

    private fun updateProxyBypassButtonLabel(cfg: VpnConfig) {
        val count = cfg.proxyBypassApps.size
        binding.btnProxyBypassApps.text = if (count > 0) {
            "Bypass Proxy App (Direct) • $count selected"
        } else {
            getString(R.string.action_bypass_proxy_app)
        }
    }

    private fun refreshSingBoxSummary(
        cfg: VpnConfig,
        activeProfile: SingBoxProfile? = cachedActiveSingBoxProfile
    ) {
        binding.layoutSingBoxChain.visibility = if (cfg.singBoxEnabled) View.VISIBLE else View.GONE
        binding.tvChainModeDetail.text = cfg.chainMode.description
        updateSpeedCompareButtonVisibility(cfg)
        val summary = when {
            !cfg.singBoxEnabled -> "OFF • Zero overhead (tap to manage profiles)"
            activeProfile != null ->
                "ON • ${cfg.chainMode.label} • ${activeProfile.protocol}: ${activeProfile.name}"
            else ->
                "ON • ${cfg.chainMode.label} • No profile selected"
        }
        binding.tvSingBoxSummary.text = summary
    }

    private fun updateHomeBadges(
        cfg: VpnConfig,
        activeProfile: SingBoxProfile? = cachedActiveSingBoxProfile
    ) {
        val iranTag = if (cfg.bypassIran) " • IR Direct" else ""
        if (cfg.singBoxEnabled) {
            val profLabel = activeProfile?.let { "${it.protocol}: ${it.name}" } ?: "No Profile"
            binding.tvChainPill.text = "${cfg.chainMode.label}$iranTag"
            binding.tvActiveProfileBadge.text = "Engine: ${cfg.chainMode.label} ($profLabel) • 127.0.0.1:${cfg.effectiveSocksPort}"
            return
        }
        val psiphonSummary = when (cfg.psiphonMode) {
            PsiphonMode.OFF -> "WARP Only"
            PsiphonMode.WARP_TO_PSIPHON -> "WARP → Psiphon" + if (cfg.psiphonRegion.isNotBlank()) " (${cfg.psiphonRegion})" else ""
            PsiphonMode.PSIPHON_TO_WARP -> "Psiphon → WARP" + if (cfg.psiphonRegion.isNotBlank()) " (${cfg.psiphonRegion})" else ""
            PsiphonMode.PSIPHON_ONLY -> "Psiphon Only" + if (cfg.psiphonRegion.isNotBlank()) " (${cfg.psiphonRegion})" else ""
        }
        binding.tvChainPill.text = "$psiphonSummary$iranTag"
        binding.tvActiveProfileBadge.text =
            "Profile: ${cfg.profileLevel.label} • ${cfg.protocol.label} • Perf: ${cfg.perfProfile.label}"
    }

    private fun updateSpeedCompareButtonVisibility(cfg: VpnConfig? = cachedConfig) {
        val show = vpnUiStateMachine.current == VpnUiState.CONNECTED && cfg?.isSingBoxOnlyActive == true
        binding.btnSingBoxSpeedCompare.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun bindActions() {
        binding.btnSingBoxSpeedCompare.setOnClickListener {
            val cfg = cachedConfig
            if (vpnUiStateMachine.current != VpnUiState.CONNECTED || cfg?.isSingBoxOnlyActive != true) {
                toast("Connect in Sing-box Only mode first")
                return@setOnClickListener
            }
            binding.btnSingBoxSpeedCompare.isEnabled = false
            binding.btnSingBoxSpeedCompare.text = "Running Speed Check..."
            uiScope.launch {
                val result = withContext(Dispatchers.IO) {
                    SingBoxEngineManager.runSocksVsTunSpeedCompare(socksPort = cfg.effectiveSocksPort)
                }
                val line = SingBoxEngineManager.formatSpeedCompareLog(result)
                DiagnosticsLog.record("SingBox", line)
                binding.btnSingBoxSpeedCompare.isEnabled = true
                binding.btnSingBoxSpeedCompare.text = "Speed Check (SOCKS5 vs TUN)"
                toast(line)
            }
        }

        binding.cardEngineLab.setOnClickListener {
            startActivity(Intent(this, EngineLabActivity::class.java))
        }

        binding.cardSingBox.setOnClickListener {
            startActivity(Intent(this, SingBoxProfilesActivity::class.java))
        }

        binding.switchSingBoxEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!isBindingUi) {
                binding.layoutSingBoxChain.visibility = if (isChecked) View.VISIBLE else View.GONE
                saveFromUiImmediate()
                cachedConfig?.let {
                    refreshSingBoxSummary(it.copy(singBoxEnabled = isChecked))
                    updateHomeBadges(it.copy(singBoxEnabled = isChecked))
                }
            }
        }

        binding.btnToggleVpn.setOnClickListener {
            when (vpnUiStateMachine.onPrimaryButtonTap()) {
                VpnUiAction.START_CONNECT -> {
                    renderHomeState(VpnStateStore.state.value)
                    requestVpnAndConnect()
                }
                VpnUiAction.CANCEL_CONNECT,
                VpnUiAction.DISCONNECT -> {
                    renderHomeState(VpnStateStore.state.value)
                    stopVpnService()
                }
                VpnUiAction.IGNORED -> Unit
            }
        }

        binding.headerDiagnostics.setOnClickListener {
            diagnosticsExpanded = !diagnosticsExpanded
            binding.layoutDiagnosticsBody.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
            binding.tvDiagExpandToggle.text = if (diagnosticsExpanded) "Controls ▴" else "Controls ▾"
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
            uiScope.launch {
                val current = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@MainActivity) }
                AppPickerDialog.show(
                    context = this@MainActivity,
                    scope = uiScope,
                    initialSelection = current.selectedPackages
                ) { newSelection ->
                    uiScope.launch {
                        val latest = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@MainActivity) }
                        val updated = latest.copy(selectedPackages = newSelection)
                        persistConfigAsync(updated)
                        updateSplitButtonLabel(updated)
                    }
                }
            }
        }

        binding.btnProxyBypassApps.setOnClickListener {
            uiScope.launch {
                val current = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@MainActivity) }
                AppPickerDialog.show(
                    context = this@MainActivity,
                    scope = uiScope,
                    initialSelection = current.proxyBypassApps,
                    title = getString(R.string.action_bypass_proxy_app)
                ) { newSelection ->
                    uiScope.launch {
                        val latest = withContext(Dispatchers.IO) { cachedConfig ?: VpnConfig.load(this@MainActivity) }
                        val updated = latest.copy(proxyBypassApps = newSelection)
                        persistConfigAsync(updated)
                        updateProxyBypassButtonLabel(updated)
                    }
                }
            }
        }

        binding.btnRunProbe.setOnClickListener {
            val cfg = cachedConfig
            val egressPort = cfg?.effectiveSocksPort ?: VpnConfig.DEFAULT_SOCKS_PORT
            val psiphonMode = cfg?.psiphonMode ?: PsiphonMode.OFF
            val activeChain = if (cfg?.singBoxEnabled == true) cfg.chainMode else null
            binding.tvDiagnosticsSummary.text = "Running on-demand tunnel probe..."
            uiScope.launch {
                val res = withContext(Dispatchers.IO) {
                    TunnelValidator.verifyThroughSocks(
                        egressPort,
                        psiphonMode = psiphonMode,
                        chainMode = activeChain
                    )
                }
                res.onSuccess { v ->
                    DiagnosticsLog.record("System", "Probe OK: IP=${v.publicIp}, WARP=${v.warpStatus}, RTT=${v.latencyMs}ms")
                }.onFailure { e ->
                    DiagnosticsLog.record("System", "Probe failed: ${e.message}")
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
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset WARP Identity?")
                .setMessage("Deletes local device identity and cached gateways. A fresh identity will be provisioned on next connect.")
                .setPositiveButton("Reset") { _, _ ->
                    uiScope.launch {
                        withContext(Dispatchers.IO) {
                            IdentityRepository.resetIdentity(filesDir)
                        }
                        DiagnosticsLog.record("System", "Local WARP identity reset by user")
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
            MaterialAlertDialogBuilder(this)
                .setTitle("Restore Configuration")
                .setView(input)
                .setPositiveButton("Restore") { _, _ ->
                    VpnConfig.fromBackupJson(input.text.toString()).onSuccess { restored ->
                        uiScope.launch {
                            persistConfigAsync(restored)
                            AetherApplication.applyTheme(restored.themeMode)
                            loadConfigIntoUi(restored)
                            toast("Configuration restored")
                        }
                    }.onFailure { e ->
                        toast("Invalid JSON: ${e.message}")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun renderHomeState(snapshot: TunnelSnapshot) {
        val uiState = vpnUiStateMachine.current
        val statusText = when (uiState) {
            VpnUiState.CONNECTING -> when (snapshot.phase) {
                TunnelPhase.WAITING_FOR_INTERNET -> "Waiting for Internet"
                TunnelPhase.CORE_CONNECTED -> "Connecting… (Core Running)"
                TunnelPhase.SOCKS5_READY -> "Connecting… (SOCKS5 Ready)"
                TunnelPhase.STARTING_HEV -> "Connecting… (Starting TUN)"
                TunnelPhase.TUN_ACTIVE -> "Connecting… (Verifying)"
                TunnelPhase.RECONNECTING -> getString(R.string.status_reconnecting)
                else -> getString(R.string.status_connecting_badge)
            }
            VpnUiState.CONNECTED -> getString(R.string.status_connected)
            VpnUiState.DISCONNECTING -> getString(R.string.status_disconnecting)
            VpnUiState.DISCONNECTED -> if (snapshot.phase == TunnelPhase.ERROR) {
                getString(R.string.status_error)
            } else {
                getString(R.string.status_disconnected)
            }
        }
        binding.tvStatus.text = statusText
        binding.tvProtocol.text = "Protocol: ${snapshot.protocolLabel}"
        binding.tvGateway.text = "Gateway: ${snapshot.gatewayLabel.ifBlank { "Auto (WARP)" }}"

        val showSpinner = uiState == VpnUiState.CONNECTING || uiState == VpnUiState.DISCONNECTING
        binding.progressConnecting.visibility = if (showSpinner) View.VISIBLE else View.GONE
        binding.viewStatusDot.visibility = if (showSpinner) View.GONE else View.VISIBLE

        val dotColor = when {
            uiState == VpnUiState.CONNECTED -> R.color.accent_primary
            snapshot.phase == TunnelPhase.ERROR -> R.color.error_text
            else -> R.color.text_secondary
        }
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, dotColor))

        when (uiState) {
            VpnUiState.DISCONNECTED -> {
                binding.btnToggleVpn.isEnabled = true
                binding.btnToggleVpn.text = getString(R.string.action_connect)
                binding.btnToggleVpn.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_primary))
            }
            VpnUiState.CONNECTING -> {
                binding.btnToggleVpn.isEnabled = true
                binding.btnToggleVpn.text = getString(R.string.action_cancel)
                binding.btnToggleVpn.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.error_text))
            }
            VpnUiState.CONNECTED -> {
                binding.btnToggleVpn.isEnabled = true
                binding.btnToggleVpn.text = getString(R.string.action_disconnect)
                binding.btnToggleVpn.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.error_text))
            }
            VpnUiState.DISCONNECTING -> {
                binding.btnToggleVpn.isEnabled = false
                binding.btnToggleVpn.text = getString(R.string.status_disconnecting)
                binding.btnToggleVpn.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        binding.tvPublicIp.text = if (snapshot.verifiedPublicIp != null) {
            val colo = snapshot.colo?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            "IP: ${snapshot.verifiedPublicIp}$colo • ${snapshot.warpStatus} • ${snapshot.latencyMs}ms"
        } else {
            getString(R.string.label_ip_unverified)
        }

        val hasError = !snapshot.errorMessage.isNullOrBlank()
        binding.tvError.text = snapshot.errorMessage.orEmpty()
        binding.cardError.visibility = if (hasError) View.VISIBLE else View.GONE

        if (snapshot.phase != TunnelPhase.VPN_CONNECTED && !snapshot.tunActive) {
            binding.tvTrafficStats.text = getString(R.string.label_stats_idle)
            binding.tvDuration.text = getString(R.string.label_duration_idle)
        }

        val egressPort = cachedConfig?.effectiveSocksPort ?: VpnConfig.DEFAULT_SOCKS_PORT
        binding.tvComponentStatusSummary.text =
            "Core: ${if (snapshot.coreActive) "Ready (127.0.0.1:$egressPort)" else "Stopped"} • TUN: ${if (snapshot.tunActive) "Active (/dev/tun)" else "Stopped"}"
        binding.btnToggleCore.text = if (snapshot.coreActive) getString(R.string.action_stop_core) else getString(R.string.action_start_core)
        binding.btnToggleTun.text = if (snapshot.tunActive) getString(R.string.action_stop_tun) else getString(R.string.action_start_tun)
        updateSpeedCompareButtonVisibility()
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
            val dnsLabel = when {
                cfg.useMapDns -> "HEV mapdns (${VpnConfig.DNS_IPV4_MAPDNS}:53)"
                cfg.customDns.isNotBlank() -> cfg.customDns
                else -> "1.1.1.1 (TUN)"
            }
            binding.tvDiagnosticsSummary.text = buildString {
                append("Phase: ").append(snap.phase.label).append(" • ")
                append("DNS: ").append(dnsLabel).append(" • ")
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
            awaitingVpnPermission = true
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

    private fun setupAboutAndUpdateEntry() {
        val versionLabel = "v${BuildConfig.VERSION_NAME}"
        binding.tvAppVersion.text = versionLabel
        binding.tvAboutAppVersion.text = "${getString(R.string.app_name)} • $versionLabel"
        binding.tvAboutChannelBadge.text = getString(
            if (BuildConfig.DEBUG) R.string.channel_debug_prerelease else R.string.channel_stable
        )
        binding.tvAboutDeviceInfo.text =
            "Device Model: ${Build.MANUFACTURER} ${Build.MODEL} • " +
                "Android Version: Android ${Build.VERSION.RELEASE} • " +
                "SDK Level: API ${Build.VERSION.SDK_INT}"

        binding.cardSoftwareUpdate.setOnClickListener {
            startActivity(Intent(this, UpdateActivity::class.java))
        }

        binding.tvAboutGithub.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.GITHUB_REPO_URL)))
            }.onFailure {
                toast("Unable to open browser")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
