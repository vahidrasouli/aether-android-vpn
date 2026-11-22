package io.aethervpn.android

import io.aethervpn.android.core.TunnelValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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

        val result = TunnelValidator.verifyThroughSocks(port, timeoutMs = 2_000)
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
}
