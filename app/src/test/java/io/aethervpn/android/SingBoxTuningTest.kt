package io.aethervpn.android

import io.aethervpn.android.core.SingBoxBlockQuicMode
import io.aethervpn.android.core.SingBoxConfigParser
import io.aethervpn.android.core.SingBoxEngineManager
import io.aethervpn.android.core.SingBoxTuningPreset
import io.aethervpn.android.core.VpnConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxTuningTest {

    private val sampleVlessUri =
        "vless://11111111-2222-3333-4444-555555555555@198.51.100.10:443?type=tcp&security=tls#TestVless"
    private val sampleHy2Uri =
        "hy2://hy2pass@hy2.example.com:8443?sni=hy2.example.com#TestHy2"

    @Test
    fun defaultConfigMatchesBalancedSingBoxTuningPreset() {
        val cfg = VpnConfig()
        assertEquals(SingBoxTuningPreset.BALANCED, cfg.detectSingBoxTuningPreset())
        assertEquals("96MiB", cfg.sbGoMemLimit)
        assertEquals("Auto", cfg.sbGoMaxProcs)
        assertEquals("100", cfg.sbGoGc)
        assertEquals("1.1.1.1", cfg.sbRemoteDns)
        assertEquals("1.1.1.1", cfg.sbDirectDns)
        assertEquals("ipv4_only", cfg.sbDnsStrategy)
        assertTrue(cfg.sbSniffEnabled)
        assertEquals(SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS, cfg.sbBlockQuic)
        assertTrue(cfg.sbBypassPrivateIp)
        assertFalse(cfg.sbBypassIrDomains)
        assertEquals(15, cfg.sbConnectTimeoutSec)
        assertFalse(cfg.sbTcpFastOpen)
        assertEquals("warn", cfg.sbLogLevel)
    }

    @Test
    fun ecoBalancedTurboPresetsApplyExactValuesAndDetectCustom() {
        val eco = VpnConfig().applySingBoxTuningPreset(SingBoxTuningPreset.ECO)
        assertEquals(SingBoxTuningPreset.ECO, eco.detectSingBoxTuningPreset())
        assertEquals("48MiB", eco.sbGoMemLimit)
        assertEquals("2", eco.sbGoMaxProcs)
        assertEquals("50", eco.sbGoGc)
        assertEquals("error", eco.sbLogLevel)
        assertEquals(SingBoxBlockQuicMode.ALWAYS, eco.sbBlockQuic)
        assertEquals("ipv4_only", eco.sbDnsStrategy)
        assertEquals(15, eco.sbConnectTimeoutSec)
        assertFalse(eco.sbTcpFastOpen)

        val balanced = eco.applySingBoxTuningPreset(SingBoxTuningPreset.BALANCED)
        assertEquals(SingBoxTuningPreset.BALANCED, balanced.detectSingBoxTuningPreset())
        assertEquals("96MiB", balanced.sbGoMemLimit)
        assertEquals("Auto", balanced.sbGoMaxProcs)
        assertEquals("100", balanced.sbGoGc)
        assertEquals("warn", balanced.sbLogLevel)
        assertEquals(SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS, balanced.sbBlockQuic)
        assertEquals("ipv4_only", balanced.sbDnsStrategy)
        assertEquals(15, balanced.sbConnectTimeoutSec)

        val turbo = balanced.applySingBoxTuningPreset(SingBoxTuningPreset.TURBO)
        assertEquals(SingBoxTuningPreset.TURBO, turbo.detectSingBoxTuningPreset())
        assertEquals("Auto", turbo.sbGoMemLimit)
        assertEquals("Auto", turbo.sbGoMaxProcs)
        assertEquals("100", turbo.sbGoGc)
        assertEquals("warn", turbo.sbLogLevel)
        assertEquals(SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS, turbo.sbBlockQuic)
        assertTrue(turbo.sbTcpFastOpen)
        assertEquals(15, turbo.sbConnectTimeoutSec)

        // Changing any individual field switches to CUSTOM
        assertEquals(SingBoxTuningPreset.CUSTOM, turbo.copy(sbBypassIrDomains = true).detectSingBoxTuningPreset())
        assertEquals(SingBoxTuningPreset.CUSTOM, turbo.copy(sbRemoteDns = "8.8.8.8").detectSingBoxTuningPreset())
        assertEquals(SingBoxTuningPreset.CUSTOM, turbo.copy(sbCustomBypassDomains = "digikala.com").detectSingBoxTuningPreset())
        assertEquals(SingBoxTuningPreset.CUSTOM, turbo.copy(sbMultiplexEnabled = true).detectSingBoxTuningPreset())

        val reset = turbo.copy(sbBypassIrDomains = true, sbGoMemLimit = "24MiB").resetSingBoxTuningDefaults()
        assertEquals(SingBoxTuningPreset.BALANCED, reset.detectSingBoxTuningPreset())
        assertFalse(reset.sbBypassIrDomains)
        assertEquals("96MiB", reset.sbGoMemLimit)
    }

    @Test
    fun vpnConfigBackupJsonPersistsAllSingBoxTuningFields() {
        val original = VpnConfig()
            .applySingBoxTuningPreset(SingBoxTuningPreset.TURBO)
            .copy(
                sbGoMemLimit = "24MiB",
                sbGoMaxProcs = "Auto",
                sbGoGc = "25",
                sbRemoteDns = "https://8.8.8.8/dns-query",
                sbDirectDns = "223.5.5.5",
                sbDnsStrategy = "prefer_ipv6",
                sbSniffEnabled = false,
                sbBlockQuic = SingBoxBlockQuicMode.OFF,
                sbBypassPrivateIp = false,
                sbBypassIrDomains = true,
                sbCustomBypassDomains = "digikala.com, shaparak.ir",
                sbMultiplexEnabled = true,
                sbConnectTimeoutSec = 15,
                sbTcpFastOpen = true,
                sbLogLevel = "info"
            )

        val restored = VpnConfig.fromBackupJson(original.toSafeBackupJson()).getOrThrow()
        assertEquals("24MiB", restored.sbGoMemLimit)
        assertEquals("Auto", restored.sbGoMaxProcs)
        assertEquals("25", restored.sbGoGc)
        assertEquals("https://8.8.8.8/dns-query", restored.sbRemoteDns)
        assertEquals("223.5.5.5", restored.sbDirectDns)
        assertEquals("prefer_ipv6", restored.sbDnsStrategy)
        assertFalse(restored.sbSniffEnabled)
        assertEquals(SingBoxBlockQuicMode.OFF, restored.sbBlockQuic)
        assertFalse(restored.sbBypassPrivateIp)
        assertTrue(restored.sbBypassIrDomains)
        assertEquals("digikala.com, shaparak.ir", restored.sbCustomBypassDomains)
        assertTrue(restored.sbMultiplexEnabled)
        assertEquals(15, restored.sbConnectTimeoutSec)
        assertTrue(restored.sbTcpFastOpen)
        assertEquals("info", restored.sbLogLevel)
    }

    @Test
    fun singBoxEngineManagerInjectsDedicatedSbGoEnvVars() {
        val cfg = VpnConfig(
            goMemLimit = "24MiB",
            goMaxProcs = "1",
            goGc = "25",
            sbGoMemLimit = "64MiB",
            sbGoMaxProcs = "4",
            sbGoGc = "100"
        )
        val env = SingBoxEngineManager.resolveGoEnvironment(cfg)
        assertEquals("64MiB", env["GOMEMLIMIT"])
        assertEquals("4", env["GOMAXPROCS"])
        assertEquals("100", env["GOGC"])

        val autoEnv = SingBoxEngineManager.resolveGoEnvironment(
            cfg.copy(sbGoMemLimit = "Auto", sbGoMaxProcs = "Auto", sbGoGc = "50")
        )
        assertNull(autoEnv["GOMEMLIMIT"])
        assertNull(autoEnv["GOMAXPROCS"])
        assertEquals("50", autoEnv["GOGC"])
    }

    @Test
    fun testAllRunnerUsesBoundedConcurrencyAndConservativeEphemeralGoCaps() {
        val testEnv = SingBoxEngineManager.resolveEphemeralTestGoEnvironment()
        assertEquals("24MiB", testEnv["GOMEMLIMIT"])
        assertEquals("1", testEnv["GOMAXPROCS"])
        assertEquals("25", testEnv["GOGC"])
        assertEquals(6_000L, SingBoxEngineManager.EPHEMERAL_TEST_TIMEOUT_MS)
        assertEquals(3, io.aethervpn.android.ui.SingBoxProfilesActivity.TEST_ALL_MAX_CONCURRENCY)
    }

    @Test
    fun hevTunnelManagerWritesTelegramSafeTimeoutsForSingBoxOnly() {
        val mgr = io.aethervpn.android.core.HevTunnelManager(java.io.File(System.getProperty("java.io.tmpdir") ?: "."))
        val yaml = mgr.generateYamlConfig(
            VpnConfig(
                singBoxEnabled = true,
                singBoxOnly = true,
                chainMode = io.aethervpn.android.core.ChainMode.SINGBOX_ONLY
            )
        )
        assertTrue(yaml.contains("mtu: 1500"))
        assertTrue(yaml.contains("tcp-buffer-size: 524288"))
        assertTrue(yaml.contains("task-stack-size: 81920"))
        assertTrue(yaml.contains("connect-timeout: 15000"))
        assertTrue(yaml.contains("tcp-read-write-timeout: 300000"))
        assertTrue(yaml.contains("udp: 'udp'"))
        assertFalse(yaml.contains("mapdns:"))
    }

    @Test
    fun singBoxConfigParserWiresTunablesIntoValid112Json() {
        val tuned = VpnConfig(
            sbRemoteDns = "9.9.9.9",
            sbDirectDns = "223.5.5.5",
            sbDnsStrategy = "prefer_ipv6",
            sbSniffEnabled = false,
            sbBlockQuic = SingBoxBlockQuicMode.ALWAYS,
            sbBypassPrivateIp = false,
            sbBypassIrDomains = true,
            sbConnectTimeoutSec = 5,
            sbTcpFastOpen = true,
            sbLogLevel = "info"
        )

        val runtime = JSONObject(
            SingBoxConfigParser.buildRuntimeConfig(sampleHy2Uri, config = tuned).getOrThrow()
        )

        // Log
        assertEquals("info", runtime.getJSONObject("log").getString("level"))

        // DNS 1.12 (runtime & ephemeral test config must omit detour on dns-local)
        val dns = runtime.getJSONObject("dns")
        assertEquals("prefer_ipv6", dns.getString("strategy"))
        val servers = dns.getJSONArray("servers")
        val remote = servers.getJSONObject(0)
        assertEquals("udp", remote.getString("type"))
        assertEquals("9.9.9.9", remote.getString("server"))
        assertEquals("proxy", remote.getString("detour"))
        val local = servers.getJSONObject(1)
        assertEquals("udp", local.getString("type"))
        assertEquals("223.5.5.5", local.getString("server"))
        assertFalse("dns-local must NOT have detour in runtime config", local.has("detour"))

        val testRuntime = JSONObject(
            SingBoxConfigParser.buildRuntimeConfig(sampleHy2Uri, listenPort = 18999, config = tuned).getOrThrow()
        )
        val testLocalDns = testRuntime.getJSONObject("dns").getJSONArray("servers").getJSONObject(1)
        assertEquals("dns-local", testLocalDns.getString("tag"))
        assertFalse("dns-local must NOT have detour in test config", testLocalDns.has("detour"))

        // Outbound dialer
        val proxy = runtime.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("5s", proxy.getString("connect_timeout"))
        assertTrue(proxy.getBoolean("tcp_fast_open"))
        assertFalse(proxy.has("tcp_keep_alive"))

        // Route rules
        val rules = runtime.getJSONObject("route").getJSONArray("rules")
        val ruleList = (0 until rules.length()).map { rules.getJSONObject(it) }
        assertFalse(ruleList.any { it.optString("action") == "sniff" })
        assertTrue(ruleList.any { it.optString("protocol") == "dns" && it.optString("action") == "hijack-dns" })
        assertFalse(ruleList.any { it.optBoolean("ip_is_private", false) })
        assertTrue(
            ruleList.any {
                val suffixes = it.optJSONArray("domain_suffix")
                suffixes != null && suffixes.length() == 1 && suffixes.getString(0) == ".ir" &&
                    it.optString("outbound") == "direct"
            }
        )
        assertTrue(ruleList.any { it.optString("protocol") == "quic" && it.optString("action") == "reject" })

        // OFF disables QUIC blocking even for TCP outbounds
        val offRuntime = JSONObject(
            SingBoxConfigParser.buildRuntimeConfig(
                sampleVlessUri,
                config = tuned.copy(sbBlockQuic = SingBoxBlockQuicMode.OFF)
            ).getOrThrow()
        )
        val offRules = offRuntime.getJSONObject("route").getJSONArray("rules")
        val offRuleList = (0 until offRules.length()).map { offRules.getJSONObject(it) }
        assertFalse(offRuleList.any { it.optString("protocol") == "quic" && it.optString("action") == "reject" })
    }

    @Test
    fun customBypassDomainsAndMultiplexEmitValidSingBox112Json() {
        val cfg = VpnConfig(
            sbCustomBypassDomains = "digikala.com, shaparak.ir, *.bank.ir",
            sbMultiplexEnabled = true
        )

        val vlessRuntime = JSONObject(SingBoxConfigParser.buildRuntimeConfig(sampleVlessUri, config = cfg).getOrThrow())
        val vlessOutbound = vlessRuntime.getJSONArray("outbounds").getJSONObject(0)
        val mux = vlessOutbound.getJSONObject("multiplex")
        assertTrue(mux.getBoolean("enabled"))
        assertEquals("h2mux", mux.getString("protocol"))
        assertEquals(4, mux.getInt("max_connections"))

        val rules = vlessRuntime.getJSONObject("route").getJSONArray("rules")
        val ruleList = (0 until rules.length()).map { rules.getJSONObject(it) }
        val customRule = ruleList.firstOrNull {
            val arr = it.optJSONArray("domain_suffix")
            arr != null && arr.length() == 3 && it.optString("outbound") == "direct"
        }
        assertTrue("Custom bypass domains rule must exist", customRule != null)
        val suffixes = customRule!!.getJSONArray("domain_suffix")
        assertEquals("digikala.com", suffixes.getString(0))
        assertEquals("shaparak.ir", suffixes.getString(1))
        assertEquals(".bank.ir", suffixes.getString(2))

        // UDP-based outbounds like hysteria2 must NOT emit multiplex
        val hy2Runtime = JSONObject(SingBoxConfigParser.buildRuntimeConfig(sampleHy2Uri, config = cfg).getOrThrow())
        assertFalse(hy2Runtime.getJSONArray("outbounds").getJSONObject(0).has("multiplex"))
    }
}
