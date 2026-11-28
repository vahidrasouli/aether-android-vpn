package io.aethervpn.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

sealed interface CiBuildState {
    data class InProgress(
        val runId: Long,
        val status: String,
        val title: String,
        val startedAtMs: Long,
        val htmlUrl: String?
    ) : CiBuildState

    data class Succeeded(
        val runId: Long,
        val title: String,
        val htmlUrl: String?
    ) : CiBuildState

    data class Failed(
        val runId: Long,
        val conclusion: String,
        val title: String,
        val htmlUrl: String?
    ) : CiBuildState
}

data class CiPollResult(
    val state: CiBuildState?,
    val etag: String?,
    val notModified: Boolean = false
)

/**
 * Lightweight, ETag-conditional GitHub Actions workflow run poller for the Update screen.
 * Sends `If-None-Match` so 304 Not Modified responses do not consume unauthenticated rate limits.
 */
object GitHubActionsStatusChecker {

    const val GITHUB_ACTIONS_RUNS_API =
        "https://api.github.com/repos/vahidrasouli/aether-android-vpn/actions/runs?per_page=1"
    const val POLL_INTERVAL_ACTIVE_MS = 12_000L
    private const val TIMEOUT_MS = 10_000

    suspend fun fetchLatestRunStatus(
        previousEtag: String?,
        apiUrl: String = GITHUB_ACTIONS_RUNS_API
    ): Result<CiPollResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "AetherVpnAndroid")
                    if (!previousEtag.isNullOrBlank()) {
                        setRequestProperty("If-None-Match", previousEtag)
                    }
                }
                try {
                    val code = conn.responseCode
                    val newEtag = conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() } ?: previousEtag
                    when (code) {
                        HttpURLConnection.HTTP_NOT_MODIFIED -> {
                            CiPollResult(state = null, etag = newEtag, notModified = true)
                        }
                        in 200..299 -> {
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            val parsed = parseWorkflowRunsJson(body)
                            CiPollResult(state = parsed, etag = newEtag, notModified = false)
                        }
                        else -> {
                            throw IllegalStateException("GitHub Actions API HTTP $code")
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }

    fun parseWorkflowRunsJson(jsonText: String): CiBuildState? {
        val root = JSONObject(jsonText)
        val runs = root.optJSONArray("workflow_runs") ?: return null
        if (runs.length() == 0) return null
        val run = runs.optJSONObject(0) ?: return null

        val runId = run.optLong("id", 0L)
        val status = run.optString("status", "").trim().lowercase()
        val conclusion = run.optString("conclusion", "").trim().lowercase()
        val rawTitle = run.optString("display_title", "").trim()
            .ifBlank { run.optString("name", "Build").trim() }
        val shortTitle = rawTitle.lineSequence().firstOrNull().orEmpty().take(48)
        val htmlUrl = run.optString("html_url", "").takeIf { it.isNotBlank() }

        return when (status) {
            "queued", "in_progress", "waiting", "pending", "requested" -> {
                val startedIso = run.optString("run_started_at", "")
                    .ifBlank { run.optString("created_at", "") }
                val startedMs = parseIsoTimestampMs(startedIso) ?: System.currentTimeMillis()
                CiBuildState.InProgress(
                    runId = runId,
                    status = status,
                    title = shortTitle,
                    startedAtMs = startedMs,
                    htmlUrl = htmlUrl
                )
            }
            "completed" -> {
                if (conclusion == "success") {
                    CiBuildState.Succeeded(
                        runId = runId,
                        title = shortTitle,
                        htmlUrl = htmlUrl
                    )
                } else {
                    CiBuildState.Failed(
                        runId = runId,
                        conclusion = conclusion.ifBlank { "failed" },
                        title = shortTitle,
                        htmlUrl = htmlUrl
                    )
                }
            }
            else -> null
        }
    }

    fun parseIsoTimestampMs(iso: String): Long? {
        if (iso.isBlank()) return null
        return runCatching { Instant.parse(iso.trim()).toEpochMilli() }.getOrNull()
    }

    fun formatElapsed(elapsedMs: Long): String {
        val totalSec = (elapsedMs / 1000L).coerceAtLeast(0L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return if (mins > 0L) "${mins}m ${secs}s" else "${secs}s"
    }
}
