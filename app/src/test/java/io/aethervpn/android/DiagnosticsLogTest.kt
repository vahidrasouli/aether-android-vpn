package io.aethervpn.android

import io.aethervpn.android.core.AetherNative
import io.aethervpn.android.core.DiagnosticsLog
import io.aethervpn.android.core.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsLogTest {

    @Before
    fun setUp() {
        DiagnosticsLog.clear()
    }

    @Test
    fun testRecordAndSnapshotRedactsSensitiveData() {
        DiagnosticsLog.record("System", "Connecting with token=secret123 and private_key: mysecretkey")
        DiagnosticsLog.record("Tunnel", "Proxy socks5://user:pass123@1.2.3.4:1080 configured")

        val snapshot = DiagnosticsLog.snapshot()
        val entries = DiagnosticsLog.entries()

        assertEquals(2, entries.size)
        assertTrue("Tag must be [System]", entries[0].contains("[System]"))
        assertTrue("Tag must be [Tunnel]", entries[1].contains("[Tunnel]"))
        assertFalse("Raw token must not appear in snapshot", snapshot.contains("secret123"))
        assertFalse("Raw private key must not appear in snapshot", snapshot.contains("mysecretkey"))
        assertFalse("Raw proxy credentials must not appear in snapshot", snapshot.contains("pass123"))

        assertTrue("Redacted tag must be present", snapshot.contains("[REDACTED]"))
        assertTrue("Proxy credential redaction must be present", snapshot.contains("://***:***@"))
    }

    @Test
    fun testAetherNativeLogBridgeAndRedaction() {
        AetherNative.onAetherLog(3, "Connected to WARP gateway 162.159.198.1:443 with token=token_abc_123")
        AetherNative.onAetherLog(1, "TLS handshake failed: private_key=0123456789abcdef")

        val snapshot = DiagnosticsLog.snapshot()
        val entries = DiagnosticsLog.entries()

        assertEquals(2, entries.size)
        assertTrue("Tag must be [Aether]", entries[0].contains("[Aether]"))
        assertTrue("Tag must be [Aether]", entries[1].contains("[Aether]"))

        assertFalse("Secret token must be scrubbed", snapshot.contains("token_abc_123"))
        assertFalse("Secret key must be scrubbed", snapshot.contains("0123456789abcdef"))
        assertTrue("Redaction tag must be present", snapshot.contains("[REDACTED]"))
    }

    @Test
    fun testPsiphonLogRouting() {
        AetherNative.onAetherLog(3, "[+] psiphon reached a server at 198.51.100.1")
        AetherNative.onAetherLog(2, "[-] psiphon: notice warning")

        val entries = DiagnosticsLog.entries()
        assertEquals(2, entries.size)
        assertTrue("Tag must be [Psiphon]", entries[0].contains("[Psiphon]"))
        assertTrue("Tag must be [Psiphon]", entries[1].contains("[Psiphon]"))
    }

    @Test
    fun testLoopbackSocketAddressNullSafety() {
        val endpoint = VpnConfig.loopbackSocketAddress(1819)
        val addr = endpoint.address
        org.junit.Assert.assertNotNull("InetAddress must not be null", addr)
        assertFalse("Endpoint must not be unresolved", endpoint.isUnresolved)
        assertEquals("Host string must be 127.0.0.1", "127.0.0.1", endpoint.hostString)
        assertEquals("127.0.0.1", addr.hostAddress)
        assertTrue("Address toString must contain 127.0.0.1", addr.toString().contains("127.0.0.1"))
        assertEquals(1819, endpoint.port)
    }

    @Test
    fun testRingBufferCapAndClear() {
        for (i in 1..150) {
            DiagnosticsLog.record("Test", "Event number $i")
        }

        val entries = DiagnosticsLog.entries()
        assertEquals("Ring buffer must be capped at 120 entries", 120, entries.size)
        assertTrue("Oldest events must have been evicted", entries.first().contains("Event number 31"))
        assertTrue("Latest event must be present", entries.last().contains("Event number 150"))

        DiagnosticsLog.clear()
        assertEquals("No diagnostic events yet.", DiagnosticsLog.snapshot())
        assertTrue(DiagnosticsLog.entries().isEmpty())
    }

    @Test
    fun testReactiveLogsFlowEmissions() {
        assertEquals(0, DiagnosticsLog.logsFlow.value.size)

        DiagnosticsLog.record("FlowTest", "Event 1")
        assertEquals(1, DiagnosticsLog.logsFlow.value.size)
        assertTrue(DiagnosticsLog.logsFlow.value[0].contains("Event 1"))

        DiagnosticsLog.record("FlowTest", "Event 2")
        assertEquals(2, DiagnosticsLog.logsFlow.value.size)

        DiagnosticsLog.clear()
        assertEquals(0, DiagnosticsLog.logsFlow.value.size)
    }

    @Test
    fun testRingBufferTrimUnderMemoryPressure() {
        for (i in 1..100) {
            DiagnosticsLog.record("PressureTest", "Event $i")
        }
        assertEquals(100, DiagnosticsLog.entries().size)

        DiagnosticsLog.trim(keepCount = 25)
        val trimmed = DiagnosticsLog.entries()
        assertEquals(25, trimmed.size)
        assertTrue(trimmed.first().contains("Event 76"))
        assertTrue(trimmed.last().contains("Event 100"))
        assertEquals(25, DiagnosticsLog.logsFlow.value.size)
    }

    @Test
    fun testLogFormatterExcludesBenignPsiphonMigrationAndProbeEofFromErrorStyling() {
        val boltdbNotice = "psiphon.boltdb old path does not exist; skipping migration"
        val probeEof = "SOCKS proxy accept error: socks5ReadCommand: EOF"
        val realError = "Aether engine exited: handshake failed"
        val readyMsg = "Connected via MASQUE | IP=1.2.3.4"

        assertEquals(
            io.aethervpn.android.ui.LogFormatter.LogSeverity.NORMAL,
            io.aethervpn.android.ui.LogFormatter.classifySeverity(boltdbNotice)
        )
        assertEquals(
            io.aethervpn.android.ui.LogFormatter.LogSeverity.NORMAL,
            io.aethervpn.android.ui.LogFormatter.classifySeverity(probeEof)
        )
        assertEquals(
            io.aethervpn.android.ui.LogFormatter.LogSeverity.ERROR,
            io.aethervpn.android.ui.LogFormatter.classifySeverity(realError)
        )
        assertEquals(
            io.aethervpn.android.ui.LogFormatter.LogSeverity.SUCCESS,
            io.aethervpn.android.ui.LogFormatter.classifySeverity(readyMsg)
        )
    }

    @Test
    fun testConsecutiveDuplicateAndDynamicPatternCollapsing() {
        DiagnosticsLog.record("Aether", "2026-11-26T03:10:00Z connect tcp 127.0.0.1:54321 tunnel=abc-123: connection refused")
        DiagnosticsLog.record("Aether", "2026-11-26T03:10:01Z connect tcp 127.0.0.1:54322 tunnel=def-456: connection refused")
        DiagnosticsLog.record("Aether", "2026-11-26T03:10:02Z connect tcp 127.0.0.1:54323 tunnel=ghi-789: connection refused")

        val entries = DiagnosticsLog.entries()
        assertEquals("Same-pattern consecutive errors must collapse into 1 entry", 1, entries.size)
        assertTrue("Collapsed entry must show (x3) badge: ${entries[0]}", entries[0].endsWith("(x3)"))

        // Different message breaks the streak
        DiagnosticsLog.record("System", "Upstream proxy 127.0.0.1:10808 appears stopped or unreachable")
        DiagnosticsLog.record("Aether", "2026-11-26T03:10:05Z connect tcp 127.0.0.1:54324 tunnel=jkl-000: connection refused")
        assertEquals(3, DiagnosticsLog.entries().size)
    }

    @Test
    fun testHighFrequencyBurstRateLimitsLogsFlowEmissions() {
        // Initial line + x2 emit immediately; burst x3..x14 within 250ms should not spam logsFlow
        DiagnosticsLog.recordInternal("SingBox", "socks5 dial error: connection refused", 1_000L)
        DiagnosticsLog.recordInternal("SingBox", "socks5 dial error: connection refused", 1_010L)
        assertTrue(DiagnosticsLog.logsFlow.value.last().endsWith("(x2)"))

        for (i in 3..14) {
            DiagnosticsLog.recordInternal("SingBox", "socks5 dial error: connection refused", 1_010L + i)
        }
        assertEquals(1, DiagnosticsLog.entries().size)
        assertTrue("Ring buffer must reflect full count (x14)", DiagnosticsLog.entries()[0].endsWith("(x14)"))
        assertTrue("Burst within 250ms must rate-limit logsFlow to (x2)", DiagnosticsLog.logsFlow.value[0].endsWith("(x2)"))

        // After 250ms window elapses, next duplicate flushes updated count to logsFlow
        DiagnosticsLog.recordInternal("SingBox", "socks5 dial error: connection refused", 1_300L)
        assertTrue(DiagnosticsLog.logsFlow.value[0].endsWith("(x15)"))
    }

    @Test
    fun testUpstreamProxyWatchdogEmitsSingleWarningWhenPortUnreachable() = kotlinx.coroutines.runBlocking {
        val warnings = mutableListOf<String>()
        val watchdog = io.aethervpn.android.vpn.UpstreamProxyWatchdog(
            proxyHost = "127.0.0.1",
            proxyPort = 10808,
            scope = this,
            portProbe = { _, _, _ -> false },
            onUnreachable = { host, port -> warnings += "Upstream proxy $host:$port appears stopped or unreachable" }
        )

        repeat(6) {
            watchdog.onEngineLog("Aether", "connect tcp 127.0.0.1:10808: connection refused")
            kotlinx.coroutines.delay(10)
        }

        assertEquals("Watchdog must emit exactly one warning per outage", 1, warnings.size)
        assertEquals("Upstream proxy 127.0.0.1:10808 appears stopped or unreachable", warnings[0])
    }
}
