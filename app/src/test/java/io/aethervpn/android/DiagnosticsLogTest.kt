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
}
