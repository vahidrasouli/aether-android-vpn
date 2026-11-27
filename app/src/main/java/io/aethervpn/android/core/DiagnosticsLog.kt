package io.aethervpn.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bounded, zero-disk, redacted in-memory diagnostic ring buffer (max 2000 entries).
 * Automatically scrubs tokens, credentials, and private keys before storing.
 * Collapses consecutive identical or same-pattern lines with a repeat counter badge `(xN)`
 * and rate-limits high-frequency UI flow emissions during error bursts.
 * Emits reactive updates via Kotlin StateFlow<List<String>> for live UI rendering.
 */
object DiagnosticsLog {
    internal const val MAX_ENTRIES = 2000
    private const val UI_BURST_EMIT_INTERVAL_MS = 250L

    private val lock = Any()
    private val ring = ArrayDeque<String>(MAX_ENTRIES)
    private val listeners = CopyOnWriteArrayList<(tag: String, message: String) -> Unit>()

    private var lastPattern: String? = null
    private var lastCleanMessage: String? = null
    private var lastTag: String? = null
    private var repeatCount: Int = 0
    private var lastUiEmitMs: Long = 0L

    @Volatile
    private var networkOffline: Boolean = false

    private val SUPPRESSED_ENGINE_TAGS = setOf(
        "Aether", "SingBox", "Psiphon", "Soar", "Tunnel", "HEV"
    )
    private val NETWORK_ERROR_KEYWORDS = listOf(
        "timeout", "timed out", "i/o timeout", "connection refused",
        "connection reset", "broken pipe", "network is unreachable",
        "no route to host", "eof", "socket", "dial", "unreachable",
        "failed to connect", "stream error", "dns"
    )

    private val secretPatterns = listOf(
        Regex("(?i)(private_key|secret|password|token|client_secret)\\s*[:=]\\s*[^\\s,;\"']+"),
        Regex("://[^:@\\s]+:[^@\\s]+@")
    )

    private val isoTimestampRegex =
        Regex("\\b\\d{4}[-/]\\d{2}[-/]\\d{2}[T\\s]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?\\b")
    private val timeOnlyRegex = Regex("\\b\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b")
    private val uuidRegex =
        Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    private val portSuffixRegex = Regex(":(\\d{2,5})\\b")
    private val portFieldRegex = Regex("(?i)\\bport\\s*[=:]?\\s*\\d+\\b")
    private val dynamicIdRegex =
        Regex("(?i)\\b(tunnel|conn|connection|session|stream|req|request|cid|id)(?:[_-]?id)?\\s*[=:#]\\s*[A-Za-z0-9_-]+")
    private val singBoxLevelPrefixRegex =
        Regex("(?i)\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\[\\d+\\]")
    private val singBoxConnDurationRegex =
        Regex("\\[\\d+\\s+\\d+(?:\\.\\d+)?(?:µs|us|ms|ns|s|m)\\]")
    private val durationRegex =
        Regex("\\b\\d+(?:\\.\\d+)?(?:µs|us|ms|ns|s)\\b")
    private val upstreamOpenFailureRegex =
        Regex("(?i)(open connection to )\\S+(.*?:\\s*dial (?:tcp|udp)\\s+[^:]+:(?:\\d+|<port>):\\s*(?:connect:\\s*connection refused|i/o timeout|connection reset by peer))")
    private val hexTokenRegex = Regex("\\b0x[0-9a-fA-F]+\\b")
    private val multiSpaceRegex = Regex("\\s+")

    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    fun addListener(listener: (tag: String, message: String) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (tag: String, message: String) -> Unit) {
        listeners.remove(listener)
    }

    fun onNetworkLost() {
        val shouldLog = synchronized(lock) {
            if (networkOffline) {
                false
            } else {
                networkOffline = true
                true
            }
        }
        if (shouldLog) {
            record("Network", "Internet connection lost — waiting for network…")
        }
    }

    fun onNetworkRestored() {
        val shouldLog = synchronized(lock) {
            if (!networkOffline) {
                false
            } else {
                networkOffline = false
                true
            }
        }
        if (shouldLog) {
            record("Network", "Internet restored")
        }
    }

    fun resetNetworkState() {
        synchronized(lock) {
            networkOffline = false
        }
    }

    internal fun isNetworkOffline(): Boolean = networkOffline

