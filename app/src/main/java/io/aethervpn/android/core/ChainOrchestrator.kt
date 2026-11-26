package io.aethervpn.android.core

/**
 * Execution plan for multi-hop Sing-box <-> Aether WARP <-> Psiphon chaining.
 *
 * Guarantees strict dependency startup order (upstream ready before downstream)
 * and clean reverse-order teardown on disconnect.
 */
data class ChainExecutionPlan(
    val chainMode: ChainMode,
    val tunTargetPort: Int,
    val startSingBoxFirst: Boolean,
    val startAetherEngine: Boolean,
    val aetherEffectiveConfig: VpnConfig?,
    val singBoxUpstreamSocksPort: Int?,
    val requireWarpValidation: Boolean,
    val protocolLabel: String
)

object ChainOrchestrator {

    private val SINGBOX_UPSTREAM_URL = "socks5://${VpnConfig.LOOPBACK_HOST}:${VpnConfig.SING_BOX_SOCKS_PORT}"

    fun plan(config: VpnConfig, profileProtocol: String = "Sing-box"): ChainExecutionPlan {
        val mode = config.chainMode
        val aetherCfg: VpnConfig? = when (mode) {
            ChainMode.SINGBOX_ONLY -> null
            ChainMode.SINGBOX_TO_WARP -> config.copy(
                singBoxEnabled = false,
                singBoxOnly = false,
                psiphonMode = PsiphonMode.OFF,
                upstreamProxy = SINGBOX_UPSTREAM_URL
            )
            ChainMode.SINGBOX_TO_PSIPHON -> config.copy(
                singBoxEnabled = false,
                singBoxOnly = false,
                psiphonMode = PsiphonMode.PSIPHON_ONLY,
                upstreamProxy = SINGBOX_UPSTREAM_URL
            )
            ChainMode.SINGBOX_TO_WARP_TO_PSIPHON -> config.copy(
                singBoxEnabled = false,
                singBoxOnly = false,
                psiphonMode = PsiphonMode.WARP_TO_PSIPHON,
                upstreamProxy = SINGBOX_UPSTREAM_URL
            )
            ChainMode.SINGBOX_TO_PSIPHON_TO_WARP -> config.copy(
                singBoxEnabled = false,
                singBoxOnly = false,
                psiphonMode = PsiphonMode.PSIPHON_TO_WARP,
                upstreamProxy = SINGBOX_UPSTREAM_URL
            )
            ChainMode.WARP_TO_SINGBOX -> config.copy(
                singBoxEnabled = false,
                singBoxOnly = false,
                psiphonMode = PsiphonMode.OFF
            )
            ChainMode.PSIPHON_TO_SINGBOX -> config.copy(
                singBoxEnabled = false,
                singBoxOnly = false,
                psiphonMode = PsiphonMode.PSIPHON_ONLY
            )
        }

        val singBoxUpstreamPort: Int? = when (mode) {
            ChainMode.WARP_TO_SINGBOX -> VpnConfig.DEFAULT_SOCKS_PORT
            ChainMode.PSIPHON_TO_SINGBOX -> VpnConfig.PSIPHON_SOCKS_PORT
            else -> null
        }

        val protoLabel = when (mode) {
            ChainMode.SINGBOX_ONLY -> "Sing-box ($profileProtocol)"
            else -> "${mode.label} ($profileProtocol)"
        }

        return ChainExecutionPlan(
            chainMode = mode,
            tunTargetPort = mode.tunTargetPort,
            startSingBoxFirst = mode.startSingBoxFirst,
            startAetherEngine = mode != ChainMode.SINGBOX_ONLY,
            aetherEffectiveConfig = aetherCfg,
            singBoxUpstreamSocksPort = singBoxUpstreamPort,
            requireWarpValidation = mode.isWarpFinalEgress,
            protocolLabel = protoLabel
        )
    }

    fun formatTopology(config: VpnConfig, activeProfile: SingBoxProfile? = null): String {
        val prefix = "TUN (${VpnConfig.TUN_IPV4_ADDRESS}) -> HEV"
        val warpHop = "Aether WARP (${VpnConfig.LOOPBACK_HOST}:${VpnConfig.DEFAULT_SOCKS_PORT})"
        val psiphonHop = "Psiphon (${VpnConfig.LOOPBACK_HOST}:${VpnConfig.PSIPHON_SOCKS_PORT})"
        val singBoxHop = "Sing-box (${VpnConfig.LOOPBACK_HOST}:${VpnConfig.SING_BOX_SOCKS_PORT})"
        val outboundDesc = activeProfile?.let {
            SingBoxConfigParser.formatOutboundDescriptor(it.rawUriOrJson, it.protocol)
        } ?: "none"

        if (config.singBoxEnabled) {
            return when (config.chainMode) {
                ChainMode.SINGBOX_ONLY -> "$prefix -> $singBoxHop -> $outboundDesc"
                ChainMode.SINGBOX_TO_WARP -> "$prefix -> $warpHop -> $singBoxHop -> $outboundDesc"
                ChainMode.SINGBOX_TO_PSIPHON -> "$prefix -> $psiphonHop -> $singBoxHop -> $outboundDesc"
                ChainMode.SINGBOX_TO_WARP_TO_PSIPHON -> "$prefix -> $psiphonHop -> $warpHop -> $singBoxHop -> $outboundDesc"
                ChainMode.SINGBOX_TO_PSIPHON_TO_WARP -> "$prefix -> $warpHop -> $psiphonHop -> $singBoxHop -> $outboundDesc"
                ChainMode.WARP_TO_SINGBOX -> "$prefix -> $singBoxHop -> $warpHop -> $outboundDesc"
                ChainMode.PSIPHON_TO_SINGBOX -> "$prefix -> $singBoxHop -> $psiphonHop -> $outboundDesc"
            }
        }

        val upstreamHop = sanitizeUpstreamEndpoint(config.upstreamProxy)
        val egressSuffix = if (upstreamHop != null) " -> Upstream ($upstreamHop)" else " -> Direct"
        return when (config.psiphonMode) {
            PsiphonMode.OFF -> "$prefix -> $warpHop$egressSuffix"
            PsiphonMode.WARP_TO_PSIPHON -> "$prefix -> $psiphonHop -> $warpHop$egressSuffix"
            PsiphonMode.PSIPHON_TO_WARP -> "$prefix -> $warpHop -> $psiphonHop$egressSuffix"
            PsiphonMode.PSIPHON_ONLY -> "$prefix -> $psiphonHop$egressSuffix"
        }
    }

    private fun sanitizeUpstreamEndpoint(rawUpstream: String): String? {
        val trimmed = rawUpstream.trim()
        if (trimmed.isBlank()) return null
        val withoutScheme = trimmed.substringAfter("://", trimmed)
        return withoutScheme.substringAfterLast("@").substringBefore("/").ifBlank { null }
    }
}
