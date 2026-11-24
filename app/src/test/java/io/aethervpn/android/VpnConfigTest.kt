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

        // MASQUE HTTP/3 requires 55s for QUIC scan
        assertEquals(55_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H3)))

        // Non-turbo scan (balanced) requires 55s
        assertEquals(55_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H2, scanMode = ScanMode.BALANCED)))

        // Thorough scan requires 310s budget (aligns with upstream Aether 300s sweep)
        assertEquals(310_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H2, scanMode = ScanMode.THOROUGH)))

        // Turbo MASQUE_H2 uses 35s budget
        assertEquals(35_000L, mgr.calculateStartupTimeoutMs(VpnConfig(protocol = AetherProtocol.MASQUE_H2, scanMode = ScanMode.TURBO)))
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
}
