package io.aethervpn.android.core

import android.content.Context
import android.content.SharedPreferences
import java.net.InetAddress
import java.net.InetSocketAddress
import org.json.JSONArray
import org.json.JSONObject

enum class ProfileLevel(val label: String) {
    BATTERY_EFFICIENT("Battery Efficient"),
    BALANCED("Balanced"),
    ADVANCED("Advanced")
}

enum class AetherProtocol(val cliProtocol: String, val label: String, val usesMasque: Boolean) {
    MASQUE_H3("masque", "MASQUE (HTTP/3 QUIC)", true),
    MASQUE_H2("masque", "MASQUE (HTTP/2 TCP)", true),
    WIREGUARD("wg", "WireGuard (WARP)", false),
    GOOL("gool", "GOOL (WireGuard over MASQUE)", true),
    GOOL_CLASSIC("gool", "GOOL Classic (Nested WireGuard)", false),
    MIM("mim", "MIM (Nested MASQUE)", true)
}

enum class ScanMode(val cliValue: String, val label: String) {
    TURBO("turbo", "Turbo (Lowest battery)"),
    BALANCED("balanced", "Balanced"),
    THOROUGH("thorough", "Thorough"),
    VERIFIED("verified", "Verified / Stealth"),
    IRONCLAD("ironclad", "Ironclad (Deep verify)")
}

enum class IpFamily(val cliValue: String, val label: String) {
    IPV4("v4", "IPv4 Only (Stable Mobile)"),
    BOTH("both", "IPv4 + IPv6 (Dual-Stack)"),
    IPV6("v6", "IPv6 Only")
}

enum class PerfProfile(val cliValue: String, val label: String) {
    LOW("low", "Low (Mobile / Battery)"),
    MEDIUM("medium", "Medium (Balanced)"),
    HIGH("high", "High (Maximum Throughput)")
}

enum class HevMode(val label: String) {
    TCP_UDP("hev-socks5-tunnel (TCP + UDP)"),
    TCP_ONLY("hev-socks5-tunnel (TCP only)")
}

enum class PsiphonMode(val label: String, val cliValue: String) {
    OFF("Off (WARP Only)", "off"),
    WARP_TO_PSIPHON("WARP → Psiphon (Chain)", "chain"),
    PSIPHON_TO_WARP("Psiphon → WARP (Reverse)", "reverse"),
    PSIPHON_ONLY("Psiphon Only", "only")
}

enum class ChainMode(
    val label: String,
    val description: String,
    val tunTargetPort: Int,
    val startSingBoxFirst: Boolean,
    val requiresWarp: Boolean,
    val requiresPsiphon: Boolean,
    val isWarpFinalEgress: Boolean,
    val isPsiphonFinalEgress: Boolean,
    val isSingBoxFinalEgress: Boolean
) {
    SINGBOX_ONLY(
        label = "Sing-box Only",
        description = "TUN → Sing-box:1825",
        tunTargetPort = 1825,
        startSingBoxFirst = true,
        requiresWarp = false,
        requiresPsiphon = false,
        isWarpFinalEgress = false,
        isPsiphonFinalEgress = false,
        isSingBoxFinalEgress = true
    ),
    SINGBOX_TO_WARP(
        label = "Sing-box → WARP",
        description = "TUN → WARP:1819 --upstream socks5://127.0.0.1:1825",
        tunTargetPort = 1819,
        startSingBoxFirst = true,
        requiresWarp = true,
        requiresPsiphon = false,
        isWarpFinalEgress = true,
        isPsiphonFinalEgress = false,
        isSingBoxFinalEgress = false
    ),
    SINGBOX_TO_PSIPHON(
        label = "Sing-box → Psiphon",
        description = "TUN → Psiphon:1821 --upstream socks5://127.0.0.1:1825",
        tunTargetPort = 1821,
        startSingBoxFirst = true,
        requiresWarp = false,
        requiresPsiphon = true,
        isWarpFinalEgress = false,
        isPsiphonFinalEgress = true,
        isSingBoxFinalEgress = false
    ),
    SINGBOX_TO_WARP_TO_PSIPHON(
        label = "Sing-box → WARP → Psiphon",
        description = "TUN → Psiphon:1821 → WARP:1819 --upstream socks5://127.0.0.1:1825",
        tunTargetPort = 1821,
        startSingBoxFirst = true,
        requiresWarp = true,
        requiresPsiphon = true,
        isWarpFinalEgress = false,
        isPsiphonFinalEgress = true,
        isSingBoxFinalEgress = false
    ),
    SINGBOX_TO_PSIPHON_TO_WARP(
        label = "Sing-box → Psiphon → WARP",
        description = "TUN → WARP:1819 → Psiphon:1821 --upstream socks5://127.0.0.1:1825",
        tunTargetPort = 1819,
        startSingBoxFirst = true,
        requiresWarp = true,
        requiresPsiphon = true,
        isWarpFinalEgress = true,
        isPsiphonFinalEgress = false,
        isSingBoxFinalEgress = false
    ),
    WARP_TO_SINGBOX(
        label = "WARP → Sing-box",
        description = "TUN → Sing-box:1825 (detour → WARP socks 127.0.0.1:1819)",
        tunTargetPort = 1825,
        startSingBoxFirst = false,
        requiresWarp = true,
        requiresPsiphon = false,
        isWarpFinalEgress = false,
        isPsiphonFinalEgress = false,
        isSingBoxFinalEgress = true
    ),
    PSIPHON_TO_SINGBOX(
        label = "Psiphon → Sing-box",
        description = "TUN → Sing-box:1825 (detour → Psiphon socks 127.0.0.1:1821)",
        tunTargetPort = 1825,
        startSingBoxFirst = false,
        requiresWarp = false,
        requiresPsiphon = true,
        isWarpFinalEgress = false,
        isPsiphonFinalEgress = false,
        isSingBoxFinalEgress = true
    )
}

