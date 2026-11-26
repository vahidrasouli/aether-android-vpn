package io.aethervpn.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

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
    DISCONNECTING("Disconnecting"),
    ERROR("Error");

    val isVpnConnected: Boolean get() = this == VPN_CONNECTED
    val isCoreReady: Boolean get() = this == SOCKS5_READY || this == STARTING_HEV || this == TUN_ACTIVE || this == VPN_CONNECTED
    val isTunReady: Boolean get() = this == TUN_ACTIVE || this == VPN_CONNECTED
    val isWorking: Boolean get() = this == STARTING_CORE || this == CORE_CONNECTED || this == SOCKS5_READY || this == STARTING_HEV || this == TUN_ACTIVE || this == RECONNECTING

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
        DiagnosticsLog.record("System", "Error: $message")
        _state.value = _state.value.copy(
            phase = TunnelPhase.ERROR,
            protocolLabel = protocolLabel,
            errorMessage = message
        )
    }
}

enum class VpnUiState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

enum class VpnUiAction {
    START_CONNECT,
    CANCEL_CONNECT,
    DISCONNECT,
    IGNORED
}

/**
 * Atomic UI state machine for the primary Connect/Cancel/Disconnect button.
 * Prevents duplicate Connect triggers during asynchronous service startup/handshake
 * and never reverts to DISCONNECTED until AetherVpnService emits STOPPED or ERROR.
 */
class VpnUiStateMachine(initialState: VpnUiState = VpnUiState.DISCONNECTED) {
    private val atomicState = AtomicReference(initialState)
    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    val current: VpnUiState
        get() = atomicState.get()

    fun tryStartConnect(): Boolean {
        if (atomicState.compareAndSet(VpnUiState.DISCONNECTED, VpnUiState.CONNECTING)) {
            _uiState.value = VpnUiState.CONNECTING
            return true
        }
        return false
    }

    fun onPrimaryButtonTap(): VpnUiAction {
        while (true) {
            val prev = atomicState.get()
            val (next, action) = when (prev) {
                VpnUiState.DISCONNECTED -> VpnUiState.CONNECTING to VpnUiAction.START_CONNECT
                VpnUiState.CONNECTING -> VpnUiState.DISCONNECTING to VpnUiAction.CANCEL_CONNECT
                VpnUiState.CONNECTED -> VpnUiState.DISCONNECTING to VpnUiAction.DISCONNECT
                VpnUiState.DISCONNECTING -> return VpnUiAction.IGNORED
            }
            if (atomicState.compareAndSet(prev, next)) {
                _uiState.value = next
                return action
            }
        }
    }

    fun onServiceSnapshot(snapshot: TunnelSnapshot): VpnUiState {
        while (true) {
            val prev = atomicState.get()
            val next = when (snapshot.phase) {
                TunnelPhase.STOPPED,
                TunnelPhase.ERROR -> VpnUiState.DISCONNECTED

                TunnelPhase.DISCONNECTING -> VpnUiState.DISCONNECTING

                TunnelPhase.VPN_CONNECTED -> {
                    if (prev == VpnUiState.DISCONNECTING) VpnUiState.DISCONNECTING
                    else VpnUiState.CONNECTED
                }

                TunnelPhase.WAITING_FOR_INTERNET,
                TunnelPhase.STARTING_CORE,
                TunnelPhase.CORE_CONNECTED,
                TunnelPhase.SOCKS5_READY,
                TunnelPhase.STARTING_HEV,
                TunnelPhase.TUN_ACTIVE,
                TunnelPhase.RECONNECTING -> {
                    if (prev == VpnUiState.DISCONNECTING) VpnUiState.DISCONNECTING
                    else VpnUiState.CONNECTING
                }
            }
            if (prev == next) return next
            if (atomicState.compareAndSet(prev, next)) {
                _uiState.value = next
                return next
            }
        }
    }

    fun reset(state: VpnUiState = VpnUiState.DISCONNECTED) {
        atomicState.set(state)
        _uiState.value = state
    }
}
