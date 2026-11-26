package io.aethervpn.android

import io.aethervpn.android.core.ChainMode
import io.aethervpn.android.core.HevMode
import io.aethervpn.android.core.HevTunnelManager
import io.aethervpn.android.core.IpFamily
import io.aethervpn.android.core.PsiphonMode
import io.aethervpn.android.core.VpnConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HevConfigGeneratorTest {

    @Test
    fun generatesBoundedLoopbackDualStackYaml() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val yaml = manager.generateYamlConfig(VpnConfig(ipFamily = IpFamily.BOTH, hevMode = HevMode.TCP_UDP))

        assertTrue(yaml.contains("address: '127.0.0.1'"))
        assertTrue(yaml.contains("port: 1819"))
        assertTrue(yaml.contains("udp: 'udp'"))
        assertTrue(yaml.contains("mtu: 1280"))
        assertTrue(yaml.contains("ipv4: '198.18.0.1'"))
        assertTrue(yaml.contains("ipv6: 'fd00:ae7::1'"))
        assertTrue(yaml.contains("task-stack-size: 81920"))
        assertTrue(yaml.contains("tcp-buffer-size: 524288"))
        assertTrue(yaml.contains("connect-timeout: 15000"))
        assertTrue(yaml.contains("max-session-count: 512"))
        assertTrue(yaml.contains("cache-size: 10000"))
        assertTrue(yaml.contains("tcp-read-write-timeout: 300000"))
    }

    @Test
    fun optimalMobileMtuConfiguration() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val defaultYaml = manager.generateYamlConfig(VpnConfig(tunMtu = 1280))
        assertTrue(defaultYaml.contains("mtu: 1280"))

        val customMtuYaml = manager.generateYamlConfig(VpnConfig(tunMtu = 1400))
        assertTrue(customMtuYaml.contains("mtu: 1400"))

        val singBoxOnlyYaml = manager.generateYamlConfig(
            VpnConfig(singBoxEnabled = true, chainMode = ChainMode.SINGBOX_ONLY, hevMode = HevMode.TCP_ONLY)
        )
        assertTrue(singBoxOnlyYaml.contains("mtu: 1500"))
        assertTrue(singBoxOnlyYaml.contains("tcp-buffer-size: 524288"))
        assertTrue(singBoxOnlyYaml.contains("task-stack-size: 81920"))
        assertTrue(singBoxOnlyYaml.contains("tcp-read-write-timeout: 300000"))
        assertTrue(singBoxOnlyYaml.contains("udp: 'udp'"))
        assertFalse("Sing-box Only must omit mapdns to prevent 240.0.0.x Fake-IP leaks", singBoxOnlyYaml.contains("mapdns:"))

        val singBoxToWarpYaml = manager.generateYamlConfig(
            VpnConfig(singBoxEnabled = true, chainMode = ChainMode.SINGBOX_TO_WARP)
        )
        assertTrue(singBoxToWarpYaml.contains("mtu: 1280"))
        assertTrue(singBoxToWarpYaml.contains("mapdns:"))
    }

    @Test
    fun omitsIpv6AddressWhenIpv4Only() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val yaml = manager.generateYamlConfig(VpnConfig(ipFamily = IpFamily.IPV4))

        assertFalse(yaml.contains("ipv6:"))
    }

    @Test
    fun omitsUdpSectionWhenTcpOnlyMode() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val yaml = manager.generateYamlConfig(VpnConfig(hevMode = HevMode.TCP_ONLY))

        assertFalse(yaml.contains("udp: 'udp'"))
    }

    @Test
    fun usesStandardUdpAssociateAndNeverTcpModeWhenPsiphonIsEgress() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        for (mode in listOf(PsiphonMode.WARP_TO_PSIPHON, PsiphonMode.PSIPHON_ONLY)) {
            for (hevMode in listOf(HevMode.TCP_ONLY, HevMode.TCP_UDP)) {
                val yaml = manager.generateYamlConfig(VpnConfig(psiphonMode = mode, hevMode = hevMode))
                assertFalse("Must never use udp: 'tcp' (0x05) for $mode", yaml.contains("udp: 'tcp'"))
                assertTrue("Must explicitly set udp: 'udp' (0x03) for $mode so HEV does not default to 0x05", yaml.contains("udp: 'udp'"))
            }
        }

        // When Psiphon is upstream but WARP is final egress (PSIPHON_TO_WARP), standard UDP is allowed
        val psiToWarp = manager.generateYamlConfig(VpnConfig(psiphonMode = PsiphonMode.PSIPHON_TO_WARP, hevMode = HevMode.TCP_UDP))
        assertTrue(psiToWarp.contains("udp: 'udp'"))
        assertFalse(psiToWarp.contains("udp: 'tcp'"))
    }

    @Test
    fun enablesMapDnsByDefaultAndDisablesWhenBypassIranOrCustomDnsSet() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))

        val defaultYaml = manager.generateYamlConfig(VpnConfig())
        assertTrue(defaultYaml.contains("mapdns:"))
        assertTrue(defaultYaml.contains("address: 198.18.0.2"))
        assertTrue(defaultYaml.contains("udp-read-write-timeout: 5000"))

        val customTimeoutYaml = manager.generateYamlConfig(VpnConfig(udpTimeoutMs = 30000))
        assertTrue(customTimeoutYaml.contains("udp-read-write-timeout: 30000"))

        val psiphonYaml = manager.generateYamlConfig(VpnConfig(psiphonMode = PsiphonMode.WARP_TO_PSIPHON))
        assertTrue(psiphonYaml.contains("mapdns:"))
        assertTrue(psiphonYaml.contains("udp-read-write-timeout: 5000"))

        val iranBypassYaml = manager.generateYamlConfig(VpnConfig(bypassIran = true))
        assertFalse(iranBypassYaml.contains("mapdns:"))

        val customDnsYaml = manager.generateYamlConfig(VpnConfig(customDns = "8.8.8.8"))
        assertFalse(customDnsYaml.contains("mapdns:"))
    }

    @Test
    fun setsGenerousTcpReadWriteTimeoutForPersistentConnections() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val yaml = manager.generateYamlConfig(VpnConfig())
        assertTrue(yaml.contains("tcp-read-write-timeout: 300000"))
    }
}
