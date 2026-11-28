package io.aethervpn.android.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.aethervpn.android.BuildConfig
import io.aethervpn.android.R
import io.aethervpn.android.core.CiBuildState
import io.aethervpn.android.core.GitHubActionsStatusChecker
import io.aethervpn.android.core.ReleaseInfo
import io.aethervpn.android.core.UpdateCheckResult
import io.aethervpn.android.core.UpdateInstallPhase
import io.aethervpn.android.core.UpdateInstallSnapshot
import io.aethervpn.android.core.UpdateInstallStateStore
import io.aethervpn.android.core.UpdateManager
import io.aethervpn.android.databinding.ActivityUpdateBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class UpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateBinding
    private var pendingUpdateRelease: ReleaseInfo? = null
    private var pendingInstallApkFile: File? = null
    private var activeUpdateDownloadJob: Job? = null
    private var downloadGeneration = 0L
    private var isChecking = false
    private var ciRingAnimator: ObjectAnimator? = null
    private var ciTimerJob: Job? = null
    private var lastCiEtag: String? = null
    private var lastTriggeredSuccessRunId: Long? = null
    private var sawInProgressBuild = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarUpdate.setNavigationOnClickListener { finish() }

        val versionLabel = "v${BuildConfig.VERSION_NAME}"
        binding.tvCurrentVersion.text = "Current: $versionLabel (b${BuildConfig.VERSION_CODE})"
        binding.tvUpdateChannelBadge.text = getString(
            if (BuildConfig.DEBUG) R.string.channel_debug_prerelease else R.string.channel_stable
        )
        binding.tvAboutDeviceInfo.text =
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        binding.tvAboutGithub.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.GITHUB_REPO_URL)))
            }.onFailure {
                toast("Unable to open browser")
            }
        }

        binding.btnPrimaryUpdateAction.setOnClickListener {
            onPrimaryActionClicked()
        }

        binding.btnCancelUpdate.setOnClickListener {
            cancelActiveDownload()
        }

        binding.btnDiscardUpdate.setOnClickListener {
            discardPartialDownload()
        }

        if (!UpdateInstallStateStore.state.value.isInstallLocked) {
            UpdateInstallStateStore.reset()
        }

        lifecycleScope.launch {
            UpdateInstallStateStore.state.collectLatest { installState ->
                renderInstallState(installState)
            }
        }

        startGitHubActionsStatusPolling()

        // Start update check after the first frame renders so navigation feels instant.
        window.decorView.post {
            if (!isFinishing && !isDestroyed && !UpdateInstallStateStore.state.value.isInstallLocked) {
                checkForUpdates()
            }
        }
    }

    override fun onDestroy() {
        activeUpdateDownloadJob?.cancel()
        ciRingAnimator?.cancel()
        ciTimerJob?.cancel()
        super.onDestroy()
    }

    private fun onPrimaryActionClicked() {
        if (isChecking ||
            activeUpdateDownloadJob?.isActive == true ||
            UpdateInstallStateStore.state.value.isInstallLocked
        ) {
            return
        }

        val cachedApk = pendingInstallApkFile
        if (cachedApk != null && cachedApk.exists()) {
            installDownloadedApk(cachedApk)
            return
        }

        val rel = pendingUpdateRelease
        if (rel == null) {
            checkForUpdates()
            return
        }

        val apkUrl = rel.apkDownloadUrl
        if (apkUrl.isNullOrBlank()) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.htmlUrl)))
            }.onFailure {
                toast("Unable to open release page")
            }
            return
        }

        startDownloadAndPatch(rel)
    }

    private fun checkForUpdates() {
        if (isChecking) return
        activeUpdateDownloadJob?.cancel()
        activeUpdateDownloadJob = null
        pendingInstallApkFile = null
        isChecking = true

        applyPrimaryButtonStyle(enabled = false, label = "Checking…", activeStyle = false)
        binding.btnCancelUpdate.visibility = View.GONE
        binding.btnDiscardUpdate.visibility = View.GONE
        binding.tvUpdateHeadline.text = getString(R.string.update_headline_checking)
        binding.tvUpdateStatus.text = "Querying GitHub Releases…"
        binding.tvLatestBuild.visibility = View.GONE
        binding.tvUpdateDeltaPill.visibility = View.GONE
        binding.tvUpdateSizeComparison.visibility = View.GONE
        binding.cardReleaseNotes.visibility = View.GONE
        binding.tvUpdateStageLabel.visibility = View.GONE
        binding.progressUpdateDownload.visibility = View.VISIBLE
        binding.progressUpdateDownload.isIndeterminate = true
        binding.tvUpdateProgressDetail.visibility = View.GONE
        setHeroIcon(R.drawable.ic_system_update_24, R.color.accent_primary)

        lifecycleScope.launch {
            val result = UpdateManager.checkForUpdates(BuildConfig.VERSION_NAME)
            isChecking = false
            binding.progressUpdateDownload.isIndeterminate = false
            binding.progressUpdateDownload.visibility = View.GONE

            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    val rel = result.release
                    pendingUpdateRelease = rel
                    val delta = rel.deltaManifest
                    val matchedPatch = delta?.matchedPatch

                    setHeroIcon(R.drawable.ic_system_update_24, R.color.accent_primary)
                    binding.tvUpdateHeadline.text = getString(R.string.update_headline_available)
                    binding.tvLatestBuild.text = if (delta != null && delta.toVersionCode > 0) {
                        "Latest: v${rel.versionName} (b${delta.toVersionCode})"
                    } else {
                        "Latest: v${rel.versionName}"
                    }
                    binding.tvLatestBuild.visibility = View.VISIBLE

                    if (delta != null && matchedPatch != null) {
                        val patchMb = matchedPatch.patchSize / (1024.0 * 1024.0)
                        val fullMb = if (delta.fullSize > 0L) delta.fullSize / (1024.0 * 1024.0) else 0.0
                        val savedPct = if (delta.fullSize > 0L) {
                            (((delta.fullSize - matchedPatch.patchSize) * 100L) / delta.fullSize).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }
                        binding.tvUpdateDeltaPill.text = String.format(
                            Locale.US,
                            "⚡ Delta Patch • %.1f MB (%d%% smaller)",
                            patchMb,
                            savedPct
                        )
                        binding.tvUpdateDeltaPill.visibility = View.VISIBLE
                        if (fullMb > 0.0) {
                            binding.tvUpdateSizeComparison.text = String.format(
                                Locale.US,
                                "vs full ~%.1f MB • SHA-256 verified bsdiff patch",
                                fullMb
                            )
                            binding.tvUpdateSizeComparison.visibility = View.VISIBLE
                        }
                        binding.tvUpdateStatus.text = "Delta update ready (${rel.tagName})"
                    } else {
                        binding.tvUpdateDeltaPill.visibility = View.GONE
                        if (delta != null && delta.fullSize > 0L) {
                            val fullMb = delta.fullSize / (1024.0 * 1024.0)
                            binding.tvUpdateSizeComparison.text = String.format(
                                Locale.US,
                                "Full APK • ~%.1f MB • SHA-256 verified",
                                fullMb
                            )
                            binding.tvUpdateSizeComparison.visibility = View.VISIBLE
                        }
                        binding.tvUpdateStatus.text = "New version available (${rel.tagName})"
                    }

                    if (rel.releaseNotes.isNotBlank()) {
                        binding.tvUpdateNotes.text = rel.releaseNotes
                        binding.cardReleaseNotes.visibility = View.VISIBLE
                    }

                    val hasPartial = rel.apkDownloadUrl != null && withContext(Dispatchers.IO) {
                        UpdateManager.getPartialDownloadSize(rel, cacheDir) > 0L
                    }
                    val btnLabel = when {
                        rel.apkDownloadUrl == null -> "Open Release Page"
                        hasPartial -> getString(R.string.action_resume_download)
                        else -> getString(R.string.action_download_install)
                    }
                    binding.btnDiscardUpdate.visibility = if (hasPartial) View.VISIBLE else View.GONE
                    applyPrimaryButtonStyle(enabled = true, label = btnLabel, activeStyle = false)
                }

                is UpdateCheckResult.UpToDate -> {
                    pendingUpdateRelease = null
                    setHeroIcon(R.drawable.ic_check_circle_24, R.color.log_success)
                    binding.tvUpdateHeadline.text = getString(R.string.update_headline_uptodate)
                    binding.tvUpdateStatus.text = "You are running the latest build (v${result.currentVersion})."
                    applyPrimaryButtonStyle(
                        enabled = true,
                        label = getString(R.string.action_check_updates),
                        activeStyle = false
                    )
                }

                is UpdateCheckResult.Unavailable -> {
                    pendingUpdateRelease = null
                    setHeroIcon(R.drawable.ic_error_24, R.color.warning_text)
                    binding.tvUpdateHeadline.text = "Update Check Unavailable"
                    binding.tvUpdateStatus.text = result.message
                    applyPrimaryButtonStyle(
                        enabled = true,
                        label = getString(R.string.action_check_updates),
                        activeStyle = false
                    )
                }
            }
        }
    }

    private fun startDownloadAndPatch(rel: ReleaseInfo) {
        val gen = ++downloadGeneration
        activeUpdateDownloadJob = lifecycleScope.launch {
            val resumingBytes = withContext(Dispatchers.IO) {
                UpdateManager.getPartialDownloadSize(rel, cacheDir)
            }

            applyPrimaryButtonStyle(enabled = false, label = "Updating…", activeStyle = true)
            binding.btnCancelUpdate.visibility = View.VISIBLE
            binding.btnDiscardUpdate.visibility = View.GONE
            binding.tvUpdateStageLabel.visibility = View.VISIBLE
            binding.tvUpdateStageLabel.text = if (resumingBytes > 0L) {
                "Resuming download..."
            } else if (rel.deltaManifest?.matchedPatch != null) {
                "Downloading delta patch..."
            } else {
                "Downloading..."
            }
            val expectedTotalBytes = rel.deltaManifest?.matchedPatch?.patchSize
                ?: rel.deltaManifest?.fullSize
                ?: -1L
            binding.progressUpdateDownload.visibility = View.VISIBLE
            binding.progressUpdateDownload.isIndeterminate = false
            if (resumingBytes > 0L && expectedTotalBytes > 0L) {
                val initPct = ((resumingBytes * 100L) / expectedTotalBytes).toInt().coerceIn(0, 100)
                binding.progressUpdateDownload.progress = initPct
                binding.tvUpdateProgressDetail.text =
                    io.aethervpn.android.core.DownloadProgress(resumingBytes, expectedTotalBytes, initPct).formatLabel()
            } else {
                binding.progressUpdateDownload.progress = 0
                binding.tvUpdateProgressDetail.text = if (resumingBytes > 0L) {
                    String.format(Locale.US, "Resuming from %.1f MB…", resumingBytes / (1024.0 * 1024.0))
                } else {
                    "0% • Starting download…"
                }
            }
            binding.tvUpdateProgressDetail.visibility = View.VISIBLE
            binding.tvUpdateStatus.text = binding.tvUpdateStageLabel.text

            try {
                val downloadResult = UpdateManager.downloadUpdate(
                    release = rel,
                    baseApkPath = applicationInfo?.sourceDir,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    cacheDir = cacheDir,
                    onStatus = { statusText ->
                        lifecycleScope.launch {
                            if (activeUpdateDownloadJob?.isActive == true) {
                                binding.tvUpdateStageLabel.text = statusText
                                binding.tvUpdateStatus.text = statusText
                                binding.progressUpdateDownload.isIndeterminate =
                                    statusText.contains("Applying", ignoreCase = true) ||
                                        statusText.contains("Verifying", ignoreCase = true)
                            }
                        }
                    }
                ) { progress ->
                    lifecycleScope.launch {
                        if (activeUpdateDownloadJob?.isActive == true) {
                            binding.progressUpdateDownload.isIndeterminate = false
                            binding.progressUpdateDownload.progress = progress.percent
                            binding.tvUpdateProgressDetail.text = progress.formatLabel()
                        }
                    }
                }

                binding.btnCancelUpdate.visibility = View.GONE
                if (gen != downloadGeneration) return@launch
                downloadResult.onSuccess { file ->
                    pendingInstallApkFile = file
                    binding.btnDiscardUpdate.visibility = View.GONE
                    binding.progressUpdateDownload.isIndeterminate = false
                    binding.progressUpdateDownload.progress = 100
                    binding.tvUpdateStageLabel.text = "Installing..."
                    binding.tvUpdateStatus.text = "Package verified. Launching installer..."
                    applyPrimaryButtonStyle(
                        enabled = true,
                        label = getString(R.string.action_install_update),
                        activeStyle = false
                    )
                    installDownloadedApk(file)
                }.onFailure { err ->
                    binding.progressUpdateDownload.isIndeterminate = false
                    val hasPart = withContext(Dispatchers.IO) {
                        UpdateManager.getPartialDownloadSize(rel, cacheDir) > 0L
                    }
                    if (gen != downloadGeneration) return@launch
                    if (hasPart) {
                        binding.btnDiscardUpdate.visibility = View.VISIBLE
                        applyPrimaryButtonStyle(
                            enabled = true,
                            label = getString(R.string.action_resume_download),
                            activeStyle = false
                        )
                        binding.tvUpdateStageLabel.text = "Download paused"
                        binding.tvUpdateStatus.text = "Download paused (${err.message ?: "network error"})"
                    } else {
                        binding.btnDiscardUpdate.visibility = View.GONE
                        applyPrimaryButtonStyle(
                            enabled = true,
                            label = getString(R.string.action_download_install),
                            activeStyle = false
                        )
                        binding.tvUpdateStageLabel.visibility = View.GONE
                        binding.progressUpdateDownload.visibility = View.GONE
                        binding.tvUpdateProgressDetail.visibility = View.GONE
                        binding.tvUpdateStatus.text = "Download failed: ${err.message ?: "unknown error"}"
                    }
                }
            } catch (_: CancellationException) {
                // UI already transitioned immediately in cancelActiveDownload()
            } finally {
                if (gen == downloadGeneration) {
                    activeUpdateDownloadJob = null
                }
            }
        }
    }

    private fun cancelActiveDownload() {
        downloadGeneration++
        val job = activeUpdateDownloadJob
        activeUpdateDownloadJob = null
        job?.cancel()
        lifecycleScope.launch(Dispatchers.IO) {
            UpdateManager.abortActiveDownloads()
        }

        binding.btnCancelUpdate.visibility = View.GONE
        binding.progressUpdateDownload.isIndeterminate = false

        val rel = pendingUpdateRelease
        lifecycleScope.launch {
            val savedBytes = if (rel != null) {
                withContext(Dispatchers.IO) {
                    UpdateManager.getPartialDownloadSize(rel, cacheDir)
                }
            } else {
                0L
            }
            if (savedBytes > 0L) {
                binding.btnDiscardUpdate.visibility = View.VISIBLE
                binding.tvUpdateStageLabel.visibility = View.VISIBLE
                binding.tvUpdateStageLabel.text = "Download paused"
                binding.tvUpdateStatus.text = "Download paused • Tap Resume to continue"
                applyPrimaryButtonStyle(
                    enabled = true,
                    label = getString(R.string.action_resume_download),
                    activeStyle = false
                )
            } else {
                resetDownloadProgressUi("Download cancelled")
            }
        }
    }

    private fun discardPartialDownload() {
        downloadGeneration++
        activeUpdateDownloadJob?.cancel()
        activeUpdateDownloadJob = null
        val rel = pendingUpdateRelease
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                UpdateManager.abortActiveDownloads()
                UpdateManager.discardAllPartialDownloads(rel, cacheDir)
            }
            resetDownloadProgressUi("Partial download discarded")
            checkForUpdates()
        }
    }

    private fun resetDownloadProgressUi(statusMessage: String) {
        binding.tvUpdateStageLabel.visibility = View.GONE
        binding.progressUpdateDownload.isIndeterminate = false
        binding.progressUpdateDownload.visibility = View.GONE
        binding.progressUpdateDownload.progress = 0
        binding.tvUpdateProgressDetail.visibility = View.GONE
        binding.tvUpdateProgressDetail.text = ""
        binding.tvUpdateStatus.text = statusMessage
        binding.btnCancelUpdate.visibility = View.GONE
        binding.btnDiscardUpdate.visibility = View.GONE
        val label = if (pendingUpdateRelease != null) {
            getString(R.string.action_download_install)
        } else {
            getString(R.string.action_check_updates)
        }
        applyPrimaryButtonStyle(enabled = true, label = label, activeStyle = false)
    }

    private fun startGitHubActionsStatusPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val result = GitHubActionsStatusChecker.fetchLatestRunStatus(lastCiEtag)
                    var nextDelayMs = 30_000L
                    result.onSuccess { poll ->
                        lastCiEtag = poll.etag
                        val state = poll.state
                        if (!poll.notModified && state != null) {
                            renderCiBuildState(state)
                            if (state is CiBuildState.InProgress) {
                                nextDelayMs = GitHubActionsStatusChecker.POLL_INTERVAL_ACTIVE_MS
                            }
                        } else if (sawInProgressBuild) {
                            nextDelayMs = GitHubActionsStatusChecker.POLL_INTERVAL_ACTIVE_MS
                        }
                    }
                    delay(nextDelayMs)
                }
            }
        }
    }

    private fun renderCiBuildState(state: CiBuildState) {
        binding.cardCiBuildStatus.visibility = View.VISIBLE
        when (state) {
            is CiBuildState.InProgress -> {
                sawInProgressBuild = true
                binding.ivCiStatusIcon.setImageResource(R.drawable.ic_gh_actions_ring)
                binding.ivCiStatusIcon.imageTintList = null
                startCiRingAnimation()
                binding.tvCiStatusPill.text = state.status.replace('_', ' ').uppercase(Locale.US)
                binding.tvCiStatusPill.setTextColor(ContextCompat.getColor(this, R.color.warning_text))
                binding.tvCiStatusTitle.text = state.title.ifBlank { "Building release…" }
                startCiElapsedTimer(state.startedAtMs)
            }

            is CiBuildState.Succeeded -> {
                stopCiAnimations()
                binding.ivCiStatusIcon.setImageResource(R.drawable.ic_check_circle_24)
                binding.ivCiStatusIcon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.log_success)
                )
                binding.tvCiStatusPill.text = "PASSED"
                binding.tvCiStatusPill.setTextColor(ContextCompat.getColor(this, R.color.log_success))

                val shouldAutoRefresh = sawInProgressBuild && lastTriggeredSuccessRunId != state.runId
                sawInProgressBuild = false
                if (shouldAutoRefresh) {
                    lastTriggeredSuccessRunId = state.runId
                    binding.tvCiStatusTitle.text = "Build succeeded — checking latest update…"
                    binding.tvCiStatusSubtitle.text = state.title
                    checkForUpdates()
                } else {
                    binding.tvCiStatusTitle.text = "Latest CI build succeeded"
                    binding.tvCiStatusSubtitle.text = state.title
                }
            }

            is CiBuildState.Failed -> {
                sawInProgressBuild = false
                stopCiAnimations()
                binding.ivCiStatusIcon.setImageResource(R.drawable.ic_error_24)
                val isCancelled = state.conclusion == "cancelled"
                val colorRes = if (isCancelled) R.color.text_secondary else R.color.error_text
                binding.ivCiStatusIcon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, colorRes)
                )
                binding.tvCiStatusPill.text = state.conclusion.uppercase(Locale.US)
                binding.tvCiStatusPill.setTextColor(ContextCompat.getColor(this, colorRes))
                binding.tvCiStatusTitle.text = if (isCancelled) "CI build cancelled" else "CI build failed"
                binding.tvCiStatusSubtitle.text = state.title
            }
        }
    }

    private fun startCiRingAnimation() {
        if (ciRingAnimator?.isRunning == true) return
        ciRingAnimator = ObjectAnimator.ofFloat(binding.ivCiStatusIcon, View.ROTATION, 0f, 360f).apply {
            duration = 1200L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun startCiElapsedTimer(startedAtMs: Long) {
        ciTimerJob?.cancel()
        ciTimerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startedAtMs
                binding.tvCiStatusSubtitle.text =
                    "Building release… ${GitHubActionsStatusChecker.formatElapsed(elapsed)}"
                delay(1000L)
            }
        }
    }

    private fun stopCiAnimations() {
        ciTimerJob?.cancel()
        ciTimerJob = null
        ciRingAnimator?.cancel()
        ciRingAnimator = null
        binding.ivCiStatusIcon.rotation = 0f
    }

    private fun installDownloadedApk(apkFile: File) {
        if (UpdateInstallStateStore.state.value.isInstallLocked) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast("Please allow installing unknown apps, then tap Install Update")
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(permIntent) }
            return
        }

        UpdateInstallStateStore.beginInstalling(getString(R.string.update_stage_installing))
        lifecycleScope.launch {
            val result = UpdateManager.installApkWithPackageInstaller(this@UpdateActivity, apkFile)
            result.onFailure { err ->
                UpdateInstallStateStore.onInstallStatus(
                    PackageInstaller.STATUS_FAILURE,
                    err.message
                )
            }
        }
    }

    private fun renderInstallState(installState: UpdateInstallSnapshot) {
        when (installState.phase) {
            UpdateInstallPhase.IDLE -> Unit

            UpdateInstallPhase.INSTALLING -> {
                binding.btnCancelUpdate.visibility = View.GONE
                binding.tvUpdateStageLabel.visibility = View.VISIBLE
                binding.tvUpdateStageLabel.text = getString(R.string.update_stage_installing)
                binding.tvUpdateStatus.text =
                    installState.statusMessage ?: getString(R.string.update_stage_installing)
                binding.progressUpdateDownload.visibility = View.VISIBLE
                binding.progressUpdateDownload.isIndeterminate = true
                applyPrimaryButtonStyle(
                    enabled = false,
                    label = getString(R.string.action_installing),
                    activeStyle = true
                )
            }

            UpdateInstallPhase.FAILED -> {
                binding.progressUpdateDownload.isIndeterminate = false
                binding.progressUpdateDownload.visibility = View.GONE
                binding.tvUpdateStageLabel.visibility = View.VISIBLE
                binding.tvUpdateStageLabel.text = "Install failed"
                binding.tvUpdateStatus.text =
                    installState.statusMessage ?: "Installation failed. Tap Install Update to retry."
                val cachedApk = pendingInstallApkFile
                val retryLabel = when {
                    cachedApk != null && cachedApk.exists() -> getString(R.string.action_install_update)
                    pendingUpdateRelease != null -> getString(R.string.action_download_install)
                    else -> getString(R.string.action_check_updates)
                }
                applyPrimaryButtonStyle(
                    enabled = true,
                    label = retryLabel,
                    activeStyle = false
                )
            }

            UpdateInstallPhase.SUCCEEDED -> {
                binding.progressUpdateDownload.isIndeterminate = false
                binding.progressUpdateDownload.visibility = View.GONE
                binding.tvUpdateStageLabel.visibility = View.VISIBLE
                binding.tvUpdateStageLabel.text = "Update installed"
                binding.tvUpdateStatus.text = installState.statusMessage ?: "Update installed"
                applyPrimaryButtonStyle(
                    enabled = false,
                    label = "Installed",
                    activeStyle = false
                )
            }
        }
    }

    private fun setHeroIcon(drawableRes: Int, colorRes: Int) {
        binding.ivUpdateStatusIcon.setImageResource(drawableRes)
        binding.ivUpdateStatusIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun applyPrimaryButtonStyle(enabled: Boolean, label: String, activeStyle: Boolean) {
        binding.btnPrimaryUpdateAction.isEnabled = enabled
        binding.btnPrimaryUpdateAction.text = label
        if (activeStyle) {
            binding.btnPrimaryUpdateAction.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent_primary_container)
            )
            binding.btnPrimaryUpdateAction.setTextColor(
                ContextCompat.getColor(this, R.color.accent_primary)
            )
        } else {
            binding.btnPrimaryUpdateAction.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent_primary)
            )
            binding.btnPrimaryUpdateAction.setTextColor(
                ContextCompat.getColor(this, R.color.surface_bg)
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