    internal fun shouldSuppressDuringNetworkDrop(tag: String, cleanMessage: String): Boolean {
        if (!networkOffline) return false
        if (tag !in SUPPRESSED_ENGINE_TAGS) return false
        val lower = cleanMessage.lowercase(Locale.US)
        return NETWORK_ERROR_KEYWORDS.any { lower.contains(it) }
    }

    internal fun normalizePattern(tag: String, cleanMessage: String): String {
        var norm = cleanMessage
        norm = isoTimestampRegex.replace(norm, "<ts>")
        norm = timeOnlyRegex.replace(norm, "<ts>")
        norm = uuidRegex.replace(norm, "<uuid>")
        norm = singBoxLevelPrefixRegex.replace(norm) { "[${it.groupValues[1].lowercase(Locale.US)}]" }
        norm = singBoxConnDurationRegex.replace(norm, "<conn>")
        norm = durationRegex.replace(norm, "<dur>")
        norm = upstreamOpenFailureRegex.replace(norm) { "${it.groupValues[1]}<dst>${it.groupValues[2]}" }
        norm = dynamicIdRegex.replace(norm) { "${it.groupValues[1].lowercase(Locale.US)}=<id>" }
        norm = portSuffixRegex.replace(norm, ":<port>")
        norm = portFieldRegex.replace(norm, "port=<port>")
        norm = hexTokenRegex.replace(norm, "<hex>")
        norm = multiSpaceRegex.replace(norm, " ").trim()
        return "$tag|$norm"
    }

    fun record(tag: String, message: String) {
        recordInternal(tag, message, System.currentTimeMillis())
    }

    internal fun recordInternal(tag: String, message: String, nowMs: Long) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nowMs))
        var clean = message
        secretPatterns.forEach { rx ->
            clean = rx.replace(clean) { m ->
                if (m.value.startsWith("://")) "://***:***@" else "[REDACTED]"
            }
        }

        val pattern = normalizePattern(tag, clean)
        var shouldNotifyListeners = true

        synchronized(lock) {
            if (shouldSuppressDuringNetworkDrop(tag, clean)) {
                return
            }
            if (ring.isNotEmpty() && lastPattern == pattern && lastTag == tag) {
                repeatCount++
                val baseMsg = lastCleanMessage ?: clean
                val updatedLine = "$ts [$tag] $baseMsg (x$repeatCount)"
                ring[ring.lastIndex] = updatedLine
                // Rate-limit high-frequency burst emissions to keep UI CPU low,
                // while always emitting the initial collapse (x2) immediately.
                if (repeatCount <= 2 || nowMs - lastUiEmitMs >= UI_BURST_EMIT_INTERVAL_MS) {
                    _logsFlow.value = ring.toList()
                    lastUiEmitMs = nowMs
                }
                shouldNotifyListeners = repeatCount <= 5 || repeatCount % 5 == 0
            } else {
                lastPattern = pattern
                lastCleanMessage = clean
                lastTag = tag
                repeatCount = 1
                val line = "$ts [$tag] $clean"
                if (ring.size >= MAX_ENTRIES) ring.removeFirst()
                ring.addLast(line)
                _logsFlow.value = ring.toList()
                lastUiEmitMs = nowMs
            }
        }

        if (shouldNotifyListeners) {
            for (listener in listeners) {
                runCatching { listener(tag, clean) }
            }
        }
    }

    fun entries(): List<String> = synchronized(lock) {
        ring.toList()
    }

    fun snapshot(): String = synchronized(lock) {
        if (ring.isEmpty()) "No diagnostic events yet." else ring.joinToString("\n")
    }

    fun clear() = synchronized(lock) {
        ring.clear()
        lastPattern = null
        lastCleanMessage = null
        lastTag = null
        repeatCount = 0
        lastUiEmitMs = 0L
        networkOffline = false
        _logsFlow.value = emptyList()
    }

    /**
     * Trims the oldest log entries under system memory pressure (onTrimMemory)
     * so that the VPN service memory footprint stays minimal.
     */
    fun trim(keepCount: Int = 30) = synchronized(lock) {
        if (ring.size > keepCount) {
            while (ring.size > keepCount) {
                ring.removeFirst()
            }
            if (ring.isEmpty()) {
                lastPattern = null
                lastCleanMessage = null
                lastTag = null
                repeatCount = 0
            }
            _logsFlow.value = ring.toList()
        }
    }
}
