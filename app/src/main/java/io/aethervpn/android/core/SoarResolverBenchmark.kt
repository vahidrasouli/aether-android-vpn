package io.aethervpn.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

data class ResolverBenchmarkMetrics(
    val ip: String,
    val avgPingMs: Int,
    val jitterMs: Int,
    val lossPercent: Int,
    val successCount: Int,
    val totalProbes: Int = DEFAULT_PROBES_PER_IP
) {
    val isUnreachable: Boolean get() = successCount <= 0 || avgPingMs == Int.MAX_VALUE

    companion object {
        const val DEFAULT_PROBES_PER_IP = 5
    }
}

data class BenchmarkProgress(
    val completedCount: Int,
    val totalCount: Int,
    val latest: ResolverBenchmarkMetrics? = null
)

/**
 * Stage-2 DNS Resolver Pack benchmark: sends 5 sequential zone probes per healthy IP,
 * computes Avg Ping, Jitter (mean absolute deviation between consecutive RTTs), and Loss %,
 * and sorts resolvers with 0% loss + lowest jitter/ping at the top.
 */
object SoarResolverBenchmark {

    const val DEFAULT_PROBES_PER_IP = ResolverBenchmarkMetrics.DEFAULT_PROBES_PER_IP
    private const val DEFAULT_BENCHMARK_WORKERS = 10
    private const val DEFAULT_PROBE_TIMEOUT_MS = 2000
    private const val DNS_PORT = 53
    private const val DNS_TYPE_TXT = 16

    fun computeMetrics(
        ip: String,
        rttsMs: List<Long?>,
        totalProbes: Int = rttsMs.size.coerceAtLeast(1)
    ): ResolverBenchmarkMetrics {
        val successRtts = rttsMs.filterNotNull().map { it.coerceAtLeast(1L) }
        val lossPercent = (((totalProbes - successRtts.size) * 100.0) / totalProbes)
            .roundToInt()
            .coerceIn(0, 100)

        if (successRtts.isEmpty()) {
            return ResolverBenchmarkMetrics(
                ip = ip,
                avgPingMs = Int.MAX_VALUE,
                jitterMs = Int.MAX_VALUE,
                lossPercent = 100,
                successCount = 0,
                totalProbes = totalProbes
            )
        }

        val avgPingMs = successRtts.average().roundToInt().coerceAtLeast(1)
        val jitterMs = if (successRtts.size < 2) {
            0
        } else {
            var diffSum = 0.0
            for (i in 0 until successRtts.size - 1) {
                diffSum += abs(successRtts[i + 1] - successRtts[i]).toDouble()
            }
            (diffSum / (successRtts.size - 1)).roundToInt()
        }

        return ResolverBenchmarkMetrics(
            ip = ip,
            avgPingMs = avgPingMs,
            jitterMs = jitterMs,
            lossPercent = lossPercent,
            successCount = successRtts.size,
            totalProbes = totalProbes
        )
    }

    fun sortByQuality(metrics: List<ResolverBenchmarkMetrics>): List<ResolverBenchmarkMetrics> {
        return metrics.sortedWith(
            compareBy<ResolverBenchmarkMetrics>(
                { it.lossPercent },
                { it.jitterMs },
                { it.avgPingMs },
                { it.ip }
            )
        )
    }

    suspend fun benchmarkPack(
        resolvers: List<String>,
        domain: String,
        useTcpFallback: Boolean = false,
        probesPerIp: Int = DEFAULT_PROBES_PER_IP,
        workers: Int = DEFAULT_BENCHMARK_WORKERS,
        probeTimedFn: (suspend (ip: String, domain: String, useTcpFallback: Boolean) -> Long?)? = null,
        onProgress: (BenchmarkProgress) -> Unit = {}
    ): List<ResolverBenchmarkMetrics> {
        val cleanDomain = domain.trim().trimEnd('.')
        val cleanIps = resolvers.asSequence()
            .mapNotNull { SoarResolverScanner.normalizeResolverIp(it) }
            .distinct()
            .toList()
        if (cleanIps.isEmpty() || cleanDomain.isEmpty()) return emptyList()

        val total = cleanIps.size
        val nextIdx = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val results = ConcurrentHashMap<String, ResolverBenchmarkMetrics>(total)

        val workerCount = workers.coerceIn(1, total)
        coroutineScope {
            repeat(workerCount) {
                launch(Dispatchers.IO) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val idx = nextIdx.getAndIncrement()
                        if (idx >= total) break
                        val ip = cleanIps[idx]

                        val rtts = ArrayList<Long?>(probesPerIp)
                        for (p in 0 until probesPerIp) {
                            coroutineContext.ensureActive()
                            val rtt = try {
                                if (probeTimedFn != null) {
                                    probeTimedFn.invoke(ip, cleanDomain, useTcpFallback)
                                } else {
                                    probeDnsZoneTimed(ip, cleanDomain, DEFAULT_PROBE_TIMEOUT_MS, useTcpFallback)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            rtts.add(rtt)
                        }

                        val metric = computeMetrics(ip, rtts, probesPerIp)
                        results[ip] = metric
                        val done = completed.incrementAndGet()
                        onProgress(BenchmarkProgress(done, total, metric))
                    }
                }
            }
        }

        return sortByQuality(results.values.toList())
    }

    fun probeDnsZoneTimed(
        ip: String,
        domain: String,
        timeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS,
        useTcpFallback: Boolean = false
    ): Long? {
        val txId = Random.nextInt(1, 65535)
        val queryName = "b${txId.toString(16)}.$domain"
        val query = SoarResolverScanner.buildDnsQueryPacket(txId, queryName, DNS_TYPE_TXT)

        val udpStartNs = System.nanoTime()
        val udpOk = runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val addr = InetAddress.getByName(ip)
                socket.send(DatagramPacket(query, query.size, addr, DNS_PORT))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                SoarResolverScanner.isValidDnsZoneResponse(buf, resp.length, txId)
            }
        }.getOrDefault(false)

        if (udpOk) {
            return ((System.nanoTime() - udpStartNs) / 1_000_000L).coerceAtLeast(1L)
        }
        if (!useTcpFallback) return null

        val tcpStartNs = System.nanoTime()
        val tcpOk = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, DNS_PORT), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()
                val framed = ByteArray(query.size + 2)
                framed[0] = ((query.size ushr 8) and 0xFF).toByte()
                framed[1] = (query.size and 0xFF).toByte()
                System.arraycopy(query, 0, framed, 2, query.size)
                out.write(framed)
                out.flush()

                val lenBuf = ByteArray(2)
                if (inp.read(lenBuf) != 2) return@use false
                val respLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                if (respLen < 12 || respLen > 4096) return@use false
                val respBuf = ByteArray(respLen)
                var readTotal = 0
                while (readTotal < respLen) {
                    val r = inp.read(respBuf, readTotal, respLen - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                SoarResolverScanner.isValidDnsZoneResponse(respBuf, readTotal, txId)
            }
        }.getOrDefault(false)

        return if (tcpOk) {
            ((System.nanoTime() - tcpStartNs) / 1_000_000L).coerceAtLeast(1L)
        } else {
            null
        }
    }
}
