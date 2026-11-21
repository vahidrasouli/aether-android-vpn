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
        DiagnosticsLog.record("VPN", "Connecting with token=secret123 and private_key: mysecretkey")
        DiagnosticsLog.record("Tunnel", "Proxy socks5://user:pass123@1.2.3.4:1080 configured")

        val snapshot = DiagnosticsLog.snapshot()
        val entries = DiagnosticsLog.entries()

        assertEquals(2, entries.size)
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
    fun testLoopbackSocketAddressNullSafety() {
        val endpoint = VpnConfig.loopbackSocketAddress(1819)
        val addr = endpoint.address
        org.junit.Assert.assertNotNull("InetAddress must not be null", addr)
        assertFalse("Endpoint must not be unresolved", endpoint.isUnresolved)
        assertEquals("Host string must be 127.0.0.1", "127.0.0.1", endpoint.hostString)
        assertEquals("Address toString must be /127.0.0.1 or 127.0.0.1", "/127.0.0.1", addr.toString())
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
}
