package io.aethervpn.android

import io.aethervpn.android.core.AetherEngineManager
import io.aethervpn.android.core.AetherProtocol
import io.aethervpn.android.core.NoizeProfile
import io.aethervpn.android.core.ProfileLevel
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
        assertFalse(args.contains("--noize"))
        assertFalse(args.contains("--fragment"))
    }

    @Test
    fun advancedProtocolAndRoutingOptionsMapToVerifiedCliFlags() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val cfg = VpnConfig(
            protocol = AetherProtocol.MASQUE_H2,
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
    fun goolClassicEmitsClassicFlag() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val args = mgr.buildCoreArguments(VpnConfig(protocol = AetherProtocol.GOOL_CLASSIC))
        assertTrue(args.contains("--gool-classic"))
    }

    @Test
    fun backupJsonRedactsProxyCredentialsAndRoundTrips() {
        val original = VpnConfig(
            protocol = AetherProtocol.WIREGUARD,
            upstreamProxy = "socks5://user:supersecret@10.0.0.2:1080",
            routeDirectRules = "private,port:22"
        )
        val json = original.toSafeBackupJson()
        assertFalse(json.contains("supersecret"))

        val restored = VpnConfig.fromBackupJson(json).getOrThrow()
        assertEquals(AetherProtocol.WIREGUARD, restored.protocol)
        assertEquals("private,port:22", restored.routeDirectRules)
        assertEquals("", restored.upstreamProxy)
    }
}
