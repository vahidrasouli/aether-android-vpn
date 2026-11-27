package io.aethervpn.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class ByeDpiIntegrationTest {

    private val sampleVlessUri =
        "vless://11111111-2222-3333-4444-555555555555@198.51.100.10:443?security=tls&sni=example.com#TestVLESS"

    @Test
    fun byeDpiPresetBuildsExactCliArgsForEachPresetAndCustomPort() {
        assertEquals(
            listOf("-i", "127.0.0.1", "-p", "1827", "-s1", "-r1+s", "-a1"),
            ByeDpiEngineManager.buildCommandArgs(ByeDpiPreset.BALANCED)
        )
        assertEquals(
            listOf("-i", "127.0.0.1", "-p", "1827", "-d1", "-r1+s", "-a1"),
            ByeDpiEngineManager.buildCommandArgs(ByeDpiPreset.AGGRESSIVE)
        )
        assertEquals(
            listOf("-i", "127.0.0.1", "-p", "1827", "-o1", "-d1", "-r1+s"),
            ByeDpiEngineManager.buildCommandArgs(ByeDpiPreset.OOB_DISORDER)
        )
        // AUTO falls back to BALANCED when no prior winner exists
        assertEquals(
            listOf("-i", "127.0.0.1", "-p", "1827", "-s1", "-r1+s", "-a1"),
            ByeDpiEngineManager.buildCommandArgs(ByeDpiPreset.AUTO)
        )
        // AUTO uses lastWinningPreset when available
        assertEquals(
            listOf("-i", "127.0.0.1", "-p", "1827", "-o1", "-d1", "-r1+s"),
            ByeDpiEngineManager.buildCommandArgs(
                preset = ByeDpiPreset.AUTO,
                lastWinningPreset = ByeDpiPreset.OOB_DISORDER
            )
        )
        // Custom port support
        assertEquals(
            listOf("-i", "127.0.0.1", "-p", "1905", "-d1", "-r1+s", "-a1"),
            ByeDpiEngineManager.buildCommandArgs(ByeDpiPreset.AGGRESSIVE, listenPort = 1905)
        )
    }

    @Test
    fun singBoxConfigParserInjectsByeDpiSocksDetourOnlyWhenEnabledInDirectMode() {
        // 1. Enabled in SINGBOX_ONLY (Direct) mode -> injects 127.0.0.1:1827 SOCKS5 detour
        val enabledCfg = VpnConfig(
            singBoxEnabled = true,
            chainMode = ChainMode.SINGBOX_ONLY,
            sbByeDpiEnabled = true,
            sbByeDpiPreset = ByeDpiPreset.BALANCED
        )
        assertTrue(enabledCfg.isByeDpiActive)
        val enabledJson = SingBoxConfigParser.buildRuntimeConfig(
            rawUriOrJson = sampleVlessUri,
            config = enabledCfg
        ).getOrThrow()
        val enabledOutbounds = JSONObject(enabledJson).getJSONArray("outbounds")
        val proxyOb = (0 until enabledOutbounds.length())
            .map { enabledOutbounds.getJSONObject(it) }
            .first { it.getString("tag") == "proxy" }
        assertEquals("upstream-socks", proxyOb.getString("detour"))

        val socksOb = (0 until enabledOutbounds.length())
            .map { enabledOutbounds.getJSONObject(it) }
            .first { it.getString("tag") == "upstream-socks" }
        assertEquals("socks", socksOb.getString("type"))
        assertEquals("127.0.0.1", socksOb.getString("server"))
        assertEquals(1827, socksOb.getInt("server_port"))
        assertEquals("5", socksOb.getString("version"))

        // 2. Disabled in SINGBOX_ONLY mode -> omits detour and 1827 outbound
        val disabledCfg = enabledCfg.copy(sbByeDpiEnabled = false)
        assertFalse(disabledCfg.isByeDpiActive)
        val disabledJson = SingBoxConfigParser.buildRuntimeConfig(
            rawUriOrJson = sampleVlessUri,
            config = disabledCfg
        ).getOrThrow()
        val disabledOutbounds = JSONObject(disabledJson).getJSONArray("outbounds")
        val disabledTags = (0 until disabledOutbounds.length()).map {
            disabledOutbounds.getJSONObject(it).getString("tag")
        }
        assertFalse(disabledTags.contains("upstream-socks"))

        // 3. Enabled in config, but chained with WARP (SINGBOX_TO_WARP / WARP_TO_SINGBOX) -> never activates ByeDPI
        for (chainedMode in listOf(ChainMode.SINGBOX_TO_WARP, ChainMode.WARP_TO_SINGBOX, ChainMode.SINGBOX_TO_PSIPHON)) {
            val chainedCfg = enabledCfg.copy(chainMode = chainedMode)
            assertFalse("ByeDPI must be inactive for $chainedMode", chainedCfg.isByeDpiActive)
            val plan = ChainOrchestrator.plan(chainedCfg)
            val chainedJson = SingBoxConfigParser.buildRuntimeConfig(
                rawUriOrJson = sampleVlessUri,
                upstreamSocksPort = plan.singBoxUpstreamSocksPort,
                config = chainedCfg
            ).getOrThrow()
            val chainedOutbounds = JSONObject(chainedJson).getJSONArray("outbounds")
            val ports = (0 until chainedOutbounds.length())
                .map { chainedOutbounds.getJSONObject(it) }
                .filter { it.optString("type") == "socks" }
                .map { it.optInt("server_port") }
            assertFalse("Port 1827 must not be injected for $chainedMode", ports.contains(1827))
        }
    }

    @Test
    fun chainTopologyFormatsByeDpiHopAccurately() {
        val vlessProfile = SingBoxProfile(
            id = "vless-1",
            name = "TestVLESS",
            protocol = "VLESS",
            rawUriOrJson = sampleVlessUri
        )

        val directWithByeDpi = VpnConfig(
            singBoxEnabled = true,
            chainMode = ChainMode.SINGBOX_ONLY,
            sbByeDpiEnabled = true,
            sbByeDpiPreset = ByeDpiPreset.AGGRESSIVE
        )
        assertEquals(
            "TUN (172.19.0.1) -> HEV -> Sing-box (127.0.0.1:1825) -> ByeDPI (127.0.0.1:1827, AGGRESSIVE) -> vless (tcp+tls)",
            ChainOrchestrator.formatTopology(directWithByeDpi, vlessProfile)
        )

        val directAutoFallback = directWithByeDpi.copy(
            sbByeDpiPreset = ByeDpiPreset.AUTO,
            sbByeDpiLastWinner = null
        )
        assertEquals(
            "TUN (172.19.0.1) -> HEV -> Sing-box (127.0.0.1:1825) -> ByeDPI (127.0.0.1:1827, BALANCED) -> vless (tcp+tls)",
            ChainOrchestrator.formatTopology(directAutoFallback, vlessProfile)
        )

        val warpChained = directWithByeDpi.copy(chainMode = ChainMode.SINGBOX_TO_WARP)
        assertEquals(
            "TUN (172.19.0.1) -> HEV -> Aether WARP (127.0.0.1:1819) -> Sing-box (127.0.0.1:1825) -> vless (tcp+tls)",
            ChainOrchestrator.formatTopology(warpChained, vlessProfile)
        )
    }

    @Test
    fun backupJsonRoundTripPreservesByeDpiSettings() {
        val original = VpnConfig(
            sbByeDpiEnabled = true,
            sbByeDpiPreset = ByeDpiPreset.OOB_DISORDER,
            sbByeDpiLastWinner = ByeDpiPreset.AGGRESSIVE
        )
        val restored = VpnConfig.fromBackupJson(original.toSafeBackupJson()).getOrThrow()
        assertTrue(restored.sbByeDpiEnabled)
        assertEquals(ByeDpiPreset.OOB_DISORDER, restored.sbByeDpiPreset)
        assertEquals(ByeDpiPreset.AGGRESSIVE, restored.sbByeDpiLastWinner)
    }

    @Test
    fun speedTestConfigBuilderInjectsIsolatedPort1828WhenEnabledAndOmitsWhenDisabled() {
        val enabledCfg = VpnConfig(
            singBoxEnabled = true,
            chainMode = ChainMode.SINGBOX_ONLY,
            sbByeDpiEnabled = true,
            sbByeDpiPreset = ByeDpiPreset.AUTO
        )
        val enabledJson = SingBoxEngineManager.buildSpeedTestRuntimeConfig(
            rawUriOrJson = sampleVlessUri,
            listenPort = 18990,
            config = enabledCfg
        ).getOrThrow()
        val enabledOutbounds = JSONObject(enabledJson).getJSONArray("outbounds")
        val proxyOb = (0 until enabledOutbounds.length())
            .map { enabledOutbounds.getJSONObject(it) }
            .first { it.getString("tag") == "proxy" }
        assertEquals("upstream-socks", proxyOb.getString("detour"))

        val socksOb = (0 until enabledOutbounds.length())
            .map { enabledOutbounds.getJSONObject(it) }
            .first { it.getString("tag") == "upstream-socks" }
        assertEquals("127.0.0.1", socksOb.getString("server"))
        assertEquals(1828, socksOb.getInt("server_port"))

        val disabledCfg = enabledCfg.copy(sbByeDpiEnabled = false)
        val disabledJson = SingBoxEngineManager.buildSpeedTestRuntimeConfig(
            rawUriOrJson = sampleVlessUri,
            listenPort = 18991,
            config = disabledCfg
        ).getOrThrow()
        val disabledOutbounds = JSONObject(disabledJson).getJSONArray("outbounds")
        val disabledTags = (0 until disabledOutbounds.length()).map {
            disabledOutbounds.getJSONObject(it).getString("tag")
        }
        assertFalse(disabledTags.contains("upstream-socks"))
    }

    @Test
    fun autoBenchmarkWinnerSelectionPicksFastestAndHandlesPartialFailures() {
        val allSuccess = listOf(
            ByeDpiPresetBenchmark(
                preset = ByeDpiPreset.BALANCED,
                speedProbe = Result.success(SpeedProbeResult(bytesDownloaded = 262_144, elapsedMs = 1400, kbPerSec = 180))
            ),
            ByeDpiPresetBenchmark(
                preset = ByeDpiPreset.AGGRESSIVE,
                speedProbe = Result.success(SpeedProbeResult(bytesDownloaded = 262_144, elapsedMs = 400, kbPerSec = 640))
            ),
            ByeDpiPresetBenchmark(
                preset = ByeDpiPreset.OOB_DISORDER,
                speedProbe = Result.success(SpeedProbeResult(bytesDownloaded = 262_144, elapsedMs = 620, kbPerSec = 410))
            )
        )
        val winner = SingBoxEngineManager.selectAutoBenchmarkWinner(allSuccess)
        assertEquals(ByeDpiPreset.AGGRESSIVE, winner?.preset)
        assertEquals(
            "[SpeedTest] Balanced: 180 KB/s | Aggressive: 640 KB/s | OOB+Disorder: 410 KB/s",
            SingBoxEngineManager.formatAutoBenchmarkSummary(allSuccess)
        )

        val partialFailure = listOf(
            ByeDpiPresetBenchmark(
                preset = ByeDpiPreset.BALANCED,
                speedProbe = Result.failure(SocketTimeoutException("Read timed out"))
            ),
            ByeDpiPresetBenchmark(
                preset = ByeDpiPreset.AGGRESSIVE,
                speedProbe = Result.failure(
                    SpeedProbeStalledException(
                        message = "Incomplete download",
                        bytesDownloaded = 65_536,
                        totalBytes = 262_144,
                        percentReached = 25,
                        partialKbPerSec = 50
                    )
                )
            ),
            ByeDpiPresetBenchmark(
                preset = ByeDpiPreset.OOB_DISORDER,
                speedProbe = Result.success(SpeedProbeResult(bytesDownloaded = 262_144, elapsedMs = 700, kbPerSec = 365))
            )
        )
        val partialWinner = SingBoxEngineManager.selectAutoBenchmarkWinner(partialFailure)
        assertEquals(ByeDpiPreset.OOB_DISORDER, partialWinner?.preset)
        assertEquals(
            "[SpeedTest] Balanced: timeout | Aggressive: 25% (50 KB/s) | OOB+Disorder: 365 KB/s",
            SingBoxEngineManager.formatAutoBenchmarkSummary(partialFailure)
        )

        val allFailed = listOf(
            ByeDpiPresetBenchmark(ByeDpiPreset.BALANCED, Result.failure(SocketTimeoutException("timeout"))),
            ByeDpiPresetBenchmark(ByeDpiPreset.AGGRESSIVE, Result.failure(IllegalStateException("EOF")))
        )
        assertNull(SingBoxEngineManager.selectAutoBenchmarkWinner(allFailed))
    }
}
