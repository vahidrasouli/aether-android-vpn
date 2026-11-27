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

    fun generateYamlConfig(
        config: VpnConfig,
        tunIpv4: String = VpnConfig.TUN_IPV4_ADDRESS,
        socksPort: Int = config.effectiveSocksPort,
        mapDnsAddress: String = config.effectiveMapDnsAddress,
        forceMapDns: Boolean = false
    ): String {
        val ipv6Line = if (config.ipv6Enabled) {
            "  ipv6: '${VpnConfig.TUN_IPV6_ADDRESS}'\n"
        } else {
            ""
        }
        return buildString {
            append("tunnel:\n")
            append("  mtu: ").append(config.effectiveTunMtu).append('\n')
            append("  ipv4: '").append(tunIpv4).append("'\n")
            append(ipv6Line)
            append("\n")
            append("socks5:\n")
            append("  port: ").append(socksPort).append('\n')
            append("  address: '").append(VpnConfig.LOOPBACK_HOST).append("'\n")
            // hev-socks5-tunnel defaults `udp` to 'tcp' (proprietary 0x05 UDP-in-TCP) when omitted.
            // Psiphon's SOCKS5 server rejects 0x05 ("not 0x01 or 0x03"), so when Psiphon is final
            // egress we must explicitly specify `udp: 'udp'` (RFC 1928 0x03) to prevent 0x05 spam.
            val isPsiphonOrSingBoxEgress = if (config.singBoxEnabled) {
                config.chainMode.isPsiphonFinalEgress || config.chainMode.isSingBoxFinalEgress
            } else {
                config.psiphonMode == PsiphonMode.WARP_TO_PSIPHON || config.psiphonMode == PsiphonMode.PSIPHON_ONLY
            }
            if (!forceMapDns && (config.enableUdp || isPsiphonOrSingBoxEgress)) {
                append("  udp: 'udp'\n")
            }
            append("\n")
            if (forceMapDns || config.useMapDns) {
                append("mapdns:\n")
                append("  address: ").append(mapDnsAddress).append('\n')
                append("  port: 53\n")
                append("  network: ").append(config.effectiveMapDnsNetwork).append('\n')
                append("  netmask: ").append(config.effectiveMapDnsNetmask).append('\n')
                append("  cache-size: 10000\n")
                append("\n")
            }
            append("misc:\n")
            // Bounded coroutine stack & UDP session lifetime from Engine & Performance Lab
            append("  task-stack-size: ").append(config.taskStackSize).append('\n')
            append("  tcp-buffer-size: ").append(TCP_BUFFER_SIZE_BYTES).append('\n')
            append("  max-session-count: 512\n")
            append("  connect-timeout: ").append(CONNECT_TIMEOUT_MS).append('\n')
            append("  tcp-read-write-timeout: 300000\n")
            append("  udp-read-write-timeout: ").append(config.udpTimeoutMs).append('\n')
            append("  limit-nofile: 65535\n")
            append("  log-file: 'stderr'\n")
            append("  log-level: 'warn'\n")
        }
    }

    fun start(
        config: VpnConfig,
        tunFd: Int,
        tunIpv4: String = VpnConfig.TUN_IPV4_ADDRESS,
        socksPort: Int = config.effectiveSocksPort,
        mapDnsAddress: String = config.effectiveMapDnsAddress,
        forceMapDns: Boolean = false
    ): Result<Unit> {
        if (!TProxyService.ensureLoaded()) {
            val errDetails = TProxyService.loadError?.message
                ?: "Native library libhev-socks5-tunnel.so initialization failed"
            return Result.failure(
                IllegalStateException("HEV tunnel loading failed: $errDetails")
            )
        }
        stop()

        val yamlFile = File(cacheDir, "hev-tunnel.yml")
        yamlFile.writeText(
            generateYamlConfig(config, tunIpv4, socksPort, mapDnsAddress, forceMapDns),
            Charsets.UTF_8
        )
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

    companion object {
        const val TCP_BUFFER_SIZE_BYTES = 131072
        const val CONNECT_TIMEOUT_MS = 15000
    }
}
