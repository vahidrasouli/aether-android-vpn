package io.aethervpn.android.core

import io.aethervpn.android.vpn.AetherVpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

class SoarIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleProfile(): SoarProfile {
        return SoarProfile(
            id = "whitedns-public-01",
            name = "WhiteDNS Public",
            domain = "t.whitedns.example.org",
            publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            resolvers = listOf("8.8.8.8:53", "1.1.1.1:53"),
            soarSettings = SoarSettings(
                transport = "tcp",
                discovery = true,
                country = "IR",
                discoveryWant = 24,
                discoveryConcurrency = 12,
                discoveryPaceMs = 35,
                safeQName = 96,
                maxQName = 240,
                maxAnswer = 1100,
                holdMs = 450,
                maxInflight = 192,
                maxPathWindow = 40,
                maxStreams = 48,
                streamWindowKiB = 512,
                sendBufferKiB = 128,
                sessionIdleSeconds = 75,
                dialTimeoutSeconds = 20
            )
        )
    }

    @Test
    fun `SoarProfileParser round-trips WhiteDNS Public schema and all 17 soarSettings`() {
        val original = sampleProfile()
        val uri = SoarProfileParser.exportUri(original)
        assertTrue(uri.startsWith("soar://"))

        val decodedResult = SoarProfileParser.parseUri(uri)
        assertTrue(decodedResult.isSuccess)
        val decoded = decodedResult.getOrThrow()

        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.domain, decoded.domain)
        assertEquals(original.publicKey, decoded.publicKey)
        assertEquals(original.resolvers, decoded.resolvers)

        val s = decoded.soarSettings
        assertEquals("tcp", s.transport)
        assertTrue(s.discovery)
        assertEquals("IR", s.country)
        assertEquals(24, s.discoveryWant)
        assertEquals(12, s.discoveryConcurrency)
        assertEquals(35, s.discoveryPaceMs)
        assertEquals(96, s.safeQName)
        assertEquals(240, s.maxQName)
        assertEquals(1100, s.maxAnswer)
        assertEquals(450, s.holdMs)
        assertEquals(192, s.maxInflight)
        assertEquals(40, s.maxPathWindow)
        assertEquals(48, s.maxStreams)
        assertEquals(512, s.streamWindowKiB)
        assertEquals(128, s.sendBufferKiB)
        assertEquals(75, s.sessionIdleSeconds)
        assertEquals(20, s.dialTimeoutSeconds)
    }

    @Test
    fun `SoarProfileParser applies defaults when optional soarSettings are omitted`() {
        val minimalJson = """
            {
              "schema": "whitedns.profile",
              "version": 1,
              "profile": {
                "id": "min-1",
                "name": "Minimal",
                "server": {
                  "domain": "dns.example.com",
                  "public_key": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
                }
              }
            }
        """.trimIndent()
        val parsed = SoarProfileParser.parseJson(minimalJson).getOrThrow()
        val defaults = SoarSettings()
        assertEquals(defaults, parsed.soarSettings)
        assertTrue(parsed.resolvers.isEmpty())
    }

    @Test
    fun `SoarEngineManager generates complete runtime JSON config and CLI args`() {
        val profile = sampleProfile()
        val runtimeJson = SoarEngineManager.buildRuntimeConfigJson(profile)
        val obj = JSONObject(runtimeJson)

        assertEquals(profile.domain, obj.getString("domain"))
        assertEquals(profile.publicKey, obj.getString("public_key"))
        assertEquals(2, obj.getJSONArray("resolvers").length())
        assertEquals("8.8.8.8:53", obj.getJSONArray("resolvers").getString(0))
        assertEquals("tcp", obj.getString("transport"))
        assertTrue(obj.getBoolean("discovery"))
        assertEquals("IR", obj.getString("country"))
        assertEquals(24, obj.getInt("discoveryWant"))
        assertEquals(12, obj.getInt("discoveryConcurrency"))
        assertEquals(35, obj.getInt("discoveryPaceMs"))
        assertEquals(96, obj.getInt("safeQName"))
        assertEquals(240, obj.getInt("maxQName"))
        assertEquals(1100, obj.getInt("maxAnswer"))
        assertEquals(450, obj.getInt("holdMs"))
        assertEquals(192, obj.getInt("maxInflight"))
        assertEquals(40, obj.getInt("maxPathWindow"))
        assertEquals(48, obj.getInt("maxStreams"))
        assertEquals(512, obj.getInt("streamWindowKiB"))
        assertEquals(128, obj.getInt("sendBufferKiB"))
        assertEquals(75, obj.getInt("sessionIdleSeconds"))
        assertEquals(20, obj.getInt("dialTimeoutSeconds"))

        val args = SoarEngineManager.buildCommandArgs(
            binaryPath = "/data/app/lib/arm64/libsoar.so",
            configPath = "/data/user/0/io.aethervpn.android/files/soar/soar-config.json"
        )
        assertEquals(
            listOf(
                "/data/app/lib/arm64/libsoar.so",
                "--config",
                "/data/user/0/io.aethervpn.android/files/soar/soar-config.json",
                "--listen",
                "127.0.0.1:1829"
            ),
            args
        )
    }

    @Test
    fun `SoarEngineManager formats Proxy Only and VPN chain headers and tg socks URI`() {
        val profile = sampleProfile()
        val proxyChain = SoarEngineManager.buildChainLog(SoarConnectionMode.PROXY_ONLY, profile)
        assertEquals(
            "Local SOCKS5 (127.0.0.1:1829) -> Soar -> DNS (tcp, IR)",
            proxyChain
        )

        val vpnChain = SoarEngineManager.buildChainLog(SoarConnectionMode.VPN_FULL_DEVICE, profile)
        assertEquals(
            "TUN (172.19.0.1, mapdns=198.18.0.2) -> HEV -> Soar (127.0.0.1:1829) -> DNS (tcp, IR)",
            vpnChain
        )

        assertEquals(
            "tg://socks?server=127.0.0.1&port=1829",
            SoarEngineManager.buildTelegramSocksUri()
        )
    }

    @Test
    fun `SoarProfileStore persists profiles, active selection, and connection mode`() {
        val dir = tempFolder.newFolder("soar_store")
        val p1 = sampleProfile()
        val p2 = p1.copy(id = "p2", name = "Second", soarSettings = p1.soarSettings.copy(discovery = false))

        var state = SoarProfileStore.upsertProfile(dir, p1, setActive = true)
        state = SoarProfileStore.upsertProfile(dir, p2, setActive = false)
        state = SoarProfileStore.setConnectionMode(dir, SoarConnectionMode.VPN_FULL_DEVICE)

        val reloaded = SoarProfileStore.load(dir)
        assertEquals(2, reloaded.profiles.size)
        assertEquals(p1.id, reloaded.activeProfile?.id)
        assertEquals(SoarConnectionMode.VPN_FULL_DEVICE, reloaded.connectionMode)

        val afterDelete = SoarProfileStore.deleteProfile(dir, p1.id)
        assertEquals(1, afterDelete.profiles.size)
        assertEquals(p2.id, afterDelete.activeProfile?.id)
        assertFalse(afterDelete.profiles.any { it.id == p1.id })
    }

    @Test
    fun `SoarResolverPackStore supports CRUD and auto-extraction from soar import`() {
        val dir = tempFolder.newFolder("soar_packs")
        val imported = sampleProfile()

        val afterExtract = SoarResolverPackStore.extractDefaultPackFromProfile(dir, imported)
        assertEquals(1, afterExtract.packs.size)
        val defaultPack = afterExtract.packs.first()
        assertEquals("WhiteDNS Public Default", defaultPack.name)
        assertEquals(listOf("8.8.8.8:53", "1.1.1.1:53"), defaultPack.resolvers)
        assertEquals(defaultPack.id, afterExtract.activePackId)

        // Importing a profile without resolvers is a no-op
        val emptyProfile = imported.copy(id = "empty", name = "Empty", resolvers = emptyList())
        val afterEmpty = SoarResolverPackStore.extractDefaultPackFromProfile(dir, emptyProfile)
        assertEquals(1, afterEmpty.packs.size)

        // Upsert, rename, export, and delete
        val customPack = ResolverPack(id = "pack-2", name = "Resolver 1", resolvers = listOf("9.9.9.9", "4.2.2.4"))
        var state = SoarResolverPackStore.upsertPack(dir, customPack, setActive = true)
        assertEquals(2, state.packs.size)
        assertEquals("pack-2", state.activePack?.id)

        state = SoarResolverPackStore.renamePack(dir, "pack-2", "Iran Clean DNS")
        assertEquals("Iran Clean DNS", state.activePack?.name)
        assertEquals("9.9.9.9\n4.2.2.4", SoarResolverPackStore.exportPackToText(state.activePack!!))

        state = SoarResolverPackStore.deletePack(dir, "pack-2")
        assertEquals(1, state.packs.size)
        assertNull(state.activePackId)
    }

    @Test
    fun `SoarEngineManager injects active ResolverPack into runtime JSON config`() {
        val profile = sampleProfile()
        val pack = ResolverPack(
            id = "clean-pack",
            name = "Clean 3",
            resolvers = listOf("9.9.9.9", "149.112.112.112", "4.2.2.1")
        )

        val jsonWithPack = JSONObject(SoarEngineManager.buildRuntimeConfigJson(profile, pack))
        val arr = jsonWithPack.getJSONArray("resolvers")
        assertEquals(3, arr.length())
        assertEquals("9.9.9.9", arr.getString(0))
        assertEquals("149.112.112.112", arr.getString(1))
        assertEquals("4.2.2.1", arr.getString(2))

        val jsonWithoutPack = JSONObject(SoarEngineManager.buildRuntimeConfigJson(profile, null))
        val fallbackArr = jsonWithoutPack.getJSONArray("resolvers")
        assertEquals(2, fallbackArr.length())
        assertEquals("8.8.8.8:53", fallbackArr.getString(0))
    }

    @Test
    fun `SoarResolverScanner parses txt lines, clamps concurrency, and validates DNS responses`() {
        val rawTxt = """
            # Comment line
            // Another comment
            
            8.8.8.8
            1.1.1.1:53 # inline comment
            999.1.1.1
            not-an-ip
            8.8.8.8
            9.9.9.9, 4.2.2.4
        """.trimIndent()

        val parsed = SoarResolverScanner.parseResolverStream(
            ByteArrayInputStream(rawTxt.toByteArray(Charsets.UTF_8))
        )
        assertEquals(listOf("8.8.8.8", "1.1.1.1", "9.9.9.9", "4.2.2.4"), parsed)

        assertEquals(1, SoarResolverScanner.clampConcurrency(0))
        assertEquals(25, SoarResolverScanner.clampConcurrency(25))
        assertEquals(100, SoarResolverScanner.clampConcurrency(500))

        // DNS packet builder + NOERROR vs NXDOMAIN response validation
        val txId = 0x1234
        val query = SoarResolverScanner.buildDnsQueryPacket(txId, "p1.t.example.org")
        assertTrue(query.size > 12)
        assertEquals(0x12.toByte(), query[0])
        assertEquals(0x34.toByte(), query[1])

        // Valid NOERROR response
        val validResp = byteArrayOf(
            0x12, 0x34, // txId
            0x81.toByte(), 0x80.toByte(), // QR=1, RCODE=0
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertTrue(SoarResolverScanner.isValidDnsZoneResponse(validResp, validResp.size, txId))

        // NXDOMAIN (RCODE=3) rejected
        val nxDomainResp = validResp.copyOf().apply { this[3] = 0x83.toByte() }
        assertFalse(SoarResolverScanner.isValidDnsZoneResponse(nxDomainResp, nxDomainResp.size, txId))

        // Wrong TXID rejected
        assertFalse(SoarResolverScanner.isValidDnsZoneResponse(validResp, validResp.size, 0x9999))
    }

    @Test
    fun `SoarResolverScanner pause and resume transitions are lossless`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val firstBatchGate = CompletableDeferred<Unit>()
            val resumeBatchGate = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)

            val scanner = SoarResolverScanner(
                scope = scope,
                probeFn = { ip, _, _ ->
                    val n = calls.incrementAndGet()
                    if (n == 2) firstBatchGate.complete(Unit)
                    if (n > 2) resumeBatchGate.await()
                    ip == "1.1.1.1" || ip == "8.8.8.8" || ip == "9.9.9.9"
                }
            )

            val ips = listOf("1.1.1.1", "2.2.2.2", "8.8.8.8", "4.4.4.4", "9.9.9.9")
            scanner.start(ips, targetDomain = "t.example.org", concurrency = 1)

            firstBatchGate.await()
            scanner.pause()
            delay(50)

            val pausedState = scanner.state.value
            assertEquals(ScannerStatus.PAUSED, pausedState.status)
            assertTrue(pausedState.healthyCount >= 1)
            assertTrue(pausedState.scannedCount < ips.size)

            resumeBatchGate.complete(Unit)
            scanner.resume()

            var attempts = 0
            while (scanner.state.value.status != ScannerStatus.COMPLETED && attempts < 40) {
                delay(25)
                attempts++
            }

            val finalState = scanner.state.value
            assertEquals(ScannerStatus.COMPLETED, finalState.status)
            assertEquals(5, finalState.totalCount)
            assertEquals(5, finalState.scannedCount)
            assertEquals(3, finalState.healthyCount)
            assertEquals(2, finalState.rejectedCount)
            assertEquals(100, finalState.progressPercent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `AetherVpnService Soar VPN mode disallows self package and enables HEV mapdns`() {
        val selfPkg = "io.aethervpn.android"
        val configs = listOf(
            VpnConfig(splitTunnelMode = SplitTunnelMode.ALL_APPS),
            VpnConfig(splitTunnelMode = SplitTunnelMode.ONLY_SELECTED, selectedPackages = setOf("com.whatsapp")),
            VpnConfig(splitTunnelMode = SplitTunnelMode.BYPASS_SELECTED, selectedPackages = setOf("com.example.bank"))
        )
        for (cfg in configs) {
            val disallowed = AetherVpnService.soarDisallowedPackages(cfg, selfPkg)
            assertTrue("Self package must always be disallowed in Soar VPN mode", selfPkg in disallowed)
        }
        assertTrue(
            "com.example.bank" in AetherVpnService.soarDisallowedPackages(configs[2], selfPkg)
        )

        val hevManager = HevTunnelManager(tempFolder.newFolder("hev_soar"))
        val yaml = AetherVpnService.generateSoarHevYaml(
            hevManager,
            VpnConfig(mapDnsEnabled = false, customDns = "8.8.8.8")
        )
        assertTrue(yaml.contains("port: 1829"))
        assertTrue(yaml.contains("address: '127.0.0.1'"))
        assertTrue("Soar VPN must force mapdns enabled", yaml.contains("mapdns:"))
        assertTrue(yaml.contains("address: 198.18.0.2"))
        assertTrue(yaml.contains("network: 198.18.0.0"))
        assertTrue(yaml.contains("netmask: 255.254.0.0"))
    }

    @Test
    fun `SoarResolverBenchmark computes jitter, packet loss and sorts 0 percent loss + lowest jitter first`() {
        val stableLowJitter = SoarResolverBenchmark.computeMetrics(
            ip = "1.1.1.1",
            rttsMs = listOf(40L, 42L, 41L, 43L, 41L), // diffs: 2, 1, 2, 2 -> avg=1.75 -> 2ms
            totalProbes = 5
        )
        assertEquals(0, stableLowJitter.lossPercent)
        assertEquals(41, stableLowJitter.avgPingMs)
        assertEquals(2, stableLowJitter.jitterMs)

        val fastButJittery = SoarResolverBenchmark.computeMetrics(
            ip = "8.8.8.8",
            rttsMs = listOf(20L, 80L, 20L, 80L, 20L), // diffs: 60, 60, 60, 60 -> 60ms
            totalProbes = 5
        )
        assertEquals(0, fastButJittery.lossPercent)
        assertEquals(44, fastButJittery.avgPingMs)
        assertEquals(60, fastButJittery.jitterMs)

        val packetLoss = SoarResolverBenchmark.computeMetrics(
            ip = "9.9.9.9",
            rttsMs = listOf(15L, null, 16L, 15L, null), // 2 lost out of 5 -> 40%
            totalProbes = 5
        )
        assertEquals(40, packetLoss.lossPercent)
        assertEquals(15, packetLoss.avgPingMs)

        val unreachable = SoarResolverBenchmark.computeMetrics(
            ip = "4.2.2.4",
            rttsMs = listOf(null, null, null, null, null),
            totalProbes = 5
        )
        assertEquals(100, unreachable.lossPercent)
        assertTrue(unreachable.isUnreachable)

        val sorted = SoarResolverBenchmark.sortByQuality(
            listOf(unreachable, packetLoss, fastButJittery, stableLowJitter)
        )
        assertEquals(listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "4.2.2.4"), sorted.map { it.ip })
    }

    @Test
    fun `SoarPreset applies expected tuning values and SoarEngineManager parses resolver telemetry`() {
        val base = SoarSettings()
        val turbo = base.withPreset(SoarPreset.TURBO)
        assertEquals("udp", turbo.transport)
        assertEquals(1232, turbo.maxAnswer)
        assertEquals(128, turbo.maxInflight)
        assertEquals(40, turbo.maxPathWindow)
        assertEquals(150, turbo.holdMs)
        assertEquals(90, turbo.sessionIdleSeconds)
        assertEquals(SoarPreset.TURBO, turbo.matchingPreset())

        val strict = base.withPreset(SoarPreset.STRICT)
        assertEquals("tcp", strict.transport)
        assertEquals(512, strict.maxAnswer)
        assertEquals(32, strict.maxInflight)
        assertEquals(12, strict.maxPathWindow)
        assertEquals(500, strict.holdMs)
        assertEquals(240, strict.sessionIdleSeconds)
        assertEquals(SoarPreset.STRICT, strict.matchingPreset())

        assertEquals(
            14 to 48,
            SoarEngineManager.parseResolverTelemetry(
                "Resolvers: active=14 verified=20 discovered=12 total=48 established=3"
            )
        )
        assertNull(SoarEngineManager.parseResolverTelemetry("SOCKS5 ready on 127.0.0.1:1829"))
    }

    @Test
    fun `SoarSpeedTester builds valid SOCKS5 ATYP 0x03 domain connect request`() {
        val domain = "speed.cloudflare.com"
        val req = SoarSpeedTester.buildSocks5ConnectRequest(domain, 443)
        assertEquals(7 + domain.length, req.size)
        assertEquals(0x05.toByte(), req[0]) // VER = 5
        assertEquals(0x01.toByte(), req[1]) // CMD = CONNECT
        assertEquals(0x00.toByte(), req[2]) // RSV = 0
        assertEquals(0x03.toByte(), req[3]) // ATYP = DOMAINNAME
        assertEquals(domain.length.toByte(), req[4])
        assertEquals(domain, String(req, 5, domain.length, Charsets.US_ASCII))
        assertEquals(0x01.toByte(), req[5 + domain.length]) // 443 >> 8 = 1
        assertEquals(0xBB.toByte(), req[6 + domain.length]) // 443 & 0xFF = 0xBB
    }
}
