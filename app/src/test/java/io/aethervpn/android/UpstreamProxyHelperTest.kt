package io.aethervpn.android

import io.aethervpn.android.core.StructuredUpstreamProxy
import io.aethervpn.android.core.UpstreamProxyPreset
import io.aethervpn.android.core.UpstreamProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamProxyHelperTest {

    @Test
    fun buildUrlAssemblesValidUrlsAndHonorsDisabledState() {
        val disabled = StructuredUpstreamProxy(
            enabled = false,
            protocol = UpstreamProxyProtocol.SOCKS5,
            host = "127.0.0.1",
            port = 10808
        )
        assertEquals("", disabled.buildUrl())

        val v2ray = StructuredUpstreamProxy(
            enabled = true,
            protocol = UpstreamProxyProtocol.SOCKS5,
            host = "127.0.0.1",
            port = 10808
        )
        assertEquals("socks5://127.0.0.1:10808", v2ray.buildUrl())
        assertEquals(UpstreamProxyPreset.V2RAYNG, v2ray.detectedPreset())

        val httpProxy = StructuredUpstreamProxy(
            enabled = true,
            protocol = UpstreamProxyProtocol.HTTP,
            host = "192.168.1.5",
            port = 8080
        )
        assertEquals("http://192.168.1.5:8080", httpProxy.buildUrl())
        assertEquals(UpstreamProxyPreset.CUSTOM, httpProxy.detectedPreset())
    }

    @Test
    fun parseRoundTripsSavedProxyUrlsAndDetectsPresets() {
        val blank = StructuredUpstreamProxy.parse("")
        assertFalse(blank.enabled)
        assertEquals("127.0.0.1", blank.host)
        assertEquals(10808, blank.port)

        val clash = StructuredUpstreamProxy.parse("socks5://127.0.0.1:7890")
        assertTrue(clash.enabled)
        assertEquals(UpstreamProxyProtocol.SOCKS5, clash.protocol)
        assertEquals("127.0.0.1", clash.host)
        assertEquals(7890, clash.port)
        assertEquals(UpstreamProxyPreset.SINGBOX_CLASH, clash.detectedPreset())

        val http = StructuredUpstreamProxy.parse("http://10.0.0.2:3128")
        assertTrue(http.enabled)
        assertEquals(UpstreamProxyProtocol.HTTP, http.protocol)
        assertEquals("10.0.0.2", http.host)
        assertEquals(3128, http.port)
    }
}
