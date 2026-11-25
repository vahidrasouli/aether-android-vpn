package io.aethervpn.android.core

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

enum class UpstreamProxyProtocol(val scheme: String, val label: String) {
    SOCKS5("socks5", "SOCKS5"),
    HTTP("http", "HTTP")
}

enum class UpstreamProxyPreset(
    val label: String,
    val protocol: UpstreamProxyProtocol,
    val host: String,
    val port: Int
) {
    V2RAYNG("v2rayNG (10808)", UpstreamProxyProtocol.SOCKS5, "127.0.0.1", 10808),
    SINGBOX_CLASH("Sing-box / Clash (7890)", UpstreamProxyProtocol.SOCKS5, "127.0.0.1", 7890),
    CUSTOM("Custom", UpstreamProxyProtocol.SOCKS5, "127.0.0.1", 10808)
}

data class StructuredUpstreamProxy(
    val enabled: Boolean = false,
    val protocol: UpstreamProxyProtocol = UpstreamProxyProtocol.SOCKS5,
    val host: String = "127.0.0.1",
    val port: Int = 10808
) {
    fun buildUrl(): String {
        if (!enabled) return ""
        val cleanHost = host.trim()
        if (cleanHost.isEmpty() || port !in 1..65535) return ""
        val formattedHost = if (cleanHost.contains(':') && !cleanHost.startsWith("[")) {
            "[$cleanHost]"
        } else {
            cleanHost
        }
        return "${protocol.scheme}://$formattedHost:$port"
    }

    fun detectedPreset(): UpstreamProxyPreset {
        val cleanHost = host.trim()
        return when {
            protocol == UpstreamProxyProtocol.SOCKS5 && cleanHost == "127.0.0.1" && port == 10808 ->
                UpstreamProxyPreset.V2RAYNG
            protocol == UpstreamProxyProtocol.SOCKS5 && cleanHost == "127.0.0.1" && port == 7890 ->
                UpstreamProxyPreset.SINGBOX_CLASH
            else -> UpstreamProxyPreset.CUSTOM
        }
    }

    companion object {
        fun parse(rawUrl: String): StructuredUpstreamProxy {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                return StructuredUpstreamProxy(enabled = false)
            }
            val schemePart = if (trimmed.contains("://")) {
                trimmed.substringBefore("://").lowercase()
            } else {
                "socks5"
            }
            val protocol = when (schemePart) {
                "http", "https" -> UpstreamProxyProtocol.HTTP
                else -> UpstreamProxyProtocol.SOCKS5
            }
            val withoutScheme = trimmed.substringAfter("://", trimmed)
            val authority = withoutScheme.substringAfterLast('@').substringBefore('/')
            val (host, portStr) = if (authority.startsWith("[")) {
                val h = authority.substringAfter("[").substringBefore("]")
                val p = authority.substringAfter("]:", "")
                h to p
            } else {
                val idx = authority.lastIndexOf(':')
                if (idx > 0) {
                    authority.substring(0, idx) to authority.substring(idx + 1)
                } else {
                    authority to ""
                }
            }
            val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: 10808
            return StructuredUpstreamProxy(
                enabled = true,
                protocol = protocol,
                host = host.trim().ifEmpty { "127.0.0.1" },
                port = port
            )
        }
    }
}

data class CloudflareTraceInfo(
    val ip: String,
    val loc: String,
    val colo: String,
    val warp: String
)

sealed interface ProxyTestResult {
    data class Success(
        val rttMs: Long,
        val publicIp: String,
        val loc: String,
        val colo: String,
        val warpStatus: String,
        val masqueEdgeOk: Boolean,
        val masqueEdgeDetail: String
    ) : ProxyTestResult {
        fun formatSummary(): String {
            val region = listOf(loc, colo).filter { it.isNotBlank() }.joinToString("/").ifBlank { "??" }
            val masqueLabel = if (masqueEdgeOk) "OK" else masqueEdgeDetail
            return "OK • IP: $publicIp ($region) • ${rttMs}ms • WARP: $warpStatus • MASQUE Edge: $masqueLabel"
        }
    }

    data class Failure(val error: String) : ProxyTestResult
}

object UpstreamProxyTester {
    private const val TRACE_HOST = "www.cloudflare.com"
    private const val TRACE_PORT = 443
    private const val MASQUE_EDGE_IP = "162.159.198.1"
    private const val MASQUE_EDGE_SNI = "cloudflareaccess.com"
    private const val MASQUE_EDGE_PORT = 443

