package io.aethervpn.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.aethervpn.android.R
import io.aethervpn.android.core.SoarConnectionMode
import io.aethervpn.android.core.SoarEngineManager
import io.aethervpn.android.core.SoarPhase
import io.aethervpn.android.core.SoarProfile
import io.aethervpn.android.core.SoarProfileParser
import io.aethervpn.android.core.SoarProfileStore
import io.aethervpn.android.core.SoarProfileStoreState
import io.aethervpn.android.core.SoarRuntimeState
import io.aethervpn.android.core.SoarSettings
import io.aethervpn.android.core.SoarStateStore
import io.aethervpn.android.core.SoarTransport
import io.aethervpn.android.databinding.ActivityMainBinding
import io.aethervpn.android.databinding.DialogSoarProfileBinding
import io.aethervpn.android.databinding.ItemSoarProfileBinding
import io.aethervpn.android.vpn.AetherVpnService
import io.aethervpn.android.vpn.SoarProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SoarTabController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onRequestVpnPermission: (Intent) -> Unit
) {
    private var storeState = SoarProfileStoreState()
    private var isBindingModeToggle = false
    private val adapter = SoarProfilesAdapter()

    fun init() {
        binding.rvSoarProfiles.layoutManager = LinearLayoutManager(activity)
        binding.rvSoarProfiles.adapter = adapter

        bindListeners()
        observeRuntimeState()
    }

    fun reloadStore() {
        activity.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                SoarProfileStore.load(activity.filesDir)
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            storeState = loaded
            isBindingModeToggle = true
            val checkedId = if (storeState.connectionMode == SoarConnectionMode.VPN_FULL_DEVICE) {
                R.id.btnSoarModeVpn
            } else {
                R.id.btnSoarModeProxy
            }
            binding.toggleSoarMode.check(checkedId)
            isBindingModeToggle = false

            adapter.submitList(storeState.profiles.toList())
            binding.tvSoarEmpty.visibility = if (storeState.profiles.isEmpty()) View.VISIBLE else View.GONE
            binding.rvSoarProfiles.visibility = if (storeState.profiles.isEmpty()) View.GONE else View.VISIBLE
            renderRuntimeState(SoarStateStore.state.value)
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
            popup.menu.add(0, 2, 1, "Manual Profile")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> importFromClipboard()
                    2 -> showProfileEditorDialog(null)
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
                toast("Add or select a Soar profile first")
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
                toast("Telegram not found — SOCKS5 127.0.0.1:1829 copied to clipboard")
            }
        }

        binding.btnSoarCopyAddr.setOnClickListener {
            val addr = "${SoarEngineManager.DEFAULT_LISTEN_HOST}:${SoarEngineManager.DEFAULT_LISTEN_PORT}"
            copyTextToClipboard("Soar SOCKS5", addr)
            toast("Copied $addr")
        }
    }

    private fun observeRuntimeState() {
        activity.lifecycleScope.launch {
            SoarStateStore.state.collectLatest { state ->
                renderRuntimeState(state)
            }
        }
    }

    private fun renderRuntimeState(state: SoarRuntimeState) {
        val selectedMode = storeState.connectionMode
        val activeProfileName = storeState.activeProfile?.name ?: "No profile selected"
        val listenAddr = state.listenAddress

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
                    "Full-Device VPN Active • TUN -> $listenAddr"
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
        reloadStore()
        toast("Imported ${profile.name}")
    }

    private fun showProfileEditorDialog(existing: SoarProfile?) {
        val dlgBinding = DialogSoarProfileBinding.inflate(LayoutInflater.from(activity))
        val transports = SoarTransport.entries.map { it.cliValue }
        dlgBinding.spinnerSoarTransport.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            transports
        )

        val defaults = existing?.soarSettings ?: SoarSettings()
        if (existing != null) {
            dlgBinding.etSoarName.setText(existing.name)
            dlgBinding.etSoarDomain.setText(existing.domain)
            dlgBinding.etSoarPublicKey.setText(existing.publicKey)
            dlgBinding.etSoarResolvers.setText(existing.resolvers.joinToString(", "))
        }

        val tIndex = transports.indexOf(defaults.transport).coerceAtLeast(0)
        dlgBinding.spinnerSoarTransport.setSelection(tIndex)
        dlgBinding.switchSoarDiscovery.isChecked = defaults.discovery
        dlgBinding.etSoarCountry.setText(defaults.country)

        dlgBinding.etSoarDiscoveryWant.setText(defaults.discoveryWant.toString())
        dlgBinding.etSoarDiscoveryConcurrency.setText(defaults.discoveryConcurrency.toString())
        dlgBinding.etSoarDiscoveryPaceMs.setText(defaults.discoveryPaceMs.toString())
        dlgBinding.etSoarSafeQName.setText(defaults.safeQName.toString())
        dlgBinding.etSoarMaxQName.setText(defaults.maxQName.toString())
        dlgBinding.etSoarMaxAnswer.setText(defaults.maxAnswer.toString())
        dlgBinding.etSoarHoldMs.setText(defaults.holdMs.toString())
        dlgBinding.etSoarMaxInflight.setText(defaults.maxInflight.toString())
        dlgBinding.etSoarMaxPathWindow.setText(defaults.maxPathWindow.toString())
        dlgBinding.etSoarMaxStreams.setText(defaults.maxStreams.toString())
        dlgBinding.etSoarStreamWindowKiB.setText(defaults.streamWindowKiB.toString())
        dlgBinding.etSoarSendBufferKiB.setText(defaults.sendBufferKiB.toString())
        dlgBinding.etSoarSessionIdleSeconds.setText(defaults.sessionIdleSeconds.toString())
        dlgBinding.etSoarDialTimeoutSeconds.setText(defaults.dialTimeoutSeconds.toString())

        var advancedVisible = false
        dlgBinding.btnSoarToggleAdvanced.setOnClickListener {
            advancedVisible = !advancedVisible
            dlgBinding.layoutSoarAdvanced.visibility = if (advancedVisible) View.VISIBLE else View.GONE
            dlgBinding.tvSoarAdvancedToggle.text = if (advancedVisible) {
                "▾ Advanced Tuning (14 parameters)"
            } else {
                "▸ Advanced Tuning (14 parameters)"
            }
        }

        val title = if (existing == null) "New Soar Profile" else "Edit Soar Profile"
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(dlgBinding.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val domain = dlgBinding.etSoarDomain.text?.toString()?.trim().orEmpty()
                val pubKey = dlgBinding.etSoarPublicKey.text?.toString()?.trim().orEmpty()
                if (domain.isEmpty() || pubKey.isEmpty()) {
                    toast("Server Domain and Public Key are required")
                    return@setPositiveButton
                }
                val name = dlgBinding.etSoarName.text?.toString()?.trim().orEmpty().ifBlank { domain }
                val resolvers = SoarProfileParser.parseResolversText(
                    dlgBinding.etSoarResolvers.text?.toString().orEmpty()
                )
                val settings = SoarSettings(
                    transport = dlgBinding.spinnerSoarTransport.selectedItem?.toString() ?: "auto",
                    discovery = dlgBinding.switchSoarDiscovery.isChecked,
                    country = dlgBinding.etSoarCountry.text?.toString()?.trim().orEmpty().ifBlank { "IR" },
                    discoveryWant = intOrDefault(dlgBinding.etSoarDiscoveryWant.text?.toString(), defaults.discoveryWant),
                    discoveryConcurrency = intOrDefault(dlgBinding.etSoarDiscoveryConcurrency.text?.toString(), defaults.discoveryConcurrency),
                    discoveryPaceMs = intOrDefault(dlgBinding.etSoarDiscoveryPaceMs.text?.toString(), defaults.discoveryPaceMs),
                    safeQName = intOrDefault(dlgBinding.etSoarSafeQName.text?.toString(), defaults.safeQName),
                    maxQName = intOrDefault(dlgBinding.etSoarMaxQName.text?.toString(), defaults.maxQName),
                    maxAnswer = intOrDefault(dlgBinding.etSoarMaxAnswer.text?.toString(), defaults.maxAnswer),
                    holdMs = intOrDefault(dlgBinding.etSoarHoldMs.text?.toString(), defaults.holdMs),
                    maxInflight = intOrDefault(dlgBinding.etSoarMaxInflight.text?.toString(), defaults.maxInflight),
                    maxPathWindow = intOrDefault(dlgBinding.etSoarMaxPathWindow.text?.toString(), defaults.maxPathWindow),
                    maxStreams = intOrDefault(dlgBinding.etSoarMaxStreams.text?.toString(), defaults.maxStreams),
                    streamWindowKiB = intOrDefault(dlgBinding.etSoarStreamWindowKiB.text?.toString(), defaults.streamWindowKiB),
                    sendBufferKiB = intOrDefault(dlgBinding.etSoarSendBufferKiB.text?.toString(), defaults.sendBufferKiB),
                    sessionIdleSeconds = intOrDefault(dlgBinding.etSoarSessionIdleSeconds.text?.toString(), defaults.sessionIdleSeconds),
                    dialTimeoutSeconds = intOrDefault(dlgBinding.etSoarDialTimeoutSeconds.text?.toString(), defaults.dialTimeoutSeconds)
                ).normalized()

                val saved = if (existing != null) {
                    existing.copy(
                        name = name,
                        domain = domain,
                        publicKey = pubKey,
                        resolvers = resolvers,
                        soarSettings = settings
                    )
                } else {
                    SoarProfile(
                        name = name,
                        domain = domain,
                        publicKey = pubKey,
                        resolvers = resolvers,
                        soarSettings = settings
                    )
                }
                storeState = SoarProfileStore.upsertProfile(activity.filesDir, saved, setActive = true)
                reloadStore()
            }
            .show()
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
        ListAdapter<SoarProfile, SoarProfilesAdapter.VH>(DIFF) {

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
                            2 -> showProfileEditorDialog(profile)
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

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SoarProfile>() {
            override fun areItemsTheSame(oldItem: SoarProfile, newItem: SoarProfile): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: SoarProfile, newItem: SoarProfile): Boolean =
                oldItem == newItem
        }
    }
}
