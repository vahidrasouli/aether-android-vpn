package io.aethervpn.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.net.HttpURLConnection
import java.net.URL
import io.aethervpn.android.R
import io.aethervpn.android.core.ChainMode
import io.aethervpn.android.core.SingBoxConfigParser
import io.aethervpn.android.core.SingBoxEngineManager
import io.aethervpn.android.core.SingBoxProfile
import io.aethervpn.android.core.SingBoxProfileRepository
import io.aethervpn.android.core.SingBoxProfileStoreState
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.databinding.ActivitySingboxProfilesBinding
import io.aethervpn.android.databinding.DialogAddSingboxProfileBinding
import io.aethervpn.android.databinding.ItemSingboxProfileBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SingBoxProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySingboxProfilesBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var storeState = SingBoxProfileStoreState()
    private val testingIds = mutableSetOf<String>()
    private val testErrors = mutableMapOf<String, String>()
    private val serverPreviewCache = mutableMapOf<String, String>()
    private var testAllJob: Job? = null
    private var isBindingSwitches = false

    private val adapter = ProfilesAdapter()

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
                R.id.action_sort_ping -> {
                    sortByPing()
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
        binding.btnCancelTestAll.setOnClickListener {
            cancelTestAll()
        }

        binding.rvSingBoxProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvSingBoxProfiles.adapter = adapter

        binding.fabAddSingBoxProfile.setOnClickListener { showProfileSheet() }

        bindSwitches()
        refreshAll()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindSwitches() {
        binding.spinnerRoutingChain.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ChainMode.entries.map { it.label }
        )

        binding.switchSingBoxMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingSwitches) return@setOnCheckedChangeListener
            uiScope.launch {
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
                uiScope.launch {
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
        } finally {
            isBindingSwitches = false
        }
    }

    private fun refreshAll() {
        uiScope.launch {
            val (cfg, state) = withContext(Dispatchers.IO) {
                VpnConfig.load(this@SingBoxProfilesActivity) to SingBoxProfileRepository.load(filesDir)
            }
            syncSwitches(cfg)
            storeState = state
            renderList()
        }
    }

    private fun renderList() {
        val empty = storeState.profiles.isEmpty()
        binding.tvProfilesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvSingBoxProfiles.visibility = if (empty) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
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
            uiScope.launch {
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

        dialog.show()
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
                    return@setPositiveButton
                }
                parentSheet.dismiss()
                importSubscriptionFromUrl(urlStr)
            }
            .show()
    }

    private fun importSubscriptionFromUrl(urlStr: String) {
        showSnack("Fetching subscription…")
        uiScope.launch {
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
                                sb.appendRange(buf, 0, read)
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

        testAllJob = uiScope.launch {
            binding.layoutTestAllProgress.visibility = View.VISIBLE
            val cfg = withContext(Dispatchers.IO) { VpnConfig.load(this@SingBoxProfilesActivity) }
            var passed = 0
            for ((index, profile) in snapshot.withIndex()) {
                if (!isActive) break
                binding.tvTestAllStatus.text = "Testing ${index + 1} / ${snapshot.size}: ${profile.name}"
                testingIds.add(profile.id)
                testErrors.remove(profile.id)
                adapter.notifyDataSetChanged()

                val result = withContext(Dispatchers.IO) {
                    SingBoxEngineManager.runEphemeralTraceTest(
                        context = this@SingBoxProfilesActivity,
                        config = cfg,
                        rawUriOrJson = profile.rawUriOrJson
                    )
                }
                testingIds.remove(profile.id)
                if (result.isSuccess) {
                    passed++
                    val v = result.getOrThrow()
                    storeState = withContext(Dispatchers.IO) {
                        SingBoxProfileRepository.updateTestResult(
                            filesDir = filesDir,
                            profileId = profile.id,
                            pingMs = v.latencyMs,
                            exitIp = v.publicIp,
                            country = v.colo
                        )
                    }
                } else {
                    testErrors[profile.id] = result.exceptionOrNull()?.message ?: "Trace test failed"
                }
                adapter.notifyDataSetChanged()
            }
            binding.layoutTestAllProgress.visibility = View.GONE
            if (isActive) {
                showSnack("Tested ${snapshot.size} profiles ($passed passed)")
            }
        }
    }

    private fun cancelTestAll() {
        testAllJob?.cancel()
        testAllJob = null
        testingIds.clear()
        binding.layoutTestAllProgress.visibility = View.GONE
        adapter.notifyDataSetChanged()
        showSnack("Test All cancelled")
    }

    private fun sortByPing() {
        if (storeState.profiles.size < 2) return
        uiScope.launch {
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
        adapter.notifyDataSetChanged()

        uiScope.launch {
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
            adapter.notifyDataSetChanged()
        }
    }

    private fun showSnack(message: String) {
        Snackbar.make(binding.singBoxRoot, message, Snackbar.LENGTH_SHORT).apply {
            setBackgroundTint(ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.surface_elevated))
            setTextColor(ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.text_primary))
            show()
        }
    }

    private inner class ProfilesAdapter : RecyclerView.Adapter<ProfilesAdapter.ProfileVH>() {

        inner class ProfileVH(val itemBinding: ItemSingboxProfileBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileVH {
            val b = ItemSingboxProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ProfileVH(b)
        }

        override fun getItemCount(): Int = storeState.profiles.size

        override fun onBindViewHolder(holder: ProfileVH, position: Int) {
            val p = storeState.profiles[position]
            val b = holder.itemBinding
            val isActive = p.id == storeState.activeProfile?.id
            val isTesting = testingIds.contains(p.id)
            val err = testErrors[p.id]

            b.radioActiveProfile.isChecked = isActive
            b.tvProtocolBadge.text = p.protocol
            b.tvProfileName.text = p.name
            b.cardProfileItem.strokeColor = ContextCompat.getColor(
                this@SingBoxProfilesActivity,
                if (isActive) R.color.accent_primary else R.color.card_border
            )

            val preview = serverPreviewCache.getOrPut(p.id) {
                SingBoxConfigParser.extractServerPreview(p.rawUriOrJson)
            }
            b.tvServerPreview.text = preview

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
                uiScope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        SingBoxProfileRepository.setActiveProfile(filesDir, p.id)
                    }
                    storeState = updated
                    notifyDataSetChanged()
                }
            }
            b.cardProfileItem.setOnClickListener(selectAction)
            b.radioActiveProfile.setOnClickListener(selectAction)

            b.btnTestProfile.setOnClickListener { runProfileTest(p) }
            b.btnEditProfile.setOnClickListener { showProfileSheet(p) }

            b.btnDeleteProfile.setOnClickListener {
                uiScope.launch {
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
        }
    }
}
