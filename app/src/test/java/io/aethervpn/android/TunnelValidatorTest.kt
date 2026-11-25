package io.aethervpn.android

import io.aethervpn.android.core.PsiphonMode
import io.aethervpn.android.core.TunnelValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

class TunnelValidatorTest {

    @Test
    fun returnsDescriptiveFailureOnSocksRefusalWithoutNpe() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = Thread {
            try {
                server.soTimeout = 3_000
                val client = server.accept()
                val inStream = client.getInputStream()
                val outStream = client.getOutputStream()

                // Read greeting [0x05, 0x01, 0x00]
                val greeting = ByteArray(3)
                inStream.read(greeting)

                // Accept NO_AUTH
                outStream.write(byteArrayOf(0x05, 0x00))
                outStream.flush()

                // Read CONNECT command header (4 bytes: 0x05, 0x01, 0x00, 0x03)
                val cmdHeader = ByteArray(4)
                inStream.read(cmdHeader)
                val domainLen = inStream.read()
                val rest = ByteArray(domainLen + 2)
                inStream.read(rest)

                // Reply 0x05: Connection Refused (upstream handshake failed)
                val reply = byteArrayOf(0x05, 0x05, 0x00, 0x01, 0x7F, 0x00, 0x00, 0x01, 0x01, 0xBB.toByte())
                outStream.write(reply)
                outStream.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                runCatching { server.close() }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val result = TunnelValidator.verifyThroughSocks(port, timeoutMs = 2_000, maxAttempts = 1)
        serverThread.join(2_000)

        assertTrue("Result must be failure on SOCKS refusal", result.isFailure)
        val err = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "Error message must contain descriptive SOCKS failure, got: $err",
            err.contains("Connection refused")
        )
        assertFalse(
            "Must never contain null object reference NPE",
            err.contains("NullPointerException")
        )
    }

    @Test
    fun returnsFailureWhenSocksPortUnreachable() {
        // Port 1 is reserved and closed on loopback
        val result = TunnelValidator.verifyThroughSocks(1, timeoutMs = 500)
        assertTrue(result.isFailure)
        assertFalse(TunnelValidator.isTunnelHealthy(1))
    }

    @Test
    fun psiphonEgressModesAcceptWarpOffWithValidIpAndLocation() {
        val traceBody = "ip=146.59.111.104\ncolo=WAW\nloc=PL\nwarp=off\n"
        for (mode in listOf(PsiphonMode.WARP_TO_PSIPHON, PsiphonMode.PSIPHON_ONLY)) {
            val (port, thread) = startMockTraceSocksServer(traceBody)
            val result = TunnelValidator.verifyThroughSocksInternal(
                socksPort = port,
                timeoutMs = 2_000,
                psiphonMode = mode,
                tlsUpgrade = { raw, _ -> raw }
            )
            thread.join(2_000)

            assertTrue("Mode $mode must succeed when warp=off", result.isSuccess)
            val value = result.getOrThrow()
            assertEquals("146.59.111.104", value.publicIp)
            assertEquals("off", value.warpStatus)
            assertEquals("WAW", value.colo)
        }
    }

    @Test
    fun warpEgressModesRequireWarpOnOrPlus() {
        val warpOffBody = "ip=146.59.111.104\ncolo=WAW\nloc=PL\nwarp=off\n"
        val warpOnBody = "ip=104.28.1.1\ncolo=WAW\nloc=PL\nwarp=on\n"

        for (mode in listOf(PsiphonMode.OFF, PsiphonMode.PSIPHON_TO_WARP)) {
            val (failPort, failThread) = startMockTraceSocksServer(warpOffBody)
            val failResult = TunnelValidator.verifyThroughSocksInternal(
                socksPort = failPort,
                timeoutMs = 2_000,
                psiphonMode = mode,
                tlsUpgrade = { raw, _ -> raw }
            )
            failThread.join(2_000)
            assertTrue("Mode $mode must fail when warp=off", failResult.isFailure)
            assertTrue(
                failResult.exceptionOrNull()?.message?.contains("did not traverse Cloudflare WARP") == true
            )

            val (okPort, okThread) = startMockTraceSocksServer(warpOnBody)
            val okResult = TunnelValidator.verifyThroughSocksInternal(
                socksPort = okPort,
                timeoutMs = 2_000,
                psiphonMode = mode,
                tlsUpgrade = { raw, _ -> raw }
            )
            okThread.join(2_000)
            assertTrue("Mode $mode must succeed when warp=on", okResult.isSuccess)
        }
    }

