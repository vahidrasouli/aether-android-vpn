package io.aethervpn.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.aethervpn.android.R
import io.aethervpn.android.core.ChainMode
import io.aethervpn.android.core.DiagnosticsLog
import io.aethervpn.android.core.QrCodeHelper
import io.aethervpn.android.core.SingBoxConfigParser
import io.aethervpn.android.core.SingBoxEngineManager
import io.aethervpn.android.core.SingBoxProfile
import io.aethervpn.android.core.SingBoxProfileRepository
import io.aethervpn.android.core.SingBoxProfileStoreState
import io.aethervpn.android.core.TunnelPhase
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.core.VpnStateStore
import io.aethervpn.android.databinding.ActivitySingboxProfilesBinding
import io.aethervpn.android.databinding.DialogAddSingboxProfileBinding
import io.aethervpn.android.databinding.DialogShareQrBinding
import io.aethervpn.android.databinding.ItemSingboxProfileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class SingBoxProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySingboxProfilesBinding

    private var storeState = SingBoxProfileStoreState()
    private val testingIds = mutableSetOf<String>()
    private val testErrors = mutableMapOf<String, String>()
    private val serverPreviewCache = mutableMapOf<String, String>()
    private var testAllJob: Job? = null
    private var isBindingSwitches = false
    private var controlsExpanded = false
    private var pendingQrTargetInput: TextInputEditText? = null

    private val adapter = ProfilesAdapter()

    private val pickQrImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        decodeQrFromGalleryUri(uri)
    }

    private val exportProfilesDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val profilesSnapshot = storeState.profiles.toList()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = SingBoxProfileRepository.exportAllUris(profilesSnapshot)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: throw IllegalStateException("Unable to open output stream")
                    profilesSnapshot.size
                }
            }
            if (result.isSuccess) {
                showSnack("Exported ${result.getOrThrow()} profiles to file")
            } else {
                showSnack("Export failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySingboxProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarSingBox.setNavigationOnClickListener { finish() }
        binding.toolbarSingBox.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_test_all -> {
                    runTestAll()
                    true
                }
                R.id.action_speed_probe -> {
                    runSpeedProbe()
                    true
                }
                R.id.action_sort_ping -> {
                    sortByPing()
                    true
                }
                R.id.action_scan_qr -> {
                    launchQrScanner(targetInput = null)
                    true
                }
                R.id.action_export_profiles -> {
                    showExportOptionsDialog()
                    true
                }
                R.id.action_singbox_tuning -> {
                    startActivity(Intent(this, SingBoxTuningActivity::class.java))
                    true
                }
                else -> false
            }
        }
        binding.layoutOpenSingBoxLab.setOnClickListener {
            startActivity(Intent(this, SingBoxTuningActivity::class.java))
        }
        binding.layoutSingBoxHeader.setOnClickListener {
            controlsExpanded = !controlsExpanded
            binding.layoutSingBoxControlsBody.visibility = if (controlsExpanded) View.VISIBLE else View.GONE
            binding.ivSingBoxChevron.rotation = if (controlsExpanded) 270f else 90f
        }
        binding.btnCancelTestAll.setOnClickListener {
            cancelTestAll()
        }

        binding.rvSingBoxProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvSingBoxProfiles.setHasFixedSize(true)
        (binding.rvSingBoxProfiles.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.rvSingBoxProfiles.adapter = adapter

        binding.fabAddSingBoxProfile.setOnClickListener { showProfileSheet() }

        bindSwitches()
        refreshAll()
    }

    private fun bindSwitches() {
        binding.spinnerRoutingChain.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ChainMode.entries.map { it.label }
        )

        binding.switchSingBoxMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingSwitches) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                val cfg = withContext(Dispatchers.IO) { VpnConfig.load(this@SingBoxProfilesActivity) }
                val updated = cfg.copy(
                    singBoxEnabled = isChecked,
                    singBoxOnly = if (!isChecked) false else cfg.chainMode == ChainMode.SINGBOX_ONLY
                )
                withContext(Dispatchers.IO) { VpnConfig.save(this@SingBoxProfilesActivity, updated) }
                syncSwitches(updated)
            }
        }

        binding.spinnerRoutingChain.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (isBindingSwitches) return
                val selectedChain = ChainMode.entries[pos]
                lifecycleScope.launch {
                    val cfg = withContext(Dispatchers.IO) { VpnConfig.load(this@SingBoxProfilesActivity) }
                    val updated = cfg.copy(
                        singBoxEnabled = true,
                        chainMode = selectedChain,
                        singBoxOnly = selectedChain == ChainMode.SINGBOX_ONLY
                    )
                    withContext(Dispatchers.IO) { VpnConfig.save(this@SingBoxProfilesActivity, updated) }
                    syncSwitches(updated)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun syncSwitches(cfg: VpnConfig) {
        isBindingSwitches = true
        try {
            binding.switchSingBoxMaster.isChecked = cfg.singBoxEnabled
            binding.layoutRoutingChain.visibility = if (cfg.singBoxEnabled) View.VISIBLE else View.GONE
            binding.spinnerRoutingChain.setSelection(cfg.chainMode.ordinal, false)
            binding.tvRoutingChainDetail.text = cfg.chainMode.description
            binding.tvSingBoxControlsSummary.text = if (cfg.singBoxEnabled) {
                "ON • ${cfg.chainMode.label} • Tap for chain & tuning"
            } else {
                "OFF • Zero RAM/CPU overhead • Tap to expand"
            }
        } finally {
            isBindingSwitches = false
        }
    }

    private fun refreshAll() {
        lifecycleScope.launch {
            val (cfg, state, previews) = withContext(Dispatchers.IO) {
                val loadedCfg = VpnConfig.load(this@SingBoxProfilesActivity)
                val loadedState = SingBoxProfileRepository.load(filesDir)
                val previewMap = loadedState.profiles.associate { p ->
                    p.id to (serverPreviewCache[p.id] ?: SingBoxConfigParser.extractServerPreview(p.rawUriOrJson))
                }
                Triple(loadedCfg, loadedState, previewMap)
            }
            serverPreviewCache.clear()
            serverPreviewCache.putAll(previews)
            syncSwitches(cfg)
            storeState = state
            renderList()
        }
    }

    private fun renderList() {
        val empty = storeState.profiles.isEmpty()
        binding.tvProfilesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvSingBoxProfiles.visibility = if (empty) View.GONE else View.VISIBLE
        submitProfilesList()
    }

    private fun submitProfilesList() {
        val activeId = storeState.activeProfile?.id
        val rows = storeState.profiles.map { profile ->
            SingBoxProfileRow(
                profile = profile,
                isActive = profile.id == activeId,
                isTesting = testingIds.contains(profile.id),
                errorMessage = testErrors[profile.id],
                serverPreview = serverPreviewCache[profile.id] ?: ""
            )
        }
        adapter.submitList(rows)
    }

    private fun showProfileSheet(editingProfile: SingBoxProfile? = null) {
        val isEditing = editingProfile != null
        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_Design_BottomSheetDialog)
        val sheetBinding = DialogAddSingboxProfileBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvSheetTitle.text = if (isEditing) {
            getString(R.string.action_edit_profile)
        } else {
            getString(R.string.action_add_profile)
        }
        sheetBinding.btnCopyShareUri.visibility = if (isEditing) View.VISIBLE else View.GONE
        sheetBinding.btnImportSubscription.visibility = if (isEditing) View.GONE else View.VISIBLE
        sheetBinding.layoutQrImportActions.visibility = if (isEditing) View.GONE else View.VISIBLE

        if (editingProfile != null) {
            sheetBinding.etSingBoxUriOrJson.setText(editingProfile.rawUriOrJson)
            sheetBinding.etSingBoxCustomName.setText(editingProfile.name)
            sheetBinding.tvDetectedProtocol.text = SingBoxConfigParser.detectProtocolBadge(editingProfile.rawUriOrJson)
        }

        sheetBinding.etSingBoxUriOrJson.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                sheetBinding.tvDetectedProtocol.text = if (text.isBlank()) {
                    "AUTO"
                } else {
                    SingBoxConfigParser.detectProtocolBadge(text)
                }
                sheetBinding.tvAddProfileError.visibility = View.GONE
            }
        })

        sheetBinding.btnPasteClipboard.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (clipText.isNotEmpty()) {
                sheetBinding.etSingBoxUriOrJson.setText(clipText)
            } else {
                showSnack("Clipboard is empty")
            }
        }

        sheetBinding.btnImportSubscription.setOnClickListener {
            showImportSubscriptionDialog(dialog)
        }

        sheetBinding.btnScanQrCamera.setOnClickListener {
            launchQrScanner(targetInput = sheetBinding.etSingBoxUriOrJson)
        }

        sheetBinding.btnPickQrGallery.setOnClickListener {
            pendingQrTargetInput = sheetBinding.etSingBoxUriOrJson
            pickQrImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        sheetBinding.btnCopyShareUri.setOnClickListener {
            val textToCopy = sheetBinding.etSingBoxUriOrJson.text?.toString()?.trim().orEmpty()
                .ifEmpty { editingProfile?.rawUriOrJson.orEmpty() }
            if (textToCopy.isNotEmpty()) {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Sing-box Profile", textToCopy))
                showSnack("Copied share URI to clipboard")
            }
        }

        sheetBinding.btnSaveSingBoxProfile.setOnClickListener {
            val raw = sheetBinding.etSingBoxUriOrJson.text?.toString().orEmpty().trim()
            val customName = sheetBinding.etSingBoxCustomName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            lifecycleScope.launch {
                if (editingProfile != null) {
                    val res = withContext(Dispatchers.IO) {
                        SingBoxProfileRepository.updateProfile(filesDir, editingProfile.id, raw, customName)
                    }
                    if (res.isSuccess) {
                        serverPreviewCache.remove(editingProfile.id)
                        dialog.dismiss()
                        refreshAll()
                        showSnack("Updated ${res.getOrThrow().name}")
                    } else {
                        sheetBinding.tvAddProfileError.text = res.exceptionOrNull()?.message ?: "Invalid profile"
                        sheetBinding.tvAddProfileError.visibility = View.VISIBLE
                    }
                } else {
                    val isSingleJson = raw.startsWith("{")
                    val nonEmptyLines = raw.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
                    if (!isSingleJson && nonEmptyLines.size > 1) {
                        val bulkRes = withContext(Dispatchers.IO) {
                            SingBoxProfileRepository.addProfilesBulk(filesDir, raw, customName)
                        }
                        if (bulkRes.isSuccess) {
                            val (imported, skipped) = bulkRes.getOrThrow()
                            dialog.dismiss()
                            refreshAll()
                            val msg = if (skipped > 0) {
                                "Imported ${imported.size} profiles ($skipped skipped)"
                            } else {
                                "Imported ${imported.size} profiles"
                            }
                            showSnack(msg)
                        } else {
                            sheetBinding.tvAddProfileError.text =
                                bulkRes.exceptionOrNull()?.message ?: "No valid profiles found"
                            sheetBinding.tvAddProfileError.visibility = View.VISIBLE
                        }
                    } else {
                        val res = withContext(Dispatchers.IO) {
                            SingBoxProfileRepository.addProfile(filesDir, raw, customName)
                        }
                        if (res.isSuccess) {
                            dialog.dismiss()
                            refreshAll()
                            showSnack("Saved ${res.getOrThrow().name}")
                        } else {
                            sheetBinding.tvAddProfileError.text = res.exceptionOrNull()?.message ?: "Invalid profile"
                            sheetBinding.tvAddProfileError.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            if (pendingQrTargetInput === sheetBinding.etSingBoxUriOrJson) {
                pendingQrTargetInput = null
            }
        }
        dialog.show()
    }

    private fun launchQrScanner(targetInput: TextInputEditText?) {
        pendingQrTargetInput = targetInput
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        runCatching {
            val scanner = GmsBarcodeScanning.getClient(this, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue?.trim().orEmpty()
                    if (rawValue.isNotEmpty()) {
                        handleDecodedQrText(rawValue)
                    } else {
                        showSnack("Scanned QR code is empty")
                    }
                }
                .addOnFailureListener {
                    // Gracefully fall back to modern Photo Picker when Play Services scanner is unavailable
                    showSnack("Camera scanner unavailable — pick a QR image from gallery")
                    pickQrImageLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
        }.onFailure {
            showSnack("Camera scanner unavailable — pick a QR image from gallery")
            pickQrImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    private fun decodeQrFromGalleryUri(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                QrCodeHelper.decodeQrFromUri(contentResolver, uri)
            }
            if (result.isSuccess) {
                handleDecodedQrText(result.getOrThrow())
            } else {
                showSnack("No QR code found in selected image")
            }
        }
    }

    private fun handleDecodedQrText(decodedText: String) {
        val target = pendingQrTargetInput
        pendingQrTargetInput = null
        if (target != null && target.isAttachedToWindow) {
            target.setText(decodedText)
            showSnack("QR code decoded")
            return
        }

        lifecycleScope.launch {
            val isSingleJson = decodedText.startsWith("{")
            val nonEmptyLines = decodedText.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

            if (!isSingleJson && nonEmptyLines.size > 1) {
                val bulkRes = withContext(Dispatchers.IO) {
                    SingBoxProfileRepository.addProfilesBulk(filesDir, decodedText)
                }
                if (bulkRes.isSuccess) {
                    val (imported, _) = bulkRes.getOrThrow()
                    refreshAll()
                    showSnack("Imported ${imported.size} profiles from QR")
                } else {
                    showSnack(bulkRes.exceptionOrNull()?.message ?: "Invalid QR profile")
                }
            } else {
                val res = withContext(Dispatchers.IO) {
                    SingBoxProfileRepository.addProfile(filesDir, decodedText)
                }
                if (res.isSuccess) {
                    refreshAll()
                    showSnack("Imported ${res.getOrThrow().name} from QR")
                } else {
                    showSnack(res.exceptionOrNull()?.message ?: "Unsupported QR payload")
                }
            }
        }
    }

    private fun showShareQrSheet(profile: SingBoxProfile) {
        lifecycleScope.launch {
            val qrResult = withContext(Dispatchers.IO) {
                QrCodeHelper.renderQrBitmap(profile.rawUriOrJson, sizePx = 640)
            }
            if (qrResult.isFailure) {
                showSnack("Profile is too large for a QR code — use Copy URI or Export instead")
                return@launch
            }

            val dialog = BottomSheetDialog(
                this@SingBoxProfilesActivity,
                com.google.android.material.R.style.Theme_Design_BottomSheetDialog
            )
            val qrBinding = DialogShareQrBinding.inflate(layoutInflater)
            dialog.setContentView(qrBinding.root)

            qrBinding.tvQrProfileName.text = profile.name
            qrBinding.tvQrProtocolBadge.text = profile.protocol
            qrBinding.ivQrCode.setImageBitmap(qrResult.getOrThrow())
            qrBinding.btnQrCopyUri.setOnClickListener {
                copyProfileUri(profile)
            }
            qrBinding.btnQrClose.setOnClickListener {
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun copyProfileUri(profile: SingBoxProfile) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Sing-box Profile", profile.rawUriOrJson))
        showSnack("Copied ${profile.name} URI to clipboard")
    }

    private fun showExportOptionsDialog() {
        val profiles = storeState.profiles
        if (profiles.isEmpty()) {
            showSnack("No profiles to export")
            return
        }
        val options = arrayOf(
            getString(R.string.action_copy_all_uris),
            getString(R.string.action_save_to_file)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_export_profiles))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val payload = SingBoxProfileRepository.exportAllUris(profiles)
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Sing-box Profiles", payload))
                        showSnack("Copied ${profiles.size} profile URIs to clipboard")
                    }
                    1 -> {
                        exportProfilesDocLauncher.launch("aether-singbox-profiles.txt")
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showImportSubscriptionDialog(parentSheet: BottomSheetDialog) {
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (8 * density).toInt()

        val til = TextInputLayout(this).apply {
            hint = "https://example.com/sub"
            setPadding(padH, padV, padH, 0)
        }
        val etUrl = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 2
            val clip = getSystemService(ClipboardManager::class.java)
                ?.primaryClip?.getItemAt(0)?.coerceToText(this@SingBoxProfilesActivity)?.toString()?.trim().orEmpty()
            if (clip.startsWith("http://", ignoreCase = true) || clip.startsWith("https://", ignoreCase = true)) {
                setText(clip)
            }
        }
        til.addView(etUrl)

        MaterialAlertDialogBuilder(this)
            .setTitle("Import Subscription")
            .setMessage("Fetch base64 or plain multi-line sing-box URIs from an http(s):// URL.")
            .setView(til)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("Import") { _, _ ->
                val urlStr = etUrl.text?.toString()?.trim().orEmpty()
                if (!urlStr.startsWith("http://", ignoreCase = true) && !urlStr.startsWith("https://", ignoreCase = true)) {
                    showSnack("Enter a valid http:// or https:// subscription URL")
                } else {
                    parentSheet.dismiss()
                    importSubscriptionFromUrl(urlStr)
                }
            }
            .show()
    }

    private fun importSubscriptionFromUrl(urlStr: String) {
        showSnack("Fetching subscription…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "AetherVPN/1.1.0")
                    }
                    try {
                        val code = conn.responseCode
                        require(code in 200..299) { "HTTP $code from subscription server" }
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            val buf = CharArray(8192)
                            val sb = StringBuilder()
                            var total = 0
                            var read = reader.read(buf)
                            while (read != -1 && total < 2 * 1024 * 1024) {
                                sb.append(buf, 0, read)
                                total += read
                                read = reader.read(buf)
                            }
                            sb.toString()
                        }
                        SingBoxProfileRepository.addSubscriptionPayload(filesDir, body).getOrThrow()
                    } finally {
                        conn.disconnect()
                    }
                }
            }
            if (result.isSuccess) {
                val (imported, skipped) = result.getOrThrow()
                refreshAll()
                val msg = if (skipped > 0) {
                    "Imported ${imported.size} profiles ($skipped skipped)"
                } else {
                    "Imported ${imported.size} profiles"
                }
                showSnack(msg)
            } else {
                showSnack("Subscription import failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
            }
        }
    }

    private fun runTestAll() {
        if (testAllJob?.isActive == true) {
            showSnack("Test All is already running")
            return
        }
        val snapshot = storeState.profiles.toList()
        if (snapshot.isEmpty()) {
            showSnack("No profiles to test")
            return
        }

        testAllJob = lifecycleScope.launch {
            binding.layoutTestAllProgress.visibility = View.VISIBLE
            binding.tvTestAllStatus.text = "Testing 0 / ${snapshot.size}…"
            val cfg = withContext(Dispatchers.IO) { VpnConfig.load(this@SingBoxProfilesActivity) }
            val semaphore = Semaphore(TEST_ALL_MAX_CONCURRENCY)
            val repoMutex = Mutex()
            val completedCount = AtomicInteger(0)
            val passedCount = AtomicInteger(0)

            try {
                coroutineScope {
                    snapshot.map { profile ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (!isActive) return@withPermit
                                withContext(Dispatchers.Main.immediate) {
                                    testingIds.add(profile.id)
                                    testErrors.remove(profile.id)
                                    binding.tvTestAllStatus.text =
                                        "Testing ${completedCount.get()} / ${snapshot.size} (${testingIds.size} active)…"
                                    submitProfilesList()
                                }

                                val result = SingBoxEngineManager.runEphemeralTraceTest(
                                    context = this@SingBoxProfilesActivity,
                                    config = cfg,
                                    rawUriOrJson = profile.rawUriOrJson,
                                    timeoutMs = SingBoxEngineManager.EPHEMERAL_TEST_TIMEOUT_MS
                                )

                                if (result.isSuccess) {
                                    passedCount.incrementAndGet()
                                    val v = result.getOrThrow()
                                    val updated = repoMutex.withLock {
                                        SingBoxProfileRepository.updateTestResult(
                                            filesDir = filesDir,
                                            profileId = profile.id,
                                            pingMs = v.latencyMs,
                                            exitIp = v.publicIp,
                                            country = v.colo
                                        )
                                    }
                                    withContext(Dispatchers.Main.immediate) {
                                        storeState = updated
                                    }
                                }

                                withContext(Dispatchers.Main.immediate) {
                                    testingIds.remove(profile.id)
                                    if (result.isFailure) {
                                        testErrors[profile.id] = result.exceptionOrNull()?.message ?: "Trace test failed"
                                    }
                                    val done = completedCount.incrementAndGet()
                                    binding.tvTestAllStatus.text = "Tested $done / ${snapshot.size}"
                                    submitProfilesList()
                                }
                            }
                        }
                    }.awaitAll()
                }
                if (isActive) {
                    showSnack("Tested ${snapshot.size} profiles (${passedCount.get()} passed)")
                }
            } finally {
                binding.layoutTestAllProgress.visibility = View.GONE
            }
        }
    }

    private fun cancelTestAll() {
        testAllJob?.cancel()
        testAllJob = null
        testingIds.clear()
        binding.layoutTestAllProgress.visibility = View.GONE
        submitProfilesList()
        showSnack("Test All cancelled")
    }

    private fun runSpeedProbe() {
        val state = VpnStateStore.state.value
        if (state.phase != TunnelPhase.VPN_CONNECTED) {
            showSnack("Connect VPN in Sing-box mode first to probe live SOCKS5 throughput")
            return
        }
        showSnack("Running 256KB direct SOCKS5 speed probe…")
        lifecycleScope.launch {
            val cfg = withContext(Dispatchers.IO) { VpnConfig.load(this@SingBoxProfilesActivity) }
            val probe = withContext(Dispatchers.IO) {
                SingBoxEngineManager.runDirectSocks5SpeedProbe(socksPort = cfg.effectiveSocksPort)
            }
            val line = SingBoxEngineManager.formatSpeedProbeLog(probe)
            DiagnosticsLog.record("SingBox", line)
            showSnack(line)
        }
    }

    private fun sortByPing() {
        if (storeState.profiles.size < 2) return
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                SingBoxProfileRepository.sortProfilesByPing(filesDir)
            }
            storeState = updated
            renderList()
            showSnack("Sorted by fastest ping")
        }
    }

    private fun runProfileTest(profile: SingBoxProfile) {
        if (testingIds.contains(profile.id)) return
        testingIds.add(profile.id)
        testErrors.remove(profile.id)
        submitProfilesList()

        lifecycleScope.launch {
            val cfg = withContext(Dispatchers.IO) { VpnConfig.load(this@SingBoxProfilesActivity) }
            val result = withContext(Dispatchers.IO) {
                SingBoxEngineManager.runEphemeralTraceTest(
                    context = this@SingBoxProfilesActivity,
                    config = cfg,
                    rawUriOrJson = profile.rawUriOrJson
                )
            }
            testingIds.remove(profile.id)
            if (result.isSuccess) {
                val v = result.getOrThrow()
                val updatedState = withContext(Dispatchers.IO) {
                    SingBoxProfileRepository.updateTestResult(
                        filesDir = filesDir,
                        profileId = profile.id,
                        pingMs = v.latencyMs,
                        exitIp = v.publicIp,
                        country = v.colo
                    )
                }
                storeState = updatedState
            } else {
                val err = result.exceptionOrNull()?.message ?: "Trace test failed"
                testErrors[profile.id] = err
            }
            submitProfilesList()
        }
    }

    private fun showSnack(message: String) {
        Snackbar.make(binding.singBoxRoot, message, Snackbar.LENGTH_SHORT).apply {
            setBackgroundTint(ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.surface_elevated))
            setTextColor(ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.text_primary))
            show()
        }
    }

    private data class SingBoxProfileRow(
        val profile: SingBoxProfile,
        val isActive: Boolean,
        val isTesting: Boolean,
        val errorMessage: String?,
        val serverPreview: String
    )

    private object ProfileDiffCallback : DiffUtil.ItemCallback<SingBoxProfileRow>() {
        override fun areItemsTheSame(oldItem: SingBoxProfileRow, newItem: SingBoxProfileRow): Boolean =
            oldItem.profile.id == newItem.profile.id

        override fun areContentsTheSame(oldItem: SingBoxProfileRow, newItem: SingBoxProfileRow): Boolean =
            oldItem == newItem
    }

    private inner class ProfilesAdapter :
        ListAdapter<SingBoxProfileRow, ProfilesAdapter.ProfileVH>(ProfileDiffCallback) {

        inner class ProfileVH(val itemBinding: ItemSingboxProfileBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileVH {
            val b = ItemSingboxProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ProfileVH(b)
        }

        override fun onBindViewHolder(holder: ProfileVH, position: Int) {
            val row = getItem(position)
            val p = row.profile
            val b = holder.itemBinding
            val isActive = row.isActive
            val isTesting = row.isTesting
            val err = row.errorMessage

            b.radioActiveProfile.isChecked = isActive
            b.tvProtocolBadge.text = p.protocol
            b.tvProfileName.text = p.name
            b.cardProfileItem.strokeColor = ContextCompat.getColor(
                this@SingBoxProfilesActivity,
                if (isActive) R.color.accent_primary else R.color.card_border
            )
            b.tvServerPreview.text = row.serverPreview

            when {
                isTesting -> {
                    b.tvProfilePingBadge.visibility = View.VISIBLE
                    b.ivProfileTestError.visibility = View.GONE
                    b.tvProfilePingBadge.text = "Testing…"
                    b.tvProfilePingBadge.setTextColor(
                        ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.accent_secondary)
                    )
                }
                !err.isNullOrBlank() -> {
                    b.tvProfilePingBadge.visibility = View.GONE
                    b.ivProfileTestError.visibility = View.VISIBLE
                    b.ivProfileTestError.setOnClickListener { showSnack("Error: $err") }
                }
                p.lastPingMs != null -> {
                    b.tvProfilePingBadge.visibility = View.VISIBLE
                    b.ivProfileTestError.visibility = View.GONE
                    val countryPart = p.lastCountry?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                    b.tvProfilePingBadge.text = "${p.lastPingMs}ms$countryPart"
                    b.tvProfilePingBadge.setTextColor(
                        ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.log_success)
                    )
                }
                else -> {
                    b.tvProfilePingBadge.visibility = View.VISIBLE
                    b.ivProfileTestError.visibility = View.GONE
                    b.tvProfilePingBadge.text = "--"
                    b.tvProfilePingBadge.setTextColor(
                        ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.text_secondary)
                    )
                }
            }

            b.btnTestProfile.isEnabled = !isTesting
            b.btnTestProfile.alpha = if (isTesting) 0.4f else 1.0f

            val selectAction = View.OnClickListener {
                lifecycleScope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        SingBoxProfileRepository.setActiveProfile(filesDir, p.id)
                    }
                    storeState = updated
                    submitProfilesList()
                }
            }
            b.cardProfileItem.setOnClickListener(selectAction)
            b.radioActiveProfile.setOnClickListener(selectAction)

            b.btnTestProfile.setOnClickListener { runProfileTest(p) }
            b.btnEditProfile.setOnClickListener { showProfileSheet(p) }

            b.btnDeleteProfile.setOnClickListener {
                lifecycleScope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        SingBoxProfileRepository.deleteProfile(filesDir, p.id)
                    }
                    testingIds.remove(p.id)
                    testErrors.remove(p.id)
                    serverPreviewCache.remove(p.id)
                    storeState = updated
                    renderList()
                }
            }

            b.btnMoreProfile.setOnClickListener { anchor ->
                PopupMenu(this@SingBoxProfilesActivity, anchor).apply {
                    menu.add(0, 1, 0, R.string.action_share_qr)
                    menu.add(0, 2, 1, R.string.action_copy_uri)
                    setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            1 -> {
                                showShareQrSheet(p)
                                true
                            }
                            2 -> {
                                copyProfileUri(p)
                                true
                            }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }
    }

    companion object {
        internal const val TEST_ALL_MAX_CONCURRENCY = 3
    }
}
