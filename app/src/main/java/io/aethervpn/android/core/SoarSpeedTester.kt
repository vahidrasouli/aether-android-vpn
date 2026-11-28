package io.aethervpn.android.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

data class SoarSpeedTestResult(
    val bytesDownloaded: Long,
    val ttfbMs: Long,
    val totalDurationMs: Long,
    val speedKbps: Double,
    val usedTemporaryInstance: Boolean
)

/**
 * Zero-disk Cloudflare payload speed test routed over Soar SOCKS5.
 * Uses the active Soar instance on 127.0.0.1:1829 if connected, or spawns
 * an isolated temporary `libsoar.so` on 127.0.0.1:1830 and tears it down cleanly.
 */
object SoarSpeedTester {

    const val SIZE_100_KB = 100 * 1024
    const val SIZE_200_KB = 200 * 1024
    const val SIZE_500_KB = 500 * 1024
    const val TEMP_SOCKS_PORT = 1830
    const val TARGET_HOST = "speed.cloudflare.com"
    const val TARGET_PORT = 443
    private const val TIMEOUT_MS = 15_000
    private const val MAX_HEADER_BYTES = 16 * 1024

    fun buildDownloadUrl(sizeBytes: Int): String =
        "https://$TARGET_HOST/__down?bytes=$sizeBytes"

