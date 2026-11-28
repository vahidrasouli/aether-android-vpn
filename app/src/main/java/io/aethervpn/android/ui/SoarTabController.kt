package io.aethervpn.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.aethervpn.android.R
import io.aethervpn.android.core.BenchmarkProgress
import io.aethervpn.android.core.ResolverBenchmarkMetrics
import io.aethervpn.android.core.ResolverPack
import io.aethervpn.android.core.ResolverScanState
import io.aethervpn.android.core.ScannerStatus
import io.aethervpn.android.core.SoarConnectionMode
import io.aethervpn.android.core.SoarEngineManager
import io.aethervpn.android.core.SoarPhase
import io.aethervpn.android.core.SoarPreset
import io.aethervpn.android.core.SoarProfile
import io.aethervpn.android.core.SoarProfileParser
import io.aethervpn.android.core.SoarProfileStore
import io.aethervpn.android.core.SoarProfileStoreState
import io.aethervpn.android.core.SoarResolverBenchmark
import io.aethervpn.android.core.SoarResolverPackStore
import io.aethervpn.android.core.SoarResolverPackStoreState
import io.aethervpn.android.core.SoarResolverScanner
import io.aethervpn.android.core.SoarRuntimeState
import io.aethervpn.android.core.SoarSettings
import io.aethervpn.android.core.SoarSpeedTester
import io.aethervpn.android.core.SoarStateStore
import io.aethervpn.android.core.SoarTransport
import io.aethervpn.android.databinding.ActivityMainBinding
import io.aethervpn.android.databinding.BottomSheetSoarProfileBinding
import io.aethervpn.android.databinding.BottomSheetSoarResolverPackBinding
import io.aethervpn.android.databinding.DialogSoarResolverPackBinding
import io.aethervpn.android.databinding.ItemSoarProfileBinding
import io.aethervpn.android.databinding.ItemSoarResolverIpBinding
import io.aethervpn.android.databinding.ItemSoarResolverPackBinding
import io.aethervpn.android.vpn.AetherVpnService
import io.aethervpn.android.vpn.SoarProxyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

