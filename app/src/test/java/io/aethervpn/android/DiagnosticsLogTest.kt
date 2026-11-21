package io.aethervpn.android

import io.aethervpn.android.core.DiagnosticsLog
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
