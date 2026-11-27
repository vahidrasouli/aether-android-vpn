package io.aethervpn.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
            "TUN (172.19.0.1) -> HEV -> Soar (127.0.0.1:1829) -> DNS (tcp, IR)",
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
}
