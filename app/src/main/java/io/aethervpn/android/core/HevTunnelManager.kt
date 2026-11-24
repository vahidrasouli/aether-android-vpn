package io.aethervpn.android.core

import hev.htproxy.TProxyService
import java.io.File

/**
 * Manages a single `hev-socks5-tunnel` instance via JNI (`libhev-socks5-tunnel.so`).
 *
 * Uses bounded packet buffers, bounded stack size, and bounded session count to keep
 * resident memory low on Android devices while supporting full dual-stack TCP + UDP (DNS).
 */
class HevTunnelManager(private val cacheDir: File) {

    @Volatile
    private var running: Boolean = false

    private var activeConfigFile: File? = null

    fun generateYamlConfig(config: VpnConfig): String {
        val ipv6Line = if (config.ipv6Enabled) {
            "  ipv6: '${VpnConfig.TUN_IPV6_ADDRESS}'\n"
        } else {
            ""
        }
        return buildString {
            append("tunnel:\n")
            append("  mtu: ").append(config.tunMtu).append('\n')
            append("  ipv4: '").append(VpnConfig.TUN_IPV4_ADDRESS).append("'\n")
            append(ipv6Line)
            append("\n")
            append("socks5:\n")
            append("  port: ").append(config.effectiveSocksPort).append('\n')
            append("  address: '").append(VpnConfig.LOOPBACK_HOST).append("'\n")
            // RFC 1928 UDP ASSOCIATE - omitted in TCP-only mode to conserve battery & RAM.
            // Also omitted when Psiphon is final egress (WARP -> Psiphon or Psiphon Only) since Psiphon is TCP-only.
            val isPsiphonEgress = config.psiphonMode == PsiphonMode.WARP_TO_PSIPHON || config.psiphonMode == PsiphonMode.PSIPHON_ONLY
            if (config.enableUdp && !isPsiphonEgress) {
                append("  udp: 'udp'\n")
            }
            append("\n")
            append("misc:\n")
            // Low-memory tuning per upstream hev-socks5-tunnel mobile guidelines
            append("  task-stack-size: 20480\n")
            append("  tcp-buffer-size: 65536\n")
            append("  max-session-count: 512\n")
            append("  connect-timeout: 5000\n")
            append("  read-write-timeout: 60000\n")
            append("  log-file: 'stderr'\n")
            append("  log-level: 'warn'\n")
        }
    }

    fun start(config: VpnConfig, tunFd: Int): Result<Unit> {
        if (!TProxyService.ensureLoaded()) {
            val errDetails = TProxyService.loadError?.message
                ?: "Native library libhev-socks5-tunnel.so initialization failed"
            return Result.failure(
                IllegalStateException("HEV tunnel loading failed: $errDetails")
            )
        }
        stop()

        val yamlFile = File(cacheDir, "hev-tunnel.yml")
        yamlFile.writeText(generateYamlConfig(config), Charsets.UTF_8)
        activeConfigFile = yamlFile

        val started = TProxyService.TProxyStartService(yamlFile.absolutePath, tunFd)
        if (!started) {
            yamlFile.delete()
            activeConfigFile = null
            return Result.failure(IllegalStateException("hev-socks5-tunnel failed to start"))
        }

        running = true
        return Result.success(Unit)
    }

    fun isRunning(): Boolean {
        if (!running || !TProxyService.ensureLoaded()) return false
        return runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)
    }

    /** On-demand atomic stats snapshot; call only when UI is visible. */
    fun readStats(): TrafficStats {
        if (!running || !TProxyService.ensureLoaded()) return TrafficStats()
        val raw = runCatching { TProxyService.TProxyGetStats() }.getOrNull() ?: return TrafficStats()
        if (raw.size < 4) return TrafficStats()
        return TrafficStats(
            txPackets = raw[0],
            txBytes = raw[1],
            rxPackets = raw[2],
            rxBytes = raw[3]
        )
    }

    fun stop() {
        if (running && TProxyService.ensureLoaded()) {
            runCatching { TProxyService.TProxyStopService() }
        }
        running = false
        activeConfigFile?.delete()
        activeConfigFile = null
    }
}