    internal fun parseCloudflareTrace(body: String): CloudflareTraceInfo? {
        val fields = mutableMapOf<String, String>()
        body.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                fields[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        val ip = fields["ip"].orEmpty()
        if (ip.isBlank()) return null
        return CloudflareTraceInfo(
            ip = ip,
            loc = fields["loc"].orEmpty(),
            colo = fields["colo"].orEmpty(),
            warp = fields["warp"].orEmpty().ifBlank { "off" }
        )
    }

    suspend fun test(
        protocol: UpstreamProxyProtocol,
        host: String,
        port: Int,
        timeoutMs: Int = 6_000
    ): ProxyTestResult = testInternal(protocol, host, port, timeoutMs)

    internal suspend fun testInternal(
        protocol: UpstreamProxyProtocol,
        host: String,
        port: Int,
        timeoutMs: Int = 6_000,
        tlsUpgrade: (Socket, String, Int, Int, Boolean) -> Socket = ::defaultTlsUpgrade
    ): ProxyTestResult = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            return@withContext ProxyTestResult.Failure("Host cannot be empty")
        }
        if (port !in 1..65535) {
            return@withContext ProxyTestResult.Failure("Invalid port ($port)")
        }

        try {
            // Stage 1: Local Handshake
            verifyLocalHandshake(protocol, cleanHost, port, timeoutMs)

            // Stage 2: End-to-End Cloudflare Trace through proxy
            val traceStartMs = System.currentTimeMillis()
            val trace = fetchTraceThroughProxy(protocol, cleanHost, port, timeoutMs, tlsUpgrade)
            val rtt = (System.currentTimeMillis() - traceStartMs).coerceAtLeast(1L)

            // Stage 3: Cloudflare MASQUE Edge Reachability (162.159.198.1:443 / cloudflareaccess.com)
            val (masqueOk, masqueDetail) = probeMasqueEdgeThroughProxy(
                protocol = protocol,
                proxyHost = cleanHost,
                proxyPort = port,
                timeoutMs = timeoutMs,
                tlsUpgrade = tlsUpgrade
            )

            ProxyTestResult.Success(
                rttMs = rtt,
                publicIp = trace.ip,
                loc = trace.loc,
                colo = trace.colo,
                warpStatus = trace.warp,
                masqueEdgeOk = masqueOk,
                masqueEdgeDetail = masqueDetail
            )
        } catch (e: Exception) {
            ProxyTestResult.Failure(e.message?.take(80) ?: e.javaClass.simpleName)
        }
    }

    private fun verifyLocalHandshake(
        protocol: UpstreamProxyProtocol,
        host: String,
        port: Int,
        timeoutMs: Int
    ) {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            when (protocol) {
                UpstreamProxyProtocol.SOCKS5 -> {
                    val out = socket.getOutputStream()
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val input = socket.getInputStream()
                    val ver = input.read()
                    val method = input.read()
                    if (ver != 0x05 || method < 0) {
                        throw IllegalStateException("Not a SOCKS5 proxy")
                    }
                    if (method == 0xFF) {
                        throw IllegalStateException("SOCKS5 requires auth")
                    }
                }
                UpstreamProxyProtocol.HTTP -> {
                    val out = socket.getOutputStream()
                    val req = "HEAD / HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n"
                    out.write(req.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                    val statusLine = reader.readLine()
                    if (statusLine.isNullOrBlank() || !statusLine.startsWith("HTTP/1.", ignoreCase = true)) {
                        throw IllegalStateException("Not an HTTP proxy")
                    }
                }
            }
        }
    }

    private fun fetchTraceThroughProxy(
        protocol: UpstreamProxyProtocol,
        proxyHost: String,
        proxyPort: Int,
        timeoutMs: Int,
        tlsUpgrade: (Socket, String, Int, Int, Boolean) -> Socket
    ): CloudflareTraceInfo {
        var rawSocket: Socket? = null
        var tlsSocket: Socket? = null
        try {
            rawSocket = openTunneledSocket(
                protocol = protocol,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                targetHost = TRACE_HOST,
                targetPort = TRACE_PORT,
                isIpLiteral = false,
                timeoutMs = timeoutMs
            )
            tlsSocket = tlsUpgrade(rawSocket, TRACE_HOST, TRACE_PORT, timeoutMs, false)
            val out = tlsSocket.getOutputStream()
            val req = "GET /cdn-cgi/trace HTTP/1.1\r\n" +
                "Host: $TRACE_HOST\r\n" +
                "User-Agent: AetherAndroid/2.4.0\r\n" +
                "Connection: close\r\n\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val reader = BufferedReader(InputStreamReader(tlsSocket.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine()
                ?: throw IllegalStateException("Empty trace response from proxy")
            val code = statusLine.split(" ", limit = 3).getOrNull(1)?.toIntOrNull() ?: 0
            if (code !in 200..299) {
                throw IllegalStateException("Trace HTTP $code via proxy")
            }

            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                line = reader.readLine()
            }
            val body = reader.readText()
            return parseCloudflareTrace(body)
                ?: throw IllegalStateException("Trace response missing IP")
        } finally {
            runCatching { tlsSocket?.close() }
            runCatching { rawSocket?.close() }
        }
    }

    private fun probeMasqueEdgeThroughProxy(
        protocol: UpstreamProxyProtocol,
        proxyHost: String,
        proxyPort: Int,
        timeoutMs: Int,
        tlsUpgrade: (Socket, String, Int, Int, Boolean) -> Socket
    ): Pair<Boolean, String> {
        var rawSocket: Socket? = null
        var tlsSocket: Socket? = null
        return try {
            rawSocket = openTunneledSocket(
                protocol = protocol,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                targetHost = MASQUE_EDGE_IP,
                targetPort = MASQUE_EDGE_PORT,
                isIpLiteral = true,
                timeoutMs = timeoutMs
            )
            tlsSocket = tlsUpgrade(rawSocket, MASQUE_EDGE_SNI, MASQUE_EDGE_PORT, timeoutMs, true)
            true to "OK"
        } catch (e: Exception) {
            false to (e.message?.take(28)?.ifBlank { null } ?: "Unreachable")
        } finally {
            runCatching { tlsSocket?.close() }
            runCatching { rawSocket?.close() }
        }
    }

    private fun openTunneledSocket(
        protocol: UpstreamProxyProtocol,
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        isIpLiteral: Boolean,
        timeoutMs: Int
    ): Socket {
        val socket = Socket()
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            when (protocol) {
                UpstreamProxyProtocol.SOCKS5 -> {
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val greeting = ByteArray(2)
                    readFully(input, greeting)
                    if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                        throw IllegalStateException("SOCKS5 handshake rejected")
                    }

                    val connectReq = if (isIpLiteral) {
                        val addrBytes = InetAddress.getByName(targetHost).address
                        ByteArray(4 + addrBytes.size + 2).apply {
                            this[0] = 0x05
                            this[1] = 0x01
                            this[2] = 0x00
                            this[3] = if (addrBytes.size == 4) 0x01 else 0x04
                            System.arraycopy(addrBytes, 0, this, 4, addrBytes.size)
                            this[size - 2] = (targetPort shr 8).toByte()
                            this[size - 1] = (targetPort and 0xFF).toByte()
                        }
                    } else {
                        val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
                        ByteArray(4 + 1 + hostBytes.size + 2).apply {
                            this[0] = 0x05
                            this[1] = 0x01
                            this[2] = 0x00
                            this[3] = 0x03
                            this[4] = hostBytes.size.toByte()
                            System.arraycopy(hostBytes, 0, this, 5, hostBytes.size)
                            this[size - 2] = (targetPort shr 8).toByte()
                            this[size - 1] = (targetPort and 0xFF).toByte()
                        }
                    }
                    out.write(connectReq)
                    out.flush()

                    val replyHeader = ByteArray(4)
                    readFully(input, replyHeader)
                    if (replyHeader[0] != 0x05.toByte()) {
                        throw IllegalStateException("Invalid SOCKS5 reply")
                    }
                    val rep = replyHeader[1].toInt() and 0xFF
                    if (rep != 0x00) {
                        throw IllegalStateException("Proxy CONNECT failed (0x%02X)".format(rep))
                    }
                    when (replyHeader[3].toInt() and 0xFF) {
                        0x01 -> skipBytes(input, 4 + 2)
                        0x03 -> {
                            val len = input.read()
                            if (len < 0) throw EOFException("Premature EOF reading SOCKS5 domain")
                            skipBytes(input, len + 2)
                        }
                        0x04 -> skipBytes(input, 16 + 2)
                        else -> throw IllegalStateException("Unknown SOCKS5 ATYP")
                    }
                }
                UpstreamProxyProtocol.HTTP -> {
                    val req = "CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
                        "Host: $targetHost:$targetPort\r\n" +
                        "Proxy-Connection: keep-alive\r\n\r\n"
                    out.write(req.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    val statusLine = readAsciiLine(input)
                        ?: throw IllegalStateException("Empty HTTP CONNECT response")
                    val code = statusLine.split(" ", limit = 3).getOrNull(1)?.toIntOrNull() ?: 0
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP CONNECT failed ($code)")
                    }
                    while (true) {
                        val headerLine = readAsciiLine(input) ?: break
                        if (headerLine.isEmpty()) break
                    }
                }
            }
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.deleteCharAt(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException("Premature EOF from proxy")
            offset += read
        }
    }

    private fun skipBytes(input: InputStream, count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped <= 0) {
                if (input.read() < 0) throw EOFException("Premature EOF skipping proxy bytes")
                remaining--
            } else {
                remaining -= skipped.toInt()
            }
        }
    }

    private fun defaultTlsUpgrade(
        raw: Socket,
        sniHost: String,
        port: Int,
        timeoutMs: Int,
        alpnH2: Boolean
    ): Socket {
        val sslFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        return (sslFactory.createSocket(raw, sniHost, port, true) as SSLSocket).apply {
            soTimeout = timeoutMs
            if (alpnH2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val params = sslParameters
                    params.applicationProtocols = arrayOf("h2", "http/1.1")
                    sslParameters = params
                }
            }
            startHandshake()
        }
    }
}
