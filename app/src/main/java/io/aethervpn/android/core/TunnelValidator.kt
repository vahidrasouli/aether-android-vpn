package io.aethervpn.android.core

import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

data class ValidationResult(
    val publicIp: String,
    val warpStatus: String,
    val colo: String,
    val latencyMs: Long
)

/**
 * Real end-to-end tunnel validator through the local loopback Aether SOCKS5 endpoint.
 *
 * Implements a direct, lightweight RFC 1928 SOCKS5 client over a plain loopback TCP Socket,
 * completely bypassing Android ART/libcore's SocksSocketImpl to eliminate any possibility of
 * the platform `InetAddress.toString()` NullPointerException.
 *
 * Also provides an on-demand health check used on network transitions to avoid
 * unnecessary, battery-draining reconnects when the existing tunnel remains healthy.
 */
object TunnelValidator {
    private const val TRACE_HOST = "www.cloudflare.com"
    private const val TRACE_PORT = 443

    fun verifyThroughSocks(
        socksPort: Int,
        timeoutMs: Int = 12_000
    ): Result<ValidationResult> {
        val startNs = System.nanoTime()
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        return try {
            rawSocket = Socket()
            rawSocket.soTimeout = timeoutMs
            rawSocket.connect(VpnConfig.loopbackSocketAddress(socksPort), timeoutMs)

            val outStream = rawSocket.getOutputStream()
            val inStream = rawSocket.getInputStream()

            // 1. SOCKS5 Greeting (RFC 1928: VER 5, NMETHODS 1, METHOD 0x00 NO_AUTH)
            outStream.write(byteArrayOf(0x05, 0x01, 0x00))
            outStream.flush()

            val greetingReply = ByteArray(2)
            readFully(inStream, greetingReply)
            if (greetingReply[0] != 0x05.toByte()) {
                return Result.failure(IllegalStateException("Invalid SOCKS version: ${greetingReply[0]}"))
            }
            if (greetingReply[1] != 0x00.toByte()) {
                return Result.failure(IllegalStateException("SOCKS5 auth rejected: ${greetingReply[1]}"))
            }

            // 2. SOCKS5 CONNECT (VER: 5, CMD: 1 CONNECT, RSV: 0, ATYP: 3 DOMAINNAME)
            val hostBytes = TRACE_HOST.toByteArray(Charsets.US_ASCII)
            val connectReq = ByteArray(4 + 1 + hostBytes.size + 2)
            connectReq[0] = 0x05 // VER
            connectReq[1] = 0x01 // CMD: CONNECT
            connectReq[2] = 0x00 // RSV
            connectReq[3] = 0x03 // ATYP: DOMAINNAME
            connectReq[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, connectReq, 5, hostBytes.size)
            connectReq[connectReq.size - 2] = (TRACE_PORT shr 8).toByte()
            connectReq[connectReq.size - 1] = (TRACE_PORT and 0xFF).toByte()

            outStream.write(connectReq)
            outStream.flush()

            // 3. SOCKS5 Reply (VER, REP, RSV, ATYP, BND.ADDR, BND.PORT)
            val replyHeader = ByteArray(4)
            readFully(inStream, replyHeader)
            if (replyHeader[0] != 0x05.toByte()) {
                return Result.failure(IllegalStateException("Invalid SOCKS reply version: ${replyHeader[0]}"))
            }

            val repCode = replyHeader[1].toInt() and 0xFF
            if (repCode != 0x00) {
                val repDesc = when (repCode) {
                    0x01 -> "General SOCKS server failure"
                    0x02 -> "Connection not allowed by ruleset"
                    0x03 -> "Network unreachable"
                    0x04 -> "Host unreachable"
                    0x05 -> "Connection refused (upstream handshake failed)"
                    0x06 -> "TTL expired"
                    0x07 -> "Command not supported"
                    0x08 -> "Address type not supported"
                    else -> "SOCKS error code 0x%02X".format(repCode)
                }
                return Result.failure(IllegalStateException("SOCKS5 CONNECT failed: $repDesc"))
            }

            // Skip BND.ADDR and BND.PORT per ATYP
            when (replyHeader[3].toInt() and 0xFF) {
                0x01 -> skipBytes(inStream, 4 + 2) // IPv4 (4) + Port (2)
                0x03 -> {
                    val len = inStream.read()
                    if (len < 0) return Result.failure(IllegalStateException("Premature EOF reading SOCKS5 domain length"))
                    skipBytes(inStream, len + 2)
                }
                0x04 -> skipBytes(inStream, 16 + 2) // IPv6 (16) + Port (2)
                else -> return Result.failure(IllegalStateException("Unknown SOCKS5 address type: ${replyHeader[3]}"))
            }

            // 4. Upgrade plain socket to SSLSocket
            val sslFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
            sslSocket = sslFactory.createSocket(rawSocket, TRACE_HOST, TRACE_PORT, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs
            sslSocket.startHandshake()

            // 5. Minimal HTTP/1.1 GET /cdn-cgi/trace
            val sslOut = sslSocket.getOutputStream()
            val sslIn = sslSocket.getInputStream()

            val request = "GET /cdn-cgi/trace HTTP/1.1\r\n" +
                    "Host: $TRACE_HOST\r\n" +
                    "User-Agent: AetherAndroid/2.4.0\r\n" +
                    "Connection: close\r\n\r\n"
            sslOut.write(request.toByteArray(Charsets.US_ASCII))
            sslOut.flush()

            val reader = BufferedReader(InputStreamReader(sslIn, Charsets.UTF_8))
            val statusLine = reader.readLine()
                ?: return Result.failure(IllegalStateException("Empty response from trace endpoint"))

            val parts = statusLine.split(" ", limit = 3)
            val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (statusCode !in 200..299) {
                return Result.failure(IllegalStateException("Tunnel trace check returned HTTP $statusCode: $statusLine"))
            }

            // Skip HTTP headers
            var line: String? = reader.readLine()
            while (!line.isNullOrEmpty()) {
                line = reader.readLine()
            }

            // Parse body fields
            val fields = mutableMapOf<String, String>()
            line = reader.readLine()
            while (line != null) {
                val idx = line.indexOf('=')
                if (idx > 0) {
                    fields[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
                line = reader.readLine()
            }

            val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
            val ip = fields["ip"].orEmpty()
            val warp = fields["warp"].orEmpty()
            val colo = fields["colo"].orEmpty()

            if (ip.isBlank()) {
                return Result.failure(IllegalStateException("Trace response missing public IP"))
            }
            if (warp != "on" && warp != "plus") {
                return Result.failure(
                    IllegalStateException("Traffic did not traverse Cloudflare WARP (warp=$warp)")
                )
            }

            Result.success(
                ValidationResult(
                    publicIp = ip,
                    warpStatus = warp,
                    colo = colo,
                    latencyMs = latencyMs
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { sslSocket?.close() }
            runCatching { rawSocket?.close() }
        }
    }

    private fun readFully(inStream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = inStream.read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                throw EOFException("Premature stream end while reading SOCKS5 response (expected ${buffer.size} bytes, got $offset)")
            }
            offset += read
        }
    }

    private fun skipBytes(inStream: InputStream, count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = inStream.skip(remaining.toLong())
            if (skipped <= 0) {
                if (inStream.read() < 0) {
                    throw EOFException("Premature stream end while skipping SOCKS5 response bytes")
                }
                remaining--
            } else {
                remaining -= skipped.toInt()
            }
        }
    }

    /** Fast 1-shot health probe before tearing down an existing tunnel on network change. */
    fun isTunnelHealthy(socksPort: Int): Boolean {
        return verifyThroughSocks(socksPort, timeoutMs = 4_000).isSuccess
    }
}
