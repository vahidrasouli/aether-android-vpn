package io.aethervpn.android.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import io.aethervpn.android.R
import io.aethervpn.android.core.SplitTunnelMode
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.databinding.ActivityAppSplitTunnelBinding

/**
 * Lightweight per-application VPN routing selector backed by VpnService.Builder.
 */
class AppSplitTunnelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSplitTunnelBinding
    private var appPackages: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSplitTunnelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val config = VpnConfig.load(this)
        when (config.splitTunnelMode) {
            SplitTunnelMode.ALL_APPS -> binding.radioAllApps.isChecked = true
            SplitTunnelMode.BYPASS_SELECTED -> binding.radioExcludeApps.isChecked = true
            SplitTunnelMode.ONLY_SELECTED -> binding.radioIncludeOnly.isChecked = true
        }

        val pm = packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        appPackages = installed.map { it.packageName }
        val labels = installed.map { "${pm.getApplicationLabel(it)} (${it.packageName})" }

        binding.listApps.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        binding.listApps.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            labels
        )

        appPackages.forEachIndexed { idx, pkg ->
            if (pkg in config.selectedPackages) {
                binding.listApps.setItemChecked(idx, true)
            }
        }

        binding.btnSaveSplitTunnel.setOnClickListener {
            val mode = when (binding.radioGroupSplitMode.checkedRadioButtonId) {
                R.id.radioExcludeApps -> SplitTunnelMode.BYPASS_SELECTED
                R.id.radioIncludeOnly -> SplitTunnelMode.ONLY_SELECTED
                else -> SplitTunnelMode.ALL_APPS
            }
            val selected = mutableSetOf<String>()
            val checked = binding.listApps.checkedItemPositions
            for (i in 0 until checked.size()) {
                if (checked.valueAt(i)) {
                    val pos = checked.keyAt(i)
                    if (pos in appPackages.indices) {
                        selected.add(appPackages[pos])
                    }
                }
            }
            VpnConfig.save(
                this,
                config.copy(splitTunnelMode = mode, selectedPackages = selected)
            )
            finish()
        }
    }
}
