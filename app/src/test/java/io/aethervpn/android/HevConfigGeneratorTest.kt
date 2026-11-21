package io.aethervpn.android

import io.aethervpn.android.core.HevTunnelManager
import io.aethervpn.android.core.IpFamily
import io.aethervpn.android.core.VpnConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HevConfigGeneratorTest {

    @Test
    fun generatesBoundedLoopbackDualStackYaml() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val yaml = manager.generateYamlConfig(VpnConfig(ipFamily = IpFamily.BOTH))

        assertTrue(yaml.contains("address: '127.0.0.1'"))
        assertTrue(yaml.contains("port: 1819"))
        assertTrue(yaml.contains("udp: 'udp'"))
        assertTrue(yaml.contains("mtu: 1280"))
        assertTrue(yaml.contains("ipv4: '198.18.0.1'"))
        assertTrue(yaml.contains("ipv6: 'fd00:ae7::1'"))
        assertTrue(yaml.contains("task-stack-size: 20480"))
        assertTrue(yaml.contains("tcp-buffer-size: 65536"))
        assertTrue(yaml.contains("max-session-count: 512"))
    }

    @Test
    fun omitsIpv6AddressWhenIpv4Only() {
        val manager = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val yaml = manager.generateYamlConfig(VpnConfig(ipFamily = IpFamily.IPV4))

        assertFalse(yaml.contains("ipv6:"))
    }
}
