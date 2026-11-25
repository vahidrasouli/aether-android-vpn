package io.aethervpn.android.core

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manages a single native CluvexStudio/Aether Rust engine instance via `libaether_jni.so`.
 *
 * Maps verified VpnConfig options directly to upstream Aether v2.4.0 CLI flags without
 * enabling unnecessary background tasks, continuous endpoint scans, or Tor/Psiphon bloat.
 */
class AetherEngineManager(private val privateDataDir: File) {

    @Volatile
    private var activeJobId: Long = -1L

    fun buildCoreArguments(
        config: VpnConfig,
        overrideProtocol: AetherProtocol? = null,
        psiphonBinaryPath: String? = null
    ): List<String> {
        val proto = overrideProtocol ?: config.protocol
        val stateDir = File(privateDataDir, "aether").apply { mkdirs() }
        val configFile = File(stateDir, "aether.toml")

        // Strict Resource Isolation: in Psiphon Only mode, skip all WARP/MASQUE flags
        // (protocol, gateway scan, netstack/IP/perf, MASQUE SNI/ECH/fragment, WireGuard keepalive)
        if (config.psiphonMode == PsiphonMode.PSIPHON_ONLY) {
            val psiphonOnlyArgs = mutableListOf(
                "--bind", "${VpnConfig.LOOPBACK_HOST}:${VpnConfig.PSIPHON_SOCKS_PORT}",
                "--config", configFile.absolutePath,
                "--log-level", "info",
                "--psiphon-only"
            )
            if (config.upstreamProxy.isNotBlank()) {
                psiphonOnlyArgs += "--upstream"; psiphonOnlyArgs += config.upstreamProxy
            }
            if (config.psiphonRegion.isNotBlank() && !config.psiphonRegion.equals("auto", ignoreCase = true)) {
                psiphonOnlyArgs += "--psiphon-region"
                psiphonOnlyArgs += config.psiphonRegion.trim().uppercase()
            }
            if (!psiphonBinaryPath.isNullOrBlank()) {
                psiphonOnlyArgs += "--psiphon-bin"
                psiphonOnlyArgs += psiphonBinaryPath
            }
            return psiphonOnlyArgs
        }

        val bindAddress = config.socksBindAddress

        val args = mutableListOf(
            "--protocol", proto.cliProtocol,
            "--bind", bindAddress,
            "--config", configFile.absolutePath,
            "--scan", config.scanMode.cliValue,
            "--ip", config.ipFamily.cliValue,
            "--perf", config.perfProfile.cliValue,
            "--log-level", "info"
        )

        if (config.quickReconnect) {
            args += "--quick-reconnect"
        } else {
            args += "--no-quick-reconnect"
        }

        when (proto) {
            AetherProtocol.MASQUE_H3 -> args += "--h3"
            AetherProtocol.MASQUE_H2 -> args += "--h2"
            AetherProtocol.GOOL_CLASSIC -> args += "--gool-classic"
            else -> Unit
        }

        if (config.hasExitLocationPolicy) {
            args += "--exit-loc"
            args += config.exitLocation.trim()
            if (config.exitLocationSecs > 0) {
                args += "--exit-loc-secs"
                args += config.exitLocationSecs.toString()
            }
        }

        if (config.manualPeer.isNotBlank()) {
            when (proto) {
                AetherProtocol.WIREGUARD -> {
                    args += "--wg-peer"; args += config.manualPeer
                }
                AetherProtocol.MASQUE_H2 -> {
                    args += "--h2-peer"; args += config.manualPeer
                }
                else -> {
                    args += "--peer"; args += config.manualPeer
                }
            }
        }

        if (config.customDns.isNotBlank()) {
            args += "--dns"; args += config.customDns
        }

        if (config.routeDirectRules.isNotBlank()) {
            args += "--route-direct"; args += config.routeDirectRules
        }
        if (config.routeBlockRules.isNotBlank()) {
            args += "--route-block"; args += config.routeBlockRules
        }

        if (config.noizeProfile != NoizeProfile.OFF) {
            args += "--noize"; args += config.noizeProfile.cliValue
        }

        if (proto.usesMasque) {
            if (config.tlsFragmentation && proto == AetherProtocol.MASQUE_H2) {
                args += "--fragment"
                args += "--fragment-size"; args += config.tlsFragmentSize.toString()
                args += "--fragment-delay"; args += config.tlsFragmentDelayMs.toString()
            }
            if (config.masqueSni.isNotBlank()) {
                args += "--masque-sni"; args += config.masqueSni
            }
            if (config.echEnabled) {
                args += "--ech"; args += "auto"
            }
            if (config.tlsVerify) {
                args += "--tls-verify"
            }
            if (config.disableQuicV2 && proto == AetherProtocol.MASQUE_H3) {
                args += "--no-quic-v2"
            }
        }

        if (config.wgKeepaliveSecs > 0 &&
            (proto == AetherProtocol.WIREGUARD || proto == AetherProtocol.GOOL || proto == AetherProtocol.GOOL_CLASSIC)
        ) {
            args += "--keepalive"; args += config.wgKeepaliveSecs.toString()
        }

        if (config.upstreamProxy.isNotBlank()) {
            args += "--upstream"; args += config.upstreamProxy
        }

        if (config.zeroTrustTeam.isNotBlank()) {
            args += "--team"; args += config.zeroTrustTeam
        }

        if (config.psiphonMode != PsiphonMode.OFF) {
            when (config.psiphonMode) {
                PsiphonMode.WARP_TO_PSIPHON -> {
                    args += "--psiphon"
                    args += "--psiphon-bind"
                    args += "${VpnConfig.LOOPBACK_HOST}:${VpnConfig.PSIPHON_SOCKS_PORT}"
                }
                PsiphonMode.PSIPHON_TO_WARP -> {
                    args += "--psiphon-reverse"
                }
                PsiphonMode.PSIPHON_ONLY -> {
                    args += "--psiphon-only"
                }
                PsiphonMode.OFF -> Unit
            }

            if (config.psiphonRegion.isNotBlank() && !config.psiphonRegion.equals("auto", ignoreCase = true)) {
                args += "--psiphon-region"
                args += config.psiphonRegion.trim().uppercase()
            }

            if (!psiphonBinaryPath.isNullOrBlank()) {
                args += "--psiphon-bin"
                args += psiphonBinaryPath
            }
        }

        return args
    }

