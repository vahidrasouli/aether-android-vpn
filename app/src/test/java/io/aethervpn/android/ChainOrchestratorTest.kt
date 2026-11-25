package io.aethervpn.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket

class ChainOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleVlessUri =
        "vless://11111111-2222-3333-4444-555555555555@198.51.100.10:443?security=tls&sni=example.com#TestVLESS"

    @Test
    fun allSevenChainModesWireCorrectTunPortsAndStartupOrder() {
        val expected = mapOf(
            ChainMode.SINGBOX_ONLY to Triple(1825, true, false),
            ChainMode.SINGBOX_TO_WARP to Triple(1819, true, true),
            ChainMode.SINGBOX_TO_PSIPHON to Triple(1821, true, true),
            ChainMode.SINGBOX_TO_WARP_TO_PSIPHON to Triple(1821, true, true),
            ChainMode.SINGBOX_TO_PSIPHON_TO_WARP to Triple(1819, true, true),
            ChainMode.WARP_TO_SINGBOX to Triple(1825, false, true),
            ChainMode.PSIPHON_TO_SINGBOX to Triple(1825, false, true)
        )

        for ((mode, spec) in expected) {
            val (expectedPort, expectedSingBoxFirst, expectedNeedsAether) = spec
            val cfg = VpnConfig(singBoxEnabled = true, chainMode = mode)
            val plan = ChainOrchestrator.plan(cfg, "VLESS")

            assertEquals("effectiveSocksPort for $mode", expectedPort, cfg.effectiveSocksPort)
            assertEquals("tunTargetPort for $mode", expectedPort, plan.tunTargetPort)
            assertEquals("startSingBoxFirst for $mode", expectedSingBoxFirst, plan.startSingBoxFirst)
            assertEquals("startAetherEngine for $mode", expectedNeedsAether, plan.startAetherEngine)
        }
    }

    @Test
    fun upstreamSocks5FlagInjectedForSingBoxFirstChains() {
        val mgr = AetherEngineManager(tempFolder.newFolder("aether_upstream"))

        val upstreamModes = mapOf(
            ChainMode.SINGBOX_TO_WARP to PsiphonMode.OFF,
            ChainMode.SINGBOX_TO_PSIPHON to PsiphonMode.PSIPHON_ONLY,
            ChainMode.SINGBOX_TO_WARP_TO_PSIPHON to PsiphonMode.WARP_TO_PSIPHON,
            ChainMode.SINGBOX_TO_PSIPHON_TO_WARP to PsiphonMode.PSIPHON_TO_WARP
        )

        for ((mode, expectedPsiphonMode) in upstreamModes) {
            val cfg = VpnConfig(singBoxEnabled = true, chainMode = mode)
            val plan = ChainOrchestrator.plan(cfg)
            assertNull(plan.singBoxUpstreamSocksPort)
            val aetherCfg = plan.aetherEffectiveConfig
            assertNotNull("aetherEffectiveConfig must be present for $mode", aetherCfg)
            assertEquals(expectedPsiphonMode, aetherCfg!!.psiphonMode)
            assertEquals("socks5://127.0.0.1:1825", aetherCfg.upstreamProxy)

            val args = mgr.buildCoreArguments(aetherCfg)
            val upIdx = args.indexOf("--upstream")
            assertTrue("--upstream missing for $mode", upIdx >= 0)
            assertEquals("socks5://127.0.0.1:1825", args[upIdx + 1])
        }
    }

    @Test
    fun detourAndUpstreamSocksOutboundInjectedForWarpAndPsiphonToSingBox() {
        val cases = listOf(
            ChainMode.WARP_TO_SINGBOX to 1819,
            ChainMode.PSIPHON_TO_SINGBOX to 1821
        )

        for ((mode, expectedUpstreamPort) in cases) {
            val cfg = VpnConfig(singBoxEnabled = true, chainMode = mode)
            val plan = ChainOrchestrator.plan(cfg)
            assertFalse(plan.startSingBoxFirst)
            assertEquals(expectedUpstreamPort, plan.singBoxUpstreamSocksPort)

            // 1. URI profile
            val uriJsonStr = SingBoxConfigParser.buildRuntimeConfig(
                rawUriOrJson = sampleVlessUri,
                listenPort = VpnConfig.SING_BOX_SOCKS_PORT,
                upstreamSocksPort = plan.singBoxUpstreamSocksPort
            ).getOrThrow()
            val uriRoot = JSONObject(uriJsonStr)
            val uriOutbounds = uriRoot.getJSONArray("outbounds")
            val proxyOb = (0 until uriOutbounds.length())
                .map { uriOutbounds.getJSONObject(it) }
                .first { it.getString("tag") == "proxy" }
            assertEquals("upstream-socks", proxyOb.getString("detour"))

            val upstreamOb = (0 until uriOutbounds.length())
                .map { uriOutbounds.getJSONObject(it) }
                .first { it.getString("tag") == "upstream-socks" }
            assertEquals("socks", upstreamOb.getString("type"))
            assertEquals("127.0.0.1", upstreamOb.getString("server"))
            assertEquals(expectedUpstreamPort, upstreamOb.getInt("server_port"))
            assertEquals("5", upstreamOb.getString("version"))

            // 2. Full JSON config
            val fullInput = """
                {
                  "outbounds": [
                    { "type": "trojan", "tag": "my-trojan", "server": "198.51.100.20", "server_port": 443, "password": "secret" },
                    { "type": "direct", "tag": "direct" }
                  ],
                  "route": { "final": "my-trojan" }
                }
            """.trimIndent()
            val fullJsonStr = SingBoxConfigParser.buildRuntimeConfig(
                rawUriOrJson = fullInput,
                listenPort = VpnConfig.SING_BOX_SOCKS_PORT,
                upstreamSocksPort = plan.singBoxUpstreamSocksPort
            ).getOrThrow()
            val fullRoot = JSONObject(fullJsonStr)
            val fullOutbounds = fullRoot.getJSONArray("outbounds")
            val trojanOb = (0 until fullOutbounds.length())
                .map { fullOutbounds.getJSONObject(it) }
                .first { it.getString("tag") == "my-trojan" }
            assertEquals("upstream-socks", trojanOb.getString("detour"))
        }

        // SINGBOX_ONLY must NOT inject upstream-socks or detour
        val onlyJsonStr = SingBoxConfigParser.buildRuntimeConfig(
            rawUriOrJson = sampleVlessUri,
            listenPort = VpnConfig.SING_BOX_SOCKS_PORT,
            upstreamSocksPort = null
        ).getOrThrow()
        val onlyOutbounds = JSONObject(onlyJsonStr).getJSONArray("outbounds")
        val tags = (0 until onlyOutbounds.length()).map { onlyOutbounds.getJSONObject(it).getString("tag") }
        assertFalse(tags.contains("upstream-socks"))
    }

    @Test
    fun tunnelValidatorRequiresWarpOnlyWhenWarpIsFinalEgress() {
        // Non-Sing-box modes
        assertTrue(TunnelValidator.requiresWarpEgress(PsiphonMode.OFF, chainMode = null)) // WARP_ONLY
        assertTrue(TunnelValidator.requiresWarpEgress(PsiphonMode.PSIPHON_TO_WARP, chainMode = null))
        assertFalse(TunnelValidator.requiresWarpEgress(PsiphonMode.WARP_TO_PSIPHON, chainMode = null))
        assertFalse(TunnelValidator.requiresWarpEgress(PsiphonMode.PSIPHON_ONLY, chainMode = null))

        // All 7 Sing-box ChainModes
        val warpFinalModes = setOf(ChainMode.SINGBOX_TO_WARP, ChainMode.SINGBOX_TO_PSIPHON_TO_WARP)
        val nonWarpFinalModes = ChainMode.entries.filterNot { it in warpFinalModes }

        val warpOffBody = "ip=198.51.100.55\ncolo=FRA\nloc=DE\nwarp=off\n"
        val warpOnBody = "ip=104.28.1.2\ncolo=FRA\nloc=DE\nwarp=on\n"

        for (mode in warpFinalModes) {
            assertTrue("$mode must require warp=on", TunnelValidator.requiresWarpEgress(chainMode = mode))

            val (failPort, failThread) = startMockTraceSocksServer(warpOffBody)
            val failRes = TunnelValidator.verifyThroughSocksInternal(
                socksPort = failPort,
                timeoutMs = 2_000,
                chainMode = mode,
                maxAttempts = 1,
                tlsUpgrade = { raw, _ -> raw }
            )
            failThread.join(2_000)
            assertTrue("$mode must fail when warp=off", failRes.isFailure)

            val (okPort, okThread) = startMockTraceSocksServer(warpOnBody)
            val okRes = TunnelValidator.verifyThroughSocksInternal(
                socksPort = okPort,
                timeoutMs = 2_000,
                chainMode = mode,
                maxAttempts = 1,
                tlsUpgrade = { raw, _ -> raw }
            )
            okThread.join(2_000)
            assertTrue("$mode must succeed when warp=on", okRes.isSuccess)
        }

        for (mode in nonWarpFinalModes) {
            assertFalse("$mode must NOT require warp=on", TunnelValidator.requiresWarpEgress(chainMode = mode))

            val (okPort, okThread) = startMockTraceSocksServer(warpOffBody)
            val okRes = TunnelValidator.verifyThroughSocksInternal(
                socksPort = okPort,
                timeoutMs = 2_000,
                chainMode = mode,
                maxAttempts = 1,
                tlsUpgrade = { raw, _ -> raw }
            )
            okThread.join(2_000)
            assertTrue("$mode must succeed when warp=off", okRes.isSuccess)
        }
    }

    @Test
    fun hevYamlTargetsCorrectPortAndRfc1928UdpForAllChainModes() {
        val hev = HevTunnelManager(tempFolder.newFolder("hev_chain"))
        for (mode in ChainMode.entries) {
            val cfg = VpnConfig(singBoxEnabled = true, chainMode = mode, hevMode = HevMode.TCP_ONLY)
            val yaml = hev.generateYamlConfig(cfg)
            assertTrue("HEV YAML missing port ${mode.tunTargetPort} for $mode", yaml.contains("port: ${mode.tunTargetPort}"))
            if (mode.isPsiphonFinalEgress || mode.isSingBoxFinalEgress) {
                assertTrue("HEV YAML must emit udp: 'udp' for $mode", yaml.contains("udp: 'udp'"))
            }
        }
    }

    @Test
    fun backupJsonRoundTripPreservesAllChainModes() {
        for (mode in ChainMode.entries) {
            val original = VpnConfig(singBoxEnabled = true, chainMode = mode)
            val restored = VpnConfig.fromBackupJson(original.toSafeBackupJson()).getOrThrow()
            assertEquals(mode, restored.chainMode)
            assertTrue(restored.singBoxEnabled)
        }
    }

    private fun startMockTraceSocksServer(traceBody: String): Pair<Int, Thread> {
        val server = ServerSocket(0)
        val port = server.localPort
        val thread = Thread {
            try {
                server.soTimeout = 3_000
                server.accept().use { client ->
                    val inStream = client.getInputStream()
                    val outStream = client.getOutputStream()

                    val greeting = ByteArray(3)
                    inStream.read(greeting)
                    outStream.write(byteArrayOf(0x05, 0x00))
                    outStream.flush()

                    val req = ByteArray(10)
                    inStream.read(req)
                    outStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    outStream.flush()

                    val reqBuf = ByteArray(512)
                    inStream.read(reqBuf)
                    val httpResp = "HTTP/1.1 200 OK\r\nContent-Length: ${traceBody.toByteArray().size}\r\n\r\n$traceBody"
                    outStream.write(httpResp.toByteArray(Charsets.UTF_8))
                    outStream.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { server.close() }
            }
        }
        thread.start()
        return port to thread
    }
}
