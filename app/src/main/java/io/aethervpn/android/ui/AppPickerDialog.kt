package io.aethervpn.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.aethervpn.android.databinding.DialogAppPickerBinding
import io.aethervpn.android.databinding.ItemAppPickerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

/**
 * Searchable, lightweight Material 3 App Picker dialog for per-app split tunneling.
 * Loads installed apps off the main thread and auto-saves on every selection change.
 */
object AppPickerDialog {

    fun show(
        context: Context,
        scope: CoroutineScope,
        initialSelection: Set<String>,
        onSelectionChanged: (Set<String>) -> Unit
    ) {
        val binding = DialogAppPickerBinding.inflate(LayoutInflater.from(context))
        val selected = initialSelection.toMutableSet()
        var allApps: List<InstalledAppEntry> = emptyList()

        fun updateCountLabel() {
            binding.tvPickerCount.text = "${selected.size} app(s) selected"
        }
        updateCountLabel()

        val adapter = AppListAdapter(
            isSelected = { pkg -> pkg in selected },
            onToggle = { pkg ->
                if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
                updateCountLabel()
                onSelectionChanged(selected.toSet())
            }
        )

        binding.rvApps.layoutManager = LinearLayoutManager(context)
        binding.rvApps.adapter = adapter

        fun applyFilter(query: String) {
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) {
                allApps
            } else {
                allApps.filter {
                    it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            }
            adapter.submit(filtered)
        }

        binding.etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .create()

        binding.btnPickerClear.setOnClickListener {
            selected.clear()
            updateCountLabel()
            adapter.notifyDataSetChanged()
            onSelectionChanged(emptySet())
        }

        binding.btnPickerDone.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val selfPkg = context.packageName
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .asSequence()
                    .filter { it.packageName != selfPkg }
                    .map { appInfo ->
                        InstalledAppEntry(
                            packageName = appInfo.packageName,
                            label = pm.getApplicationLabel(appInfo).toString(),
                            icon = pm.getApplicationIcon(appInfo)
                        )
                    }
                    .sortedWith(
                        compareByDescending<InstalledAppEntry> { it.packageName in selected }
                            .thenBy { it.label.lowercase() }
                    )
                    .toList()
            }
            if (!dialog.isShowing) return@launch
            allApps = loaded
            binding.progressApps.visibility = View.GONE
            binding.rvApps.visibility = View.VISIBLE
            applyFilter(binding.etSearchApps.text?.toString().orEmpty())
        }
    }

    private class AppListAdapter(
        private val isSelected: (String) -> Boolean,
        private val onToggle: (String) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.Holder>() {

        private var items: List<InstalledAppEntry> = emptyList()

        fun submit(newItems: List<InstalledAppEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemAppPickerBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val b: ItemAppPickerBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: InstalledAppEntry) {
                b.ivAppIcon.setImageDrawable(item.icon)
                b.tvAppName.text = item.label
                b.tvAppPackage.text = item.packageName
                b.cbAppSelected.isChecked = isSelected(item.packageName)
                b.root.setOnClickListener {
                    onToggle(item.packageName)
                    b.cbAppSelected.isChecked = isSelected(item.packageName)
                }
            }
        }
    }
}
