package io.aethervpn.android

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
        assertTrue(yaml.contains("task-stack-size: 16384"))
        assertTrue(yaml.contains("tcp-buffer-size: 65536"))
        assertTrue(yaml.contains("max-session-count: 512"))
    }

    @Test
    fun optimalMobileMtuConfiguration() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val defaultYaml = manager.generateYamlConfig(VpnConfig(tunMtu = 1280))
        assertTrue(defaultYaml.contains("mtu: 1280"))

        val customMtuYaml = manager.generateYamlConfig(VpnConfig(tunMtu = 1400))
        assertTrue(customMtuYaml.contains("mtu: 1400"))
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
    fun omitsUdpSectionWhenPsiphonIsEgress() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val warpToPsi = manager.generateYamlConfig(VpnConfig(psiphonMode = PsiphonMode.WARP_TO_PSIPHON))
        assertFalse(warpToPsi.contains("udp: 'udp'"))

        val psiOnly = manager.generateYamlConfig(VpnConfig(psiphonMode = PsiphonMode.PSIPHON_ONLY))
        assertFalse(psiOnly.contains("udp: 'udp'"))

        // When Psiphon is upstream but WARP is final egress (PSIPHON_TO_WARP), UDP is allowed
        val psiToWarp = manager.generateYamlConfig(VpnConfig(psiphonMode = PsiphonMode.PSIPHON_TO_WARP, hevMode = HevMode.TCP_UDP))
        assertTrue(psiToWarp.contains("udp: 'udp'"))
    }
}
