package io.aethervpn.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SingBoxConfigParserTest {

    @Test
    fun parsesVlessRealityVision() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@198.51.100.10:443" +
            "?type=tcp&security=reality&pbk=pubKey123&sid=ab12&sni=www.example.com&fp=chrome&flow=xtls-rprx-vision#MyReality"
        val parsed = SingBoxConfigParser.parse(uri).getOrThrow()
        assertEquals("VLESS", parsed.protocol)
        assertEquals("MyReality", parsed.name)
        assertFalse(parsed.isFullConfig)

        val out = parsed.outboundOrFullJson
        assertEquals("vless", out.getString("type"))
        assertEquals("198.51.100.10", out.getString("server"))
        assertEquals(443, out.getInt("server_port"))
        assertEquals("xtls-rprx-vision", out.getString("flow"))

        val tls = out.getJSONObject("tls")
        assertTrue(tls.getBoolean("enabled"))
        assertEquals("www.example.com", tls.getString("server_name"))
        assertEquals("pubKey123", tls.getJSONObject("reality").getString("public_key"))
        assertEquals("ab12", tls.getJSONObject("reality").getString("short_id"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun parsesVlessWsAndGrpcAndHttpUpgrade() {
        val wsUri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@ws.example.com:8443" +
            "?type=ws&security=tls&sni=ws.example.com&host=cdn.example.com&path=%2Fws#WSNode"
        val wsOut = SingBoxConfigParser.parse(wsUri).getOrThrow().outboundOrFullJson
        assertEquals("ws", wsOut.getJSONObject("transport").getString("type"))
        assertEquals("/ws", wsOut.getJSONObject("transport").getString("path"))
        assertEquals("cdn.example.com", wsOut.getJSONObject("transport").getJSONObject("headers").getString("Host"))

        val grpcUri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@grpc.example.com:443" +
            "?type=grpc&security=tls&serviceName=myGrpc#gRPCNode"
        val grpcOut = SingBoxConfigParser.parse(grpcUri).getOrThrow().outboundOrFullJson
        assertEquals("grpc", grpcOut.getJSONObject("transport").getString("type"))
        assertEquals("myGrpc", grpcOut.getJSONObject("transport").getString("service_name"))

        val httpUpUri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@hu.example.com:443" +
            "?type=httpupgrade&security=tls&host=hu.example.com&path=/upgrade#HUNode"
        val huOut = SingBoxConfigParser.parse(httpUpUri).getOrThrow().outboundOrFullJson
        assertEquals("httpupgrade", huOut.getJSONObject("transport").getString("type"))
        assertEquals("/upgrade", huOut.getJSONObject("transport").getString("path"))
        assertEquals("hu.example.com", huOut.getJSONObject("transport").getString("host"))
    }

    @Test
    fun parsesVmessBase64Json() {
        val json = """
            {"v":"2","ps":"VMess-NL","add":"203.0.113.5","port":"443","id":"12345678-1234-1234-1234-1234567890ab","aid":"0","scy":"auto","net":"ws","host":"nl.example.com","path":"/v2","tls":"tls","sni":"nl.example.com"}
        """.trimIndent()
        val uri = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val parsed = SingBoxConfigParser.parse(uri).getOrThrow()
        assertEquals("VMess", parsed.protocol)
        assertEquals("VMess-NL", parsed.name)
        val out = parsed.outboundOrFullJson
        assertEquals("vmess", out.getString("type"))
        assertEquals("203.0.113.5", out.getString("server"))
        assertEquals(443, out.getInt("server_port"))
        assertEquals("ws", out.getJSONObject("transport").getString("type"))
        assertTrue(out.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun parsesTrojanUri() {
        val uri = "trojan://secretPass@trojan.example.com:443?sni=sni.example.com&allowInsecure=1#TrojanDE"
        val parsed = SingBoxConfigParser.parse(uri).getOrThrow()
        assertEquals("Trojan", parsed.protocol)
        assertEquals("TrojanDE", parsed.name)
        val out = parsed.outboundOrFullJson
        assertEquals("trojan", out.getString("type"))
        assertEquals("secretPass", out.getString("password"))
        assertTrue(out.getJSONObject("tls").getBoolean("insecure"))
        assertEquals("sni.example.com", out.getJSONObject("tls").getString("server_name"))
    }

    @Test
    fun parsesShadowsocksSip002AndLegacy() {
        val sipUser = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:myPass123".toByteArray(Charsets.UTF_8))
        val sipUri = "ss://$sipUser@198.51.100.20:8388#SS-SIP002"
        val sipParsed = SingBoxConfigParser.parse(sipUri).getOrThrow()
        assertEquals("SS", sipParsed.protocol)
        assertEquals("SS-SIP002", sipParsed.name)
        assertEquals("shadowsocks", sipParsed.outboundOrFullJson.getString("type"))
        assertEquals("aes-256-gcm", sipParsed.outboundOrFullJson.getString("method"))
        assertEquals("myPass123", sipParsed.outboundOrFullJson.getString("password"))

        val legacyPayload = Base64.getEncoder()
            .encodeToString("chacha20-ietf-poly1305:legacyPass@198.51.100.21:9000".toByteArray(Charsets.UTF_8))
        val legacyParsed = SingBoxConfigParser.parse("ss://$legacyPayload#SS-Legacy").getOrThrow()
        assertEquals("198.51.100.21", legacyParsed.outboundOrFullJson.getString("server"))
        assertEquals(9000, legacyParsed.outboundOrFullJson.getInt("server_port"))
        assertEquals("chacha20-ietf-poly1305", legacyParsed.outboundOrFullJson.getString("method"))
    }

    @Test
    fun parsesHysteria2AndHysteria1() {
        val hy2Uri = "hy2://hy2pass@hy2.example.com:8443?sni=hy2.example.com&insecure=1&obfs=salamander&obfs-password=obfspass&upmbps=50&downmbps=100#HY2-FI"
        val hy2 = SingBoxConfigParser.parse(hy2Uri).getOrThrow()
        assertEquals("HY2", hy2.protocol)
        assertEquals("HY2-FI", hy2.name)
        val out = hy2.outboundOrFullJson
        assertEquals("hysteria2", out.getString("type"))
        assertEquals("salamander", out.getJSONObject("obfs").getString("type"))
        assertEquals(50, out.getInt("up_mbps"))
        assertEquals(100, out.getInt("down_mbps"))

        val hy1Uri = "hysteria://hy1.example.com:443?auth=authstr&upmbps=20&downmbps=80&peer=hy1.example.com#HY1"
        val hy1 = SingBoxConfigParser.parse(hy1Uri).getOrThrow()
        assertEquals("HY", hy1.protocol)
        assertEquals("hysteria", hy1.outboundOrFullJson.getString("type"))
        assertEquals("authstr", hy1.outboundOrFullJson.getString("auth_str"))
    }

    @Test
    fun parsesWireGuardAndSocksAndHttp() {
        val wgUri = "wireguard://privKeyBase64@198.51.100.30:51820?address=10.0.0.2/32,fd00::2/128&publickey=pubKeyBase64&mtu=1280&reserved=1,2,3#WG-Home"
        val wg = SingBoxConfigParser.parse(wgUri).getOrThrow()
        assertEquals("WG", wg.protocol)
        assertEquals("wireguard", wg.outboundOrFullJson.getString("type"))
        assertEquals("pubKeyBase64", wg.outboundOrFullJson.getString("peer_public_key"))
        assertEquals(2, wg.outboundOrFullJson.getJSONArray("local_address").length())
        assertEquals(3, wg.outboundOrFullJson.getJSONArray("reserved").length())

        val socks = SingBoxConfigParser.parse("socks5://user1:pass1@127.0.0.1:1080#LocalSocks").getOrThrow()
        assertEquals("SOCKS5", socks.protocol)
        assertEquals("socks", socks.outboundOrFullJson.getString("type"))
        assertEquals("user1", socks.outboundOrFullJson.getString("username"))

        val https = SingBoxConfigParser.parse("https://proxy.example.com:443#HttpsProxy").getOrThrow()
        assertEquals("HTTP", https.protocol)
        assertTrue(https.outboundOrFullJson.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun parsesRawJsonAndBuildsRuntimeConfigOnPort1825() {
        val fullJson = """
            {
              "inbounds": [{"type": "socks", "listen": "127.0.0.1", "listen_port": 1080}],
              "outbounds": [{"type": "direct", "tag": "direct"}]
            }
        """.trimIndent()
        val parsed = SingBoxConfigParser.parse(fullJson, "Custom JSON").getOrThrow()
        assertEquals("JSON", parsed.protocol)
        assertEquals("Custom JSON", parsed.name)
        assertTrue(parsed.isFullConfig)

        val runtime = JSONObject(SingBoxConfigParser.buildRuntimeConfig(fullJson, 1825).getOrThrow())
        val inbound = runtime.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("mixed", inbound.getString("type"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(1825, inbound.getInt("listen_port"))
    }

    @Test
    fun rejectsEmptyOrUnknownUri() {
        assertTrue(SingBoxConfigParser.parse("").isFailure)
        assertTrue(SingBoxConfigParser.parse("ftp://example.com").isFailure)
        assertTrue(SingBoxConfigParser.parse("{}").isFailure)
    }

    @Test
    fun buildsSingBox112DnsRoutingDialAndLogForVlessAndVmess() {
        val vlessUri = "vless://11111111-2222-3333-4444-555555555555@198.51.100.10:443?type=tcp&security=tls#VlessNode"
        val vmessJson = """
            {"v":"2","ps":"VMessNode","add":"203.0.113.5","port":"443","id":"12345678-1234-1234-1234-1234567890ab","aid":"0","scy":"auto","net":"tcp"}
        """.trimIndent()
        val vmessUri = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray(Charsets.UTF_8))

        for (uri in listOf(vlessUri, vmessUri)) {
            val runtime = JSONObject(SingBoxConfigParser.buildRuntimeConfig(uri).getOrThrow())

            // Log config: warn level, not disabled, no timestamp
            val log = runtime.getJSONObject("log")
            assertFalse(log.getBoolean("disabled"))
            assertEquals("warn", log.getString("level"))
            assertFalse(log.getBoolean("timestamp"))

            // Sing-box 1.12 typed DNS servers
            val dns = runtime.getJSONObject("dns")
            assertEquals("dns-remote", dns.getString("final"))
            val servers = dns.getJSONArray("servers")
            assertEquals(2, servers.length())

            val remoteDns = servers.getJSONObject(0)
            assertEquals("dns-remote", remoteDns.getString("tag"))
            assertEquals("https", remoteDns.getString("type"))
            assertEquals("1.1.1.1", remoteDns.getString("server"))
            assertEquals("proxy", remoteDns.getString("detour"))
            assertFalse(remoteDns.has("address"))

            val localDns = servers.getJSONObject(1)
            assertEquals("dns-local", localDns.getString("tag"))
            assertEquals("udp", localDns.getString("type"))
            assertEquals("1.1.1.1", localDns.getString("server"))
            assertEquals("direct", localDns.getString("detour"))
            assertFalse(localDns.has("address"))

            // Proxy outbound dial keep-alive & timeout fields
            val proxyOutbound = runtime.getJSONArray("outbounds").getJSONObject(0)
            assertEquals("proxy", proxyOutbound.getString("tag"))
            assertEquals("30s", proxyOutbound.getString("tcp_keep_alive"))
            assertEquals("15s", proxyOutbound.getString("tcp_keep_alive_interval"))
            assertEquals("10s", proxyOutbound.getString("connect_timeout"))

            // Route rules: sniff, hijack-dns, ip_is_private -> direct, quic -> reject
            val route = runtime.getJSONObject("route")
            assertEquals("dns-local", route.getString("default_domain_resolver"))
            val rules = route.getJSONArray("rules")
            val ruleList = (0 until rules.length()).map { rules.getJSONObject(it) }

            assertTrue(ruleList.any { it.optString("action") == "sniff" })
            assertTrue(ruleList.any { it.optString("protocol") == "dns" && it.optString("action") == "hijack-dns" })
            assertTrue(ruleList.any { it.optBoolean("ip_is_private", false) && it.optString("outbound") == "direct" })
            assertTrue(ruleList.any { it.optString("protocol") == "quic" && it.optString("action") == "reject" })
        }
    }

    @Test
    fun omitsQuicRejectRuleForUdpBasedOutbound() {
        val hy2Uri = "hy2://hy2pass@hy2.example.com:8443?sni=hy2.example.com#HY2"
        val runtime = JSONObject(SingBoxConfigParser.buildRuntimeConfig(hy2Uri).getOrThrow())
        val rules = runtime.getJSONObject("route").getJSONArray("rules")
        val ruleList = (0 until rules.length()).map { rules.getJSONObject(it) }

        assertTrue(ruleList.any { it.optString("protocol") == "dns" && it.optString("action") == "hijack-dns" })
        assertFalse(ruleList.any { it.optString("protocol") == "quic" && it.optString("action") == "reject" })
    }

    @Test
    fun stripsAnsiEscapeCodesFromSingBoxLogs() {
        val raw = "\u001B[33mWARN\u001B[0m[0005] \u001B[1;31mconnection reset\u001B[0m"
        assertEquals("WARN[0005] connection reset", SingBoxEngineManager.stripAnsiCodes(raw))
        assertEquals("plain log line", SingBoxEngineManager.stripAnsiCodes("plain log line"))
    }
}
