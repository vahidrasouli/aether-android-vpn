package io.aethervpn.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded, zero-disk, redacted in-memory diagnostic ring buffer (max 120 entries).
 * Automatically scrubs tokens, credentials, and private keys before storing.
 * Emits reactive updates via Kotlin StateFlow<List<String>> for live UI rendering.
 */
object DiagnosticsLog {
    private const val MAX_ENTRIES = 120
    private val lock = Any()
    private val ring = ArrayDeque<String>(MAX_ENTRIES)
    private val secretPatterns = listOf(
        Regex("(?i)(private_key|secret|password|token|client_secret)\\s*[:=]\\s*[^\\s,;\"']+"),
        Regex("://[^:@\\s]+:[^@\\s]+@")
    )

    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    fun record(tag: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        var clean = message
        secretPatterns.forEach { rx ->
            clean = rx.replace(clean) { m ->
                if (m.value.startsWith("://")) "://***:***@" else "[REDACTED]"
            }
        }
        val line = "$ts [$tag] $clean"
        synchronized(lock) {
            if (ring.size >= MAX_ENTRIES) ring.removeFirst()
            ring.addLast(line)
            _logsFlow.value = ring.toList()
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
        _logsFlow.value = emptyList()
    }
}