    fun calculateStartupTimeoutMs(
        config: VpnConfig,
        overrideProtocol: AetherProtocol? = null
    ): Long {
        val proto = overrideProtocol ?: config.protocol
        val baseTimeoutMs = when {
            // Thorough scan sweeps entire subnets (upstream Aether 300s budget + 10s margin)
            config.scanMode == ScanMode.THOROUGH -> 310_000L
            // Ironclad scan has 180s budget + 10s margin
            config.scanMode == ScanMode.IRONCLAD -> 190_000L
            // Balanced scan has 120s budget + 10s margin
            config.scanMode == ScanMode.BALANCED -> 130_000L
            // Verified scan has 60s budget + 10s margin
            config.scanMode == ScanMode.VERIFIED -> 70_000L

            proto == AetherProtocol.GOOL ||
            proto == AetherProtocol.GOOL_CLASSIC ||
            proto == AetherProtocol.MIM -> 60_000L

            // MASQUE H2 & H3 Turbo scans both have a 45s budget; give 55s (45s + 10s margin)
            proto == AetherProtocol.MASQUE_H3 ||
            proto == AetherProtocol.MASQUE_H2 -> 55_000L

            else -> 35_000L
        }
        return when (config.psiphonMode) {
            // Psiphon needs extra time for initial Meek/CDN dial, tactics fetch, and (for PSIPHON_TO_WARP) WARP chaining
            PsiphonMode.PSIPHON_ONLY -> maxOf(baseTimeoutMs, 120_000L)
            PsiphonMode.PSIPHON_TO_WARP -> maxOf(baseTimeoutMs, 150_000L)
            PsiphonMode.WARP_TO_PSIPHON,
            PsiphonMode.OFF -> baseTimeoutMs
        }
    }