    @Test
    fun psiphonEgressRejectsMissingExitLocation() {
        val missingLocBody = "ip=146.59.111.104\nwarp=off\n"
        val (port, thread) = startMockTraceSocksServer(missingLocBody)
        val result = TunnelValidator.verifyThroughSocksInternal(
            socksPort = port,
            timeoutMs = 2_000,
            psiphonMode = PsiphonMode.WARP_TO_PSIPHON,
            tlsUpgrade = { raw, _ -> raw }
        )
        thread.join(2_000)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("missing exit location") == true)
    }

    @Test
    fun retriesTransientGeneralSocksFailureUpToThreeAttemptsAndUsesDirectIpv4Connect() {
        val server = ServerSocket(0)
        val port = server.localPort
        var attemptCount = 0
        var observedAtyp = -1
        var observedIp = ByteArray(0)

        val thread = Thread {
            try {
                server.soTimeout = 4_000
                repeat(3) { idx ->
                    val client = server.accept()
                    attemptCount++
                    val inStream = client.getInputStream()
                    val outStream = client.getOutputStream()

                    val greeting = ByteArray(3)
                    inStream.read(greeting)
                    outStream.write(byteArrayOf(0x05, 0x00))
                    outStream.flush()

                    val cmdHeader = ByteArray(4)
                    inStream.read(cmdHeader)
                    observedAtyp = cmdHeader[3].toInt() and 0xFF
                    observedIp = ByteArray(4)
                    inStream.read(observedIp)
                    val portBytes = ByteArray(2)
                    inStream.read(portBytes)

                    if (idx < 2) {
                        // First 2 attempts fail with 0x01 General SOCKS server failure
                        outStream.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        outStream.flush()
                        client.close()
                    } else {
                        // 3rd attempt succeeds
                        outStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 1, 0xBB.toByte()))
                        outStream.flush()
                        val reader = BufferedReader(InputStreamReader(inStream, Charsets.US_ASCII))
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            line = reader.readLine()
                        }
                        val resp = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nip=104.28.1.2\ncolo=FRA\nloc=DE\nwarp=on\n"
                        outStream.write(resp.toByteArray(Charsets.UTF_8))
                        outStream.flush()
                        client.close()
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { server.close() }
            }
        }
        thread.isDaemon = true
        thread.start()

        val result = TunnelValidator.verifyThroughSocksInternal(
            socksPort = port,
            timeoutMs = 2_000,
            psiphonMode = PsiphonMode.OFF,
            maxAttempts = 3,
            retryDelayMs = 20L,
            tlsUpgrade = { raw, _ -> raw }
        )
        thread.join(3_000)

        assertTrue("Expected retry loop to succeed on 3rd attempt: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(3, attemptCount)
        assertEquals("Must use ATYP 0x01 (IPv4) to avoid inner DNS stall", 0x01, observedAtyp)
        assertEquals(listOf(1, 1, 1, 1), observedIp.map { it.toInt() and 0xFF })
        assertEquals("104.28.1.2", result.getOrThrow().publicIp)
    }

    private fun startMockTraceSocksServer(traceBody: String): Pair<Int, Thread> {
        val server = ServerSocket(0)
        val port = server.localPort
        val thread = Thread {
            try {
                server.soTimeout = 3_000
                val client = server.accept()
                val inStream = client.getInputStream()
                val outStream = client.getOutputStream()

                // 1. Greeting
                val greeting = ByteArray(3)
                inStream.read(greeting)
                outStream.write(byteArrayOf(0x05, 0x00))
                outStream.flush()

                // 2. CONNECT request
                val cmdHeader = ByteArray(4)
                inStream.read(cmdHeader)
                val domainLen = inStream.read()
                val rest = ByteArray(domainLen + 2)
                inStream.read(rest)

                // 3. Reply success
                outStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x7F, 0x00, 0x00, 0x01, 0x01, 0xBB.toByte()))
                outStream.flush()

                // 4. Read HTTP request headers
                val reader = BufferedReader(InputStreamReader(inStream, Charsets.US_ASCII))
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }

                // 5. Write HTTP trace response
                val httpResp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n$traceBody"
                outStream.write(httpResp.toByteArray(Charsets.UTF_8))
                outStream.flush()
                client.close()
            } catch (_: Exception) {
            } finally {
                runCatching { server.close() }
            }
        }
        thread.isDaemon = true
        thread.start()
        return port to thread
    }
}