enum class PsiphonProtocol(val cliValue: String, val label: String) {
    AUTO("auto", "Auto (Best Available)"),
    FRONTED_MEEK_CDN_OSSH("FRONTED-MEEK-CDN-OSSH", "Fronted Meek CDN (OSSH)"),
    FRONTED_MEEK_OSSH("FRONTED-MEEK-OSSH", "Fronted Meek (OSSH)"),
    FRONTED_MEEK_CDN_HTTP_OSSH("FRONTED-MEEK-CDN-HTTP-OSSH", "Fronted Meek CDN HTTP (OSSH)"),
    FRONTED_MEEK_HTTP_OSSH("FRONTED-MEEK-HTTP-OSSH", "Fronted Meek HTTP (OSSH)"),
    UNFRONTED_MEEK_HTTPS_OSSH("UNFRONTED-MEEK-HTTPS-OSSH", "Unfronted Meek HTTPS (OSSH)"),
    UNFRONTED_MEEK_OSSH("UNFRONTED-MEEK-OSSH", "Unfronted Meek (OSSH)"),
    UNFRONTED_MEEK_SESSION_TICKET_OSSH("UNFRONTED-MEEK-SESSION-TICKET-OSSH", "Unfronted Meek Session Ticket"),
    OSSH("OSSH", "Direct OSSH"),
    TLS_OSSH("TLS-OSSH", "TLS OSSH"),
    SSH("SSH", "Direct SSH"),
    SHADOWSOCKS_OSSH("SHADOWSOCKS-OSSH", "Shadowsocks OSSH"),
    QUIC_OSSH("QUIC-OSSH", "QUIC OSSH"),
    FRONTED_MEEK_QUIC_OSSH("FRONTED-MEEK-QUIC-OSSH", "Fronted Meek QUIC (OSSH)"),
    FRONTED_MEEK_CDN_QUIC_OSSH("FRONTED-MEEK-CDN-QUIC-OSSH", "Fronted Meek CDN QUIC (OSSH)"),
    CONJURE_OSSH("CONJURE-OSSH", "Conjure OSSH"),
    INPROXY_WEBRTC_OSSH("INPROXY-WEBRTC-OSSH", "Inproxy WebRTC (OSSH)"),
    INPROXY_WEBRTC_TLS_OSSH("INPROXY-WEBRTC-TLS-OSSH", "Inproxy WebRTC (TLS-OSSH)"),
    INPROXY_WEBRTC_QUIC_OSSH("INPROXY-WEBRTC-QUIC-OSSH", "Inproxy WebRTC (QUIC-OSSH)"),
    INPROXY_WEBRTC_FRONTED_MEEK_OSSH("INPROXY-WEBRTC-FRONTED-MEEK-OSSH", "Inproxy WebRTC (Fronted Meek)"),
    INPROXY_WEBRTC_SHADOWSOCKS_OSSH("INPROXY-WEBRTC-SHADOWSOCKS-OSSH", "Inproxy WebRTC (Shadowsocks)")
}

enum class NoizeProfile(val cliValue: String, val label: String) {
    OFF("off", "Off (Best battery)"),
    LIGHT("light", "Light"),
    BALANCED("balanced", "Balanced"),
    GFW("gfw", "Aggressive / GFW")
}

enum class SplitTunnelMode(val label: String) {
    ALL_APPS("All Apps (Full VPN)"),
    ONLY_SELECTED("Only Selected Apps"),
    BYPASS_SELECTED("Bypass Selected Apps")
}

enum class AppThemeMode(val label: String) {
    SYSTEM("System Default"),
    DARK("Dark"),
    LIGHT("Light")
}

enum class EngineLabPreset(val label: String) {
    ECO("Eco (Battery)"),
    BALANCED("Balanced (Default)"),
    TURBO("Turbo (Media)"),
    CUSTOM("Custom")
}

enum class SingBoxTuningPreset(val label: String) {
    ECO("Eco (Battery)"),
    BALANCED("Balanced (Default)"),
    TURBO("Turbo (Speed)"),
    CUSTOM("Custom")
}

enum class SingBoxBlockQuicMode(val label: String) {
    AUTO_TCP_OUTBOUNDS("Auto (TCP Outbounds)"),
    ALWAYS("Always"),
    OFF("Off")
}

/**
 * Complete configuration model mapped 1:1 to verified CluvexStudio/Aether v2.4.0 capabilities.
 * Local SOCKS5 endpoint is permanently locked to loopback (127.0.0.1).
 */
