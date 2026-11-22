package io.aethervpn.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TunnelPhase(val label: String) {
    STOPPED("Disconnected"),
    WAITING_FOR_INTERNET("Waiting for Internet"),
    STARTING_CORE("Starting Aether Core"),
    CORE_CONNECTED("Core Running"),
    SOCKS5_READY("SOCKS5 Ready"),
    STARTING_HEV("Starting TUN / HEV"),
    TUN_ACTIVE("TUN Active"),
    VPN_CONNECTED("Connected"),
    RECONNECTING("Reconnecting"),
    ERROR("Error");

    val isVpnConnected: Boolean get() = this == VPN_CONNECTED
    val isCoreReady: Boolean get() = this == SOCKS5_READY || this == STARTING_HEV || this == TUN_ACTIVE || this == VPN_CONNECTED
    val isTunReady: Boolean get() = this == TUN_ACTIVE || this == VPN_CONNECTED
    val isWorking: Boolean get() = this == STARTING_CORE || this == CORE_CONNECTED || this == STARTING_HEV || this == RECONNECTING

    companion object {
        // Compatibility aliases for existing references
        @JvmField val DISCONNECTED = STOPPED
        @JvmField val CONNECTING = STARTING_CORE
        @JvmField val VALIDATING = STARTING_HEV
        @JvmField val CONNECTED = VPN_CONNECTED
    }
}

data class TrafficStats(
    val txPackets: Long = 0L,
    val txBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val rxBytes: Long = 0L
)

data class TunnelSnapshot(
    val phase: TunnelPhase = TunnelPhase.STOPPED,
    val protocolLabel: String = AetherProtocol.MASQUE_H3.label,
    val gatewayLabel: String = "",
    val verifiedPublicIp: String? = null,
    val warpStatus: String? = null,
    val colo: String? = null,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null,
    val connectedSinceMs: Long = 0L,
    val coreActive: Boolean = false,
    val tunActive: Boolean = false
)

/**
 * Event-driven state holder shared between AetherVpnService and UI.
 * Emits updates only on real lifecycle transitions (zero background polling).
 */
object VpnStateStore {
    private val _state = MutableStateFlow(TunnelSnapshot())
    val state: StateFlow<TunnelSnapshot> = _state.asStateFlow()

    fun update(snapshot: TunnelSnapshot) {
        _state.value = snapshot
    }

    fun resetDisconnected(protocolLabel: String = _state.value.protocolLabel) {
        _state.value = TunnelSnapshot(
            phase = TunnelPhase.STOPPED,
            protocolLabel = protocolLabel,
            coreActive = false,
            tunActive = false
        )
    }

    fun reportError(message: String, protocolLabel: String = _state.value.protocolLabel) {
        DiagnosticsLog.record("VPN", "Error: $message")
        _state.value = _state.value.copy(
            phase = TunnelPhase.ERROR,
            protocolLabel = protocolLabel,
            errorMessage = message
        )
    }
}
