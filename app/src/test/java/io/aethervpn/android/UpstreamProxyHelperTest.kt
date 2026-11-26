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
            masqueEdgeDetail = "OK",
            s3Reachable = true,
            s3Detail = "OK"
        ).formatSummary()
        assertEquals(
            "OK • IP: 198.51.100.42 (DE/FRA) • 280ms • WARP: off • MASQUE Edge: OK • Psiphon(S3): OK",
            summary
        )

        val s3FailedSummary = ProxyTestResult.Success(
            rttMs = 240L,
            publicIp = parsed.ip,
            loc = parsed.loc,
            colo = "",
            warpStatus = "OK",
            masqueEdgeOk = true,
            masqueEdgeDetail = "OK",
            s3Reachable = false,
            s3Detail = "Unreachable"
        ).formatSummary()
        assertEquals(
            "OK • IP: 198.51.100.42 (DE) • 240ms • WARP: OK • MASQUE Edge: OK • Psiphon(S3): Unreachable",
            s3FailedSummary
        )
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
                // Connection 4: Stage 4 Psiphon Bootstrap (AWS S3 server_list_compressed) reachability
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
                    val requestLines = mutableListOf<String>()
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        requestLines.add(line)
                        line = reader.readLine()
                    }
                    val reqLine = requestLines.firstOrNull().orEmpty()
                    val hasRange = requestLines.any { it.equals("Range: bytes=0-1023", ignoreCase = true) }
                    if (reqLine.startsWith("GET /psiphon/web/mjr4-p23r-puwl/server_list_compressed ") && hasRange) {
                        out.write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-1023/102400\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    } else {
                        out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    }
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
        assertTrue(ok.s3Reachable)
        assertEquals("OK", ok.s3Detail)
    }

    @Test
    fun authenticatedRemoteProxyUrlBuildAndParseRoundTripsWithUrlEncoding() {
        val socksAuth = StructuredUpstreamProxy(
            enabled = true,
            protocol = UpstreamProxyProtocol.SOCKS5,
            host = "proxy.example.com",
            port = 1080,
            username = "user@domain",
            password = "p@ss:word 1+2"
        )
        val built = socksAuth.buildUrl()
        assertEquals("socks5://user%40domain:p%40ss%3Aword%201%2B2@proxy.example.com:1080", built)
        assertEquals(
            "socks5://user%40domain:••••@proxy.example.com:1080",
            socksAuth.buildUrl(maskPassword = true)
        )
        assertEquals(UpstreamProxyPreset.CUSTOM, socksAuth.detectedPreset())

        val parsedSocks = StructuredUpstreamProxy.parse(built)
        assertTrue(parsedSocks.enabled)
        assertEquals(UpstreamProxyProtocol.SOCKS5, parsedSocks.protocol)
        assertEquals("proxy.example.com", parsedSocks.host)
        assertEquals(1080, parsedSocks.port)
        assertEquals("user@domain", parsedSocks.username)
        assertEquals("p@ss:word 1+2", parsedSocks.password)

        val httpAuth = StructuredUpstreamProxy(
            enabled = true,
            protocol = UpstreamProxyProtocol.HTTP,
            host = "203.0.113.10",
            port = 8080,
            username = "alice",
            password = "secret"
        )
        val httpUrl = httpAuth.buildUrl()
        assertEquals("http://alice:secret@203.0.113.10:8080", httpUrl)
        val parsedHttp = StructuredUpstreamProxy.parse(httpUrl)
        assertEquals(UpstreamProxyProtocol.HTTP, parsedHttp.protocol)
        assertEquals("alice", parsedHttp.username)
        assertEquals("secret", parsedHttp.password)
    }

    @Test
    fun socks5Rfc1929AndHttpBasicAuthAreSupportedInProxyTester() = runBlocking {
        // 1. SOCKS5 RFC 1929 Username/Password Auth
        val socksServer = ServerSocket(0)
        val socksPort = socksServer.localPort
        val socksThread = Thread {
            try {
                socksServer.soTimeout = 4_000
                repeat(4) { connIdx ->
                    socksServer.accept().use { s ->
                        val inp = s.getInputStream()
                        val out = s.getOutputStream()
                        val greeting = ByteArray(4)
                        inp.read(greeting)
                        // Expect [0x05, 0x02, 0x00, 0x02]
                        assertEquals(0x02.toByte(), greeting[3])
                        out.write(byteArrayOf(0x05, 0x02))
                        out.flush()

                        // RFC 1929 auth subnegotiation
                        val ver = inp.read()
                        val uLen = inp.read()
                        val uBytes = ByteArray(uLen).also { inp.read(it) }
                        val pLen = inp.read()
                        val pBytes = ByteArray(pLen).also { inp.read(it) }
                        assertEquals(0x01, ver)
                        assertEquals("alice", String(uBytes, Charsets.UTF_8))
                        assertEquals("s3cr3t", String(pBytes, Charsets.UTF_8))
                        out.write(byteArrayOf(0x01, 0x00))
                        out.flush()

                        if (connIdx > 0) {
                            val hdr = ByteArray(4)
                            inp.read(hdr)
                            if (hdr[3].toInt() == 0x03) {
                                val len = inp.read()
                                inp.read(ByteArray(len + 2))
                            } else {
                                inp.read(ByteArray(4 + 2))
                            }
                            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 1, 0xBB.toByte()))
                            out.flush()
                            if (connIdx == 1) {
                                val reader = BufferedReader(InputStreamReader(inp, Charsets.US_ASCII))
                                var line = reader.readLine()
                                while (!line.isNullOrEmpty()) line = reader.readLine()
                                out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nip=198.51.100.77\nloc=DE\ncolo=FRA\nwarp=off\n".toByteArray())
                                out.flush()
                            } else if (connIdx == 3) {
                                val reader = BufferedReader(InputStreamReader(inp, Charsets.US_ASCII))
                                var line = reader.readLine()
                                while (!line.isNullOrEmpty()) line = reader.readLine()
                                out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                                out.flush()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { socksServer.close() }
            }
        }
        socksThread.isDaemon = true
        socksThread.start()

        val socksResult = UpstreamProxyTester.testInternal(
            protocol = UpstreamProxyProtocol.SOCKS5,
            host = "127.0.0.1",
            port = socksPort,
            timeoutMs = 3_000,
            username = "alice",
            password = "s3cr3t",
            tlsUpgrade = { raw, _, _, _, _ -> raw }
        )
        socksThread.join(3_000)
        assertTrue("Expected SOCKS5 auth Success, got $socksResult", socksResult is ProxyTestResult.Success)

        // 2. HTTP Proxy-Authorization: Basic
        val httpServer = ServerSocket(0)
        val httpPort = httpServer.localPort
        val expectedAuth = "Proxy-Authorization: Basic " +
            java.util.Base64.getEncoder().encodeToString("bob:hunter2".toByteArray(Charsets.UTF_8))
        val httpThread = Thread {
            try {
                httpServer.soTimeout = 4_000
                repeat(4) { connIdx ->
                    httpServer.accept().use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                        val out = s.getOutputStream()
                        val headers = mutableListOf<String>()
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            headers += line
                            line = reader.readLine()
                        }
                        assertTrue(
                            "Missing Basic Proxy-Authorization header: $headers",
                            headers.any { it.equals(expectedAuth, ignoreCase = true) }
                        )
                        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                        if (connIdx == 1) {
                            var reqLine = reader.readLine()
                            while (!reqLine.isNullOrEmpty()) reqLine = reader.readLine()
                            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nip=203.0.113.55\nloc=NL\ncolo=AMS\nwarp=off\n".toByteArray())
                            out.flush()
                        } else if (connIdx == 3) {
                            var reqLine = reader.readLine()
                            while (!reqLine.isNullOrEmpty()) reqLine = reader.readLine()
                            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { httpServer.close() }
            }
        }
        httpThread.isDaemon = true
        httpThread.start()

        val httpResult = UpstreamProxyTester.testInternal(
            protocol = UpstreamProxyProtocol.HTTP,
            host = "127.0.0.1",
            port = httpPort,
            timeoutMs = 3_000,
            username = "bob",
            password = "hunter2",
            tlsUpgrade = { raw, _, _, _, _ -> raw }
        )
        httpThread.join(3_000)
        assertTrue("Expected HTTP auth Success, got $httpResult", httpResult is ProxyTestResult.Success)
    }
}
