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
}
