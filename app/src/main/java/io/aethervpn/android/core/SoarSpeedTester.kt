package io.aethervpn.android.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
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
    private const val TIMEOUT_MS = 15_000

    fun buildDownloadUrl(sizeBytes: Int): String =
        "https://speed.cloudflare.com/__down?bytes=$sizeBytes"

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
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        var conn: HttpURLConnection? = null
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { conn?.disconnect() }
            }
        }
        try {
            val startNs = System.nanoTime()
            conn = (URL(buildDownloadUrl(sizeBytes)).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("User-Agent", "AetherVpnAndroid")
                setRequestProperty("Accept-Encoding", "identity")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Speed test HTTP $code")
            }

            var ttfbMs = -1L
            var totalRead = 0L
            val buf = ByteArray(16 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val r = input.read(buf)
                    if (r <= 0) break
                    if (ttfbMs < 0L) {
                        ttfbMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    }
                    totalRead += r
                }
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
            runCatching { conn?.disconnect() }
        }
    }
}