class SoarTabController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onRequestVpnPermission: (Intent) -> Unit
) {
    private var storeState = SoarProfileStoreState()
    private var packState = SoarResolverPackStoreState()
    private var isBindingModeToggle = false
    private var isBindingQuickPreset = false
    private var speedTestJob: Job? = null

    private val profilesAdapter = SoarProfilesAdapter()
    private val packsAdapter = SoarResolverPacksAdapter()
    private val scanner = SoarResolverScanner(activity.lifecycleScope)

    private var scannerLoadedFileResolvers: List<String> = emptyList()
    private var scannerLoadedFileName: String = ""
    private var scannerSelectedSourcePackId: String? = null
    private var scannerSelectedTargetProfileId: String? = null
    private var scannerWorkers: Int = SoarResolverScanner.DEFAULT_CONCURRENCY
    private var isWorkersEditable: Boolean = true
    private var pendingExportPack: ResolverPack? = null

    private lateinit var scannerFilePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var packImportFileLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var packExportFileLauncher: ActivityResultLauncher<String>

    fun init() {
        registerLaunchers()

        binding.rvSoarProfiles.layoutManager = LinearLayoutManager(activity)
        binding.rvSoarProfiles.adapter = profilesAdapter

        binding.rvSoarPacks.layoutManager = LinearLayoutManager(activity)
        binding.rvSoarPacks.adapter = packsAdapter

        syncWorkersControls(scannerWorkers, isEditable = true)
        bindListeners()
        observeRuntimeState()
        observeScannerState()
    }

    private fun registerLaunchers() {
        scannerFilePickerLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) loadScannerTxtFile(uri)
        }

        packImportFileLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) importPackFromTxtUri(uri)
        }

        packExportFileLauncher = activity.registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            val pack = pendingExportPack
            pendingExportPack = null
            if (uri != null && pack != null) {
                exportPackToUri(pack, uri)
            }
        }
    }

    fun reloadStore() {
        activity.lifecycleScope.launch {
            val (loadedProfiles, loadedPacks) = withContext(Dispatchers.IO) {
                SoarProfileStore.load(activity.filesDir) to SoarResolverPackStore.load(activity.filesDir)
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            storeState = loadedProfiles
            packState = loadedPacks

            isBindingModeToggle = true
            val checkedId = if (storeState.connectionMode == SoarConnectionMode.VPN_FULL_DEVICE) {
                R.id.btnSoarModeVpn
            } else {
                R.id.btnSoarModeProxy
            }
            binding.toggleSoarMode.check(checkedId)
            isBindingModeToggle = false

            // Configs card
            profilesAdapter.submitList(storeState.profiles.toList())
            binding.tvSoarProfilesCount.text = storeState.profiles.size.toString()
            binding.tvSoarEmpty.visibility = if (storeState.profiles.isEmpty()) View.VISIBLE else View.GONE
            binding.rvSoarProfiles.visibility = if (storeState.profiles.isEmpty()) View.GONE else View.VISIBLE

            // Resolver Packs card
            packsAdapter.submitList(packState.packs.map { PackRow(it, it.id == packState.activePackId) })
            binding.tvSoarPacksCount.text = packState.packs.size.toString()
            binding.tvSoarPacksEmpty.visibility = if (packState.packs.isEmpty()) View.VISIBLE else View.GONE
            binding.rvSoarPacks.visibility = if (packState.packs.isEmpty()) View.GONE else View.VISIBLE

            bindDropdowns()
            syncQuickPresetChips()
            renderRuntimeState(SoarStateStore.state.value)
        }
    }

    private fun syncQuickPresetChips() {
        val active = storeState.activeProfile
        isBindingQuickPreset = true
        val chipId = when (active?.soarSettings?.matchingPreset()) {
            SoarPreset.BALANCED -> R.id.chipSoarQuickBalanced
            SoarPreset.TURBO -> R.id.chipSoarQuickTurbo
            SoarPreset.STABLE -> R.id.chipSoarQuickStable
            SoarPreset.STRICT -> R.id.chipSoarQuickStrict
            null -> View.NO_ID
        }
        if (chipId == View.NO_ID) {
            binding.chipGroupSoarQuickPresets.clearCheck()
        } else {
            binding.chipGroupSoarQuickPresets.check(chipId)
        }
        isBindingQuickPreset = false
    }

    private fun bindDropdowns() {
        // 1. Active Resolver Pack dropdown
        val activePackOptions = mutableListOf("None (Profile / Discovery)")
        activePackOptions.addAll(packState.packs.map { "${it.name} (${it.resolvers.size} DNS)" })
        binding.actSoarActivePack.setAdapter(
            ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, activePackOptions)
        )
        val activeIdx = packState.activePack?.let { ap ->
            packState.packs.indexOfFirst { it.id == ap.id } + 1
        } ?: 0
        binding.actSoarActivePack.setText(activePackOptions.getOrElse(activeIdx) { activePackOptions.first() }, false)
        binding.actSoarActivePack.setOnItemClickListener { _, _, position, _ ->
            val selectedId = if (position == 0) null else packState.packs.getOrNull(position - 1)?.id
            packState = SoarResolverPackStore.setActivePack(activity.filesDir, selectedId)
            reloadStore()
            toast(
                packState.activePack?.let { "Active Resolver Pack: ${it.name}" }
                    ?: "Using Profile / Discovery resolvers"
            )
        }

        // 2. Scanner Source Pack dropdown
        val sourcePackOptions = packState.packs.map { "${it.name} (${it.resolvers.size} DNS)" }
        binding.actScannerSourcePack.setAdapter(
            ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, sourcePackOptions)
        )
        if (packState.packs.isEmpty()) {
            scannerSelectedSourcePackId = null
            binding.actScannerSourcePack.setText("", false)
        } else {
            val currentIdx = packState.packs.indexOfFirst { it.id == scannerSelectedSourcePackId }
                .takeIf { it >= 0 } ?: 0
            val selectedPack = packState.packs[currentIdx]
            scannerSelectedSourcePackId = selectedPack.id
            binding.actScannerSourcePack.setText(sourcePackOptions[currentIdx], false)
        }
        binding.actScannerSourcePack.setOnItemClickListener { _, _, position, _ ->
            scannerSelectedSourcePackId = packState.packs.getOrNull(position)?.id
        }

        // 3. Scanner Target Soar Config dropdown
        val targetOptions = storeState.profiles.map { "${it.name} (${it.domain})" }
        binding.actScannerTargetConfig.setAdapter(
            ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, targetOptions)
        )
        if (storeState.profiles.isEmpty()) {
            scannerSelectedTargetProfileId = null
            binding.actScannerTargetConfig.setText("", false)
        } else {
            val preferredId = scannerSelectedTargetProfileId ?: storeState.activeProfile?.id
            val currentIdx = storeState.profiles.indexOfFirst { it.id == preferredId }
                .takeIf { it >= 0 } ?: 0
            val selectedProfile = storeState.profiles[currentIdx]
            scannerSelectedTargetProfileId = selectedProfile.id
            binding.actScannerTargetConfig.setText(targetOptions[currentIdx], false)
        }
        binding.actScannerTargetConfig.setOnItemClickListener { _, _, position, _ ->
            scannerSelectedTargetProfileId = storeState.profiles.getOrNull(position)?.id
        }
    }

    private fun bindListeners() {
        binding.toggleSoarMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBindingModeToggle) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.btnSoarModeVpn) {
                SoarConnectionMode.VPN_FULL_DEVICE
            } else {
                SoarConnectionMode.PROXY_ONLY
            }
            storeState = SoarProfileStore.setConnectionMode(activity.filesDir, mode)
            renderRuntimeState(SoarStateStore.state.value)
        }

        binding.btnSoarAdd.setOnClickListener { anchor ->
            val popup = PopupMenu(activity, anchor)
            popup.menu.add(0, 1, 0, "Import soar:// from Clipboard")
            popup.menu.add(0, 2, 1, "Manual Config")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> importFromClipboard()
                    2 -> showProfileEditorBottomSheet(null)
                }
                true
            }
            popup.show()
        }

        binding.btnSoarNewPack.setOnClickListener { anchor ->
            val popup = PopupMenu(activity, anchor)
            popup.menu.add(0, 1, 0, "Manual Entry")
            popup.menu.add(0, 2, 1, "Import .txt File")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showResolverPackEditorDialog(null)
                    2 -> packImportFileLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
                }
                true
            }
            popup.show()
        }

        binding.btnSoarConnect.setOnClickListener {
            val runtime = SoarStateStore.state.value
            if (runtime.isActive) {
                if (runtime.mode == SoarConnectionMode.VPN_FULL_DEVICE) {
                    AetherVpnService.stopSoarVpn(activity)
                } else {
                    SoarProxyService.stop(activity)
                }
                return@setOnClickListener
            }

            if (storeState.activeProfile == null) {
                toast("Add or select a Soar config first")
                return@setOnClickListener
            }

            if (storeState.connectionMode == SoarConnectionMode.VPN_FULL_DEVICE) {
                val prep = VpnService.prepare(activity)
                if (prep != null) {
                    onRequestVpnPermission(prep)
                } else {
                    AetherVpnService.startSoarVpn(activity)
                }
            } else {
                SoarProxyService.start(activity)
            }
        }

        binding.btnSoarOpenTelegram.setOnClickListener {
            val tgUri = Uri.parse(SoarEngineManager.buildTelegramSocksUri())
            val intent = Intent(Intent.ACTION_VIEW, tgUri)
            runCatching {
                activity.startActivity(intent)
            }.onFailure {
                copyTextToClipboard(
                    "Soar SOCKS5",
                    "${SoarEngineManager.DEFAULT_LISTEN_HOST}:${SoarEngineManager.DEFAULT_LISTEN_PORT}"
                )
                toast("Telegram not found — SOCKS5 127.0.0.1:1829 copied")
            }
        }

        binding.btnSoarCopyAddr.setOnClickListener {
            val addr = "${SoarEngineManager.DEFAULT_LISTEN_HOST}:${SoarEngineManager.DEFAULT_LISTEN_PORT}"
            copyTextToClipboard("Soar SOCKS5", addr)
            toast("Copied $addr")
        }

        binding.chipGroupSoarQuickPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingQuickPreset) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val preset = when (checkedId) {
                R.id.chipSoarQuickBalanced -> SoarPreset.BALANCED
                R.id.chipSoarQuickTurbo -> SoarPreset.TURBO
                R.id.chipSoarQuickStable -> SoarPreset.STABLE
                R.id.chipSoarQuickStrict -> SoarPreset.STRICT
                else -> return@setOnCheckedStateChangeListener
            }
            val active = storeState.activeProfile
            if (active == null) {
                toast("Add or select a Soar config first")
                syncQuickPresetChips()
                return@setOnCheckedStateChangeListener
            }
            val updated = active.copy(soarSettings = active.soarSettings.withPreset(preset))
            storeState = SoarProfileStore.upsertProfile(activity.filesDir, updated, setActive = true)
            profilesAdapter.submitList(storeState.profiles.toList())
            val note = if (SoarStateStore.state.value.isConnected) {
                "Applied ${preset.label} — reconnect Soar to take effect"
            } else {
                "Applied ${preset.label} preset"
            }
            toast(note)
        }

        binding.btnSoarSpeedTest.setOnClickListener {
            if (speedTestJob?.isActive == true) {
                speedTestJob?.cancel()
                speedTestJob = null
                binding.btnSoarSpeedTest.text = "Test Speed"
                binding.tvSoarSpeedResult.text = "Speed test cancelled"
                return@setOnClickListener
            }
            runSoarSpeedTest()
        }

        // Scanner controls
        binding.toggleScannerSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val useFile = checkedId == R.id.btnScannerSourceFile
            binding.layoutScannerFileSource.visibility = if (useFile) View.VISIBLE else View.GONE
            binding.layoutScannerPackSource.visibility = if (useFile) View.GONE else View.VISIBLE
        }

        binding.btnScannerPickFile.setOnClickListener {
            scannerFilePickerLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
        }

        binding.btnScannerWorkersMinus.setOnClickListener {
            stepScannerWorkers(-1)
        }

        binding.btnScannerWorkersPlus.setOnClickListener {
            stepScannerWorkers(1)
        }

        binding.btnScannerStartPauseResume.setOnClickListener {
            when (scanner.state.value.status) {
                ScannerStatus.RUNNING -> scanner.pause()
                ScannerStatus.PAUSED -> scanner.resume()
                ScannerStatus.IDLE, ScannerStatus.COMPLETED, ScannerStatus.STOPPED -> startScanner()
            }
        }

        binding.btnScannerStop.setOnClickListener {
            scanner.stop()
        }

        binding.btnScannerSaveHealthy.setOnClickListener {
            val healthy = scanner.state.value.healthyResolvers
            if (healthy.isEmpty()) {
                toast("No healthy resolvers discovered yet")
                return@setOnClickListener
            }
            showSaveHealthyPackDialog(healthy)
        }
    }

    private fun runSoarSpeedTest() {
        val profile = storeState.activeProfile
        if (profile == null) {
            toast("Add or select a Soar config first")
            return
        }
        val sizeBytes = when (binding.chipGroupSoarSpeedSize.checkedChipId) {
            R.id.chipSoarSpeed100k -> SoarSpeedTester.SIZE_100_KB
            R.id.chipSoarSpeed500k -> SoarSpeedTester.SIZE_500_KB
            else -> SoarSpeedTester.SIZE_200_KB
        }
        val sizeLabel = "${sizeBytes / 1024} KB"
        val activePack = packState.activePack

        binding.btnSoarSpeedTest.text = "Cancel"
        binding.tvSoarSpeedResult.setTextColor(ContextCompat.getColor(activity, R.color.accent_primary))
        binding.tvSoarSpeedResult.text = if (SoarStateStore.state.value.isConnected) {
            "Testing $sizeLabel via 127.0.0.1:1829…"
        } else {
            "Starting temp Soar (:1830) & testing $sizeLabel…"
        }

        speedTestJob = activity.lifecycleScope.launch {
            try {
                val res = SoarSpeedTester.runTest(
                    context = activity,
                    profile = profile,
                    activePack = activePack,
                    sizeBytes = sizeBytes,
                    privateDataDir = activity.filesDir
                )
                if (activity.isFinishing || activity.isDestroyed) return@launch
                res.onSuccess { r ->
                    binding.tvSoarSpeedResult.setTextColor(ContextCompat.getColor(activity, R.color.log_success))
                    binding.tvSoarSpeedResult.text = String.format(
                        Locale.US,
                        "TTFB: %d ms  •  Speed: %.1f KB/s",
                        r.ttfbMs,
                        r.speedKbps
                    )
                }.onFailure { err ->
                    binding.tvSoarSpeedResult.setTextColor(ContextCompat.getColor(activity, R.color.error_text))
                    binding.tvSoarSpeedResult.text = "Failed: ${err.message ?: "error"}"
                }
            } catch (_: CancellationException) {
                binding.tvSoarSpeedResult.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                binding.tvSoarSpeedResult.text = "Speed test cancelled"
            } finally {
                speedTestJob = null
                if (!activity.isFinishing && !activity.isDestroyed) {
                    binding.btnSoarSpeedTest.text = "Test Speed"
                }
            }
        }
    }

    private fun startScanner() {
        val targetProfile = storeState.profiles.firstOrNull { it.id == scannerSelectedTargetProfileId }
            ?: storeState.activeProfile
        if (targetProfile == null) {
            toast("Add a Soar config first")
            return
        }

        val useFile = binding.toggleScannerSource.checkedButtonId == R.id.btnScannerSourceFile
        val inputResolvers = if (useFile) {
            scannerLoadedFileResolvers
        } else {
            packState.packs.firstOrNull { it.id == scannerSelectedSourcePackId }?.resolvers.orEmpty()
        }

        if (inputResolvers.isEmpty()) {
            toast(if (useFile) "Choose a .txt file with resolver IPs first" else "Select a non-empty Resolver Pack")
            return
        }

        val concurrency = scannerWorkers
        val useTcpFallback = targetProfile.soarSettings.transport.equals("tcp", ignoreCase = true) ||
            targetProfile.soarSettings.transport.equals("auto", ignoreCase = true)

        scanner.start(
            resolvers = inputResolvers,
            targetDomain = targetProfile.domain,
            concurrency = concurrency,
            useTcpFallback = useTcpFallback
        )
    }

    private fun loadScannerTxtFile(uri: Uri) {
        binding.tvScannerFileSummary.text = "Loading…"
        activity.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.use { stream ->
                        SoarResolverScanner.parseResolverStream(stream)
                    }.orEmpty()
                }.getOrElse { emptyList() }
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            scannerLoadedFileResolvers = parsed
            scannerLoadedFileName = uri.lastPathSegment?.substringAfterLast('/') ?: "resolvers.txt"
            val formatted = NumberFormat.getIntegerInstance(Locale.US).format(parsed.size)
            binding.tvScannerFileSummary.text = if (parsed.isEmpty()) {
                "No valid IPs found in $scannerLoadedFileName"
            } else {
                "$scannerLoadedFileName ($formatted IPs)"
            }
        }
    }

    private fun importPackFromTxtUri(uri: Uri) {
        activity.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.use { stream ->
                        SoarResolverScanner.parseResolverStream(stream)
                    }.orEmpty()
                }.getOrElse { emptyList() }
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (parsed.isEmpty()) {
                toast("No valid IPv4 resolvers found in file")
                return@launch
            }
            val defaultName = (uri.lastPathSegment?.substringAfterLast('/') ?: "Imported Pack")
                .substringBeforeLast('.')
                .ifBlank { "Imported Pack" }
            showPromptPackNameDialog(
                title = "Save Imported Pack (${parsed.size} DNS)",
                defaultName = defaultName
            ) { packName ->
                packState = SoarResolverPackStore.upsertPack(
                    activity.filesDir,
                    ResolverPack(name = packName, resolvers = parsed),
                    setActive = packState.activePackId == null
                )
                reloadStore()
                toast("Saved '$packName' (${parsed.size} DNS)")
            }
        }
    }

    private fun exportPackToUri(pack: ResolverPack, uri: Uri) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(SoarResolverPackStore.exportPackToText(pack).toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            toast(if (ok) "Exported ${pack.name}" else "Failed to export ${pack.name}")
        }
    }

    private fun showSaveHealthyPackDialog(healthyResolvers: List<String>) {
        val defaultName = "Healthy ${healthyResolvers.size}"
        showPromptPackNameDialog(
            title = "Save ${healthyResolvers.size} Healthy Resolvers",
            defaultName = defaultName
        ) { packName ->
            packState = SoarResolverPackStore.upsertPack(
                activity.filesDir,
                ResolverPack(name = packName, resolvers = healthyResolvers),
                setActive = packState.activePackId == null
            )
            reloadStore()
            toast("Saved '$packName' (${healthyResolvers.size} DNS)")
        }
    }

    private fun showPromptPackNameDialog(
        title: String,
        defaultName: String,
        onConfirm: (String) -> Unit
    ) {
        val til = TextInputLayout(activity).apply {
            hint = "Pack Name"
            setPadding(48, 24, 48, 8)
        }
        val et = TextInputEditText(til.context).apply {
            setText(defaultName)
            setSingleLine(true)
        }
        til.addView(et)

        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(til)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text?.toString()?.trim().orEmpty().ifBlank { defaultName }
                onConfirm(name)
            }
            .show()
    }

    private fun showResolverPackViewerSheet(initialPack: ResolverPack) {
        val sheet = BottomSheetDialog(activity)
        val sb = BottomSheetSoarResolverPackBinding.inflate(LayoutInflater.from(activity))
        sheet.setContentView(sb.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        var currentPack = packState.packs.firstOrNull { it.id == initialPack.id } ?: initialPack
        var searchQuery = ""
        var benchmarkJob: Job? = null

        lateinit var ipAdapter: ResolverIpAdapter

        fun renderPackViewer() {
            val isActive = currentPack.id == packState.activePackId
            sb.tvPackViewerName.text = currentPack.name
            sb.tvPackViewerCountBadge.text = "${currentPack.resolvers.size} DNS"
            sb.btnPackViewerSetActive.text = if (isActive) "Active ✓" else "Use Pack"
            sb.btnPackViewerSetActive.setTextColor(
                ContextCompat.getColor(activity, if (isActive) R.color.log_success else R.color.accent_primary)
            )

            val indexed = currentPack.resolvers.mapIndexed { idx, ip ->
                ResolverIpRow(
                    index = idx + 1,
                    ip = ip,
                    metrics = currentPack.metrics[ip]
                )
            }
            val filtered = if (searchQuery.isBlank()) {
                indexed
            } else {
                val q = searchQuery.trim()
                indexed.filter { it.ip.contains(q, ignoreCase = true) }
            }
            ipAdapter.submitList(filtered)
            sb.tvPackViewerEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            sb.rvPackResolvers.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }

        ipAdapter = ResolverIpAdapter(
            onCopy = { ip ->
                copyTextToClipboard("DNS Resolver", ip)
                toast("Copied $ip")
            },
            onDelete = { ip ->
                val updatedResolvers = currentPack.resolvers.filterNot { it == ip }
                val updatedMetrics = currentPack.metrics.filterKeys { it != ip }
                currentPack = currentPack.copy(resolvers = updatedResolvers, metrics = updatedMetrics)
                packState = SoarResolverPackStore.upsertPack(activity.filesDir, currentPack)
                reloadStore()
                renderPackViewer()
            }
        )

        sb.rvPackResolvers.layoutManager = LinearLayoutManager(activity)
        sb.rvPackResolvers.adapter = ipAdapter

        sb.etPackSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                renderPackViewer()
            }
        })

        sb.btnPackViewerSetActive.setOnClickListener {
            val isActive = currentPack.id == packState.activePackId
            val nextId = if (isActive) null else currentPack.id
            packState = SoarResolverPackStore.setActivePack(activity.filesDir, nextId)
            reloadStore()
            renderPackViewer()
        }

        sb.btnPackAddIp.setOnClickListener {
            val til = TextInputLayout(activity).apply {
                hint = "IPv4 Resolver(s), one per line or comma-separated"
                setPadding(48, 24, 48, 8)
            }
            val et = TextInputEditText(til.context).apply {
                minLines = 2
                maxLines = 5
            }
            til.addView(et)

            MaterialAlertDialogBuilder(activity)
                .setTitle("Add Resolver IP")
                .setView(til)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add") { _, _ ->
                    val raw = et.text?.toString().orEmpty()
                    val parsed = SoarResolverScanner.parseResolverLines(raw.lineSequence())
                    if (parsed.isEmpty()) {
                        toast("Enter at least one valid IPv4 address")
                        return@setPositiveButton
                    }
                    val merged = (currentPack.resolvers + parsed).distinct()
                    val addedCount = merged.size - currentPack.resolvers.size
                    currentPack = currentPack.copy(resolvers = merged)
                    packState = SoarResolverPackStore.upsertPack(activity.filesDir, currentPack)
                    reloadStore()
                    renderPackViewer()
                    toast(if (addedCount > 0) "Added $addedCount IP(s)" else "IP(s) already in pack")
                }
                .show()
        }

        sb.btnPackExport.setOnClickListener {
            pendingExportPack = currentPack
            val safeName = currentPack.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            packExportFileLauncher.launch("$safeName.txt")
        }

        sb.btnPackBenchmarkCancel.setOnClickListener {
            benchmarkJob?.cancel()
        }

        sb.btnPackBenchmark.setOnClickListener {
            if (benchmarkJob?.isActive == true) return@setOnClickListener
            val targetProfile = storeState.profiles.firstOrNull { it.id == scannerSelectedTargetProfileId }
                ?: storeState.activeProfile
            if (targetProfile == null || targetProfile.domain.isBlank()) {
                toast("Add a Soar config with a valid domain first")
                return@setOnClickListener
            }
            if (currentPack.resolvers.isEmpty()) {
                toast("Pack is empty")
                return@setOnClickListener
            }

            val useTcpFallback = targetProfile.soarSettings.transport.equals("tcp", ignoreCase = true) ||
                targetProfile.soarSettings.transport.equals("auto", ignoreCase = true)

            sb.btnPackBenchmark.isEnabled = false
            sb.layoutPackBenchmarkProgress.visibility = View.VISIBLE
            sb.progressPackBenchmark.progress = 0
            sb.tvPackBenchmarkStatus.text = "Benchmarking 0 / ${currentPack.resolvers.size} (5 probes/IP)…"

            benchmarkJob = activity.lifecycleScope.launch {
                try {
                    val sortedMetrics = SoarResolverBenchmark.benchmarkPack(
                        resolvers = currentPack.resolvers,
                        domain = targetProfile.domain,
                        useTcpFallback = useTcpFallback
                    ) { progress: BenchmarkProgress ->
                        activity.runOnUiThread {
                            if (!sheet.isShowing) return@runOnUiThread
                            val pct = if (progress.totalCount > 0) {
                                ((progress.completedCount * 100L) / progress.totalCount).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            sb.progressPackBenchmark.setProgressCompat(pct, true)
                            sb.tvPackBenchmarkStatus.text =
                                "Benchmarking ${progress.completedCount} / ${progress.totalCount} (5 probes/IP)…"
                        }
                    }

                    val sortedIps = sortedMetrics.map { it.ip }
                    val metricsMap = sortedMetrics.associateBy { it.ip }
                    currentPack = currentPack.copy(resolvers = sortedIps, metrics = metricsMap)
                    packState = SoarResolverPackStore.upsertPack(activity.filesDir, currentPack)
                    reloadStore()
                    renderPackViewer()
                    toast("Ranked ${sortedIps.size} resolvers (0% loss & lowest jitter first)")
                } catch (_: CancellationException) {
                    toast("Benchmark cancelled")
                } finally {
                    benchmarkJob = null
                    if (sheet.isShowing) {
                        sb.btnPackBenchmark.isEnabled = true
                        sb.layoutPackBenchmarkProgress.visibility = View.GONE
                    }
                }
            }
        }

        sheet.setOnDismissListener {
            benchmarkJob?.cancel()
            benchmarkJob = null
        }

        renderPackViewer()
        sheet.show()
    }

    private fun showResolverPackEditorDialog(existing: ResolverPack?) {
        val dlgBinding = DialogSoarResolverPackBinding.inflate(LayoutInflater.from(activity))
        if (existing != null) {
            dlgBinding.etPackName.setText(existing.name)
            dlgBinding.etPackResolvers.setText(existing.resolvers.joinToString("\n"))
        }

        fun updateCount(raw: String) {
            val count = SoarResolverScanner.parseResolverLines(raw.lineSequence()).size
            dlgBinding.tvPackEditorStats.text = "$count valid resolvers"
        }
        updateCount(dlgBinding.etPackResolvers.text?.toString().orEmpty())

        dlgBinding.etPackResolvers.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateCount(s?.toString().orEmpty())
            }
        })

        MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) "New Resolver Pack" else "Edit Resolver Pack")
            .setView(dlgBinding.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val rawResolvers = dlgBinding.etPackResolvers.text?.toString().orEmpty()
                val parsed = SoarResolverScanner.parseResolverLines(rawResolvers.lineSequence())
                if (parsed.isEmpty()) {
                    toast("Enter at least one valid IPv4 resolver")
                    return@setPositiveButton
                }
                val name = dlgBinding.etPackName.text?.toString()?.trim().orEmpty()
                    .ifBlank { "Resolver Pack ${packState.packs.size + 1}" }
                val pack = existing?.copy(name = name, resolvers = parsed)
                    ?: ResolverPack(name = name, resolvers = parsed)
                packState = SoarResolverPackStore.upsertPack(
                    activity.filesDir,
                    pack,
                    setActive = packState.activePackId == null
                )
                reloadStore()
            }
            .show()
    }

    private fun observeRuntimeState() {
        activity.lifecycleScope.launch {
            SoarStateStore.state.collectLatest { state ->
                renderRuntimeState(state)
            }
        }
    }

    private fun observeScannerState() {
        activity.lifecycleScope.launch {
            scanner.state.collectLatest { state ->
                renderScannerState(state)
            }
        }
    }

    private fun renderScannerState(state: ResolverScanState) {
        val nf = NumberFormat.getIntegerInstance(Locale.US)
        binding.tvScannerStatusPill.text = state.status.label
        val pillColor = when (state.status) {
            ScannerStatus.RUNNING -> R.color.accent_primary
            ScannerStatus.PAUSED -> R.color.warning_text
            ScannerStatus.COMPLETED -> R.color.log_success
            ScannerStatus.STOPPED, ScannerStatus.IDLE -> R.color.text_secondary
        }
        binding.tvScannerStatusPill.setTextColor(ContextCompat.getColor(activity, pillColor))

        binding.tvStatTotal.text = nf.format(state.totalCount)
        binding.tvStatHealthy.text = nf.format(state.healthyCount)
        binding.tvStatRejected.text = nf.format(state.rejectedCount)
        binding.tvStatPercent.text = "${state.progressPercent}%"
        binding.progressScanner.setProgressCompat(state.progressPercent, true)

        if (state.errorMessage.isNullOrBlank()) {
            binding.tvScannerError.visibility = View.GONE
        } else {
            binding.tvScannerError.text = state.errorMessage
            binding.tvScannerError.visibility = View.VISIBLE
        }

        when (state.status) {
            ScannerStatus.RUNNING -> {
                binding.btnScannerStartPauseResume.text = "Pause"
                binding.btnScannerStartPauseResume.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(activity, R.color.warning_text)
                )
                binding.btnScannerStop.isEnabled = true
                syncWorkersControls(scannerWorkers, isEditable = false)
            }
            ScannerStatus.PAUSED -> {
                binding.btnScannerStartPauseResume.text = "Resume"
                binding.btnScannerStartPauseResume.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(activity, R.color.accent_primary)
                )
                binding.btnScannerStop.isEnabled = true
                syncWorkersControls(scannerWorkers, isEditable = false)
            }
            ScannerStatus.IDLE, ScannerStatus.COMPLETED, ScannerStatus.STOPPED -> {
                binding.btnScannerStartPauseResume.text = "Start"
                binding.btnScannerStartPauseResume.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(activity, R.color.accent_primary)
                )
                binding.btnScannerStop.isEnabled = false
                syncWorkersControls(scannerWorkers, isEditable = true)
            }
        }

        binding.btnScannerSaveHealthy.isEnabled = state.healthyCount > 0
    }

    private fun stepScannerWorkers(delta: Int) {
        if (!isWorkersEditable) return
        val next = (scannerWorkers + delta).coerceIn(
            SoarResolverScanner.MIN_CONCURRENCY,
            SoarResolverScanner.MAX_CONCURRENCY
        )
        syncWorkersControls(next, isEditable = isWorkersEditable)
    }

    private fun syncWorkersControls(workers: Int, isEditable: Boolean) {
        val clamped = workers.coerceIn(
            SoarResolverScanner.MIN_CONCURRENCY,
            SoarResolverScanner.MAX_CONCURRENCY
        )
        scannerWorkers = clamped
        isWorkersEditable = isEditable
        binding.tvScannerConcurrencyBadge.text = clamped.toString()
        binding.btnScannerWorkersMinus.isEnabled = isEditable && clamped > SoarResolverScanner.MIN_CONCURRENCY
        binding.btnScannerWorkersPlus.isEnabled = isEditable && clamped < SoarResolverScanner.MAX_CONCURRENCY
    }

    private fun renderRuntimeState(state: SoarRuntimeState) {
        val selectedMode = storeState.connectionMode
        val activeProfileName = storeState.activeProfile?.name ?: "No config selected"
        val listenAddr = state.listenAddress

        binding.tvSoarStatusPill.text = state.phase.label
        val pillColor = when (state.phase) {
            SoarPhase.CONNECTED -> R.color.log_success
            SoarPhase.STARTING, SoarPhase.DISCONNECTING -> R.color.warning_text
            SoarPhase.ERROR -> R.color.error_text
            SoarPhase.STOPPED -> R.color.text_secondary
        }
        binding.tvSoarStatusPill.setTextColor(ContextCompat.getColor(activity, pillColor))

        val activeRes = state.activeResolvers
        val totalRes = state.totalResolvers
        if (state.isConnected && activeRes != null && totalRes != null) {
            binding.tvSoarResolverPill.text = "$activeRes / $totalRes DNS"
            binding.tvSoarResolverPill.visibility = View.VISIBLE
        } else {
            binding.tvSoarResolverPill.visibility = View.GONE
        }

        when (state.phase) {
            SoarPhase.STOPPED -> {
                binding.tvSoarSubtitle.text = "Disconnected • $activeProfileName"
                binding.btnSoarConnect.text = if (selectedMode == SoarConnectionMode.VPN_FULL_DEVICE) {
                    "Start Soar VPN"
                } else {
                    "Start Soar Proxy"
                }
                binding.btnSoarConnect.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(activity, R.color.accent_primary)
                )
                binding.btnSoarConnect.isEnabled = true
                binding.layoutSoarProxyActions.visibility = View.GONE
                binding.tvSoarError.visibility = View.GONE
            }

            SoarPhase.STARTING -> {
                binding.tvSoarSubtitle.text = "Starting (${state.mode.label})…"
                binding.btnSoarConnect.text = "Stop"
                binding.btnSoarConnect.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(activity, R.color.warning_text)
                )
                binding.btnSoarConnect.isEnabled = true
                binding.layoutSoarProxyActions.visibility = View.GONE
                binding.tvSoarError.visibility = View.GONE
            }

            SoarPhase.CONNECTED -> {
                binding.tvSoarSubtitle.text = if (state.mode == SoarConnectionMode.PROXY_ONLY) {
                    "Proxy Active • $listenAddr"
                } else {
                    "Full-Device VPN • TUN -> $listenAddr"
                }
                binding.btnSoarConnect.text = "Stop Soar"
                binding.btnSoarConnect.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(activity, R.color.error_text)
                )
                binding.btnSoarConnect.isEnabled = true
                binding.layoutSoarProxyActions.visibility =
                    if (state.mode == SoarConnectionMode.PROXY_ONLY) View.VISIBLE else View.GONE
                binding.tvSoarError.visibility = View.GONE
            }

            SoarPhase.DISCONNECTING -> {
                binding.tvSoarSubtitle.text = "Stopping…"
                binding.btnSoarConnect.text = "Stopping…"
                binding.btnSoarConnect.isEnabled = false
                binding.layoutSoarProxyActions.visibility = View.GONE
            }

            SoarPhase.ERROR -> {
                binding.tvSoarSubtitle.text = "Error • $activeProfileName"
                binding.btnSoarConnect.text = if (selectedMode == SoarConnectionMode.VPN_FULL_DEVICE) {
                    "Retry Soar VPN"
                } else {
                    "Retry Soar Proxy"
                }
                binding.btnSoarConnect.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(activity, R.color.accent_primary)
                )
                binding.btnSoarConnect.isEnabled = true
                binding.layoutSoarProxyActions.visibility = View.GONE
                binding.tvSoarError.text = state.errorMessage ?: "Soar failed to start"
                binding.tvSoarError.visibility = View.VISIBLE
            }
        }
    }

    private fun importFromClipboard() {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (clip.isEmpty()) {
            toast("Clipboard is empty")
            return
        }
        val parsed = SoarProfileParser.parseUri(clip)
        if (parsed.isFailure) {
            toast("Invalid soar:// link: ${parsed.exceptionOrNull()?.message}")
            return
        }
        val profile = parsed.getOrThrow()
        storeState = SoarProfileStore.upsertProfile(activity.filesDir, profile, setActive = true)
        packState = SoarResolverPackStore.extractDefaultPackFromProfile(
            filesDir = activity.filesDir,
            profile = profile,
            setActiveIfNone = true
        )
        reloadStore()
        val packNote = if (profile.resolvers.isNotEmpty()) {
            " (+ ${profile.resolvers.size} DNS pack)"
        } else {
            ""
        }
        toast("Imported ${profile.name}$packNote")
    }

    private fun showProfileEditorBottomSheet(existing: SoarProfile?) {
        val sheet = BottomSheetDialog(activity)
        val sheetBinding = BottomSheetSoarProfileBinding.inflate(LayoutInflater.from(activity))
        sheet.setContentView(sheetBinding.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        sheetBinding.tvSheetTitle.text = if (existing == null) "New Soar Config" else "Edit Soar Config"

        val transports = SoarTransport.entries.map { it.cliValue }
        sheetBinding.actSoarTransport.setAdapter(
            ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, transports)
        )

        val defaults = existing?.soarSettings ?: SoarSettings()
        if (existing != null) {
            sheetBinding.etSoarName.setText(existing.name)
            sheetBinding.etSoarDomain.setText(existing.domain)
            sheetBinding.etSoarPublicKey.setText(existing.publicKey)
        }

        sheetBinding.actSoarTransport.setText(defaults.transport, false)
        sheetBinding.switchSoarDiscovery.isChecked = defaults.discovery
        sheetBinding.etSoarCountry.setText(defaults.country)

        sheetBinding.etSoarDiscoveryWant.setText(defaults.discoveryWant.toString())
        sheetBinding.etSoarDiscoveryConcurrency.setText(defaults.discoveryConcurrency.toString())
        sheetBinding.etSoarDiscoveryPaceMs.setText(defaults.discoveryPaceMs.toString())
        sheetBinding.etSoarSafeQName.setText(defaults.safeQName.toString())
        sheetBinding.etSoarMaxQName.setText(defaults.maxQName.toString())
        sheetBinding.etSoarMaxAnswer.setText(defaults.maxAnswer.toString())
        sheetBinding.etSoarHoldMs.setText(defaults.holdMs.toString())
        sheetBinding.etSoarMaxInflight.setText(defaults.maxInflight.toString())
        sheetBinding.etSoarMaxPathWindow.setText(defaults.maxPathWindow.toString())
        sheetBinding.etSoarMaxStreams.setText(defaults.maxStreams.toString())
        sheetBinding.etSoarStreamWindowKiB.setText(defaults.streamWindowKiB.toString())
        sheetBinding.etSoarSendBufferKiB.setText(defaults.sendBufferKiB.toString())
        sheetBinding.etSoarSessionIdleSeconds.setText(defaults.sessionIdleSeconds.toString())
        sheetBinding.etSoarDialTimeoutSeconds.setText(defaults.dialTimeoutSeconds.toString())

        var isBindingSheetPreset = true
        val matchedSheetChip = when (defaults.matchingPreset()) {
            SoarPreset.BALANCED -> R.id.chipSoarSheetBalanced
            SoarPreset.TURBO -> R.id.chipSoarSheetTurbo
            SoarPreset.STABLE -> R.id.chipSoarSheetStable
            SoarPreset.STRICT -> R.id.chipSoarSheetStrict
            null -> View.NO_ID
        }
        if (matchedSheetChip != View.NO_ID) {
            sheetBinding.chipGroupSoarSheetPresets.check(matchedSheetChip)
        }
        isBindingSheetPreset = false

        sheetBinding.chipGroupSoarSheetPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingSheetPreset) return@setOnCheckedStateChangeListener
            val chipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val preset = when (chipId) {
                R.id.chipSoarSheetBalanced -> SoarPreset.BALANCED
                R.id.chipSoarSheetTurbo -> SoarPreset.TURBO
                R.id.chipSoarSheetStable -> SoarPreset.STABLE
                R.id.chipSoarSheetStrict -> SoarPreset.STRICT
                else -> return@setOnCheckedStateChangeListener
            }
            sheetBinding.actSoarTransport.setText(preset.transport, false)
            sheetBinding.etSoarMaxAnswer.setText(preset.maxAnswer.toString())
            sheetBinding.etSoarMaxInflight.setText(preset.maxInflight.toString())
            sheetBinding.etSoarMaxPathWindow.setText(preset.maxPathWindow.toString())
            sheetBinding.etSoarHoldMs.setText(preset.holdMs.toString())
            sheetBinding.etSoarSessionIdleSeconds.setText(preset.sessionIdleSeconds.toString())
        }

        var advancedVisible = false
        sheetBinding.btnSoarToggleAdvanced.setOnClickListener {
            advancedVisible = !advancedVisible
            sheetBinding.layoutSoarAdvanced.visibility = if (advancedVisible) View.VISIBLE else View.GONE
            sheetBinding.tvSoarAdvancedToggle.text = if (advancedVisible) {
                "▾ ADVANCED TUNING (14)"
            } else {
                "▸ ADVANCED TUNING (14)"
            }
        }

        sheetBinding.btnSoarCancel.setOnClickListener { sheet.dismiss() }
        sheetBinding.btnSoarSave.setOnClickListener {
            val domain = sheetBinding.etSoarDomain.text?.toString()?.trim().orEmpty()
            val pubKey = sheetBinding.etSoarPublicKey.text?.toString()?.trim().orEmpty()
            if (domain.isEmpty() || pubKey.isEmpty()) {
                toast("Server Domain and Public Key are required")
                return@setOnClickListener
            }
            val name = sheetBinding.etSoarName.text?.toString()?.trim().orEmpty().ifBlank { domain }
            val transport = sheetBinding.actSoarTransport.text?.toString()?.trim().orEmpty().ifBlank { "auto" }
            val settings = SoarSettings(
                transport = transport,
                discovery = sheetBinding.switchSoarDiscovery.isChecked,
                country = sheetBinding.etSoarCountry.text?.toString()?.trim().orEmpty().ifBlank { "IR" },
                discoveryWant = intOrDefault(sheetBinding.etSoarDiscoveryWant.text?.toString(), defaults.discoveryWant),
                discoveryConcurrency = intOrDefault(sheetBinding.etSoarDiscoveryConcurrency.text?.toString(), defaults.discoveryConcurrency),
                discoveryPaceMs = intOrDefault(sheetBinding.etSoarDiscoveryPaceMs.text?.toString(), defaults.discoveryPaceMs),
                safeQName = intOrDefault(sheetBinding.etSoarSafeQName.text?.toString(), defaults.safeQName),
                maxQName = intOrDefault(sheetBinding.etSoarMaxQName.text?.toString(), defaults.maxQName),
                maxAnswer = intOrDefault(sheetBinding.etSoarMaxAnswer.text?.toString(), defaults.maxAnswer),
                holdMs = intOrDefault(sheetBinding.etSoarHoldMs.text?.toString(), defaults.holdMs),
                maxInflight = intOrDefault(sheetBinding.etSoarMaxInflight.text?.toString(), defaults.maxInflight),
                maxPathWindow = intOrDefault(sheetBinding.etSoarMaxPathWindow.text?.toString(), defaults.maxPathWindow),
                maxStreams = intOrDefault(sheetBinding.etSoarMaxStreams.text?.toString(), defaults.maxStreams),
                streamWindowKiB = intOrDefault(sheetBinding.etSoarStreamWindowKiB.text?.toString(), defaults.streamWindowKiB),
                sendBufferKiB = intOrDefault(sheetBinding.etSoarSendBufferKiB.text?.toString(), defaults.sendBufferKiB),
                sessionIdleSeconds = intOrDefault(sheetBinding.etSoarSessionIdleSeconds.text?.toString(), defaults.sessionIdleSeconds),
                dialTimeoutSeconds = intOrDefault(sheetBinding.etSoarDialTimeoutSeconds.text?.toString(), defaults.dialTimeoutSeconds)
            ).normalized()

            val saved = if (existing != null) {
                existing.copy(
                    name = name,
                    domain = domain,
                    publicKey = pubKey,
                    soarSettings = settings
                )
            } else {
                SoarProfile(
                    name = name,
                    domain = domain,
                    publicKey = pubKey,
                    soarSettings = settings
                )
            }
            storeState = SoarProfileStore.upsertProfile(activity.filesDir, saved, setActive = true)
            reloadStore()
            sheet.dismiss()
        }

        sheet.show()
    }

    private fun intOrDefault(raw: String?, fallback: Int): Int {
        return raw?.trim()?.toIntOrNull() ?: fallback
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    private inner class SoarProfilesAdapter :
        ListAdapter<SoarProfile, SoarProfilesAdapter.VH>(PROFILE_DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val itemBinding = ItemSoarProfileBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(itemBinding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        inner class VH(private val b: ItemSoarProfileBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(profile: SoarProfile) {
                val isActive = profile.id == storeState.activeProfile?.id
                b.radioSoarActive.isChecked = isActive
                b.tvSoarProfileName.text = profile.name
                b.tvSoarDomain.text = profile.domain
                b.tvSoarBadge.text = profile.soarSettings.badgeLabel()

                val strokeColor = ContextCompat.getColor(
                    activity,
                    if (isActive) R.color.accent_primary else R.color.card_border
                )
                b.cardSoarProfile.strokeColor = strokeColor

                val selectClick = View.OnClickListener {
                    storeState = SoarProfileStore.setActiveProfile(activity.filesDir, profile.id)
                    reloadStore()
                }
                b.root.setOnClickListener(selectClick)
                b.radioSoarActive.setOnClickListener(selectClick)

                b.btnSoarMore.setOnClickListener { anchor ->
                    val popup = PopupMenu(activity, anchor)
                    popup.menu.add(0, 1, 0, "Copy soar:// link")
                    popup.menu.add(0, 2, 1, "Edit")
                    popup.menu.add(0, 3, 2, "Delete")
                    popup.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> {
                                val uri = SoarProfileParser.exportUri(profile)
                                copyTextToClipboard("soar://", uri)
                                toast("Copied soar:// link")
                            }
                            2 -> showProfileEditorBottomSheet(profile)
                            3 -> {
                                storeState = SoarProfileStore.deleteProfile(activity.filesDir, profile.id)
                                reloadStore()
                            }
                        }
                        true
                    }
                    popup.show()
                }
            }
        }
    }

    private data class PackRow(
        val pack: ResolverPack,
        val isActive: Boolean
    )

    private inner class SoarResolverPacksAdapter :
        ListAdapter<PackRow, SoarResolverPacksAdapter.VH>(PACK_DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val itemBinding = ItemSoarResolverPackBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(itemBinding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        inner class VH(private val b: ItemSoarResolverPackBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: PackRow) {
                val pack = row.pack
                val isActive = row.isActive
                b.tvPackName.text = pack.name
                b.tvPackCountBadge.text = "${pack.resolvers.size} DNS"
                b.tvPackActivePill.visibility = if (isActive) View.VISIBLE else View.GONE
                b.tvPackPreview.text = pack.previewSummary()

                b.cardResolverPack.strokeColor = ContextCompat.getColor(
                    activity,
                    if (isActive) R.color.accent_primary else R.color.card_border
                )

                b.root.setOnClickListener {
                    showResolverPackViewerSheet(pack)
                }

                b.btnPackMore.setOnClickListener { anchor ->
                    val popup = PopupMenu(activity, anchor)
                    popup.menu.add(0, 1, 0, "Inspect & Benchmark")
                    popup.menu.add(0, 5, 1, if (isActive) "Deactivate Pack" else "Set as Active Pack")
                    popup.menu.add(0, 2, 2, "Rename")
                    popup.menu.add(0, 3, 3, "Export .txt")
                    popup.menu.add(0, 4, 4, "Delete")
                    popup.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> showResolverPackViewerSheet(pack)
                            5 -> {
                                val nextId = if (isActive) null else pack.id
                                packState = SoarResolverPackStore.setActivePack(activity.filesDir, nextId)
                                reloadStore()
                            }
                            2 -> showPromptPackNameDialog(
                                title = "Rename Resolver Pack",
                                defaultName = pack.name
                            ) { newName ->
                                packState = SoarResolverPackStore.renamePack(activity.filesDir, pack.id, newName)
                                reloadStore()
                            }
                            3 -> {
                                pendingExportPack = pack
                                val safeName = pack.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                                packExportFileLauncher.launch("$safeName.txt")
                            }
                            4 -> {
                                packState = SoarResolverPackStore.deletePack(activity.filesDir, pack.id)
                                reloadStore()
                            }
                        }
                        true
                    }
                    popup.show()
                }
            }
        }
    }

    private data class ResolverIpRow(
        val index: Int,
        val ip: String,
        val metrics: ResolverBenchmarkMetrics?
    )

    private inner class ResolverIpAdapter(
        private val onCopy: (String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : ListAdapter<ResolverIpRow, ResolverIpAdapter.VH>(RESOLVER_IP_DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val itemBinding = ItemSoarResolverIpBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(itemBinding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        inner class VH(private val b: ItemSoarResolverIpBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: ResolverIpRow) {
                b.tvResolverIndex.text = "#${row.index}"
                b.tvResolverIp.text = row.ip

                val m = row.metrics
                if (m == null) {
                    b.layoutResolverMetrics.visibility = View.GONE
                } else {
                    b.layoutResolverMetrics.visibility = View.VISIBLE
                    if (m.isUnreachable) {
                        b.tvResolverPing.text = "Timeout"
                        b.tvResolverJitter.text = "--"
                        b.tvResolverLoss.text = "100% loss"
                        val red = ContextCompat.getColor(activity, R.color.error_text)
                        b.tvResolverPing.setTextColor(red)
                        b.tvResolverJitter.setTextColor(red)
                        b.tvResolverLoss.setTextColor(red)
                    } else {
                        b.tvResolverPing.text = "${m.avgPingMs}ms"
                        b.tvResolverJitter.text = "±${m.jitterMs}ms"
                        b.tvResolverLoss.text = "${m.lossPercent}% loss"

                        val pingColor = when {
                            m.avgPingMs <= 120 -> R.color.log_success
                            m.avgPingMs <= 300 -> R.color.warning_text
                            else -> R.color.error_text
                        }
                        val jitterColor = when {
                            m.jitterMs <= 15 -> R.color.log_success
                            m.jitterMs <= 50 -> R.color.warning_text
                            else -> R.color.error_text
                        }
                        val lossColor = when {
                            m.lossPercent == 0 -> R.color.log_success
                            m.lossPercent <= 20 -> R.color.warning_text
                            else -> R.color.error_text
                        }
                        b.tvResolverPing.setTextColor(ContextCompat.getColor(activity, pingColor))
                        b.tvResolverJitter.setTextColor(ContextCompat.getColor(activity, jitterColor))
                        b.tvResolverLoss.setTextColor(ContextCompat.getColor(activity, lossColor))
                    }
                }

                b.btnCopyResolverIp.setOnClickListener { onCopy(row.ip) }
                b.btnDeleteResolverIp.setOnClickListener { onDelete(row.ip) }
            }
        }
    }

    companion object {
        private val RESOLVER_IP_DIFF = object : DiffUtil.ItemCallback<ResolverIpRow>() {
            override fun areItemsTheSame(oldItem: ResolverIpRow, newItem: ResolverIpRow): Boolean =
                oldItem.ip == newItem.ip

            override fun areContentsTheSame(oldItem: ResolverIpRow, newItem: ResolverIpRow): Boolean =
                oldItem == newItem
        }
        private val PROFILE_DIFF = object : DiffUtil.ItemCallback<SoarProfile>() {
            override fun areItemsTheSame(oldItem: SoarProfile, newItem: SoarProfile): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: SoarProfile, newItem: SoarProfile): Boolean =
                oldItem == newItem
        }

        private val PACK_DIFF = object : DiffUtil.ItemCallback<PackRow>() {
            override fun areItemsTheSame(oldItem: PackRow, newItem: PackRow): Boolean =
                oldItem.pack.id == newItem.pack.id

            override fun areContentsTheSame(oldItem: PackRow, newItem: PackRow): Boolean =
                oldItem == newItem
        }
    }
}
