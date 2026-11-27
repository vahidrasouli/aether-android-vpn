package io.aethervpn.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

enum class ScannerStatus(val label: String) {
    IDLE("Idle"),
    RUNNING("Scanning"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    STOPPED("Stopped")
}

data class ResolverScanState(
    val status: ScannerStatus = ScannerStatus.IDLE,
    val totalCount: Int = 0,
    val scannedCount: Int = 0,
    val healthyResolvers: List<String> = emptyList(),
    val rejectedCount: Int = 0,
    val targetDomain: String = "",
    val concurrency: Int = SoarResolverScanner.DEFAULT_CONCURRENCY,
    val errorMessage: String? = null
) {
    val healthyCount: Int get() = healthyResolvers.size
    val progressPercent: Int
        get() = if (totalCount <= 0) 0 else ((scannedCount * 100L) / totalCount).toInt().coerceIn(0, 100)
}

/**
 * High-speed coroutine DNS resolver scanner with lossless Pause/Resume gate.
 * Probes each candidate resolver against a Soar config's `domain` zone over UDP/53
 * (with optional TCP/53 fallback) and classifies Healthy vs Rejected resolvers.
 */
class SoarResolverScanner(
    private val scope: CoroutineScope,
    private val probeTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val probeFn: (suspend (ip: String, domain: String, useTcpFallback: Boolean) -> Boolean)? = null
) {
    private val _state = MutableStateFlow(ResolverScanState())
    val state: StateFlow<ResolverScanState> = _state.asStateFlow()

    private var scanJob: Job? = null

    @Volatile
    private var pauseGate: CompletableDeferred<Unit>? = null

    fun start(
        resolvers: List<String>,
        targetDomain: String,
        concurrency: Int = DEFAULT_CONCURRENCY,
        useTcpFallback: Boolean = false
    ) {
        val cleanDomain = targetDomain.trim().trimEnd('.')
        if (cleanDomain.isEmpty()) {
            _state.value = ResolverScanState(
                status = ScannerStatus.IDLE,
                errorMessage = "Select a Soar config with a valid domain"
            )
            return
        }

        val cleanList = resolvers.asSequence()
            .mapNotNull { normalizeResolverIp(it) }
            .distinct()
            .toList()

        if (cleanList.isEmpty()) {
            _state.value = ResolverScanState(
                status = ScannerStatus.IDLE,
                targetDomain = cleanDomain,
                errorMessage = "No valid resolver IPs to scan"
            )
            return
        }

        stop()

        val clampedConcurrency = clampConcurrency(concurrency)
        val workerCount = clampedConcurrency.coerceAtMost(cleanList.size)
        pauseGate = null

        _state.value = ResolverScanState(
            status = ScannerStatus.RUNNING,
            totalCount = cleanList.size,
            scannedCount = 0,
            healthyResolvers = emptyList(),
            rejectedCount = 0,
            targetDomain = cleanDomain,
            concurrency = clampedConcurrency,
            errorMessage = null
        )

        val nextIndex = AtomicInteger(0)
        val scannedCounter = AtomicInteger(0)
        val rejectedCounter = AtomicInteger(0)
        val lastEmitMs = AtomicLong(0L)
        val healthyLock = Any()
        val healthyBuffer = mutableListOf<String>()

        fun emitSnapshot(newStatus: ScannerStatus) {
            val healthyCopy = synchronized(healthyLock) { healthyBuffer.toList() }
            _state.value = _state.value.copy(
                status = newStatus,
                scannedCount = scannedCounter.get(),
                healthyResolvers = healthyCopy,
                rejectedCount = rejectedCounter.get()
            )
        }

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                coroutineScope {
                    repeat(workerCount) {
                        launch {
                            while (isActive) {
                                awaitIfPaused()
                                if (!isActive) break

                                val idx = nextIndex.getAndIncrement()
                                if (idx >= cleanList.size) break

                                val ip = cleanList[idx]
                                val isHealthy = if (probeFn != null) {
                                    runCatching { probeFn.invoke(ip, cleanDomain, useTcpFallback) }.getOrDefault(false)
                                } else {
                                    probeDnsZone(ip, cleanDomain, probeTimeoutMs, useTcpFallback)
                                }

                                if (isHealthy) {
                                    synchronized(healthyLock) { healthyBuffer.add(ip) }
                                } else {
                                    rejectedCounter.incrementAndGet()
                                }
                                val done = scannedCounter.incrementAndGet()

                                val now = System.currentTimeMillis()
                                val prev = lastEmitMs.get()
                                if (done == cleanList.size || (now - prev >= UI_EMIT_INTERVAL_MS && lastEmitMs.compareAndSet(prev, now))) {
                                    val currentStatus = if (pauseGate != null) ScannerStatus.PAUSED else ScannerStatus.RUNNING
                                    emitSnapshot(currentStatus)
                                }
                            }
                        }
                    }
                }
                if (isActive) {
                    emitSnapshot(ScannerStatus.COMPLETED)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Handled by stop() / reset()
            }
        }
    }