data class VpnConfig(
    val profileLevel: ProfileLevel = ProfileLevel.BATTERY_EFFICIENT,
    val protocol: AetherProtocol = AetherProtocol.MASQUE_H3,
    val autoFallbackToH2: Boolean = true,
    val quickReconnect: Boolean = true,
    val scanMode: ScanMode = ScanMode.TURBO,
    val manualPeer: String = "",
    val exitLocation: String = "",
    val exitLocationSecs: Int = 60,
    val ipFamily: IpFamily = IpFamily.IPV4,
    val perfProfile: PerfProfile = PerfProfile.LOW,
    val hevMode: HevMode = HevMode.TCP_UDP,
    val customDns: String = "",
    val routeDirectRules: String = "",
    val routeBlockRules: String = "",
    val noizeProfile: NoizeProfile = NoizeProfile.OFF,
    val tlsFragmentation: Boolean = false,
    val tlsFragmentSize: Int = 64,
    val tlsFragmentDelayMs: Int = 10,
    val masqueSni: String = "",
    val echEnabled: Boolean = false,
    val tlsVerify: Boolean = false,
    val disableQuicV2: Boolean = false,
    val wgKeepaliveSecs: Int = 0,
    val upstreamProxy: String = "",
    val zeroTrustTeam: String = "",
    val psiphonMode: PsiphonMode = PsiphonMode.OFF,
    val psiphonRegion: String = "",
    val psiphonProtocol: PsiphonProtocol = PsiphonProtocol.AUTO,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val tunMtu: Int = DEFAULT_TUN_MTU,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val selectedPackages: Set<String> = emptySet(),
    val proxyBypassApps: Set<String> = emptySet(),
    val bypassIran: Boolean = false,
    val statsRefreshIntervalMs: Long = 5_000L,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val languageTag: String = "system",
    // Engine & Performance Lab tunables (defaults match Balanced)
    val aetherMaxClients: Int = 512,
    val aetherMasqueH2KeepaliveSecs: Int = 45,
    val aetherTcpKeepaliveSecs: Int = 60,
    val aetherHalfCloseSecs: Int = 30,
    val goMemLimit: String = "48MiB",
    val goMaxProcs: String = "2",
    val goGc: String = "50",
    val taskStackSize: Int = 81920,
    val udpTimeoutMs: Int = 5000,
    val mapDnsEnabled: Boolean = true,
    // Sing-box integration (Phase 1 & Phase 2 multi-hop chaining)
    val singBoxEnabled: Boolean = false,
    val singBoxOnly: Boolean = false,
    val chainMode: ChainMode = ChainMode.SINGBOX_ONLY,
    // Dedicated Sing-box Engine & Tuning Lab config (independent of Psiphon Go limits)
    val sbGoMemLimit: String = "96MiB",
    val sbGoMaxProcs: String = "Auto",
    val sbGoGc: String = "100",
    val sbRemoteDns: String = "1.1.1.1",
    val sbDirectDns: String = "1.1.1.1",
    val sbDnsStrategy: String = "ipv4_only",
    val sbSniffEnabled: Boolean = false,
    val sbBlockQuic: SingBoxBlockQuicMode = SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS,
    val sbBypassPrivateIp: Boolean = false,
    val sbBypassIrDomains: Boolean = false,
    val sbCustomBypassDomains: String = "",
    val sbMultiplexEnabled: Boolean = false,
    val sbConnectTimeoutSec: Int = 10,
    val sbTcpFastOpen: Boolean = false,
    val sbTlsFragment: Boolean = false,
    val sbByeDpiEnabled: Boolean = false,
    val sbByeDpiPreset: ByeDpiPreset = ByeDpiPreset.AUTO,
    val sbByeDpiLastWinner: ByeDpiPreset? = null,
    val sbLogLevel: String = "warn",
    val sbSpeedTestUrl: String = DEFAULT_SB_SPEED_TEST_URL
) {
    val isSingBoxOnlyActive: Boolean
        get() = singBoxEnabled && chainMode == ChainMode.SINGBOX_ONLY

    val isByeDpiActive: Boolean
        get() = sbByeDpiEnabled && isSingBoxOnlyActive

    val effectiveTunMtu: Int
        get() = if (isSingBoxOnlyActive) SING_BOX_ONLY_TUN_MTU else tunMtu

    val socksBindAddress: String
        get() = "$LOOPBACK_HOST:$socksPort"

    val effectiveSocksPort: Int
        get() = when {
            singBoxEnabled -> chainMode.tunTargetPort
            psiphonMode == PsiphonMode.WARP_TO_PSIPHON ||
                psiphonMode == PsiphonMode.PSIPHON_ONLY -> PSIPHON_SOCKS_PORT
            else -> DEFAULT_SOCKS_PORT
        }

    val effectiveSocksBindAddress: String
        get() = "$LOOPBACK_HOST:$effectiveSocksPort"

    val ipv6Enabled: Boolean
        get() = ipFamily == IpFamily.BOTH || ipFamily == IpFamily.IPV6

    val enableUdp: Boolean
        get() = hevMode == HevMode.TCP_UDP

    val useMapDns: Boolean
        get() = mapDnsEnabled && !bypassIran && customDns.isBlank()

    val effectiveMapDnsAddress: String
        get() = if (isSingBoxOnlyActive) SING_BOX_MAPDNS_ADDRESS else DNS_IPV4_MAPDNS

    val effectiveMapDnsNetwork: String
        get() = if (isSingBoxOnlyActive) SING_BOX_MAPDNS_NETWORK else MAPDNS_NETWORK

    val effectiveMapDnsNetmask: String
        get() = if (isSingBoxOnlyActive) SING_BOX_MAPDNS_NETMASK else MAPDNS_NETMASK

    fun detectEngineLabPreset(): EngineLabPreset = when {
        perfProfile == PerfProfile.LOW &&
            goMemLimit == "32MiB" &&
            goMaxProcs == "1" &&
            goGc == "25" &&
            aetherMaxClients == 256 &&
            aetherMasqueH2KeepaliveSecs == 60 &&
            aetherTcpKeepaliveSecs == 90 &&
            aetherHalfCloseSecs == 15 &&
            taskStackSize == 32768 &&
            udpTimeoutMs == 5000 -> EngineLabPreset.ECO

        perfProfile == PerfProfile.LOW &&
            goMemLimit == "48MiB" &&
            goMaxProcs == "2" &&
            goGc == "50" &&
            aetherMaxClients == 512 &&
            aetherMasqueH2KeepaliveSecs == 45 &&
            aetherTcpKeepaliveSecs == 60 &&
            aetherHalfCloseSecs == 30 &&
            taskStackSize == 81920 &&
            udpTimeoutMs == 5000 -> EngineLabPreset.BALANCED

        perfProfile == PerfProfile.MEDIUM &&
            goMemLimit == "64MiB" &&
            goMaxProcs == "4" &&
            goGc == "100" &&
            aetherMaxClients == 1024 &&
            aetherMasqueH2KeepaliveSecs == 30 &&
            aetherTcpKeepaliveSecs == 45 &&
            aetherHalfCloseSecs == 30 &&
            taskStackSize == 81920 &&
            udpTimeoutMs == 5000 -> EngineLabPreset.TURBO

        else -> EngineLabPreset.CUSTOM
    }

    fun applyEngineLabPreset(preset: EngineLabPreset): VpnConfig = when (preset) {
        EngineLabPreset.ECO -> copy(
            perfProfile = PerfProfile.LOW,
            goMemLimit = "32MiB",
            goMaxProcs = "1",
            goGc = "25",
            aetherMaxClients = 256,
            aetherMasqueH2KeepaliveSecs = 60,
            aetherTcpKeepaliveSecs = 90,
            aetherHalfCloseSecs = 15,
            taskStackSize = 32768,
            udpTimeoutMs = 5000
        )

        EngineLabPreset.BALANCED -> copy(
            perfProfile = PerfProfile.LOW,
            goMemLimit = "48MiB",
            goMaxProcs = "2",
            goGc = "50",
            aetherMaxClients = 512,
            aetherMasqueH2KeepaliveSecs = 45,
            aetherTcpKeepaliveSecs = 60,
            aetherHalfCloseSecs = 30,
            taskStackSize = 81920,
            udpTimeoutMs = 5000
        )

        EngineLabPreset.TURBO -> copy(
            perfProfile = PerfProfile.MEDIUM,
            goMemLimit = "64MiB",
            goMaxProcs = "4",
            goGc = "100",
            aetherMaxClients = 1024,
            aetherMasqueH2KeepaliveSecs = 30,
            aetherTcpKeepaliveSecs = 45,
            aetherHalfCloseSecs = 30,
            taskStackSize = 81920,
            udpTimeoutMs = 5000
        )

        EngineLabPreset.CUSTOM -> this
    }

    fun resetEngineLabDefaults(): VpnConfig = applyEngineLabPreset(EngineLabPreset.BALANCED).copy(
        tunMtu = DEFAULT_TUN_MTU,
        mapDnsEnabled = true
    )

    fun detectSingBoxTuningPreset(): SingBoxTuningPreset {
        val baseRoutingAndDnsDefaults =
            sbRemoteDns == "1.1.1.1" &&
                sbDirectDns == "1.1.1.1" &&
                !sbSniffEnabled &&
                !sbBypassPrivateIp &&
                !sbBypassIrDomains &&
                sbCustomBypassDomains.isBlank() &&
                !sbMultiplexEnabled
        if (!baseRoutingAndDnsDefaults) return SingBoxTuningPreset.CUSTOM

        return when {
            sbGoMemLimit == "48MiB" &&
                sbGoMaxProcs == "2" &&
                sbGoGc == "50" &&
                sbLogLevel == "error" &&
                sbBlockQuic == SingBoxBlockQuicMode.ALWAYS &&
                sbDnsStrategy == "ipv4_only" &&
                sbConnectTimeoutSec == 10 &&
                !sbTcpFastOpen -> SingBoxTuningPreset.ECO

            sbGoMemLimit == "96MiB" &&
                sbGoMaxProcs == "Auto" &&
                sbGoGc == "100" &&
                sbLogLevel == "warn" &&
                sbBlockQuic == SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS &&
                sbDnsStrategy == "ipv4_only" &&
                sbConnectTimeoutSec == 10 &&
                !sbTcpFastOpen -> SingBoxTuningPreset.BALANCED

            sbGoMemLimit == "Auto" &&
                sbGoMaxProcs == "Auto" &&
                sbGoGc == "100" &&
                sbLogLevel == "warn" &&
                sbBlockQuic == SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS &&
                sbDnsStrategy == "ipv4_only" &&
                sbConnectTimeoutSec == 10 &&
                sbTcpFastOpen -> SingBoxTuningPreset.TURBO

            else -> SingBoxTuningPreset.CUSTOM
        }
    }

    fun applySingBoxTuningPreset(preset: SingBoxTuningPreset): VpnConfig = when (preset) {
        SingBoxTuningPreset.ECO -> copy(
            sbGoMemLimit = "48MiB",
            sbGoMaxProcs = "2",
            sbGoGc = "50",
            sbRemoteDns = "1.1.1.1",
            sbDirectDns = "1.1.1.1",
            sbDnsStrategy = "ipv4_only",
            sbSniffEnabled = false,
            sbBlockQuic = SingBoxBlockQuicMode.ALWAYS,
            sbBypassPrivateIp = false,
            sbBypassIrDomains = false,
            sbCustomBypassDomains = "",
            sbMultiplexEnabled = false,
            sbConnectTimeoutSec = 10,
            sbTcpFastOpen = false,
            sbLogLevel = "error"
        )

        SingBoxTuningPreset.BALANCED -> copy(
            sbGoMemLimit = "96MiB",
            sbGoMaxProcs = "Auto",
            sbGoGc = "100",
            sbRemoteDns = "1.1.1.1",
            sbDirectDns = "1.1.1.1",
            sbDnsStrategy = "ipv4_only",
            sbSniffEnabled = false,
            sbBlockQuic = SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS,
            sbBypassPrivateIp = false,
            sbBypassIrDomains = false,
            sbCustomBypassDomains = "",
            sbMultiplexEnabled = false,
            sbConnectTimeoutSec = 10,
            sbTcpFastOpen = false,
            sbLogLevel = "warn"
        )

        SingBoxTuningPreset.TURBO -> copy(
            sbGoMemLimit = "Auto",
            sbGoMaxProcs = "Auto",
            sbGoGc = "100",
            sbRemoteDns = "1.1.1.1",
            sbDirectDns = "1.1.1.1",
            sbDnsStrategy = "ipv4_only",
            sbSniffEnabled = false,
            sbBlockQuic = SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS,
            sbBypassPrivateIp = false,
            sbBypassIrDomains = false,
            sbCustomBypassDomains = "",
            sbMultiplexEnabled = false,
            sbConnectTimeoutSec = 10,
            sbTcpFastOpen = true,
            sbLogLevel = "warn"
        )

        SingBoxTuningPreset.CUSTOM -> this
    }

    fun resetSingBoxTuningDefaults(): VpnConfig =
        applySingBoxTuningPreset(SingBoxTuningPreset.BALANCED).copy(
            sbSpeedTestUrl = DEFAULT_SB_SPEED_TEST_URL
        )

    fun effectiveSbSpeedTestUrl(): String =
        sbSpeedTestUrl.trim().ifBlank { DEFAULT_SB_SPEED_TEST_URL }

    val hasExitLocationPolicy: Boolean
        get() = exitLocation.isNotBlank() &&
            !exitLocation.equals("any", ignoreCase = true) &&
            !exitLocation.equals("off", ignoreCase = true)

    fun applyPreset(level: ProfileLevel): VpnConfig = when (level) {
        ProfileLevel.BATTERY_EFFICIENT -> copy(
            profileLevel = level,
            protocol = AetherProtocol.MASQUE_H3,
            autoFallbackToH2 = true,
            quickReconnect = true,
            scanMode = ScanMode.TURBO,
            ipFamily = IpFamily.IPV4,
            perfProfile = PerfProfile.LOW,
            hevMode = HevMode.TCP_ONLY,
            noizeProfile = NoizeProfile.OFF,
            tlsFragmentation = false,
            echEnabled = false,
            statsRefreshIntervalMs = 5_000L
        )

        ProfileLevel.BALANCED -> copy(
            profileLevel = level,
            protocol = AetherProtocol.MASQUE_H3,
            autoFallbackToH2 = true,
            quickReconnect = true,
            scanMode = ScanMode.BALANCED,
            ipFamily = IpFamily.IPV4,
            perfProfile = PerfProfile.LOW,
            hevMode = HevMode.TCP_UDP,
            noizeProfile = NoizeProfile.OFF,
            tlsFragmentation = false,
            echEnabled = false,
            statsRefreshIntervalMs = 3_000L
        )

        ProfileLevel.ADVANCED -> copy(
            profileLevel = level,
            perfProfile = PerfProfile.MEDIUM,
            hevMode = HevMode.TCP_UDP,
            statsRefreshIntervalMs = 3_000L
        )
    }

    /**
     * Exports non-confidential configuration to JSON for backup/restore.
     * Deliberately excludes upstream proxy credentials and never touches private WARP keys.
     */
    fun toSafeBackupJson(): String {
        val safeUpstream = if (upstreamProxy.contains("@")) "" else upstreamProxy.trim()
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("profileLevel", profileLevel.name)
            put("protocol", protocol.name)
            put("autoFallbackToH2", autoFallbackToH2)
            put("quickReconnect", quickReconnect)
            put("scanMode", scanMode.name)
            put("manualPeer", manualPeer)
            put("exitLocation", exitLocation.trim())
            put("exitLocationSecs", exitLocationSecs)
            put("ipFamily", ipFamily.name)
            put("perfProfile", perfProfile.name)
            put("hevMode", hevMode.name)
            put("customDns", customDns)
            put("routeDirectRules", routeDirectRules)
            put("routeBlockRules", routeBlockRules)
            put("noizeProfile", noizeProfile.name)
            put("tlsFragmentation", tlsFragmentation)
            put("tlsFragmentSize", tlsFragmentSize)
            put("tlsFragmentDelayMs", tlsFragmentDelayMs)
            put("masqueSni", masqueSni)
            put("echEnabled", echEnabled)
            put("tlsVerify", tlsVerify)
            put("disableQuicV2", disableQuicV2)
            put("wgKeepaliveSecs", wgKeepaliveSecs)
            put("upstreamProxy", safeUpstream)
            put("zeroTrustTeam", zeroTrustTeam)
            put("psiphonMode", psiphonMode.name)
            put("psiphonRegion", psiphonRegion.trim())
            put("psiphonProtocol", psiphonProtocol.name)
            put("splitTunnelMode", splitTunnelMode.name)
            put("selectedPackages", JSONArray(selectedPackages.sorted()))
            put("proxyBypassApps", JSONArray(proxyBypassApps.sorted()))
            put("bypassIran", bypassIran)
            put("statsRefreshIntervalMs", statsRefreshIntervalMs)
            put("themeMode", themeMode.name)
            put("languageTag", languageTag)
            put("tunMtu", tunMtu)
            put("aetherMaxClients", aetherMaxClients)
            put("aetherMasqueH2KeepaliveSecs", aetherMasqueH2KeepaliveSecs)
            put("aetherTcpKeepaliveSecs", aetherTcpKeepaliveSecs)
            put("aetherHalfCloseSecs", aetherHalfCloseSecs)
            put("goMemLimit", goMemLimit)
            put("goMaxProcs", goMaxProcs)
            put("goGc", goGc)
            put("taskStackSize", taskStackSize)
            put("udpTimeoutMs", udpTimeoutMs)
            put("mapDnsEnabled", mapDnsEnabled)
            put("singBoxEnabled", singBoxEnabled)
            put("singBoxOnly", chainMode == ChainMode.SINGBOX_ONLY)
            put("chainMode", chainMode.name)
            put("sbGoMemLimit", sbGoMemLimit)
            put("sbGoMaxProcs", sbGoMaxProcs)
            put("sbGoGc", sbGoGc)
            put("sbRemoteDns", sbRemoteDns)
            put("sbDirectDns", sbDirectDns)
            put("sbDnsStrategy", sbDnsStrategy)
            put("sbSniffEnabled", sbSniffEnabled)
            put("sbBlockQuic", sbBlockQuic.name)
            put("sbBypassPrivateIp", sbBypassPrivateIp)
            put("sbBypassIrDomains", sbBypassIrDomains)
            put("sbCustomBypassDomains", sbCustomBypassDomains)
            put("sbMultiplexEnabled", sbMultiplexEnabled)
            put("sbConnectTimeoutSec", sbConnectTimeoutSec)
            put("sbTcpFastOpen", sbTcpFastOpen)
            put("sbTlsFragment", sbTlsFragment)
            put("sbByeDpiEnabled", sbByeDpiEnabled)
            put("sbByeDpiPreset", sbByeDpiPreset.name)
            sbByeDpiLastWinner?.let { put("sbByeDpiLastWinner", it.name) }
            put("sbLogLevel", sbLogLevel)
            put("sbSpeedTestUrl", effectiveSbSpeedTestUrl())
        }.toString(2)
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        val LOOPBACK_INET4: InetAddress by lazy {
            try {
                InetAddress.getByAddress(LOOPBACK_HOST, byteArrayOf(127, 0, 0, 1))
            } catch (_: Exception) {
                InetAddress.getLoopbackAddress()
            }
        }

        fun loopbackSocketAddress(port: Int): InetSocketAddress {
            return InetSocketAddress(LOOPBACK_INET4, port)
        }

        const val DEFAULT_SOCKS_PORT = 1819
        const val PSIPHON_SOCKS_PORT = 1821
        const val SING_BOX_SOCKS_PORT = 1825
        const val BYEDPI_SOCKS_PORT = ByeDpiEngineManager.DEFAULT_PORT
        const val DEFAULT_TUN_MTU = 1280
        const val SING_BOX_ONLY_TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "172.19.0.1"
        const val TUN_IPV4_PREFIX = 30
        const val TUN_IPV6_ADDRESS = "fd00:ae7::1"
        const val TUN_IPV6_PREFIX = 126
        const val DNS_IPV4_MAPDNS = "172.19.0.2"
        const val MAPDNS_NETWORK = "240.0.0.0"
        const val MAPDNS_NETMASK = "240.0.0.0"
        const val SING_BOX_MAPDNS_ADDRESS = "198.18.0.2"
        const val SING_BOX_MAPDNS_NETWORK = "198.18.0.0"
        const val SING_BOX_MAPDNS_NETMASK = "255.254.0.0"
        const val SPEED_TEST_URL_500KB = "https://speed.cloudflare.com/__down?bytes=512000"
        const val DEFAULT_SB_SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=1048576"
        const val SPEED_TEST_URL_5MB = "https://speed.cloudflare.com/__down?bytes=5242880"

        val SPEED_TEST_URL_PRESETS: List<Pair<String, String>> = listOf(
            "500 KB" to SPEED_TEST_URL_500KB,
            "1 MB (Default)" to DEFAULT_SB_SPEED_TEST_URL,
            "5 MB" to SPEED_TEST_URL_5MB
        )
        const val DNS_IPV4_PRIMARY = "1.1.1.1"
        const val DNS_IPV4_SECONDARY = "1.0.0.1"
        const val DNS_IPV6_PRIMARY = "2606:4700:4700::1111"

        /**
         * Valid Psiphon country codes supported by Aether core (ISO 3166-1 alpha-2).
         * Empty code represents Auto (Best Available).
         */
        val PSIPHON_REGIONS = listOf(
            "" to "Auto (Best Available)",
            "AT" to "Austria (AT)",
            "AU" to "Australia (AU)",
            "BE" to "Belgium (BE)",
            "BG" to "Bulgaria (BG)",
            "CA" to "Canada (CA)",
            "CH" to "Switzerland (CH)",
            "CZ" to "Czech Republic (CZ)",
            "DE" to "Germany (DE)",
            "DK" to "Denmark (DK)",
            "ES" to "Spain (ES)",
            "FI" to "Finland (FI)",
            "FR" to "France (FR)",
            "GB" to "United Kingdom (GB)",
            "HU" to "Hungary (HU)",
            "IE" to "Ireland (IE)",
            "IN" to "India (IN)",
            "IT" to "Italy (IT)",
            "JP" to "Japan (JP)",
            "NL" to "Netherlands (NL)",
            "NO" to "Norway (NO)",
            "PL" to "Poland (PL)",
            "RO" to "Romania (RO)",
            "SE" to "Sweden (SE)",
            "SG" to "Singapore (SG)",
            "SK" to "Slovakia (SK)",
            "US" to "United States (US)"
        )

        private const val PREFS_NAME = "aether_vpn_config"

        fun fromBackupJson(rawJson: String): Result<VpnConfig> = runCatching {
            val o = JSONObject(rawJson)
            val pkgs = mutableSetOf<String>()
            val arr = o.optJSONArray("selectedPackages")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(pkgs::add)
                }
            }
            val proxyBypass = mutableSetOf<String>()
            val proxyBypassArr = o.optJSONArray("proxyBypassApps")
            if (proxyBypassArr != null) {
                for (i in 0 until proxyBypassArr.length()) {
                    proxyBypassArr.optString(i).takeIf { it.isNotBlank() }?.let(proxyBypass::add)
                }
            }
            VpnConfig(
                profileLevel = enumOrDefault(o.optString("profileLevel"), ProfileLevel.BATTERY_EFFICIENT),
                protocol = enumOrDefault(o.optString("protocol"), AetherProtocol.MASQUE_H3),
                autoFallbackToH2 = o.optBoolean("autoFallbackToH2", true),
                quickReconnect = o.optBoolean("quickReconnect", true),
                scanMode = enumOrDefault(o.optString("scanMode"), ScanMode.TURBO),
                manualPeer = o.optString("manualPeer", "").trim(),
                exitLocation = o.optString("exitLocation", "").trim(),
                exitLocationSecs = o.optInt("exitLocationSecs", 60).coerceIn(10, 600),
                ipFamily = enumOrDefault(o.optString("ipFamily"), IpFamily.IPV4),
                perfProfile = enumOrDefault(o.optString("perfProfile"), PerfProfile.LOW),
                hevMode = enumOrDefault(o.optString("hevMode"), HevMode.TCP_UDP),
                customDns = o.optString("customDns", "").trim(),
                routeDirectRules = o.optString("routeDirectRules", "").trim(),
                routeBlockRules = o.optString("routeBlockRules", "").trim(),
                noizeProfile = enumOrDefault(o.optString("noizeProfile"), NoizeProfile.OFF),
                tlsFragmentation = o.optBoolean("tlsFragmentation", false),
                tlsFragmentSize = o.optInt("tlsFragmentSize", 64).coerceIn(16, 1024),
                tlsFragmentDelayMs = o.optInt("tlsFragmentDelayMs", 10).coerceIn(0, 200),
                masqueSni = o.optString("masqueSni", "").trim(),
                echEnabled = o.optBoolean("echEnabled", false),
                tlsVerify = o.optBoolean("tlsVerify", false),
                disableQuicV2 = o.optBoolean("disableQuicV2", false),
                wgKeepaliveSecs = o.optInt("wgKeepaliveSecs", 0).coerceIn(0, 300),
                upstreamProxy = o.optString("upstreamProxy", "").trim(),
                zeroTrustTeam = o.optString("zeroTrustTeam", "").trim(),
                psiphonMode = enumOrDefault(o.optString("psiphonMode"), PsiphonMode.OFF),
                psiphonRegion = o.optString("psiphonRegion", "").trim(),
                psiphonProtocol = enumOrDefault(o.optString("psiphonProtocol"), PsiphonProtocol.AUTO),
                splitTunnelMode = parseSplitTunnelMode(o.optString("splitTunnelMode")),
                selectedPackages = pkgs,
                proxyBypassApps = proxyBypass,
                bypassIran = o.optBoolean("bypassIran", false),
                statsRefreshIntervalMs = o.optLong("statsRefreshIntervalMs", 5_000L).coerceIn(2_000L, 15_000L),
                themeMode = enumOrDefault(o.optString("themeMode"), AppThemeMode.DARK),
                languageTag = o.optString("languageTag", "system"),
                tunMtu = o.optInt("tunMtu", DEFAULT_TUN_MTU).coerceIn(1280, 1500),
                aetherMaxClients = o.optInt("aetherMaxClients", 512).coerceIn(64, 4096),
                aetherMasqueH2KeepaliveSecs = o.optInt("aetherMasqueH2KeepaliveSecs", 45).coerceIn(10, 300),
                aetherTcpKeepaliveSecs = o.optInt("aetherTcpKeepaliveSecs", 60).coerceIn(10, 300),
                aetherHalfCloseSecs = o.optInt("aetherHalfCloseSecs", 30).coerceIn(5, 300),
                goMemLimit = o.optString("goMemLimit", "48MiB").ifBlank { "48MiB" },
                goMaxProcs = o.optString("goMaxProcs", "2").ifBlank { "2" },
                goGc = o.optString("goGc", "50").ifBlank { "50" },
                taskStackSize = o.optInt("taskStackSize", 81920).coerceIn(8192, 131072),
                udpTimeoutMs = o.optInt("udpTimeoutMs", 5000).coerceIn(1000, 120_000),
                mapDnsEnabled = o.optBoolean("mapDnsEnabled", true),
                singBoxEnabled = o.optBoolean("singBoxEnabled", false),
                singBoxOnly = o.optBoolean("singBoxOnly", false),
                chainMode = enumOrDefault(o.optString("chainMode"), ChainMode.SINGBOX_ONLY),
                sbGoMemLimit = o.optString("sbGoMemLimit", "96MiB").ifBlank { "96MiB" },
                sbGoMaxProcs = o.optString("sbGoMaxProcs", "Auto").ifBlank { "Auto" },
                sbGoGc = o.optString("sbGoGc", "100").ifBlank { "100" },
                sbRemoteDns = o.optString("sbRemoteDns", "1.1.1.1").ifBlank { "1.1.1.1" },
                sbDirectDns = o.optString("sbDirectDns", "1.1.1.1").ifBlank { "1.1.1.1" },
                sbDnsStrategy = o.optString("sbDnsStrategy", "ipv4_only").ifBlank { "ipv4_only" },
                sbSniffEnabled = o.optBoolean("sbSniffEnabled", false),
                sbBlockQuic = enumOrDefault(o.optString("sbBlockQuic"), SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS),
                sbBypassPrivateIp = o.optBoolean("sbBypassPrivateIp", false),
                sbBypassIrDomains = o.optBoolean("sbBypassIrDomains", false),
                sbCustomBypassDomains = o.optString("sbCustomBypassDomains", "").trim(),
                sbMultiplexEnabled = o.optBoolean("sbMultiplexEnabled", false),
                sbConnectTimeoutSec = o.optInt("sbConnectTimeoutSec", 10).coerceIn(3, 60),
                sbTcpFastOpen = o.optBoolean("sbTcpFastOpen", false),
                sbTlsFragment = o.optBoolean("sbTlsFragment", false),
                sbByeDpiEnabled = o.optBoolean("sbByeDpiEnabled", false),
                sbByeDpiPreset = enumOrDefault(o.optString("sbByeDpiPreset"), ByeDpiPreset.AUTO),
                sbByeDpiLastWinner = o.optString("sbByeDpiLastWinner", "").takeIf { it.isNotBlank() }?.let {
                    runCatching { enumValueOf<ByeDpiPreset>(it) }.getOrNull()
                },
                sbLogLevel = o.optString("sbLogLevel", "warn").ifBlank { "warn" },
                sbSpeedTestUrl = o.optString("sbSpeedTestUrl", DEFAULT_SB_SPEED_TEST_URL)
                    .trim()
                    .ifBlank { DEFAULT_SB_SPEED_TEST_URL }
            )
        }

        fun load(context: Context): VpnConfig {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return VpnConfig(
                profileLevel = enumOrDefault(p.getString("profile_level", null), ProfileLevel.BATTERY_EFFICIENT),
                protocol = enumOrDefault(p.getString("protocol", null), AetherProtocol.MASQUE_H3),
                autoFallbackToH2 = p.getBoolean("auto_fallback_h2", true),
                quickReconnect = p.getBoolean("quick_reconnect", true),
                scanMode = enumOrDefault(p.getString("scan_mode", null), ScanMode.TURBO),
                manualPeer = p.getString("manual_peer", "").orEmpty(),
                exitLocation = p.getString("exit_location", "").orEmpty(),
                exitLocationSecs = p.getInt("exit_location_secs", 60),
                ipFamily = enumOrDefault(p.getString("ip_family", null), IpFamily.IPV4),
                perfProfile = enumOrDefault(p.getString("perf_profile", null), PerfProfile.LOW),
                hevMode = enumOrDefault(p.getString("hev_mode", null), HevMode.TCP_UDP),
                customDns = p.getString("custom_dns", "").orEmpty(),
                routeDirectRules = p.getString("route_direct", "").orEmpty(),
                routeBlockRules = p.getString("route_block", "").orEmpty(),
                noizeProfile = enumOrDefault(p.getString("noize_profile", null), NoizeProfile.OFF),
                tlsFragmentation = p.getBoolean("tls_fragmentation", false),
                tlsFragmentSize = p.getInt("tls_fragment_size", 64),
                tlsFragmentDelayMs = p.getInt("tls_fragment_delay", 10),
                masqueSni = p.getString("masque_sni", "").orEmpty(),
                echEnabled = p.getBoolean("ech_enabled", false),
                tlsVerify = p.getBoolean("tls_verify", false),
                disableQuicV2 = p.getBoolean("disable_quic_v2", false),
                wgKeepaliveSecs = p.getInt("wg_keepalive", 0),
                upstreamProxy = p.getString("upstream_proxy", "").orEmpty(),
                zeroTrustTeam = p.getString("zerotrust_team", "").orEmpty(),
                psiphonMode = enumOrDefault(p.getString("psiphon_mode", null), PsiphonMode.OFF),
                psiphonRegion = p.getString("psiphon_region", "").orEmpty(),
                psiphonProtocol = enumOrDefault(p.getString("psiphon_protocol", null), PsiphonProtocol.AUTO),
                splitTunnelMode = parseSplitTunnelMode(p.getString("split_mode", null)),
                selectedPackages = p.getStringSet("selected_packages", emptySet())?.toSet() ?: emptySet(),
                proxyBypassApps = p.getStringSet("proxy_bypass_apps", emptySet())?.toSet() ?: emptySet(),
                bypassIran = p.getBoolean("bypass_iran", false),
                statsRefreshIntervalMs = p.getLong("stats_refresh_ms", 5_000L),
                themeMode = enumOrDefault(p.getString("theme_mode", null), AppThemeMode.DARK),
                languageTag = p.getString("language_tag", "system").orEmpty(),
                tunMtu = p.getInt("tun_mtu", DEFAULT_TUN_MTU).coerceIn(1280, 1500),
                aetherMaxClients = p.getInt("aether_max_clients", 512),
                aetherMasqueH2KeepaliveSecs = p.getInt("aether_masque_h2_keepalive_secs", 45),
                aetherTcpKeepaliveSecs = p.getInt("aether_tcp_keepalive_secs", 60),
                aetherHalfCloseSecs = p.getInt("aether_half_close_secs", 30),
                goMemLimit = p.getString("go_mem_limit", "48MiB").orEmpty().ifBlank { "48MiB" },
                goMaxProcs = p.getString("go_max_procs", "2").orEmpty().ifBlank { "2" },
                goGc = p.getString("go_gc", "50").orEmpty().ifBlank { "50" },
                taskStackSize = p.getInt("task_stack_size", 81920),
                udpTimeoutMs = p.getInt("udp_timeout_ms", 5000),
                mapDnsEnabled = p.getBoolean("map_dns_enabled", true),
                singBoxEnabled = p.getBoolean("sing_box_enabled", false),
                singBoxOnly = p.getBoolean("sing_box_only", false),
                chainMode = enumOrDefault(p.getString("chain_mode", null), ChainMode.SINGBOX_ONLY),
                sbGoMemLimit = p.getString("sb_go_mem_limit", "96MiB").orEmpty().ifBlank { "96MiB" },
                sbGoMaxProcs = p.getString("sb_go_max_procs", "Auto").orEmpty().ifBlank { "Auto" },
                sbGoGc = p.getString("sb_go_gc", "100").orEmpty().ifBlank { "100" },
                sbRemoteDns = p.getString("sb_remote_dns", "1.1.1.1").orEmpty().ifBlank { "1.1.1.1" },
                sbDirectDns = p.getString("sb_direct_dns", "1.1.1.1").orEmpty().ifBlank { "1.1.1.1" },
                sbDnsStrategy = p.getString("sb_dns_strategy", "ipv4_only").orEmpty().ifBlank { "ipv4_only" },
                sbSniffEnabled = p.getBoolean("sb_sniff_enabled", false),
                sbBlockQuic = enumOrDefault(p.getString("sb_block_quic", null), SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS),
                sbBypassPrivateIp = p.getBoolean("sb_bypass_private_ip", false),
                sbBypassIrDomains = p.getBoolean("sb_bypass_ir_domains", false),
                sbCustomBypassDomains = p.getString("sb_custom_bypass_domains", "").orEmpty(),
                sbMultiplexEnabled = p.getBoolean("sb_multiplex_enabled", false),
                sbConnectTimeoutSec = p.getInt("sb_connect_timeout_sec", 10).coerceIn(3, 60),
                sbTcpFastOpen = p.getBoolean("sb_tcp_fast_open", false),
                sbTlsFragment = p.getBoolean("sb_tls_fragment", false),
                sbByeDpiEnabled = p.getBoolean("sb_byedpi_enabled", false),
                sbByeDpiPreset = enumOrDefault(p.getString("sb_byedpi_preset", null), ByeDpiPreset.AUTO),
                sbByeDpiLastWinner = p.getString("sb_byedpi_last_winner", null)?.takeIf { it.isNotBlank() }?.let {
                    runCatching { enumValueOf<ByeDpiPreset>(it) }.getOrNull()
                },
                sbLogLevel = p.getString("sb_log_level", "warn").orEmpty().ifBlank { "warn" },
                sbSpeedTestUrl = p.getString("sb_speed_test_url", DEFAULT_SB_SPEED_TEST_URL)
                    .orEmpty()
                    .trim()
                    .ifBlank { DEFAULT_SB_SPEED_TEST_URL }
            )
        }

        fun save(context: Context, config: VpnConfig) {
            val p: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            p.edit()
                .putString("profile_level", config.profileLevel.name)
                .putString("protocol", config.protocol.name)
                .putBoolean("auto_fallback_h2", config.autoFallbackToH2)
                .putBoolean("quick_reconnect", config.quickReconnect)
                .putString("scan_mode", config.scanMode.name)
                .putString("manual_peer", config.manualPeer.trim())
                .putString("exit_location", config.exitLocation.trim())
                .putInt("exit_location_secs", config.exitLocationSecs)
                .putString("ip_family", config.ipFamily.name)
                .putString("perf_profile", config.perfProfile.name)
                .putString("hev_mode", config.hevMode.name)
                .putString("custom_dns", config.customDns.trim())
                .putString("route_direct", config.routeDirectRules.trim())
                .putString("route_block", config.routeBlockRules.trim())
                .putString("noize_profile", config.noizeProfile.name)
                .putBoolean("tls_fragmentation", config.tlsFragmentation)
                .putInt("tls_fragment_size", config.tlsFragmentSize)
                .putInt("tls_fragment_delay", config.tlsFragmentDelayMs)
                .putString("masque_sni", config.masqueSni.trim())
                .putBoolean("ech_enabled", config.echEnabled)
                .putBoolean("tls_verify", config.tlsVerify)
                .putBoolean("disable_quic_v2", config.disableQuicV2)
                .putInt("wg_keepalive", config.wgKeepaliveSecs)
                .putString("upstream_proxy", config.upstreamProxy.trim())
                .putString("zerotrust_team", config.zeroTrustTeam.trim())
                .putString("psiphon_mode", config.psiphonMode.name)
                .putString("psiphon_region", config.psiphonRegion.trim())
                .putString("psiphon_protocol", config.psiphonProtocol.name)
                .putString("split_mode", config.splitTunnelMode.name)
                .putStringSet("selected_packages", config.selectedPackages)
                .putStringSet("proxy_bypass_apps", config.proxyBypassApps)
                .putBoolean("bypass_iran", config.bypassIran)
                .putLong("stats_refresh_ms", config.statsRefreshIntervalMs)
                .putString("theme_mode", config.themeMode.name)
                .putString("language_tag", config.languageTag)
                .putInt("tun_mtu", config.tunMtu)
                .putInt("aether_max_clients", config.aetherMaxClients)
                .putInt("aether_masque_h2_keepalive_secs", config.aetherMasqueH2KeepaliveSecs)
                .putInt("aether_tcp_keepalive_secs", config.aetherTcpKeepaliveSecs)
                .putInt("aether_half_close_secs", config.aetherHalfCloseSecs)
                .putString("go_mem_limit", config.goMemLimit)
                .putString("go_max_procs", config.goMaxProcs)
                .putString("go_gc", config.goGc)
                .putInt("task_stack_size", config.taskStackSize)
                .putInt("udp_timeout_ms", config.udpTimeoutMs)
                .putBoolean("map_dns_enabled", config.mapDnsEnabled)
                .putBoolean("sing_box_enabled", config.singBoxEnabled)
                .putBoolean("sing_box_only", config.chainMode == ChainMode.SINGBOX_ONLY)
                .putString("chain_mode", config.chainMode.name)
                .putString("sb_go_mem_limit", config.sbGoMemLimit)
                .putString("sb_go_max_procs", config.sbGoMaxProcs)
                .putString("sb_go_gc", config.sbGoGc)
                .putString("sb_remote_dns", config.sbRemoteDns)
                .putString("sb_direct_dns", config.sbDirectDns)
                .putString("sb_dns_strategy", config.sbDnsStrategy)
                .putBoolean("sb_sniff_enabled", config.sbSniffEnabled)
                .putString("sb_block_quic", config.sbBlockQuic.name)
                .putBoolean("sb_bypass_private_ip", config.sbBypassPrivateIp)
                .putBoolean("sb_bypass_ir_domains", config.sbBypassIrDomains)
                .putString("sb_custom_bypass_domains", config.sbCustomBypassDomains.trim())
                .putBoolean("sb_multiplex_enabled", config.sbMultiplexEnabled)
                .putInt("sb_connect_timeout_sec", config.sbConnectTimeoutSec)
                .putBoolean("sb_tcp_fast_open", config.sbTcpFastOpen)
                .putBoolean("sb_tls_fragment", config.sbTlsFragment)
                .putBoolean("sb_byedpi_enabled", config.sbByeDpiEnabled)
                .putString("sb_byedpi_preset", config.sbByeDpiPreset.name)
                .putString("sb_byedpi_last_winner", config.sbByeDpiLastWinner?.name)
                .putString("sb_log_level", config.sbLogLevel)
                .putString("sb_speed_test_url", config.effectiveSbSpeedTestUrl())
                .apply()
        }

        fun parseSplitTunnelMode(raw: String?): SplitTunnelMode = when (raw?.trim()) {
            "ONLY_SELECTED", "INCLUDE_ONLY_SELECTED" -> SplitTunnelMode.ONLY_SELECTED
            "BYPASS_SELECTED", "EXCLUDE_SELECTED" -> SplitTunnelMode.BYPASS_SELECTED
            else -> SplitTunnelMode.ALL_APPS
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T {
            if (raw.isNullOrBlank()) return fallback
            return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
        }
    }
}
