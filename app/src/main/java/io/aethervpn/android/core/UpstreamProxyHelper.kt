package io.aethervpn.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

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

sealed interface ProxyTestResult {
    data class Success(val rttMs: Long) : ProxyTestResult
    data class Failure(val error: String) : ProxyTestResult
}

object UpstreamProxyTester {
    suspend fun test(
        protocol: UpstreamProxyProtocol,
        host: String,
        port: Int,
        timeoutMs: Int = 3_500
    ): ProxyTestResult = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            return@withContext ProxyTestResult.Failure("Host cannot be empty")
        }
        if (port !in 1..65535) {
            return@withContext ProxyTestResult.Failure("Invalid port ($port)")
        }

        val startMs = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
                when (protocol) {
                    UpstreamProxyProtocol.SOCKS5 -> {
                        val out = socket.getOutputStream()
                        out.write(byteArrayOf(0x05, 0x01, 0x00))
                        out.flush()
                        val input = socket.getInputStream()
                        val ver = input.read()
                        val method = input.read()
                        if (ver != 0x05 || method < 0) {
                            return@withContext ProxyTestResult.Failure("Not a SOCKS5 proxy")
                        }
                        if (method == 0xFF) {
                            return@withContext ProxyTestResult.Failure("SOCKS5 requires auth")
                        }
                    }
                    UpstreamProxyProtocol.HTTP -> {
                        val out = socket.getOutputStream()
                        val req = "HEAD / HTTP/1.1\r\nHost: $cleanHost:$port\r\nConnection: close\r\n\r\n"
                        out.write(req.toByteArray(Charsets.US_ASCII))
                        out.flush()
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                        val statusLine = reader.readLine()
                        if (statusLine.isNullOrBlank() || !statusLine.startsWith("HTTP/1.", ignoreCase = true)) {
                            return@withContext ProxyTestResult.Failure("Not an HTTP proxy")
                        }
                    }
                }
            }
            val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            ProxyTestResult.Success(rtt)
        } catch (e: Exception) {
            ProxyTestResult.Failure(e.message?.take(60) ?: e.javaClass.simpleName)
        }
    }
}