    fun pause() {
        val current = _state.value
        if (current.status != ScannerStatus.RUNNING) return
        if (pauseGate == null) {
            pauseGate = CompletableDeferred()
        }
        _state.value = current.copy(status = ScannerStatus.PAUSED)
    }

    fun resume() {
        val current = _state.value
        if (current.status != ScannerStatus.PAUSED) return
        val gate = pauseGate
        pauseGate = null
        _state.value = current.copy(status = ScannerStatus.RUNNING)
        gate?.complete(Unit)
    }

    fun stop() {
        val gate = pauseGate
        pauseGate = null
        gate?.complete(Unit)
        scanJob?.cancel()
        scanJob = null
        val current = _state.value
        if (current.status == ScannerStatus.RUNNING || current.status == ScannerStatus.PAUSED) {
            _state.value = current.copy(status = ScannerStatus.STOPPED)
        }
    }

    fun reset() {
        stop()
        _state.value = ResolverScanState()
    }

    private suspend fun awaitIfPaused() {
        val gate = pauseGate ?: return
        gate.await()
    }

    companion object {
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 100
        const val DEFAULT_CONCURRENCY = 25
        const val DEFAULT_TIMEOUT_MS = 2200
        private const val UI_EMIT_INTERVAL_MS = 80L
        private const val DNS_PORT = 53
        private const val DNS_TYPE_A = 1
        private const val DNS_TYPE_TXT = 16

        private val POISONED_IPS = setOf(
            "10.10.34.34",
            "10.10.34.35",
            "10.10.34.36",
            "127.0.0.1",
            "0.0.0.0"
        )

        fun clampConcurrency(value: Int): Int = value.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)

