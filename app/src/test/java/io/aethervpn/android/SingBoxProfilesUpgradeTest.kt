package io.aethervpn.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SingBoxProfilesUpgradeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun richProtocolBadgeFormatsProtocolTransportAndSecurity() {
        val vlessWsTls = "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=ws&security=tls&path=/ws#VLESS-WS"
        assertEquals("VLESS • WS + TLS", SingBoxConfigParser.formatRichProtocolBadge(vlessWsTls))

        val vlessReality = "vless://11111111-2222-3333-4444-555555555555@198.51.100.10:443?type=tcp&security=reality&pbk=abc123&sni=www.google.com#Reality"
        assertEquals("VLESS • TCP + Reality", SingBoxConfigParser.formatRichProtocolBadge(vlessReality))

        val trojanGrpcTls = "trojan://secret@example.com:443?type=grpc&security=tls&serviceName=grpc#Trojan-gRPC"
        assertEquals("Trojan • gRPC + TLS", SingBoxConfigParser.formatRichProtocolBadge(trojanGrpcTls))

        val ssTcp = "ss://YWVzLTEyOC1nY206cGFzc3dvcmQ=@198.51.100.20:8388#SS-Node"
        assertEquals("SS • TCP", SingBoxConfigParser.formatRichProtocolBadge(ssTcp))
    }

    @Test
    fun countryBadgeExtractsFromTraceOrProfileName() {
        assertEquals("🇩🇪 DE", SingBoxConfigParser.extractCountryBadge("Server 1", lastCountry = "DE"))
        assertEquals("🇺🇸 US", SingBoxConfigParser.extractCountryBadge("🇺🇸 New York 01", lastCountry = null))
        assertEquals("🇳🇱 NL", SingBoxConfigParser.extractCountryBadge("[NL] Amsterdam Fast", lastCountry = null))
    }

    @Test
    fun subscriptionGroupingAndFilteringBySubscriptionId() {
        val dir = tempFolder.newFolder("sub_tabs")

        val manual = SingBoxProfileRepository.addProfile(
            dir,
            "vless://11111111-2222-3333-4444-555555555555@198.51.100.1:443?security=tls#ManualNode"
        ).getOrThrow()

        val sub1Payload = """
            vless://11111111-2222-3333-4444-555555555555@198.51.100.2:443?security=tls#Sub1-A
            trojan://pass@198.51.100.3:443#Sub1-B
        """.trimIndent()
        val (sub1Profiles, _) = SingBoxProfileRepository.addSubscriptionPayload(
            filesDir = dir,
            rawPayload = sub1Payload,
            subscriptionId = "sub-alpha",
            subscriptionName = "AlphaSub",
            subscriptionUrl = "https://alpha.example.com/sub"
        ).getOrThrow()

        val sub2Payload = "ss://YWVzLTEyOC1nY206cGFzc3dvcmQ=@198.51.100.4:8388#Sub2-A"
        val (sub2Profiles, _) = SingBoxProfileRepository.addSubscriptionPayload(
            filesDir = dir,
            rawPayload = sub2Payload,
            subscriptionId = "sub-beta",
            subscriptionName = "BetaSub",
            subscriptionUrl = "https://beta.example.com/sub"
        ).getOrThrow()

        val loaded = SingBoxProfileRepository.load(dir)
        assertEquals(4, loaded.profiles.size)

        val subs = SingBoxProfileRepository.extractSubscriptions(loaded)
        assertEquals(2, subs.size)
        assertEquals("AlphaSub", subs[0].name)
        assertEquals("BetaSub", subs[1].name)

        assertEquals(4, SingBoxProfileRepository.filterProfilesByTab(loaded.profiles, SingBoxProfileRepository.TAB_ALL_ID).size)

        val manualOnly = SingBoxProfileRepository.filterProfilesByTab(loaded.profiles, SingBoxProfileRepository.TAB_MANUAL_ID)
        assertEquals(1, manualOnly.size)
        assertEquals(manual.id, manualOnly.first().id)

        val alphaOnly = SingBoxProfileRepository.filterProfilesByTab(loaded.profiles, "sub-alpha")
        assertEquals(2, alphaOnly.size)
        assertEquals(sub1Profiles.map { it.id }, alphaOnly.map { it.id })

        val betaOnly = SingBoxProfileRepository.filterProfilesByTab(loaded.profiles, "sub-beta")
        assertEquals(1, betaOnly.size)
        assertEquals(sub2Profiles.first().id, betaOnly.first().id)
    }

    @Test
    fun prettyPrintedSingBoxJsonExportMatchesParserRuntimeConfig() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@198.51.100.55:443?type=ws&security=tls&sni=cdn.example.com&path=/ws#ExportTest"
        val cfg = VpnConfig(
            sbRemoteDns = "8.8.8.8",
            sbTlsFragment = true,
            sbLogLevel = "info"
        )
        val exported = SingBoxConfigParser.exportPrettyRuntimeConfig(uri, cfg).getOrThrow()
        val expected = SingBoxConfigParser.buildRuntimeConfig(uri, config = cfg).getOrThrow()

        assertEquals(expected, exported)
        assertTrue(exported.contains("\n  \""))

        val root = JSONObject(exported)
        assertEquals("info", root.getJSONObject("log").getString("level"))
        val proxyOutbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertTrue(proxyOutbound.getJSONObject("tls").getBoolean("fragment"))
    }

    @Test
    fun customSpeedTestUrlFallsBackToDefaultWhenBlank() {
        val defaultCfg = VpnConfig()
        assertEquals(VpnConfig.DEFAULT_SB_SPEED_TEST_URL, defaultCfg.effectiveSbSpeedTestUrl())

        val blankCfg = VpnConfig(sbSpeedTestUrl = "   ")
        assertEquals(VpnConfig.DEFAULT_SB_SPEED_TEST_URL, blankCfg.effectiveSbSpeedTestUrl())

        val customUrl = "https://example.org/test-1mb.bin"
        val customCfg = VpnConfig(sbSpeedTestUrl = customUrl)
        assertEquals(customUrl, customCfg.effectiveSbSpeedTestUrl())

        val restored = VpnConfig.fromBackupJson(customCfg.toSafeBackupJson()).getOrThrow()
        assertEquals(customUrl, restored.effectiveSbSpeedTestUrl())

        val reset = customCfg.resetSingBoxTuningDefaults()
        assertEquals(VpnConfig.DEFAULT_SB_SPEED_TEST_URL, reset.effectiveSbSpeedTestUrl())
        assertFalse(reset.effectiveSbSpeedTestUrl().isBlank())

        val parsedTarget = SingBoxEngineManager.parseSpeedProbeUrl(VpnConfig.DEFAULT_SB_SPEED_TEST_URL)
        assertEquals("speed.cloudflare.com", parsedTarget.host)
        assertEquals(443, parsedTarget.port)
        assertEquals(1_048_576, parsedTarget.expectedBytes)
        assertTrue(parsedTarget.useTls)
    }
}
