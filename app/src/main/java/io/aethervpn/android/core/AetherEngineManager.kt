package io.aethervpn.android.core

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
        overrideProtocol: AetherProtocol? = null
    ): List<String> {
        val proto = overrideProtocol ?: config.protocol
        val stateDir = File(privateDataDir, "aether").apply { mkdirs() }
        val configFile = File(stateDir, "aether.toml")

        val args = mutableListOf(
            "--protocol", proto.cliProtocol,
            "--bind", config.socksBindAddress,
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

        return args
    }

    fun calculateStartupTimeoutMs(
        config: VpnConfig,
        overrideProtocol: AetherProtocol? = null
    ): Long {
        val proto = overrideProtocol ?: config.protocol
        return when {
            proto == AetherProtocol.GOOL ||
            proto == AetherProtocol.GOOL_CLASSIC ||
            proto == AetherProtocol.MIM -> 60_000L

            proto == AetherProtocol.MASQUE_H3 -> 55_000L
            config.scanMode != ScanMode.TURBO -> 55_000L
            else -> 35_000L
        }
    }

    suspend fun start(
        config: VpnConfig,
        protector: SocketProtectorCallback,
        overrideProtocol: AetherProtocol? = null,
        readinessTimeoutMs: Long? = null
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
        val cliArgs = buildCoreArguments(config, overrideProtocol)
        runCatching { android.system.Os.setenv("AETHER_ROUTE_SNIFF", "1", true) }
        DiagnosticsLog.record("Engine", "Starting Aether (${proto.label}) [budget: ${effectiveTimeoutMs / 1000}s]")

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
            if (isSocks5Ready(config.socksPort)) {
                return Result.success(jobId)
            }
            delay(backoffMs)
            backoffMs = (backoffMs + 100L).coerceAtMost(500L)
        }

        stop()
        return Result.failure(
            IllegalStateException("Timed out waiting for Aether SOCKS5 listener on ${config.socksBindAddress} after ${effectiveTimeoutMs / 1000}s")
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
}