    internal fun buildSocks5ConnectRequest(host: String, port: Int): ByteArray {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        require(hostBytes.size in 1..255) { "SOCKS5 domain length out of range: ${hostBytes.size}" }
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05 // VER
        req[1] = 0x01 // CMD = CONNECT
        req[2] = 0x00 // RSV
        req[3] = 0x03 // ATYP = DOMAINNAME
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((port ushr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (port and 0xFF).toByte()
        return req
    }

    suspend fun runTest(
        context: Context,
        profile: SoarProfile,
        activePack: ResolverPack?,
        sizeBytes: Int = SIZE_200_KB,
        privateDataDir: File = context.filesDir
    ): Result<SoarSpeedTestResult> = withContext(Dispatchers.IO) {
        val runtime = SoarStateStore.state.value
        val useExisting = runtime.isConnected
        var tempEngine: SoarEngineManager? = null

        try {
            val port = if (useExisting) {
                SoarEngineManager.DEFAULT_LISTEN_PORT
            } else {
                val engine = SoarEngineManager(privateDataDir)
                tempEngine = engine
                val startRes = engine.start(
                    context = context,
                    profile = profile,
                    mode = SoarConnectionMode.PROXY_ONLY,
                    activePack = activePack,
                    listenHost = SoarEngineManager.DEFAULT_LISTEN_HOST,
                    listenPort = TEMP_SOCKS_PORT,
                    readinessTimeoutMs = 12_000L,
                    configFileName = "soar-speedtest-config.json",
                    recordDiagnostics = false
                )
                if (startRes.isFailure) {
                    return@withContext Result.failure(
                        startRes.exceptionOrNull() ?: IllegalStateException("Failed to start temporary Soar instance")
                    )
                }
                TEMP_SOCKS_PORT
            }

            coroutineContext.ensureActive()
            val result = measureOverSocks5(
                host = SoarEngineManager.DEFAULT_LISTEN_HOST,
                port = port,
                sizeBytes = sizeBytes,
                usedTemporaryInstance = !useExisting
            )
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { tempEngine?.stop() }
        }
    }

    private suspend fun measureOverSocks5(
        host: String,
        port: Int,
        sizeBytes: Int,
        usedTemporaryInstance: Boolean
    ): SoarSpeedTestResult {
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { sslSocket?.close() }
                runCatching { rawSocket?.close() }
            }
        }
        try {
            val sock = Socket().apply {
                soTimeout = TIMEOUT_MS
                connect(InetSocketAddress(host, port), TIMEOUT_MS)
            }
            rawSocket = sock

            val rawOut = sock.getOutputStream()
            val rawIn = DataInputStream(sock.getInputStream())

            // SOCKS5 greeting: version 5, 1 auth method, 0x00 (No Auth)
            rawOut.write(byteArrayOf(0x05, 0x01, 0x00))
            rawOut.flush()

            val greetingResp = ByteArray(2)
            rawIn.readFully(greetingResp)
            if (greetingResp[0] != 0x05.toByte() || greetingResp[1] != 0x00.toByte()) {
                throw IllegalStateException(
                    "SOCKS5 handshake rejected (ver=${greetingResp[0].toInt() and 0xFF}, method=${greetingResp[1].toInt() and 0xFF})"
                )
            }

            // SOCKS5 CONNECT with ATYP=0x03 (domain name) so DNS resolves remotely through Soar
            rawOut.write(buildSocks5ConnectRequest(TARGET_HOST, TARGET_PORT))
            rawOut.flush()

            val replyHeader = ByteArray(4)
            rawIn.readFully(replyHeader)
            if (replyHeader[0] != 0x05.toByte() || replyHeader[1] != 0x00.toByte()) {
                throw IllegalStateException("SOCKS5 CONNECT failed (rep=${replyHeader[1].toInt() and 0xFF})")
            }
            when (replyHeader[3].toInt() and 0xFF) {
                0x01 -> rawIn.readFully(ByteArray(6)) // IPv4 (4) + port (2)
                0x04 -> rawIn.readFully(ByteArray(18)) // IPv6 (16) + port (2)
                0x03 -> {
                    val domainLen = rawIn.readUnsignedByte()
                    rawIn.readFully(ByteArray(domainLen + 2))
                }
                else -> throw IllegalStateException("Unsupported SOCKS5 ATYP: ${replyHeader[3].toInt() and 0xFF}")
            }

            coroutineContext.ensureActive()
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = (sslFactory.createSocket(sock, TARGET_HOST, TARGET_PORT, true) as SSLSocket).apply {
                soTimeout = TIMEOUT_MS
                startHandshake()
            }
            sslSocket = tls

            val startNs = System.nanoTime()
            val req = "GET /__down?bytes=$sizeBytes HTTP/1.1\r\n" +
                "Host: $TARGET_HOST\r\n" +
                "User-Agent: AetherVpnAndroid\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Connection: close\r\n\r\n"
            val tlsOut = tls.getOutputStream()
            tlsOut.write(req.toByteArray(Charsets.US_ASCII))
            tlsOut.flush()

            val input = BufferedInputStream(tls.getInputStream(), 16 * 1024)
            val statusCode = readHttpHeaders(input)
            if (statusCode !in 200..299) {
                throw IllegalStateException("Speed test HTTP $statusCode")
            }

            var ttfbMs = -1L
            var totalRead = 0L
            val buf = ByteArray(16 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val r = input.read(buf)
                if (r <= 0) break
                if (ttfbMs < 0L) {
                    ttfbMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                }
                totalRead += r
            }

            val totalMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            if (ttfbMs < 0L) ttfbMs = totalMs
            val speedKbps = (totalRead / 1024.0) / (totalMs / 1000.0)
            return SoarSpeedTestResult(
                bytesDownloaded = totalRead,
                ttfbMs = ttfbMs,
                totalDurationMs = totalMs,
                speedKbps = speedKbps,
                usedTemporaryInstance = usedTemporaryInstance
            )
        } finally {
            cancelHandle?.dispose()
            runCatching { sslSocket?.close() }
            runCatching { rawSocket?.close() }
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): Int {
        val headerBuf = ByteArrayOutputStream(512)
        var state = 0
        while (headerBuf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) throw IllegalStateException("Unexpected EOF reading HTTP headers")
            headerBuf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> break
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val headerText = headerBuf.toString(Charsets.US_ASCII.name())
        val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
        return statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IllegalStateException("Malformed HTTP status line: $statusLine")
    }
}
