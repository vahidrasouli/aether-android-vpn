package io.aethervpn.android.ui

import android.content.ClipboardManager
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
import com.google.android.material.snackbar.Snackbar
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SingBoxProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySingboxProfilesBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var storeState = SingBoxProfileStoreState()
    private val testingIds = mutableSetOf<String>()
    private val testErrors = mutableMapOf<String, String>()
    private var isBindingSwitches = false

    private val adapter = ProfilesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySingboxProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarSingBox.setNavigationOnClickListener { finish() }

        binding.rvSingBoxProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvSingBoxProfiles.adapter = adapter

        binding.fabAddSingBoxProfile.setOnClickListener { showAddProfileSheet() }

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

    private fun showAddProfileSheet() {
        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_Design_BottomSheetDialog)
        val sheetBinding = DialogAddSingboxProfileBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

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

        sheetBinding.btnSaveSingBoxProfile.setOnClickListener {
            val raw = sheetBinding.etSingBoxUriOrJson.text?.toString().orEmpty().trim()
            val customName = sheetBinding.etSingBoxCustomName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            uiScope.launch {
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

        dialog.show()
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

            when {
                isTesting -> {
                    b.tvProfileStatusPill.text = "Testing via cloudflare.com/cdn-cgi/trace…"
                    b.tvProfileStatusPill.setTextColor(ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.accent_secondary))
                }
                !err.isNullOrBlank() -> {
                    b.tvProfileStatusPill.text = "Error: $err"
                    b.tvProfileStatusPill.setTextColor(ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.error_text))
                }
                p.lastPingMs != null && !p.lastExitIp.isNullOrBlank() -> {
                    val countryPart = p.lastCountry?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                    b.tvProfileStatusPill.text = "OK • ${p.lastExitIp}$countryPart • ${p.lastPingMs} ms"
                    b.tvProfileStatusPill.setTextColor(ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.log_success))
                }
                else -> {
                    b.tvProfileStatusPill.text = "Not tested"
                    b.tvProfileStatusPill.setTextColor(ContextCompat.getColor(this@SingBoxProfilesActivity, R.color.text_secondary))
                }
            }

            b.btnTestProfile.isEnabled = !isTesting
            b.btnTestProfile.text = if (isTesting) "Testing…" else getString(R.string.action_test)

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

            b.btnDeleteProfile.setOnClickListener {
                uiScope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        SingBoxProfileRepository.deleteProfile(filesDir, p.id)
                    }
                    testingIds.remove(p.id)
                    testErrors.remove(p.id)
                    storeState = updated
                    renderList()
                }
            }
        }
    }
}
