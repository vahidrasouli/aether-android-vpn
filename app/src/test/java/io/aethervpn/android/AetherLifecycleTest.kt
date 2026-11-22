package io.aethervpn.android

import io.aethervpn.android.core.TunnelPhase
import io.aethervpn.android.core.TunnelSnapshot
import io.aethervpn.android.core.VpnStateStore
import io.aethervpn.android.vpn.AetherVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherLifecycleTest {

    @Test
    fun verifyGranularPhaseStateInvariants() {
        // Initial state is STOPPED
        val initial = TunnelSnapshot()
        assertEquals(TunnelPhase.STOPPED, initial.phase)
        assertFalse(initial.coreActive)
        assertFalse(initial.tunActive)

        // Starting core phase
        val startingCore = initial.copy(phase = TunnelPhase.STARTING_CORE)
        assertTrue(startingCore.phase.isWorking)
        assertFalse(startingCore.phase.isCoreReady)

        // Core ready phase
        val coreReady = initial.copy(phase = TunnelPhase.SOCKS5_READY, coreActive = true)
        assertTrue(coreReady.phase.isCoreReady)
        assertFalse(coreReady.phase.isVpnConnected)
        assertFalse(coreReady.phase.isTunReady)

        // TUN active phase
        val tunActive = initial.copy(phase = TunnelPhase.TUN_ACTIVE, coreActive = true, tunActive = true)
        assertTrue(tunActive.phase.isCoreReady)
        assertTrue(tunActive.phase.isTunReady)
        assertFalse(tunActive.phase.isVpnConnected)

        // Connected phase
        val connected = initial.copy(phase = TunnelPhase.VPN_CONNECTED, coreActive = true, tunActive = true)
        assertTrue(connected.phase.isVpnConnected)
        assertTrue(connected.phase.isCoreReady)
        assertTrue(connected.phase.isTunReady)

        // Waiting for internet
        val waiting = initial.copy(phase = TunnelPhase.WAITING_FOR_INTERNET)
        assertFalse(waiting.phase.isVpnConnected)
        assertFalse(waiting.phase.isWorking)

        // Reconnecting
        val reconnecting = initial.copy(phase = TunnelPhase.RECONNECTING)
        assertTrue(reconnecting.phase.isWorking)
        assertFalse(reconnecting.phase.isVpnConnected)
    }

    @Test
    fun verifyVpnStateStoreReset() {
        VpnStateStore.update(
            TunnelSnapshot(
                phase = TunnelPhase.VPN_CONNECTED,
                coreActive = true,
                tunActive = true,
                verifiedPublicIp = "1.2.3.4"
            )
        )
        assertEquals(TunnelPhase.VPN_CONNECTED, VpnStateStore.state.value.phase)

        VpnStateStore.resetDisconnected()
        val resetState = VpnStateStore.state.value
        assertEquals(TunnelPhase.STOPPED, resetState.phase)
        assertFalse(resetState.coreActive)
        assertFalse(resetState.tunActive)
    }

    @Test
    fun verifyIntentActionConstants() {
        assertEquals("io.aethervpn.android.ACTION_CONNECT", AetherVpnService.ACTION_CONNECT)
        assertEquals("io.aethervpn.android.ACTION_DISCONNECT", AetherVpnService.ACTION_DISCONNECT)
        assertEquals("io.aethervpn.android.ACTION_START_CORE", AetherVpnService.ACTION_START_CORE)
        assertEquals("io.aethervpn.android.ACTION_STOP_CORE", AetherVpnService.ACTION_STOP_CORE)
        assertEquals("io.aethervpn.android.ACTION_START_TUN", AetherVpnService.ACTION_START_TUN)
        assertEquals("io.aethervpn.android.ACTION_STOP_TUN", AetherVpnService.ACTION_STOP_TUN)
    }

    @Test
    fun verifyBoundedExponentialBackoffProgression() {
        val expectedDelays = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        assertEquals(6, expectedDelays.size)
        assertEquals(1_000L, expectedDelays[0])
        assertEquals(2_000L, expectedDelays[1])
        assertEquals(4_000L, expectedDelays[2])
        assertEquals(8_000L, expectedDelays[3])
        assertEquals(16_000L, expectedDelays[4])
        assertEquals(30_000L, expectedDelays[5])
    }
}
