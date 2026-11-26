package io.aethervpn.android.core

import io.aethervpn.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val htmlUrl: String
)

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int
) {
    fun formatLabel(): String {
        val dlMb = downloadedBytes / (1024.0 * 1024.0)
        return if (totalBytes > 0L) {
            val totMb = totalBytes / (1024.0 * 1024.0)
            String.format(java.util.Locale.US, "%d%% • %.1f / %.1f MB", percent, dlMb, totMb)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", dlMb)
        }
    }
}

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class Unavailable(val message: String) : UpdateCheckResult
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
        apiUrl: String = apiUrlForChannel(isDebugBuild)
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
            parseReleaseResponse(bodyText, currentVersion, isDebugBuild)
        } catch (e: Exception) {
            UpdateCheckResult.Unavailable(
                "Unable to check for updates: ${e.message ?: "network error"}"
            )
        } finally {
            conn?.disconnect()
        }
    }

    internal fun parseReleaseResponse(
        jsonBody: String,
        currentVersion: String,
        isDebugBuild: Boolean = false
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
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    apkUrl = url
                    apkName = name
                    val isArm64 = name.contains("arm64", ignoreCase = true)
                    val isDebugAsset = name.contains("debug", ignoreCase = true)
                    if (isArm64 && isDebugAsset == isDebugBuild) {
                        break
                    }
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

        return UpdateCheckResult.UpdateAvailable(
            ReleaseInfo(
                tagName = tagName,
                versionName = resolvedVersionName,
                releaseNotes = releaseNotes,
                apkDownloadUrl = apkUrl,
                apkFileName = apkName,
                htmlUrl = htmlUrl
            )
        )
    }

    internal fun sanitizeFileName(fileName: String): String =
        fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun getPartialDownloadSize(fileName: String, cacheDir: File): Long {
        val updatesDir = File(cacheDir, "updates")
        val partFile = File(updatesDir, "${sanitizeFileName(fileName)}.part")
        return if (partFile.exists() && partFile.isFile) partFile.length() else 0L
    }

    fun cancelPartialDownload(fileName: String, cacheDir: File) {
        val updatesDir = File(cacheDir, "updates")
        val partFile = File(updatesDir, "${sanitizeFileName(fileName)}.part")
        if (partFile.exists()) {
            runCatching { partFile.delete() }
        }
    }

    fun cleanupInstalledApks(cacheDir: File) {
        val updatesDir = File(cacheDir, "updates")
        if (!updatesDir.exists() || !updatesDir.isDirectory) return
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".apk", ignoreCase = true) ||
                    file.name.endsWith(".tmp", ignoreCase = true))
            ) {
                runCatching { file.delete() }
            }
        }
    }

    internal fun cleanupUpdatesDir(updatesDir: File, keepPartFileName: String? = null) {
        updatesDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val isSamePart = keepPartFileName != null &&
                file.name == keepPartFileName &&
                file.length() > 0L
            if (isSamePart) return@forEach
            if (file.name.endsWith(".apk", ignoreCase = true) ||
                file.name.endsWith(".tmp", ignoreCase = true) ||
                file.name.endsWith(".part", ignoreCase = true)
            ) {
                runCatching { file.delete() }
            }
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        fileName: String,
        cacheDir: File,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val safeName = sanitizeFileName(fileName)
        val targetFile = File(updatesDir, safeName)
        val partFile = File(updatesDir, "$safeName.part")
        cleanupUpdatesDir(updatesDir, keepPartFileName = partFile.name)

        val existingBytes = if (partFile.exists() && partFile.isFile) partFile.length() else 0L
        var conn: HttpURLConnection? = null
        var cancelHandle: kotlinx.coroutines.DisposableHandle? = null
        var completed = false
        var cancelled = false
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
                return@withContext Result.failure(
                    IllegalStateException("Download failed (HTTP $code)")
                )
            }

            val appendMode = isPartial && existingBytes > 0L
            val contentLength = conn.contentLengthLong
            val totalBytes = if (appendMode) {
                parseTotalBytesFromContentRange(conn.getHeaderField("Content-Range"))
                    ?: if (contentLength > 0L) existingBytes + contentLength else -1L
            } else {
                contentLength
            }
            var downloadedBytes = if (appendMode) existingBytes else 0L
            var lastReportedPct = -1
            var lastReportedBytes = downloadedBytes

            if (appendMode && totalBytes > 0L) {
                val initPct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                lastReportedPct = initPct
                onProgress(DownloadProgress(downloadedBytes, totalBytes, initPct))
            }

            conn.inputStream.use { input ->
                FileOutputStream(partFile, appendMode).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val pct = if (totalBytes > 0L) {
                            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (pct != lastReportedPct || downloadedBytes - lastReportedBytes >= 131_072L) {
                            lastReportedPct = pct
                            lastReportedBytes = downloadedBytes
                            onProgress(DownloadProgress(downloadedBytes, totalBytes, pct))
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
            completed = true
            onProgress(DownloadProgress(downloadedBytes, totalBytes.coerceAtLeast(downloadedBytes), 100))
            Result.success(targetFile)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            cancelHandle?.dispose()
            runCatching { conn?.disconnect() }
            if (!completed) {
                val keepName = if (!cancelled && partFile.exists() && partFile.length() > 0L) {
                    partFile.name
                } else {
                    null
                }
                cleanupUpdatesDir(updatesDir, keepPartFileName = keepName)
            }
        }
    }

    private fun parseTotalBytesFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slashIdx = header.lastIndexOf('/')
        if (slashIdx < 0 || slashIdx + 1 >= header.length) return null
        return header.substring(slashIdx + 1).trim().toLongOrNull()?.takeIf { it > 0L }
    }
}
