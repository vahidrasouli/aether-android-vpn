package io.aethervpn.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SingBoxProfileRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun addSelectUpdateTestAndDeleteProfiles() {
        val dir = tempFolder.newFolder("singbox_repo")
        val initial = SingBoxProfileRepository.load(dir)
        assertTrue(initial.profiles.isEmpty())
        assertNull(initial.activeProfile)

        val p1 = SingBoxProfileRepository.addProfile(
            dir,
            "vless://11111111-2222-3333-4444-555555555555@198.51.100.1:443?security=tls#First"
        ).getOrThrow()
        val p2 = SingBoxProfileRepository.addProfile(
            dir,
            "trojan://pass@198.51.100.2:443#Second"
        ).getOrThrow()

        var state = SingBoxProfileRepository.load(dir)
        assertEquals(2, state.profiles.size)
        assertEquals(p1.id, state.activeProfile?.id)

        state = SingBoxProfileRepository.setActiveProfile(dir, p2.id)
        assertEquals(p2.id, state.activeProfile?.id)

        state = SingBoxProfileRepository.updateTestResult(dir, p2.id, pingMs = 135L, exitIp = "198.51.100.2", country = "DE")
        val updatedP2 = state.profiles.first { it.id == p2.id }
        assertEquals(135L, updatedP2.lastPingMs)
        assertEquals("198.51.100.2", updatedP2.lastExitIp)
        assertEquals("DE", updatedP2.lastCountry)

        state = SingBoxProfileRepository.deleteProfile(dir, p2.id)
        assertEquals(1, state.profiles.size)
        assertNotNull(state.activeProfile)
        assertEquals(p1.id, state.activeProfile?.id)
    }

    @Test
    fun vpnConfigMasterSwitchAndSingBoxOnlyPortIsolation() {
        val offConfig = VpnConfig(singBoxEnabled = false, singBoxOnly = true)
        assertFalse(offConfig.isSingBoxOnlyActive)
        assertEquals(VpnConfig.DEFAULT_SOCKS_PORT, offConfig.effectiveSocksPort)

        val onConfig = VpnConfig(singBoxEnabled = true, singBoxOnly = true)
        assertTrue(onConfig.isSingBoxOnlyActive)
        assertEquals(VpnConfig.SING_BOX_SOCKS_PORT, onConfig.effectiveSocksPort)
        assertEquals(1825, onConfig.effectiveSocksPort)
    }

    @Test
    fun vpnConfigBackupRoundTripPreservesSingBoxFlags() {
        val original = VpnConfig(singBoxEnabled = true, singBoxOnly = true)
        val restored = VpnConfig.fromBackupJson(original.toSafeBackupJson()).getOrThrow()
        assertTrue(restored.singBoxEnabled)
        assertTrue(restored.singBoxOnly)
        assertTrue(restored.isSingBoxOnlyActive)
    }
}
