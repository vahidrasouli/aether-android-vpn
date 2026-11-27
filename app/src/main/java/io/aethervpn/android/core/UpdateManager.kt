package io.aethervpn.android.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.aethervpn.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.coroutines.coroutineContext

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // Stable release > pre-release for the same major.minor.patch
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        if (!preRelease.isNullOrBlank()) {
            append("-$preRelease")
        }
    }

    companion object {
        private val SEMVER_REGEX =
            Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(raw: String): SemVer? {
            val trimmed = raw.trim()
            val match = SEMVER_REGEX.matchEntire(trimmed) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            val pre = match.groupValues[4].ifEmpty { null }
            return SemVer(major, minor, patch, pre)
        }

        fun isNewerVersion(remote: String, current: String): Boolean {
            val remoteVer = parse(remote) ?: return false
            val currentVer = parse(current) ?: return false
            return remoteVer > currentVer
        }

        private fun comparePreRelease(a: String, b: String): Int {
            val partsA = a.split('.')
            val partsB = b.split('.')
            val count = maxOf(partsA.size, partsB.size)
            for (i in 0 until count) {
                val pa = partsA.getOrNull(i) ?: return -1
                val pb = partsB.getOrNull(i) ?: return 1
                val na = pa.toIntOrNull()
                val nb = pb.toIntOrNull()
                val cmp = when {
                    na != null && nb != null -> na.compareTo(nb)
                    na != null -> -1 // Numeric identifiers have lower precedence than non-numeric
                    nb != null -> 1
                    else -> pa.compareTo(pb)
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

data class DebugBuildVersion(
    val baseSemVer: SemVer,
    val buildNumber: Int?,
    val isLocal: Boolean,
    val rawVersion: String
) {
    fun isNewerThan(currentRaw: String): Boolean {
        val currentDebug = parse(currentRaw)
        val currentBase = currentDebug?.baseSemVer ?: SemVer.parse(currentRaw)?.let {
            SemVer(it.major, it.minor, it.patch)
        } ?: return false

        if (baseSemVer > currentBase) return true
        if (baseSemVer < currentBase) return false

        if (currentDebug == null) {
            return buildNumber != null
        }
        if (currentDebug.isLocal && buildNumber != null) {
            return true
        }
        val remoteNum = buildNumber ?: return false
        val currentNum = currentDebug.buildNumber ?: return false
        return remoteNum > currentNum
    }

    companion object {
        private val DEBUG_VERSION_REGEX =
            Regex("""(?:^|aether-android-arm64-)[vV]?(\d+)\.(\d+)\.(\d+)-debug[.-](?:b?(\d+)(?:-[0-9A-Za-z]+)?|(local))(?:\.apk)?$""")

        fun parse(raw: String): DebugBuildVersion? {
            val trimmed = raw.trim()
            val match = DEBUG_VERSION_REGEX.find(trimmed) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            val buildNum = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val isLocal = match.groupValues[5] == "local"
            val normalized = trimmed
                .removePrefix("aether-android-arm64-")
                .removeSuffix(".apk")
                .removePrefix("v")
                .removePrefix("V")
            return DebugBuildVersion(
                baseSemVer = SemVer(major, minor, patch),
                buildNumber = buildNum,
                isLocal = isLocal,
                rawVersion = normalized
            )
        }
    }
}

data class DeltaPatchEntry(
    val fromVersionCode: Int,
    val patchAssetName: String,
    val patchSize: Long,
    val patchSha256: String,
    val patchDownloadUrl: String? = null
)

data class DeltaManifest(
    val toVersionCode: Int,
    val toVersionName: String,
    val fullAssetName: String,
    val fullSize: Long,
    val targetApkSha256: String,
    val matchedPatch: DeltaPatchEntry? = null
)

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val htmlUrl: String,
    val deltaManifest: DeltaManifest? = null
)

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val speedBytesPerSec: Long = 0L
) {
    fun formatLabel(): String {
        val dlMb = downloadedBytes / (1024.0 * 1024.0)
        val speedSuffix = formatSpeedSuffix(speedBytesPerSec)
        return if (totalBytes > 0L) {
            val totMb = totalBytes / (1024.0 * 1024.0)
            String.format(java.util.Locale.US, "%d%% • %.1f / %.1f MB%s", percent, dlMb, totMb, speedSuffix)
        } else {
            String.format(java.util.Locale.US, "%.1f MB%s", dlMb, speedSuffix)
        }
    }

    companion object {
        private fun formatSpeedSuffix(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return ""
            return if (bytesPerSec >= 1024L * 1024L) {
                String.format(java.util.Locale.US, " • %.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            } else {
                String.format(java.util.Locale.US, " • %.0f KB/s", bytesPerSec / 1024.0)
            }
        }
    }
}

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class Unavailable(val message: String) : UpdateCheckResult
}

enum class UpdateInstallPhase {
    IDLE,
    INSTALLING,
    FAILED,
    SUCCEEDED
}

data class UpdateInstallSnapshot(
    val phase: UpdateInstallPhase = UpdateInstallPhase.IDLE,
    val statusMessage: String? = null,
    val awaitingUserConfirmation: Boolean = false
) {
    val isInstallLocked: Boolean get() = phase == UpdateInstallPhase.INSTALLING
}

object UpdateInstallStateStore {
    private val _state = MutableStateFlow(UpdateInstallSnapshot())
    val state: StateFlow<UpdateInstallSnapshot> = _state.asStateFlow()

    fun beginInstalling(message: String = "Installing update…") {
        _state.value = UpdateInstallSnapshot(
            phase = UpdateInstallPhase.INSTALLING,
            statusMessage = message,
            awaitingUserConfirmation = false
        )
    }

    fun onInstallStatus(status: Int, statusMessage: String? = null) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                _state.value = _state.value.copy(
                    phase = UpdateInstallPhase.INSTALLING,
                    statusMessage = "Confirm the system prompt to finish installing…",
                    awaitingUserConfirmation = true
                )
            }

            PackageInstaller.STATUS_SUCCESS -> {
                _state.value = UpdateInstallSnapshot(
                    phase = UpdateInstallPhase.SUCCEEDED,
                    statusMessage = "Update installed",
                    awaitingUserConfirmation = false
                )
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                _state.value = UpdateInstallSnapshot(
                    phase = UpdateInstallPhase.FAILED,
                    statusMessage = "Installation cancelled. Tap Install Update to retry.",
                    awaitingUserConfirmation = false
                )
            }

            else -> {
                val detail = statusMessage?.takeIf { it.isNotBlank() } ?: "status=$status"
                _state.value = UpdateInstallSnapshot(
                    phase = UpdateInstallPhase.FAILED,
                    statusMessage = "Installation failed ($detail). Tap Install Update to retry.",
                    awaitingUserConfirmation = false
                )
            }
        }
    }

    fun reset() {
        _state.value = UpdateInstallSnapshot()
    }
}

