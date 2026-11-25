package io.aethervpn.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    suspend fun checkForUpdates(
        currentVersion: String,
        apiUrl: String = GITHUB_LATEST_RELEASE_API
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
            parseReleaseResponse(bodyText, currentVersion)
        } catch (e: Exception) {
            UpdateCheckResult.Unavailable(
                "Unable to check for updates: ${e.message ?: "network error"}"
            )
        } finally {
            conn?.disconnect()
        }
    }

    internal fun parseReleaseResponse(jsonBody: String, currentVersion: String): UpdateCheckResult {
        val json = runCatching { JSONObject(jsonBody) }.getOrNull()
            ?: return UpdateCheckResult.Unavailable("Invalid release response from GitHub.")

        val tagName = json.optString("tag_name", "").trim()
        if (tagName.isEmpty()) {
            return UpdateCheckResult.Unavailable("Latest release has no version tag.")
        }

        val parsedRemote = SemVer.parse(tagName)
            ?: return UpdateCheckResult.Unavailable("Unrecognized release tag: $tagName")

        if (!SemVer.isNewerVersion(tagName, currentVersion)) {
            return UpdateCheckResult.UpToDate(currentVersion)
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
                    if (name.contains("arm64", ignoreCase = true) && !name.contains("debug", ignoreCase = true)) {
                        break
                    }
                }
            }
        }

        return UpdateCheckResult.UpdateAvailable(
            ReleaseInfo(
                tagName = tagName,
                versionName = parsedRemote.toString(),
                releaseNotes = releaseNotes,
                apkDownloadUrl = apkUrl,
                apkFileName = apkName,
                htmlUrl = htmlUrl
            )
        )
    }

    suspend fun downloadApk(
        downloadUrl: String,
        fileName: String,
        cacheDir: File,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && old.name.endsWith(".apk", ignoreCase = true)) {
                    runCatching { old.delete() }
                }
            }

            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val targetFile = File(updatesDir, safeName)

            conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AetherVpnAndroid")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("Download failed (HTTP $code)")
                )
            }

            val totalBytes = conn.contentLengthLong
            var downloadedBytes = 0L
            var lastReportedPct = -1
            var lastReportedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
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
            onProgress(DownloadProgress(downloadedBytes, totalBytes.coerceAtLeast(downloadedBytes), 100))
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}
