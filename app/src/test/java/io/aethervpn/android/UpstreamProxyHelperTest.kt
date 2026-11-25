package io.aethervpn.android

import io.aethervpn.android.core.ProxyTestResult
import io.aethervpn.android.core.StructuredUpstreamProxy
import io.aethervpn.android.core.UpstreamProxyPreset
import io.aethervpn.android.core.UpstreamProxyProtocol
import io.aethervpn.android.core.UpstreamProxyTester
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

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

    @Test
    fun parseCloudflareTraceExtractsIpLocColoAndWarp() {
        val parsed = UpstreamProxyTester.parseCloudflareTrace(
            "fl=123f\nh=www.cloudflare.com\nip=198.51.100.42\nloc=DE\ncolo=FRA\nwarp=off\n"
        )
        assertNotNull(parsed)
        assertEquals("198.51.100.42", parsed!!.ip)
        assertEquals("DE", parsed.loc)
        assertEquals("FRA", parsed.colo)
        assertEquals("off", parsed.warp)

        val summary = ProxyTestResult.Success(
            rttMs = 280L,
            publicIp = parsed.ip,
            loc = parsed.loc,
            colo = parsed.colo,
            warpStatus = parsed.warp,
            masqueEdgeOk = true,
            masqueEdgeDetail = "OK"
        ).formatSummary()
        assertEquals("OK • IP: 198.51.100.42 (DE/FRA) • 280ms • WARP: off • MASQUE Edge: OK", summary)
    }

    @Test
    fun threeStageProxyTestVerifiesHandshakeTraceAndMasqueEdge() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            try {
                server.soTimeout = 4_000
                // Connection 1: Stage 1 local handshake
                server.accept().use { s ->
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    inp.read(ByteArray(3))
                    out.write(byteArrayOf(0x05, 0x00))
                    out.flush()
                }
                // Connection 2: Stage 2 Cloudflare trace
                server.accept().use { s ->
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    inp.read(ByteArray(3))
                    out.write(byteArrayOf(0x05, 0x00))
                    out.flush()
                    val hdr = ByteArray(4)
                    inp.read(hdr)
                    val len = inp.read()
                    inp.read(ByteArray(len + 2))
                    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 1, 0xBB.toByte()))
                    out.flush()
                    val reader = BufferedReader(InputStreamReader(inp, Charsets.US_ASCII))
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        line = reader.readLine()
                    }
                    val resp = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nip=203.0.113.9\nloc=NL\ncolo=AMS\nwarp=off\n"
                    out.write(resp.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                // Connection 3: Stage 3 MASQUE Edge reachability (162.159.198.1:443)
                server.accept().use { s ->
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    inp.read(ByteArray(3))
                    out.write(byteArrayOf(0x05, 0x00))
                    out.flush()
                    val hdr = ByteArray(4)
                    inp.read(hdr)
                    inp.read(ByteArray(4 + 2))
                    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 1, 0xBB.toByte()))
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { server.close() }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val result = UpstreamProxyTester.testInternal(
            protocol = UpstreamProxyProtocol.SOCKS5,
            host = "127.0.0.1",
            port = port,
            timeoutMs = 3_000,
            tlsUpgrade = { raw, _, _, _, _ -> raw }
        )
        serverThread.join(3_000)

        assertTrue("Expected Success, got $result", result is ProxyTestResult.Success)
        val ok = result as ProxyTestResult.Success
        assertEquals("203.0.113.9", ok.publicIp)
        assertEquals("NL", ok.loc)
        assertEquals("AMS", ok.colo)
        assertEquals("off", ok.warpStatus)
        assertTrue(ok.masqueEdgeOk)
        assertEquals("OK", ok.masqueEdgeDetail)
    }
}
