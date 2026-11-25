package io.aethervpn.android

import io.aethervpn.android.core.AetherEngineManager
import io.aethervpn.android.core.EngineLabPreset
import io.aethervpn.android.core.HevTunnelManager
import io.aethervpn.android.core.PerfProfile
import io.aethervpn.android.core.PsiphonMode
import io.aethervpn.android.core.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineLabConfigTest {

    @Test
    fun defaultConfigMatchesBalancedPreset() {
        val cfg = VpnConfig()
        assertEquals(EngineLabPreset.BALANCED, cfg.detectEngineLabPreset())
        assertEquals(PerfProfile.LOW, cfg.perfProfile)
        assertEquals("48MiB", cfg.goMemLimit)
        assertEquals("2", cfg.goMaxProcs)
        assertEquals("50", cfg.goGc)
        assertEquals(512, cfg.aetherMaxClients)
        assertEquals(45, cfg.aetherMasqueH2KeepaliveSecs)
        assertEquals(60, cfg.aetherTcpKeepaliveSecs)
        assertEquals(30, cfg.aetherHalfCloseSecs)
        assertEquals(16384, cfg.taskStackSize)
        assertEquals(5000, cfg.udpTimeoutMs)
        assertEquals(1280, cfg.tunMtu)
        assertTrue(cfg.mapDnsEnabled)
    }

    @Test
    fun ecoBalancedTurboPresetsApplyExactValuesAndDetectCustom() {
        val eco = VpnConfig().applyEngineLabPreset(EngineLabPreset.ECO)
        assertEquals(EngineLabPreset.ECO, eco.detectEngineLabPreset())
        assertEquals(PerfProfile.LOW, eco.perfProfile)
        assertEquals("32MiB", eco.goMemLimit)
        assertEquals("1", eco.goMaxProcs)
        assertEquals("25", eco.goGc)
        assertEquals(256, eco.aetherMaxClients)
        assertEquals(60, eco.aetherMasqueH2KeepaliveSecs)
        assertEquals(90, eco.aetherTcpKeepaliveSecs)
        assertEquals(15, eco.aetherHalfCloseSecs)
        assertEquals(16384, eco.taskStackSize)
        assertEquals(5000, eco.udpTimeoutMs)

        val balanced = eco.applyEngineLabPreset(EngineLabPreset.BALANCED)
        assertEquals(EngineLabPreset.BALANCED, balanced.detectEngineLabPreset())
        assertEquals("48MiB", balanced.goMemLimit)
        assertEquals("2", balanced.goMaxProcs)
        assertEquals("50", balanced.goGc)
        assertEquals(512, balanced.aetherMaxClients)

        val turbo = balanced.applyEngineLabPreset(EngineLabPreset.TURBO)
        assertEquals(EngineLabPreset.TURBO, turbo.detectEngineLabPreset())
        assertEquals(PerfProfile.MEDIUM, turbo.perfProfile)
        assertEquals("64MiB", turbo.goMemLimit)
        assertEquals("4", turbo.goMaxProcs)
        assertEquals("100", turbo.goGc)
        assertEquals(1024, turbo.aetherMaxClients)
        assertEquals(30, turbo.aetherMasqueH2KeepaliveSecs)
        assertEquals(45, turbo.aetherTcpKeepaliveSecs)
        assertEquals(30, turbo.aetherHalfCloseSecs)
        assertEquals(24576, turbo.taskStackSize)
        assertEquals(5000, turbo.udpTimeoutMs)

        val custom = turbo.copy(aetherMaxClients = 128)
        assertEquals(EngineLabPreset.CUSTOM, custom.detectEngineLabPreset())

        val reset = custom.copy(tunMtu = 1420, mapDnsEnabled = false).resetEngineLabDefaults()
        assertEquals(EngineLabPreset.BALANCED, reset.detectEngineLabPreset())
        assertEquals(1280, reset.tunMtu)
        assertTrue(reset.mapDnsEnabled)
    }

    @Test
    fun vpnConfigBackupJsonPersistsAllEngineLabFields() {
        val original = VpnConfig()
            .applyEngineLabPreset(EngineLabPreset.TURBO)
            .copy(
                tunMtu = 1400,
                taskStackSize = 32768,
                udpTimeoutMs = 15000,
                mapDnsEnabled = false,
                goMemLimit = "24MiB",
                goMaxProcs = "Auto",
                goGc = "25"
            )

        val json = original.toSafeBackupJson()
        val restored = VpnConfig.fromBackupJson(json).getOrThrow()

        assertEquals(1400, restored.tunMtu)
        assertEquals(1024, restored.aetherMaxClients)
        assertEquals(30, restored.aetherMasqueH2KeepaliveSecs)
        assertEquals(45, restored.aetherTcpKeepaliveSecs)
        assertEquals(30, restored.aetherHalfCloseSecs)
        assertEquals("24MiB", restored.goMemLimit)
        assertEquals("Auto", restored.goMaxProcs)
        assertEquals("25", restored.goGc)
        assertEquals(32768, restored.taskStackSize)
        assertEquals(15000, restored.udpTimeoutMs)
        assertFalse(restored.mapDnsEnabled)
        assertFalse(restored.useMapDns)
    }

    @Test
    fun aetherEngineManagerInjectsEnvVarsAndRespectsPsiphonAndAutoModes() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))

        // 1. Psiphon OFF: AETHER_* set, GO* vars unset (null)
        val cfgOff = VpnConfig().applyEngineLabPreset(EngineLabPreset.ECO).copy(psiphonMode = PsiphonMode.OFF)
        val envOff = mgr.resolveEnvironmentVariables(cfgOff)
        assertEquals("1", envOff["AETHER_ROUTE_SNIFF"])
        assertEquals("256", envOff["AETHER_MAX_CLIENTS"])
        assertEquals("15", envOff["AETHER_HALF_CLOSE_SECS"])
        assertEquals("90", envOff["AETHER_TCP_KEEPALIVE_SECS"])
        assertEquals("60", envOff["AETHER_MASQUE_H2_KEEPALIVE_SECS"])
        assertNull(envOff["GOMEMLIMIT"])
        assertNull(envOff["GOGC"])
        assertNull(envOff["GOMAXPROCS"])
        assertFalse(mgr.formatActiveLimitsSummary(cfgOff).contains("Go["))

        // 2. Psiphon Active: GO* vars set from config
        val cfgChain = VpnConfig().applyEngineLabPreset(EngineLabPreset.TURBO)
            .copy(psiphonMode = PsiphonMode.WARP_TO_PSIPHON)
        val envChain = mgr.resolveEnvironmentVariables(cfgChain)
        assertEquals("1024", envChain["AETHER_MAX_CLIENTS"])
        assertEquals("30", envChain["AETHER_MASQUE_H2_KEEPALIVE_SECS"])
        assertEquals("64MiB", envChain["GOMEMLIMIT"])
        assertEquals("4", envChain["GOMAXPROCS"])
        assertEquals("100", envChain["GOGC"])
        assertTrue(mgr.formatActiveLimitsSummary(cfgChain).contains("Go[mem=64MiB, procs=4, gc=100]"))

        // 3. Psiphon Only + Auto Go limits: H2 keepalive & Auto GO* vars unset (null)
        val cfgAuto = VpnConfig(
            psiphonMode = PsiphonMode.PSIPHON_ONLY,
            goMemLimit = "Auto",
            goMaxProcs = "Auto",
            goGc = "50"
        )
        val envAuto = mgr.resolveEnvironmentVariables(cfgAuto)
        assertNull(envAuto["AETHER_MASQUE_H2_KEEPALIVE_SECS"])
        assertNull(envAuto["GOMEMLIMIT"])
        assertNull(envAuto["GOMAXPROCS"])
        assertEquals("50", envAuto["GOGC"])
    }

    @Test
    fun hevTunnelManagerAppliesMtuStackTimeoutAndMapDnsToggle() {
        val mgr = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))

        val turboCfg = VpnConfig().applyEngineLabPreset(EngineLabPreset.TURBO).copy(
            tunMtu = 1420,
            taskStackSize = 32768,
            udpTimeoutMs = 15000,
            mapDnsEnabled = false
        )
        val yaml = mgr.generateYamlConfig(turboCfg)

        assertTrue(yaml.contains("mtu: 1420"))
        assertTrue(yaml.contains("task-stack-size: 32768"))
        assertTrue(yaml.contains("udp-read-write-timeout: 15000"))
        assertFalse(yaml.contains("mapdns:"))
    }
}
