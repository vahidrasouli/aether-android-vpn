package io.aethervpn.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TunnelPhase {
    DISCONNECTED,
    CONNECTING,
    VALIDATING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

data class TrafficStats(
    val txPackets: Long = 0L,
    val txBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val rxBytes: Long = 0L
)

data class TunnelSnapshot(
    val phase: TunnelPhase = TunnelPhase.DISCONNECTED,
    val protocolLabel: String = AetherProtocol.MASQUE_H3.label,
    val gatewayLabel: String = "",
    val verifiedPublicIp: String? = null,
    val warpStatus: String? = null,
    val colo: String? = null,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null,
    val connectedSinceMs: Long = 0L
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
            phase = TunnelPhase.DISCONNECTED,
            protocolLabel = protocolLabel
        )
    }

    fun reportError(message: String, protocolLabel: String = _state.value.protocolLabel) {
        DiagnosticsLog.record("VPN", "Error: $message")
        _state.value = TunnelSnapshot(
            phase = TunnelPhase.ERROR,
            protocolLabel = protocolLabel,
            errorMessage = message
        )
    }
}