        fun parseResolverStream(input: InputStream): List<String> {
            return BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                parseResolverLines(lines)
            }
        }

        fun parseResolverLines(lines: Sequence<String>): List<String> {
            val out = LinkedHashSet<String>()
            for (rawLine in lines) {
                val noHash = rawLine.substringBefore('#')
                val noSlash = noHash.substringBefore("//")
                val trimmed = noSlash.trim()
                if (trimmed.isEmpty()) continue
                // Support comma/whitespace separated tokens on a line as well
                for (token in trimmed.split(',', ';', ' ', '\t')) {
                    val ip = normalizeResolverIp(token)
                    if (ip != null) {
                        out.add(ip)
                    }
                }
            }
            return out.toList()
        }

        fun normalizeResolverIp(raw: String): String? {
            var token = raw.trim()
            if (token.isEmpty()) return null
            // Strip optional :port for IPv4:port
            val colonIdx = token.indexOf(':')
            if (colonIdx > 0 && token.indexOf(':', colonIdx + 1) == -1) {
                val portStr = token.substring(colonIdx + 1)
                val port = portStr.toIntOrNull()
                if (port == null || port !in 1..65535) return null
                token = token.substring(0, colonIdx)
            }
            return if (isValidIpv4(token)) token else null
        }

        fun isValidIpv4(ip: String): Boolean {
            if (ip.length < 7 || ip.length > 15) return false
            var octets = 0
            var current = 0
            var digits = 0
            for (i in ip.indices) {
                val c = ip[i]
                if (c == '.') {
                    if (digits == 0 || current > 255) return false
                    octets++
                    if (octets > 3) return false
                    current = 0
                    digits = 0
                } else if (c in '0'..'9') {
                    current = current * 10 + (c - '0')
                    digits++
                    if (digits > 3 || current > 255) return false
                } else {
                    return false
                }
            }
            return octets == 3 && digits > 0 && current <= 255
        }

        fun buildDnsQueryPacket(txId: Int, domain: String, qType: Int = DNS_TYPE_TXT): ByteArray {
            val labels = domain.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
            val qnameSize = labels.sumOf { 1 + it.length } + 1
            val packet = ByteArray(12 + qnameSize + 4)

            // Header
            packet[0] = ((txId ushr 8) and 0xFF).toByte()
            packet[1] = (txId and 0xFF).toByte()
            packet[2] = 0x01 // RD = 1 (Recursion Desired)
            packet[3] = 0x00
            packet[4] = 0x00
            packet[5] = 0x01 // QDCOUNT = 1

            var offset = 12
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                val len = bytes.size.coerceAtMost(63)
                packet[offset++] = len.toByte()
                System.arraycopy(bytes, 0, packet, offset, len)
                offset += len
            }
            packet[offset++] = 0x00 // Root label

            // QTYPE
            packet[offset++] = ((qType ushr 8) and 0xFF).toByte()
            packet[offset++] = (qType and 0xFF).toByte()
            // QCLASS = IN (1)
            packet[offset++] = 0x00
            packet[offset] = 0x01

            return packet
        }

        fun isValidDnsZoneResponse(packet: ByteArray, length: Int, expectedTxId: Int): Boolean {
            if (length < 12 || length > packet.size) return false
            val rxId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
            if (rxId != (expectedTxId and 0xFFFF)) return false

            val flagsHi = packet[2].toInt() and 0xFF
            val flagsLo = packet[3].toInt() and 0xFF
            val isResponse = (flagsHi and 0x80) != 0
            if (!isResponse) return false

            val rcode = flagsLo and 0x0F
            // Accept only NOERROR (0); reject NXDOMAIN (3), SERVFAIL (2), REFUSED (5), FORMERR (1)
            if (rcode != 0) return false

            val anCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
            if (anCount > 0 && containsPoisonedARecord(packet, length)) {
                return false
            }
            return true
        }

        private fun containsPoisonedARecord(packet: ByteArray, length: Int): Boolean {
            val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
            val anCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
            var offset = 12

            // Skip Question section
            for (q in 0 until qdCount) {
                offset = skipDnsName(packet, offset, length)
                if (offset < 0 || offset + 4 > length) return false
                offset += 4
            }

            // Inspect Answer RRs for poisoned A records
            for (a in 0 until anCount) {
                offset = skipDnsName(packet, offset, length)
                if (offset < 0 || offset + 10 > length) return false
                val rrType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
                val rdLen = ((packet[offset + 8].toInt() and 0xFF) shl 8) or (packet[offset + 9].toInt() and 0xFF)
                offset += 10
                if (offset + rdLen > length) return false
                if (rrType == DNS_TYPE_A && rdLen == 4) {
                    val ip = "${packet[offset].toInt() and 0xFF}." +
                        "${packet[offset + 1].toInt() and 0xFF}." +
                        "${packet[offset + 2].toInt() and 0xFF}." +
                        "${packet[offset + 3].toInt() and 0xFF}"
                    if (ip in POISONED_IPS) return true
                }
                offset += rdLen
            }
            return false
        }

        private fun skipDnsName(packet: ByteArray, start: Int, length: Int): Int {
            var pos = start
            while (pos < length) {
                val len = packet[pos].toInt() and 0xFF
                if (len == 0) return pos + 1
                if ((len and 0xC0) == 0xC0) {
                    return if (pos + 2 <= length) pos + 2 else -1
                }
                pos += 1 + len
            }
            return -1
        }

        fun probeDnsZone(
            ip: String,
            domain: String,
            timeoutMs: Int = DEFAULT_TIMEOUT_MS,
            useTcpFallback: Boolean = false
        ): Boolean {
            val txId = Random.nextInt(1, 65535)
            val queryName = "p${txId.toString(16)}.$domain"
            val query = buildDnsQueryPacket(txId, queryName, DNS_TYPE_TXT)

            val udpOk = runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val addr = InetAddress.getByName(ip)
                    socket.send(DatagramPacket(query, query.size, addr, DNS_PORT))
                    val buf = ByteArray(1500)
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    isValidDnsZoneResponse(buf, resp.length, txId)
                }
            }.getOrDefault(false)

            if (udpOk || !useTcpFallback) return udpOk

            return runCatching {
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
                    isValidDnsZoneResponse(respBuf, readTotal, txId)
                }
            }.getOrDefault(false)
        }
    }
}