object UpdateManager {
    const val GITHUB_REPO_URL = "https://github.com/vahidrasouli/aether-android-vpn"
    const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/vahidrasouli/aether-android-vpn/releases/latest"
    const val GITHUB_DEBUG_RELEASE_API =
        "https://api.github.com/repos/vahidrasouli/aether-android-vpn/releases/tags/debug-latest"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    fun apiUrlForChannel(isDebugBuild: Boolean): String =
        if (isDebugBuild) GITHUB_DEBUG_RELEASE_API else GITHUB_LATEST_RELEASE_API

    suspend fun checkForUpdates(
        currentVersion: String,
        isDebugBuild: Boolean = BuildConfig.DEBUG,
        apiUrl: String = apiUrlForChannel(isDebugBuild),
        currentVersionCode: Int = BuildConfig.VERSION_CODE
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AetherVpnAndroid/$currentVersion")
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                return@withContext UpdateCheckResult.Unavailable(
                    "No public releases found yet (HTTP $code)."
                )
            }
            if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                return@withContext UpdateCheckResult.Unavailable(
                    "GitHub API rate limit or access restricted (HTTP 403)."
                )
            }
            if (code !in 200..299) {
                return@withContext UpdateCheckResult.Unavailable(
                    "Update check returned HTTP $code."
                )
            }

            val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
            val deltaManifestJson = if (isDebugBuild) {
                extractAssetUrl(bodyText, DELTA_MANIFEST_ASSET_NAME)?.let { manifestUrl ->
                    fetchTextSafely(manifestUrl, currentVersion)
                }
            } else {
                null
            }
            parseReleaseResponse(
                jsonBody = bodyText,
                currentVersion = currentVersion,
                isDebugBuild = isDebugBuild,
                deltaManifestJson = deltaManifestJson,
                currentVersionCode = currentVersionCode
            )
        } catch (e: Exception) {
            UpdateCheckResult.Unavailable(
                "Unable to check for updates: ${e.message ?: "network error"}"
            )
        } finally {
            conn?.disconnect()
        }
    }

    private const val DELTA_MANIFEST_ASSET_NAME = "delta-manifest.json"

    private fun extractAssetUrl(jsonBody: String, targetAssetName: String): String? {
        val json = runCatching { JSONObject(jsonBody) }.getOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name", "") == targetAssetName) {
                val url = asset.optString("browser_download_url", "")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private fun fetchTextSafely(url: String, currentVersion: String): String? = runCatching {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "AetherVpnAndroid/$currentVersion")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            conn?.disconnect()
        }
    }.getOrNull()

    internal fun parseDeltaManifest(
        manifestJson: String,
        currentVersionCode: Int,
        assetUrls: Map<String, String>
    ): DeltaManifest? {
        val obj = runCatching { JSONObject(manifestJson) }.getOrNull() ?: return null
        val toCode = obj.optInt("toVersionCode", -1)
        val toVersionName = obj.optString("toVersionName", "").trim()
        val fullName = obj.optString("fullAssetName", "").trim()
        val fullSize = obj.optLong("fullSize", -1L)
        val sha256 = obj.optString("targetApkSha256", "").trim()

        if (toCode <= 0 || fullName.isEmpty() || sha256.length != 64) return null

        var matchedPatch: DeltaPatchEntry? = null
        val patchesArray = obj.optJSONArray("patches")
        if (patchesArray != null && currentVersionCode in 1 until toCode) {
            for (i in 0 until patchesArray.length()) {
                val entry = patchesArray.optJSONObject(i) ?: continue
                val fromCode = entry.optInt("fromVersionCode", -1)
                if (fromCode != currentVersionCode) continue
                val patchName = entry.optString("patchAssetName", "").trim()
                val patchSize = entry.optLong("patchSize", -1L)
                val patchSha256 = entry.optString("patchSha256", "").trim()
                val patchUrl = assetUrls[patchName]?.takeIf { it.isNotBlank() }
                if (patchName.isNotEmpty() && patchSize > 0L && patchSha256.length == 64 && patchUrl != null) {
                    matchedPatch = DeltaPatchEntry(
                        fromVersionCode = fromCode,
                        patchAssetName = patchName,
                        patchSize = patchSize,
                        patchSha256 = patchSha256,
                        patchDownloadUrl = patchUrl
                    )
                    break
                }
            }
        }

        return DeltaManifest(
            toVersionCode = toCode,
            toVersionName = toVersionName,
            fullAssetName = fullName,
            fullSize = fullSize,
            targetApkSha256 = sha256,
            matchedPatch = matchedPatch
        )
    }

    internal fun parseReleaseResponse(
        jsonBody: String,
        currentVersion: String,
        isDebugBuild: Boolean = false,
        deltaManifestJson: String? = null,
        currentVersionCode: Int = 0
    ): UpdateCheckResult {
        val json = runCatching { JSONObject(jsonBody) }.getOrNull()
            ?: return UpdateCheckResult.Unavailable("Invalid release response from GitHub.")

        val tagName = json.optString("tag_name", "").trim()
        if (tagName.isEmpty()) {
            return UpdateCheckResult.Unavailable("Latest release has no version tag.")
        }

        val releaseNotes = json.optString("body", "").trim()
            .ifEmpty { "Bug fixes and performance improvements." }
        val htmlUrl = json.optString("html_url", GITHUB_REPO_URL).ifBlank { GITHUB_REPO_URL }

        var apkUrl: String? = null
        var apkName: String? = null
        val assetUrls = mutableMapOf<String, String>()
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.isNotBlank() && url.isNotBlank()) {
                    assetUrls[name] = url
                }
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    val isArm64 = name.contains("arm64", ignoreCase = true)
                    val isDebugAsset = name.contains("debug", ignoreCase = true)
                    if (apkUrl == null || (isArm64 && isDebugAsset == isDebugBuild)) {
                        apkUrl = url
                        apkName = name
                    }
                    if (isArm64 && isDebugAsset == isDebugBuild) {
                        break
                    }
                }
            }
            // Ensure all remaining assets are indexed for delta manifest lookup
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.isNotBlank() && url.isNotBlank()) {
                    assetUrls[name] = url
                }
            }
        }

        val resolvedVersionName = if (isDebugBuild) {
            val releaseName = json.optString("name", "").trim()
            val remoteDebug = DebugBuildVersion.parse(releaseName)
                ?: apkName?.let { DebugBuildVersion.parse(it) }
                ?: DebugBuildVersion.parse(tagName)
                ?: return UpdateCheckResult.Unavailable("Unrecognized debug release version.")

            if (!remoteDebug.isNewerThan(currentVersion)) {
                return UpdateCheckResult.UpToDate(currentVersion)
            }
            remoteDebug.rawVersion
        } else {
            val parsedRemote = SemVer.parse(tagName)
                ?: return UpdateCheckResult.Unavailable("Unrecognized release tag: $tagName")

            if (!SemVer.isNewerVersion(tagName, currentVersion)) {
                return UpdateCheckResult.UpToDate(currentVersion)
            }
            parsedRemote.toString()
        }

        val deltaManifest = if (isDebugBuild && deltaManifestJson != null && currentVersionCode > 0) {
            parseDeltaManifest(deltaManifestJson, currentVersionCode, assetUrls)
        } else {
            null
        }

        return UpdateCheckResult.UpdateAvailable(
            ReleaseInfo(
                tagName = tagName,
                versionName = resolvedVersionName,
                releaseNotes = releaseNotes,
                apkDownloadUrl = apkUrl,
                apkFileName = apkName,
                htmlUrl = htmlUrl,
                deltaManifest = deltaManifest
            )
        )
    }

    internal fun sanitizeFileName(fileName: String): String =
        fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    internal fun partFileNameFor(
        fileName: String,
        targetVersionCode: Int = -1,
        isPatch: Boolean = false
    ): String = if (targetVersionCode > 0) {
        if (isPatch) "b$targetVersionCode.patch.part" else "b$targetVersionCode.apk.part"
    } else {
        "${sanitizeFileName(fileName)}.part"
    }

    internal const val PARALLEL_MIN_BYTES = 1_048_576L
    internal const val PARALLEL_CHUNK_COUNT = 4
    private const val CHUNK_BUFFER_SIZE = 64 * 1024
    private const val CHUNKS_META_SUFFIX = ".chunks.json"
    private const val MAX_REDIRECTS = 5

    internal data class ChunkState(
        val index: Int,
        val start: Long,
        val end: Long,
        val downloaded: Long
    ) {
        val length: Long get() = (end - start + 1L).coerceAtLeast(0L)
        val isComplete: Boolean get() = downloaded >= length
    }

    private data class ProbeResult(
        val resolvedUrl: String,
        val totalBytes: Long,
        val acceptRanges: Boolean
    )

    internal fun chunksFileFor(partFile: File): File =
        File(partFile.parentFile, "${partFile.name}$CHUNKS_META_SUFFIX")

    internal fun buildInitialChunks(
        totalBytes: Long,
        chunkCount: Int = PARALLEL_CHUNK_COUNT
    ): List<ChunkState> {
        val baseSize = totalBytes / chunkCount
        return (0 until chunkCount).map { i ->
            val start = i * baseSize
            val end = if (i == chunkCount - 1) totalBytes - 1L else (i + 1) * baseSize - 1L
            ChunkState(index = i, start = start, end = end, downloaded = 0L)
        }
    }

    internal fun loadChunkStates(
        chunksFile: File,
        partFile: File,
        expectedTotalBytes: Long = -1L
    ): List<ChunkState>? {
        if (!chunksFile.isFile || !partFile.isFile) return null
        val obj = runCatching { JSONObject(chunksFile.readText()) }.getOrNull() ?: return null
        val totalBytes = obj.optLong("totalBytes", -1L)
        if (totalBytes <= 0L || partFile.length() != totalBytes) return null
        if (expectedTotalBytes > 0L && totalBytes != expectedTotalBytes) return null

        val arr = obj.optJSONArray("chunks") ?: return null
        if (arr.length() != PARALLEL_CHUNK_COUNT) return null
        val expected = buildInitialChunks(totalBytes, PARALLEL_CHUNK_COUNT)
        val loaded = ArrayList<ChunkState>(PARALLEL_CHUNK_COUNT)
        for (i in 0 until PARALLEL_CHUNK_COUNT) {
            val item = arr.optJSONObject(i) ?: return null
            val idx = item.optInt("index", -1)
            val start = item.optLong("start", -1L)
            val end = item.optLong("end", -1L)
            val downloaded = item.optLong("downloaded", -1L)
            val exp = expected[i]
            if (idx != exp.index || start != exp.start || end != exp.end) return null
            if (downloaded < 0L || downloaded > exp.length) return null
            loaded.add(ChunkState(index = idx, start = start, end = end, downloaded = downloaded))
        }
        return loaded
    }

    private fun saveChunkStates(
        chunksFile: File,
        totalBytes: Long,
        resolvedUrl: String,
        chunks: List<ChunkState>,
        downloadedPerChunk: AtomicLongArray
    ) {
        val arr = JSONArray()
        for (c in chunks) {
            arr.put(
                JSONObject().apply {
                    put("index", c.index)
                    put("start", c.start)
                    put("end", c.end)
                    put("downloaded", downloadedPerChunk.get(c.index))
                }
            )
        }
        val root = JSONObject().apply {
            put("totalBytes", totalBytes)
            put("url", resolvedUrl)
            put("chunks", arr)
        }
        runCatching { chunksFile.writeText(root.toString()) }
    }

    fun getPartialDownloadSize(
        fileName: String,
        cacheDir: File,
        targetVersionCode: Int = -1,
        isPatch: Boolean = false
    ): Long {
        val updatesDir = File(cacheDir, "updates")
        val partFile = File(updatesDir, partFileNameFor(fileName, targetVersionCode, isPatch))
        if (!partFile.exists() || !partFile.isFile) return 0L
        val chunksFile = chunksFileFor(partFile)
        val chunkStates = loadChunkStates(chunksFile, partFile)
        if (chunkStates != null) {
            return chunkStates.sumOf { it.downloaded }
        }
        return partFile.length()
    }

    fun getPartialDownloadSize(release: ReleaseInfo, cacheDir: File): Long {
        val delta = release.deltaManifest
        val targetVc = delta?.toVersionCode ?: -1
        val patch = delta?.matchedPatch
        if (patch != null && targetVc > 0) {
            val patchPart = getPartialDownloadSize(patch.patchAssetName, cacheDir, targetVc, isPatch = true)
            if (patchPart > 0L) return patchPart
        }
        val fullName = release.apkFileName ?: "aether-android-arm64-v${release.versionName}.apk"
        return getPartialDownloadSize(fullName, cacheDir, targetVc, isPatch = false)
    }

    fun cancelPartialDownload(
        fileName: String,
        cacheDir: File,
        targetVersionCode: Int = -1,
        isPatch: Boolean = false
    ) {
        val updatesDir = File(cacheDir, "updates")
        val partFile = File(updatesDir, partFileNameFor(fileName, targetVersionCode, isPatch))
        val chunksFile = chunksFileFor(partFile)
        if (chunksFile.exists()) {
            runCatching { chunksFile.delete() }
        }
        if (partFile.exists()) {
            runCatching { partFile.delete() }
        }
    }

    fun cancelPartialDownloads(release: ReleaseInfo, cacheDir: File) {
        val delta = release.deltaManifest
        val targetVc = delta?.toVersionCode ?: -1
        val fullName = release.apkFileName ?: "aether-android-arm64-v${release.versionName}.apk"
        cancelPartialDownload(fullName, cacheDir, targetVc, isPatch = false)
        delta?.matchedPatch?.let { patch ->
            cancelPartialDownload(patch.patchAssetName, cacheDir, targetVc, isPatch = true)
        }
    }

    fun cleanupInstalledApks(cacheDir: File) {
        val updatesDir = File(cacheDir, "updates")
        if (!updatesDir.exists() || !updatesDir.isDirectory) return
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".apk", ignoreCase = true) ||
                    file.name.endsWith(".tmp", ignoreCase = true) ||
                    file.name.endsWith(".patch", ignoreCase = true))
            ) {
                runCatching { file.delete() }
            }
        }
    }

    internal fun cleanupUpdatesDir(updatesDir: File, keepPartFileName: String? = null) {
        val keepPartFile = keepPartFileName?.let { File(updatesDir, it) }
        val keepPartExists = keepPartFile != null && keepPartFile.isFile && keepPartFile.length() > 0L
        val keepChunksName = if (keepPartExists) "${keepPartFileName}$CHUNKS_META_SUFFIX" else null
        updatesDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val isSamePart = keepPartExists && file.name == keepPartFileName
            val isSameChunks = keepChunksName != null && file.name == keepChunksName
            if (isSamePart || isSameChunks) return@forEach
            if (file.name.endsWith(".apk", ignoreCase = true) ||
                file.name.endsWith(".tmp", ignoreCase = true) ||
                file.name.endsWith(".part", ignoreCase = true) ||
                file.name.endsWith(".patch", ignoreCase = true) ||
                file.name.endsWith(CHUNKS_META_SUFFIX, ignoreCase = true)
            ) {
                runCatching { file.delete() }
            }
        }
    }

    suspend fun downloadUpdate(
        release: ReleaseInfo,
        baseApkPath: String?,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        cacheDir: File,
        onStatus: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> {
        val delta = release.deltaManifest
        val targetVersionCode = delta?.toVersionCode ?: -1
        val matchedPatch = delta?.matchedPatch
        val baseApkFile = baseApkPath?.takeIf { it.isNotBlank() }?.let { File(it) }

        if (delta != null &&
            matchedPatch != null &&
            matchedPatch.fromVersionCode == currentVersionCode &&
            delta.toVersionCode > currentVersionCode &&
            !matchedPatch.patchDownloadUrl.isNullOrBlank() &&
            baseApkFile != null &&
            baseApkFile.isFile
        ) {
            onStatus("Downloading patch...")
            val patchPartName = partFileNameFor(
                fileName = matchedPatch.patchAssetName,
                targetVersionCode = targetVersionCode,
                isPatch = true
            )
            val patchResult = downloadApk(
                downloadUrl = matchedPatch.patchDownloadUrl,
                fileName = matchedPatch.patchAssetName,
                cacheDir = cacheDir,
                partFileNameOverride = patchPartName,
                expectedTotalBytes = matchedPatch.patchSize,
                preservePartialOnCancel = true,
                onProgress = onProgress
            )
            if (patchResult.isSuccess) {
                val patchFile = patchResult.getOrThrow()
                val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
                val safeTargetName = sanitizeFileName(delta.fullAssetName)
                val targetFile = File(updatesDir, safeTargetName)
                val tmpTargetFile = File(updatesDir, "$safeTargetName.tmp")
                try {
                    val patched = withContext(Dispatchers.IO) {
                        onStatus("Verifying patch SHA-256...")
                        val actualPatchSha = BsPatch.sha256Hex(patchFile)
                        if (!actualPatchSha.equals(matchedPatch.patchSha256, ignoreCase = true)) {
                            throw IllegalStateException(
                                "Delta patch SHA-256 mismatch (expected ${matchedPatch.patchSha256}, got $actualPatchSha)"
                            )
                        }
                        onStatus("Applying delta...")
                        BsPatch.patch(
                            oldFile = baseApkFile,
                            patchFile = patchFile,
                            newFile = tmpTargetFile
                        )
                        onStatus("Verifying SHA-256...")
                        val actualSha = BsPatch.sha256Hex(tmpTargetFile)
                        if (!actualSha.equals(delta.targetApkSha256, ignoreCase = true)) {
                            throw IllegalStateException(
                                "Patched APK SHA-256 mismatch (expected ${delta.targetApkSha256}, got $actualSha)"
                            )
                        }
                        if (targetFile.exists()) {
                            targetFile.delete()
                        }
                        if (!tmpTargetFile.renameTo(targetFile)) {
                            tmpTargetFile.copyTo(targetFile, overwrite = true)
                            tmpTargetFile.delete()
                        }
                        targetFile
                    }
                    onStatus("Ready to install")
                    return Result.success(patched)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    runCatching { tmpTargetFile.delete() }
                    runCatching { targetFile.delete() }
                    cancelPartialDownload(
                        fileName = matchedPatch.patchAssetName,
                        cacheDir = cacheDir,
                        targetVersionCode = targetVersionCode,
                        isPatch = true
                    )
                    onStatus("Delta failed, downloading full APK...")
                } finally {
                    runCatching { patchFile.delete() }
                }
            } else {
                cancelPartialDownload(
                    fileName = matchedPatch.patchAssetName,
                    cacheDir = cacheDir,
                    targetVersionCode = targetVersionCode,
                    isPatch = true
                )
                onStatus("Delta download failed, downloading full APK...")
            }
        }

        val fullUrl = release.apkDownloadUrl
            ?: return Result.failure(IllegalStateException("No APK download URL available."))
        val fullFileName = release.apkFileName ?: "aether-android-arm64-v${release.versionName}.apk"
        val fullPartName = partFileNameFor(
            fileName = fullFileName,
            targetVersionCode = targetVersionCode,
            isPatch = false
        )
        onStatus("Downloading full APK...")
        val fullResult = downloadApk(
            downloadUrl = fullUrl,
            fileName = fullFileName,
            cacheDir = cacheDir,
            partFileNameOverride = fullPartName,
            expectedTotalBytes = delta?.fullSize ?: -1L,
            preservePartialOnCancel = true,
            onProgress = onProgress
        )
        return fullResult.fold(
            onSuccess = { apkFile ->
                val expectedSha = delta?.targetApkSha256
                if (!expectedSha.isNullOrBlank()) {
                    onStatus("Verifying SHA-256...")
                    val actualSha = withContext(Dispatchers.IO) { BsPatch.sha256Hex(apkFile) }
                    if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                        runCatching { apkFile.delete() }
                        cancelPartialDownload(
                            fileName = fullFileName,
                            cacheDir = cacheDir,
                            targetVersionCode = targetVersionCode,
                            isPatch = false
                        )
                        return Result.failure(
                            IllegalStateException(
                                "Full APK SHA-256 mismatch (expected $expectedSha, got $actualSha)"
                            )
                        )
                    }
                }
                onStatus("Ready to install")
                Result.success(apkFile)
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun downloadApk(
        downloadUrl: String,
        fileName: String,
        cacheDir: File,
        partFileNameOverride: String? = null,
        expectedTotalBytes: Long = -1L,
        preservePartialOnCancel: Boolean = false,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val safeName = sanitizeFileName(fileName)
        val targetFile = File(updatesDir, safeName)
        val partFile = File(updatesDir, partFileNameOverride ?: "$safeName.part")
        cleanupUpdatesDir(updatesDir, keepPartFileName = partFile.name)

        var completed = false
        var cancelled = false
        try {
            coroutineContext.ensureActive()
            val hasSingleStreamPart = partFile.isFile && partFile.length() > 0L && !chunksFileFor(partFile).exists()
            val skipProbe = hasSingleStreamPart || (expectedTotalBytes in 1L until PARALLEL_MIN_BYTES)
            val probe = if (skipProbe) {
                null
            } else {
                runCatching { probeDownloadTarget(downloadUrl, expectedTotalBytes) }.getOrNull()
            }
            coroutineContext.ensureActive()

            if (probe != null && probe.acceptRanges && probe.totalBytes >= PARALLEL_MIN_BYTES) {
                downloadParallelChunks(
                    resolvedUrl = probe.resolvedUrl,
                    totalBytes = probe.totalBytes,
                    partFile = partFile,
                    targetFile = targetFile,
                    preservePartialOnCancel = preservePartialOnCancel,
                    onProgress = onProgress
                )
            } else {
                downloadSingleStream(
                    downloadUrl = probe?.resolvedUrl ?: downloadUrl,
                    partFile = partFile,
                    targetFile = targetFile,
                    expectedTotalBytes = probe?.totalBytes?.takeIf { it > 0L } ?: expectedTotalBytes,
                    onProgress = onProgress
                )
            }
            completed = true
            Result.success(targetFile)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (!completed) {
                val keepName = if ((!cancelled || preservePartialOnCancel) && partFile.exists() && partFile.length() > 0L) {
                    partFile.name
                } else {
                    null
                }
                cleanupUpdatesDir(updatesDir, keepPartFileName = keepName)
            }
        }
    }

    private fun openRedirectFollowedProbe(
        initialUrl: String,
        method: String,
        rangeHeader: String? = null
    ): Pair<String, HttpURLConnection> {
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "AetherVpnAndroid")
                if (rangeHeader != null) {
                    setRequestProperty("Range", rangeHeader)
                }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect without Location header (HTTP $code)")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                continue
            }
            return currentUrl to conn
        }
        throw IllegalStateException("Too many redirects while probing update URL")
    }

    private fun probeDownloadTarget(initialUrl: String, expectedTotalBytes: Long): ProbeResult {
        var headPair: Pair<String, HttpURLConnection>? = null
        try {
            headPair = openRedirectFollowedProbe(initialUrl, "HEAD")
            val (resolvedUrl, conn) = headPair
            val code = conn.responseCode
            if (code in 200..299) {
                val len = conn.contentLengthLong.takeIf { it > 0L } ?: expectedTotalBytes
                val ranges = conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                if (len < 0L) {
                    conn.disconnect()
                    headPair = null
                    return probeWithRangeGet(resolvedUrl, expectedTotalBytes)
                }
                return ProbeResult(resolvedUrl, len, ranges)
            }
        } finally {
            headPair?.second?.disconnect()
        }
        return probeWithRangeGet(initialUrl, expectedTotalBytes)
    }

    private fun probeWithRangeGet(initialUrl: String, expectedTotalBytes: Long): ProbeResult {
        val (resolvedUrl, conn) = openRedirectFollowedProbe(initialUrl, "GET", rangeHeader = "bytes=0-0")
        return try {
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val total = parseTotalBytesFromContentRange(conn.getHeaderField("Content-Range"))
                        ?: expectedTotalBytes
                    ProbeResult(resolvedUrl, total, acceptRanges = true)
                }
                in 200..299 -> {
                    val len = conn.contentLengthLong.takeIf { it > 0L } ?: expectedTotalBytes
                    val ranges = conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                    ProbeResult(resolvedUrl, len, ranges)
                }
                else -> ProbeResult(resolvedUrl, expectedTotalBytes, acceptRanges = false)
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadParallelChunks(
        resolvedUrl: String,
        totalBytes: Long,
        partFile: File,
        targetFile: File,
        preservePartialOnCancel: Boolean,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val chunksFile = chunksFileFor(partFile)
        val chunks = loadChunkStates(chunksFile, partFile, totalBytes) ?: run {
            // If an unchunked .part file existed from a fallback/old run, replace it cleanly.
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.setLength(totalBytes)
            }
            buildInitialChunks(totalBytes, PARALLEL_CHUNK_COUNT)
        }

        val downloadedPerChunk = AtomicLongArray(PARALLEL_CHUNK_COUNT)
        val totalDownloaded = AtomicLong(0L)
        for (c in chunks) {
            downloadedPerChunk.set(c.index, c.downloaded)
            totalDownloaded.addAndGet(c.downloaded)
        }

        val metaLock = Any()
        val progressLock = Any()
        var lastReportedPct = -1
        var lastReportedBytes = totalDownloaded.get()
        var lastMetaSaveBytes = totalDownloaded.get()
        var lastMetaSaveNs = System.nanoTime()
        var lastSpeedSampleNs = System.nanoTime()
        var lastSpeedSampleBytes = totalDownloaded.get()
        var currentSpeedBps = 0L

        if (totalDownloaded.get() > 0L) {
            val initPct = ((totalDownloaded.get() * 100L) / totalBytes).toInt().coerceIn(0, 100)
            lastReportedPct = initPct
            onProgress(DownloadProgress(totalDownloaded.get(), totalBytes, initPct, 0L))
        } else {
            synchronized(metaLock) {
                saveChunkStates(chunksFile, totalBytes, resolvedUrl, chunks, downloadedPerChunk)
            }
        }

        val activeConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
        var completed = false
        var cancelled = false

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                activeConnections.toList().forEach { runCatching { it.disconnect() } }
            }
        }

        try {
            FileChannel.open(
                partFile.toPath(),
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            ).use { channel ->
                coroutineScope {
                    val workers = chunks.map { chunk ->
                        async(Dispatchers.IO) {
                            if (chunk.isComplete) return@async
                            val startOffset = chunk.start + downloadedPerChunk.get(chunk.index)
                            if (startOffset > chunk.end) return@async

                            var conn: HttpURLConnection? = null
                            try {
                                conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    instanceFollowRedirects = true
                                    connectTimeout = CONNECT_TIMEOUT_MS
                                    readTimeout = 30_000
                                    setRequestProperty("User-Agent", "AetherVpnAndroid")
                                    setRequestProperty("Range", "bytes=$startOffset-${chunk.end}")
                                }
                                activeConnections.add(conn)

                                coroutineContext.ensureActive()
                                val code = conn.responseCode
                                val validSingleChunk200 = code in 200..299 &&
                                    startOffset == 0L &&
                                    chunk.end == totalBytes - 1L
                                if (code != HttpURLConnection.HTTP_PARTIAL && !validSingleChunk200) {
                                    throw IllegalStateException("Chunk ${chunk.index} failed (HTTP $code)")
                                }

                                val buffer = ByteArray(CHUNK_BUFFER_SIZE)
                                var chunkPos = startOffset
                                conn.inputStream.use { input ->
                                    while (chunkPos <= chunk.end) {
                                        coroutineContext.ensureActive()
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        coroutineContext.ensureActive()

                                        val remaining = (chunk.end - chunkPos + 1L)
                                        val toWrite = minOf(read.toLong(), remaining).toInt()
                                        val byteBuf = ByteBuffer.wrap(buffer, 0, toWrite)
                                        var writePos = chunkPos
                                        while (byteBuf.hasRemaining()) {
                                            writePos += channel.write(byteBuf, writePos)
                                        }
                                        chunkPos += toWrite

                                        downloadedPerChunk.addAndGet(chunk.index, toWrite.toLong())
                                        val newTotal = totalDownloaded.addAndGet(toWrite.toLong())

                                        val nowNs = System.nanoTime()
                                        var progressToEmit: DownloadProgress? = null
                                        var shouldSaveMeta = false
                                        synchronized(progressLock) {
                                            val speedElapsedNs = nowNs - lastSpeedSampleNs
                                            if (speedElapsedNs >= 400_000_000L) {
                                                val deltaBytes = (newTotal - lastSpeedSampleBytes).coerceAtLeast(0L)
                                                currentSpeedBps = (deltaBytes * 1_000_000_000L) / speedElapsedNs
                                                lastSpeedSampleNs = nowNs
                                                lastSpeedSampleBytes = newTotal
                                            }
                                            val pct = ((newTotal * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                            if (pct != lastReportedPct || newTotal - lastReportedBytes >= 131_072L) {
                                                lastReportedPct = pct
                                                lastReportedBytes = newTotal
                                                progressToEmit = DownloadProgress(
                                                    downloadedBytes = newTotal,
                                                    totalBytes = totalBytes,
                                                    percent = pct,
                                                    speedBytesPerSec = currentSpeedBps
                                                )
                                            }
                                            if (newTotal - lastMetaSaveBytes >= 262_144L || nowNs - lastMetaSaveNs >= 500_000_000L) {
                                                lastMetaSaveBytes = newTotal
                                                lastMetaSaveNs = nowNs
                                                shouldSaveMeta = true
                                            }
                                        }
                                        if (shouldSaveMeta) {
                                            synchronized(metaLock) {
                                                saveChunkStates(chunksFile, totalBytes, resolvedUrl, chunks, downloadedPerChunk)
                                            }
                                        }
                                        progressToEmit?.let { onProgress(it) }
                                    }
                                }
                                if (chunkPos <= chunk.end) {
                                    throw IllegalStateException(
                                        "Chunk ${chunk.index} ended early at $chunkPos/${chunk.end}"
                                    )
                                }
                            } finally {
                                if (conn != null) {
                                    activeConnections.remove(conn)
                                    runCatching { conn.disconnect() }
                                }
                            }
                        }
                    }
                    workers.awaitAll()
                }
                channel.force(false)
            }

            coroutineContext.ensureActive()
            val finalTotal = totalDownloaded.get()
            if (finalTotal != totalBytes) {
                throw IllegalStateException("Incomplete parallel download: $finalTotal/$totalBytes bytes")
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            runCatching { chunksFile.delete() }
            completed = true
            onProgress(DownloadProgress(totalBytes, totalBytes, 100, currentSpeedBps))
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            cancelHandle?.dispose()
            activeConnections.toList().forEach { runCatching { it.disconnect() } }
            if (!completed && (!cancelled || preservePartialOnCancel)) {
                synchronized(metaLock) {
                    saveChunkStates(chunksFile, totalBytes, resolvedUrl, chunks, downloadedPerChunk)
                }
            }
        }
    }

    private suspend fun downloadSingleStream(
        downloadUrl: String,
        partFile: File,
        targetFile: File,
        expectedTotalBytes: Long,
        onProgress: (DownloadProgress) -> Unit
    ) {
        // If a stale .chunks.json exists for a single-stream fallback, remove it
        val chunksFile = chunksFileFor(partFile)
        if (chunksFile.exists()) {
            runCatching { chunksFile.delete() }
        }

        val existingBytes = if (partFile.exists() && partFile.isFile) partFile.length() else 0L
        var conn: HttpURLConnection? = null
        var cancelHandle: kotlinx.coroutines.DisposableHandle? = null
        try {
            conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AetherVpnAndroid")
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

            cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    runCatching { conn?.disconnect() }
                }
            }

            coroutineContext.ensureActive()
            val code = conn.responseCode
            val isPartial = code == HttpURLConnection.HTTP_PARTIAL
            if (!isPartial && code !in 200..299) {
                throw IllegalStateException("Download failed (HTTP $code)")
            }

            val appendMode = isPartial && existingBytes > 0L
            val contentLength = conn.contentLengthLong
            val totalBytes = expectedTotalBytes.takeIf { it > 0L } ?: if (appendMode) {
                parseTotalBytesFromContentRange(conn.getHeaderField("Content-Range"))
                    ?: if (contentLength > 0L) existingBytes + contentLength else -1L
            } else {
                contentLength
            }
            var downloadedBytes = if (appendMode) existingBytes else 0L
            var lastReportedPct = -1
            var lastReportedBytes = downloadedBytes
            var lastSpeedSampleNs = System.nanoTime()
            var lastSpeedSampleBytes = downloadedBytes
            var currentSpeedBps = 0L

            if (appendMode && totalBytes > 0L) {
                val initPct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                lastReportedPct = initPct
                onProgress(DownloadProgress(downloadedBytes, totalBytes, initPct, 0L))
            }

            conn.inputStream.use { input ->
                FileOutputStream(partFile, appendMode).use { output ->
                    val buffer = ByteArray(CHUNK_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val nowNs = System.nanoTime()
                        val speedElapsedNs = nowNs - lastSpeedSampleNs
                        if (speedElapsedNs >= 400_000_000L) {
                            val deltaBytes = (downloadedBytes - lastSpeedSampleBytes).coerceAtLeast(0L)
                            currentSpeedBps = (deltaBytes * 1_000_000_000L) / speedElapsedNs
                            lastSpeedSampleNs = nowNs
                            lastSpeedSampleBytes = downloadedBytes
                        }
                        val pct = if (totalBytes > 0L) {
                            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (pct != lastReportedPct || downloadedBytes - lastReportedBytes >= 131_072L) {
                            lastReportedPct = pct
                            lastReportedBytes = downloadedBytes
                            onProgress(DownloadProgress(downloadedBytes, totalBytes, pct, currentSpeedBps))
                        }
                    }
                    output.flush()
                }
            }
            coroutineContext.ensureActive()
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            onProgress(DownloadProgress(downloadedBytes, totalBytes.coerceAtLeast(downloadedBytes), 100, currentSpeedBps))
        } finally {
            cancelHandle?.dispose()
            runCatching { conn?.disconnect() }
        }
    }

    private fun parseTotalBytesFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slashIdx = header.lastIndexOf('/')
        if (slashIdx < 0 || slashIdx + 1 >= header.length) return null
        return header.substring(slashIdx + 1).trim().toLongOrNull()?.takeIf { it > 0L }
    }

    const val ACTION_INSTALL_STATUS = "io.aethervpn.android.ACTION_INSTALL_STATUS"
    private const val INSTALL_SESSION_REQUEST_CODE = 4201

    /**
     * Streams a verified APK file into an Android [PackageInstaller.Session] and commits it
     * with a status [PendingIntent] targeting [UpdateInstallReceiver], so the user only sees
     * the single mandatory system confirmation prompt.
     */
    suspend fun installApkWithPackageInstaller(
        context: Context,
        apkFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
            return@withContext Result.failure(IllegalArgumentException("APK file is missing or empty"))
        }

        val packageInstaller = context.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
            }
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            val apkBytes = apkFile.length()
            session.openWrite("aether-update.apk", 0L, apkBytes).use { out ->
                FileInputStream(apkFile).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
                session.fsync(out)
            }

            val statusIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val statusPendingIntent = PendingIntent.getBroadcast(
                context,
                INSTALL_SESSION_REQUEST_CODE,
                statusIntent,
                pendingFlags
            )

            session.commit(statusPendingIntent.intentSender)
            session.close()
            session = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            runCatching { session?.abandon() }
            runCatching { session?.close() }
            throw e
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            runCatching { session?.close() }
            Result.failure(e)
        }
    }
}