    suspend fun start(
        config: VpnConfig,
        protector: SocketProtectorCallback,
        overrideProtocol: AetherProtocol? = null,
        readinessTimeoutMs: Long? = null,
        psiphonBinaryPath: String? = null
    ): Result<Long> {
        if (!AetherNative.ensureLoaded()) {
            val errDetails = AetherNative.diagnosticInfo
                ?: AetherNative.loadError?.message
                ?: "Native library initialization failed"
            return Result.failure(
                IllegalStateException("Native library loading failed: $errDetails")
            )
        }

        stop()

        if (!AetherNative.nativeRegisterProtector(protector)) {
            return Result.failure(IllegalStateException("Failed to register VpnService socket protector"))
        }

        val effectiveTimeoutMs = readinessTimeoutMs ?: calculateStartupTimeoutMs(config, overrideProtocol)
        val proto = overrideProtocol ?: config.protocol
        val cliArgs = buildCoreArguments(config, overrideProtocol, psiphonBinaryPath)

        // Mobile runtime tuning for Rust engine (allow cellular radio idle, low memory footprint)
        runCatching {
            android.system.Os.setenv("AETHER_ROUTE_SNIFF", "1", true)
            android.system.Os.setenv("AETHER_MAX_CLIENTS", MAX_CLIENTS.toString(), true)
            android.system.Os.setenv("AETHER_HALF_CLOSE_SECS", "30", true)
            android.system.Os.setenv("AETHER_TCP_KEEPALIVE_SECS", "60", true)
            if (config.psiphonMode != PsiphonMode.PSIPHON_ONLY) {
                android.system.Os.setenv("AETHER_MASQUE_H2_KEEPALIVE_SECS", "45", true)
            } else {
                android.system.Os.unsetenv("AETHER_MASQUE_H2_KEEPALIVE_SECS")
            }
        }

        // Apply mobile Go runtime caps when Psiphon is enabled to prevent Android LMK kills
        if (config.psiphonMode != PsiphonMode.OFF) {
            runCatching {
                android.system.Os.setenv("GOMEMLIMIT", "64MiB", true)
                android.system.Os.setenv("GOGC", "50", true)
                android.system.Os.setenv("GOMAXPROCS", "2", true)
            }
        } else {
            runCatching {
                android.system.Os.unsetenv("GOMEMLIMIT")
                android.system.Os.unsetenv("GOGC")
                android.system.Os.unsetenv("GOMAXPROCS")
            }
        }

        val logStartCount = DiagnosticsLog.entries().size
        DiagnosticsLog.record("Aether", "Starting Aether (${proto.label}) [budget: ${effectiveTimeoutMs / 1000}s, egress port: ${config.effectiveSocksPort}]")

        val rawReply = AetherNative.nativeStartCore(JSONArray(cliArgs).toString())
        val parsed = runCatching { JSONObject(rawReply) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Invalid native response from Aether core"))

        if (!parsed.optBoolean("ok", false)) {
            val err = parsed.optString("error", "Unknown Aether startup error")
            return Result.failure(IllegalStateException(err))
        }

        val jobId = parsed.optLong("job", -1L)
        if (jobId <= 0L) {
            return Result.failure(IllegalStateException("Aether core returned invalid job id"))
        }
        activeJobId = jobId

        val deadline = System.currentTimeMillis() + effectiveTimeoutMs
        var backoffMs = 150L
        while (System.currentTimeMillis() < deadline) {
            val exitError = pollIfExited(jobId)
            if (exitError != null) {
                stop()
                return Result.failure(IllegalStateException("Aether engine exited: $exitError"))
            }
            if (isEndpointReady(config, logStartCount)) {
                return Result.success(jobId)
            }
            delay(backoffMs)
            backoffMs = (backoffMs + 100L).coerceAtMost(500L)
        }

        stop()
        return Result.failure(
            IllegalStateException("Timed out waiting for Aether SOCKS5 listener on 127.0.0.1:${config.effectiveSocksPort} after ${effectiveTimeoutMs / 1000}s")
        )
    }

    fun isRunning(): Boolean {
        val jobId = activeJobId
        if (jobId <= 0L) return false
        return pollIfExited(jobId) == null
    }

    fun stop() {
        val jobId = activeJobId
        activeJobId = -1L
        if (jobId > 0L && AetherNative.ensureLoaded()) {
            runCatching { AetherNative.nativeCancelJob(jobId) }
            runCatching { AetherNative.nativeFreeJob(jobId) }
        }
        if (AetherNative.ensureLoaded()) {
            runCatching { AetherNative.nativeRegisterProtector(null) }
        }
        // Clean up Go runtime caps on stop to ensure zero lingering overhead
        runCatching {
            android.system.Os.unsetenv("GOMEMLIMIT")
            android.system.Os.unsetenv("GOGC")
            android.system.Os.unsetenv("GOMAXPROCS")
        }
    }

    private fun pollIfExited(jobId: Long): String? {
        val raw = runCatching { AetherNative.nativePollJob(jobId) }.getOrNull() ?: return "poll failed"
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return "malformed poll json"
        if (!obj.optBoolean("ok", false)) {
            return obj.optString("error", "job error")
        }
        if (obj.optString("state") == "done") {
            val res = obj.optJSONObject("result")
            return res?.optString("error")?.takeIf { it.isNotBlank() }
                ?: res?.optString("state")?.takeIf { it.isNotBlank() }
                ?: "stopped"
        }
        return null
    }

    /**
     * Checks readiness without spamming raw TCP/SOCKS5 connect-and-close probes against
     * Psiphon's SOCKS5 port (1821), which triggers benign `socks5ReadCommand: EOF` noise.
     */
    internal fun isEndpointReady(
        config: VpnConfig,
        logStartIndex: Int,
        logEntriesProvider: () -> List<String> = { DiagnosticsLog.entries() },
        socksProbe: (Int) -> Boolean = ::isSocks5Ready
    ): Boolean {
        val usesPsiphonEgress = config.psiphonMode == PsiphonMode.WARP_TO_PSIPHON ||
            config.psiphonMode == PsiphonMode.PSIPHON_ONLY
        if (usesPsiphonEgress) {
            val entries = logEntriesProvider()
            return entries.drop(logStartIndex).any { it.contains("psiphon is ready", ignoreCase = true) }
        }
        return socksProbe(config.effectiveSocksPort)
    }

    private fun isSocks5Ready(port: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.soTimeout = 400
                sock.connect(VpnConfig.loopbackSocketAddress(port), 400)
                val out = sock.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val inStream = sock.getInputStream()
                val response = ByteArray(2)
                val read = inStream.read(response)
                read == 2 && response[0] == 0x05.toByte() && response[1] == 0x00.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val MAX_CLIENTS = 512

        /**
         * Resolves external Psiphon client binary on Android filesystem.
         * On Android 10+ (API 29+), non-root binaries must reside in nativeLibraryDir
         * or have explicit execution permissions.
         */
        fun findPsiphonBinary(context: Context): File? {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val candidates = listOf(
                File(nativeLibDir, "libpsiphon-tunnel-core.so"),
                File(nativeLibDir, "psiphon-tunnel-core"),
                File(context.filesDir, "psiphon-tunnel-core"),
                File("/data/local/tmp/psiphon-tunnel-core")
            )
            return candidates.firstOrNull { it.exists() && it.canExecute() }
        }
    }
}
