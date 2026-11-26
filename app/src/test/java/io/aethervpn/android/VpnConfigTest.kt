package io.aethervpn.android

import io.aethervpn.android.core.AetherEngineManager
import io.aethervpn.android.core.AetherProtocol
import io.aethervpn.android.core.HevMode
import io.aethervpn.android.core.IpFamily
import io.aethervpn.android.core.NoizeProfile
import io.aethervpn.android.core.PerfProfile
import io.aethervpn.android.core.ProfileLevel
import io.aethervpn.android.core.ScanMode
import io.aethervpn.android.core.TunnelPhase
import io.aethervpn.android.core.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnConfigTest {

    @Test
    fun loopbackOnlySocksBindingIsEnforced() {
        val cfg = VpnConfig()
        assertEquals("127.0.0.1:1819", cfg.socksBindAddress)
    }

    @Test
    fun batteryEfficientDefaultUsesMasqueH3QuickReconnectAndTurboScan() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val args = mgr.buildCoreArguments(VpnConfig().applyPreset(ProfileLevel.BATTERY_EFFICIENT))

        assertTrue(args.contains("--protocol"))
        assertTrue(args.contains("masque"))
        assertTrue(args.contains("--h3"))
        assertTrue(args.contains("--quick-reconnect"))
        assertTrue(args.contains("turbo"))
        assertTrue(args.contains("--perf"))
        assertTrue(args.contains("low"))
        assertTrue(args.contains("v4"))
        assertFalse(args.contains("--noize"))
        assertFalse(args.contains("--fragment"))
    }

    @Test
    fun advancedProtocolAndRoutingOptionsMapToVerifiedCliFlags() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val cfg = VpnConfig(
            protocol = AetherProtocol.MASQUE_H2,
            perfProfile = PerfProfile.MEDIUM,
            hevMode = HevMode.TCP_UDP,
            manualPeer = "162.159.198.1:443",
            routeDirectRules = "domain:local,private",
            routeBlockRules = "domain:ads.example",
            noizeProfile = NoizeProfile.LIGHT,
            tlsFragmentation = true,
            masqueSni = "example.com",
            echEnabled = true,
            upstreamProxy = "socks5://127.0.0.1:1080",
            zeroTrustTeam = "acme"
        )
        val args = mgr.buildCoreArguments(cfg)

        assertTrue(args.contains("--h2"))
        assertTrue(args.contains("--h2-peer"))
        assertTrue(args.contains("162.159.198.1:443"))
        assertTrue(args.contains("--perf"))
        assertTrue(args.contains("medium"))
        assertTrue(args.contains("--route-direct"))
        assertTrue(args.contains("--route-block"))
        assertTrue(args.contains("--noize"))
        assertTrue(args.contains("light"))
        assertTrue(args.contains("--fragment"))
        assertTrue(args.contains("--masque-sni"))
        assertTrue(args.contains("--ech"))
        assertTrue(args.contains("auto"))
        assertTrue(args.contains("--upstream"))
        assertTrue(args.contains("--team"))
    }

    @Test
    fun exitLocationFlagsEmittedWhenPolicyPresent() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val cfg = VpnConfig(
            exitLocation = "DE,NL,!IR",
            exitLocationSecs = 45
        )
        val args = mgr.buildCoreArguments(cfg)
        assertTrue(args.contains("--exit-loc"))
        assertTrue(args.contains("DE,NL,!IR"))
        assertTrue(args.contains("--exit-loc-secs"))
        assertTrue(args.contains("45"))
    }

    @Test
    fun exitLocationFlagsOmittedWhenEmptyOrOff() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val cfg1 = VpnConfig(exitLocation = "")
        val args1 = mgr.buildCoreArguments(cfg1)
        assertFalse(args1.contains("--exit-loc"))

        val cfg2 = VpnConfig(exitLocation = "off")
        val args2 = mgr.buildCoreArguments(cfg2)
        assertFalse(args2.contains("--exit-loc"))

        val cfg3 = VpnConfig(exitLocation = "any")
        val args3 = mgr.buildCoreArguments(cfg3)
        assertFalse(args3.contains("--exit-loc"))
    }

    @Test
    fun startupTimeoutCalculatedDynamicallyBasedOnProtocolAndScanBudget() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))

        // Multi-hop protocols require full 60s scan budget
        assertEquals(60_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.GOOL)))
        assertEquals(60_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MIM)))
        assertEquals(60_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.GOOL_CLASSIC)))

        // MASQUE HTTP/3 and HTTP/2 both have a 45s Turbo scan budget -> 55s startup timeout
        assertEquals(55_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H3)))
        assertEquals(55_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H2, scanMode = ScanMode.TURBO)))

        // Balanced (120s budget), Verified (60s budget), Ironclad (180s budget), Thorough (300s budget) + 10s margin
        assertEquals(130_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H2, scanMode = ScanMode.BALANCED)))
        assertEquals(70_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H2, scanMode = ScanMode.VERIFIED)))
        assertEquals(190_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H2, scanMode = ScanMode.IRONCLAD)))
        assertEquals(310_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H2, scanMode = ScanMode.THOROUGH)))

        // WireGuard Turbo uses 35s budget
        assertEquals(35_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.WIREGUARD, scanMode = ScanMode.TURBO)))
    }

    @Test
    fun granularTunnelPhaseStateProperties() {
        assertFalse(TunnelPhase.STOPPED.isVpnConnected)
        assertFalse(TunnelPhase.STOPPED.isCoreReady)
        assertTrue(TunnelPhase.SOCKS5_READY.isCoreReady)
        assertFalse(TunnelPhase.SOCKS5_READY.isVpnConnected)
        assertTrue(TunnelPhase.TUN_ACTIVE.isTunReady)
        assertFalse(TunnelPhase.TUN_ACTIVE.isVpnConnected)
        assertTrue(TunnelPhase.VPN_CONNECTED.isVpnConnected)
        assertTrue(TunnelPhase.VPN_CONNECTED.isCoreReady)
        assertTrue(TunnelPhase.VPN_CONNECTED.isTunReady)
        assertTrue(TunnelPhase.STARTING_CORE.isWorking)
        assertTrue(TunnelPhase.RECONNECTING.isWorking)
    }

    @Test
    fun goolClassicEmitsClassicFlag() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val args = mgr.buildCoreArguments(VpnConfig(protocol = AetherProtocol.GOOL_CLASSIC))
        assertTrue(args.contains("--gool-classic"))
    }

    @Test
    fun backupJsonRedactsProxyCredentialsAndRoundTrips() {
        val original = VpnConfig(
            protocol = AetherProtocol.WIREGUARD,
            perfProfile = PerfProfile.LOW,
            hevMode = HevMode.TCP_ONLY,
            ipFamily = IpFamily.IPV4,
            exitLocation = "DE,NL",
            exitLocationSecs = 90,
            upstreamProxy = "socks5://user:supersecret@10.0.0.2:1080",
            routeDirectRules = "private,port:22"
        )
        val json = original.toSafeBackupJson()
        assertFalse(json.contains("supersecret"))

        val restored = VpnConfig.fromBackupJson(json).getOrThrow()
        assertEquals(AetherProtocol.WIREGUARD, restored.protocol)
        assertEquals(PerfProfile.LOW, restored.perfProfile)
        assertEquals(HevMode.TCP_ONLY, restored.hevMode)
        assertEquals(IpFamily.IPV4, restored.ipFamily)
        assertEquals("DE,NL", restored.exitLocation)
        assertEquals(90, restored.exitLocationSecs)
        assertEquals("private,port:22", restored.routeDirectRules)
        assertEquals("", restored.upstreamProxy)
    }

    @Test
    fun psiphonChainingCliFlagsAndDynamicPortRouting() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))

        // 1. Off mode: Egress port 1819, no Psiphon flags
        val cfgOff = VpnConfig(psiphonMode = io.aethervpn.android.core.PsiphonMode.OFF)
        assertEquals(1819, cfgOff.effectiveSocksPort)
        val argsOff = mgr.buildCoreArguments(cfgOff)
        assertFalse(argsOff.contains("--psiphon"))
        assertFalse(argsOff.contains("--psiphon-reverse"))
        assertFalse(argsOff.contains("--psiphon-only"))

        // 2. WARP -> Psiphon: Egress port 1821, emits --psiphon, --psiphon-bind, --psiphon-region
        val cfgChain = VpnConfig(
            psiphonMode = io.aethervpn.android.core.PsiphonMode.WARP_TO_PSIPHON,
            psiphonRegion = "DE"
        )
        assertEquals(1821, cfgChain.effectiveSocksPort)
        val argsChain = mgr.buildCoreArguments(cfgChain, psiphonBinaryPath = "/data/local/tmp/psiphon-tunnel-core")
        assertTrue(argsChain.contains("--psiphon"))
        assertTrue(argsChain.contains("--psiphon-bind"))
        assertTrue(argsChain.contains("127.0.0.1:1821"))
        assertTrue(argsChain.contains("--psiphon-region"))
        assertTrue(argsChain.contains("DE"))
        assertTrue(argsChain.contains("--psiphon-bin"))
        assertTrue(argsChain.contains("/data/local/tmp/psiphon-tunnel-core"))

        // 3. Psiphon -> WARP: Egress port 1819, emits --psiphon-reverse
        val cfgReverse = VpnConfig(
            psiphonMode = io.aethervpn.android.core.PsiphonMode.PSIPHON_TO_WARP,
            psiphonRegion = "NL"
        )
        assertEquals(1819, cfgReverse.effectiveSocksPort)
        val argsReverse = mgr.buildCoreArguments(cfgReverse)
        assertTrue(argsReverse.contains("--psiphon-reverse"))
        assertTrue(argsReverse.contains("--psiphon-region"))
        assertTrue(argsReverse.contains("NL"))

        // 4. Psiphon Only: Egress port 1821, binds directly on 1821, emits --psiphon-only
        val cfgOnly = VpnConfig(
            psiphonMode = io.aethervpn.android.core.PsiphonMode.PSIPHON_ONLY
        )
        assertEquals(1821, cfgOnly.effectiveSocksPort)
        val argsOnly = mgr.buildCoreArguments(cfgOnly)
        assertTrue(argsOnly.contains("--psiphon-only"))
        val bindIdx = argsOnly.indexOf("--bind")
        assertTrue(bindIdx >= 0 && argsOnly[bindIdx + 1] == "127.0.0.1:1821")
    }

    @Test
    fun settingsPreferenceKeysAndBackupRoundTripRemainCompatible() {
        val original = VpnConfig(
            profileLevel = ProfileLevel.ADVANCED,
            protocol = AetherProtocol.MASQUE_H2,
            psiphonMode = io.aethervpn.android.core.PsiphonMode.WARP_TO_PSIPHON,
            psiphonRegion = "DE",
            bypassIran = true,
            singBoxEnabled = true,
            chainMode = io.aethervpn.android.core.ChainMode.SINGBOX_TO_WARP
        )
        val json = original.toSafeBackupJson()
        val restored = VpnConfig.fromBackupJson(json).getOrThrow()
        assertEquals(original.profileLevel, restored.profileLevel)
        assertEquals(original.protocol, restored.protocol)
        assertEquals(original.psiphonMode, restored.psiphonMode)
        assertEquals(original.psiphonRegion, restored.psiphonRegion)
        assertEquals(original.bypassIran, restored.bypassIran)
        assertEquals(original.singBoxEnabled, restored.singBoxEnabled)
        assertEquals(original.chainMode, restored.chainMode)

        val vpnConfigFile = listOf(
            File("src/main/java/io/aethervpn/android/core/VpnConfig.kt"),
            File("app/src/main/java/io/aethervpn/android/core/VpnConfig.kt")
        ).firstOrNull { it.isFile }
        assertTrue("VpnConfig.kt not found", vpnConfigFile != null)
        val source = vpnConfigFile!!.readText()
        val legacyKeys = listOf(
            "\"profile_level\"",
            "\"protocol\"",
            "\"psiphon_mode\"",
            "\"psiphon_region\"",
            "\"psiphon_protocol\"",
            "\"sing_box_enabled\"",
            "\"chain_mode\"",
            "\"bypass_iran\"",
            "\"upstream_proxy\"",
            "\"theme_mode\""
        )
        legacyKeys.forEach { key ->
            assertTrue("Missing backward-compatible preference key $key", source.contains(key))
        }
    }
}
