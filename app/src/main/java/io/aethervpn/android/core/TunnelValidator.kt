package io.aethervpn.android.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class ValidationResult(
    val publicIp: String,
    val warpStatus: String,
    val colo: String,
    val latencyMs: Long
)

/**
 * Real end-to-end tunnel validator through the local loopback Aether SOCKS5 endpoint.
 * Also provides an on-demand health check used on network transitions to avoid
 * unnecessary, battery-draining reconnects when the existing tunnel remains healthy.
 */
object TunnelValidator {
    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    fun verifyThroughSocks(
        socksPort: Int,
        timeoutMs: Int = 12_000
    ): Result<ValidationResult> {
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            VpnConfig.loopbackSocketAddress(socksPort)
        )
        var conn: HttpsURLConnection? = null
        val startNs = System.nanoTime()
        return try {
            conn = (URL(TRACE_URL).openConnection(proxy) as HttpsURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Connection", "close")
            }

            val code = conn.responseCode
            val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("Tunnel trace check returned HTTP $code"))
            }

            val fields = mutableMapOf<String, String>()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                reader.lineSequence().forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        fields[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                    }
                }
            }

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
            conn?.disconnect()
        }
    }

    /** Fast 1-shot health probe before tearing down an existing tunnel on network change. */
    fun isTunnelHealthy(socksPort: Int): Boolean {
        return verifyThroughSocks(socksPort, timeoutMs = 4_000).isSuccess
    }
}
