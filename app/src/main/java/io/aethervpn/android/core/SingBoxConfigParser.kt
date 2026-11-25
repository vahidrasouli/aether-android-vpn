package io.aethervpn.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

data class ParsedSingBoxProfile(
    val name: String,
    val protocol: String,
    val isFullConfig: Boolean,
    val outboundOrFullJson: JSONObject
)

/**
 * Universal URI & JSON parser for SagerNet/sing-box.
 *
 * Supports:
 * - `vless://` (Reality, Vision, WS, gRPC, HTTPUpgrade, TLS)
 * - `vmess://` (base64 JSON)
 * - `trojan://`
 * - `ss://` (SIP002 & legacy base64)
 * - `hy2://`, `hysteria2://`, `hysteria://`
 * - `wireguard://`, `wg://`
 * - `socks://`, `socks5://`, `http://`, `https://`
 * - Raw `sing-box` JSON (either full config or single outbound object)
 */
object SingBoxConfigParser {

    fun parse(rawInput: String, customName: String? = null): Result<ParsedSingBoxProfile> = runCatching {
        val trimmed = rawInput.trim()
        require(trimmed.isNotEmpty()) { "Profile input cannot be empty" }

        if (trimmed.startsWith("{")) {
            return@runCatching parseRawJson(trimmed, customName)
        }

        val scheme = trimmed.substringBefore("://", "").lowercase()
        require(scheme.isNotEmpty()) { "Unsupported format: expected URI scheme or JSON object" }

        when (scheme) {
            "vless" -> parseVless(trimmed, customName)
            "vmess" -> parseVmess(trimmed, customName)
            "trojan" -> parseTrojan(trimmed, customName)
            "ss" -> parseShadowsocks(trimmed, customName)
            "hy2", "hysteria2" -> parseHysteria2(trimmed, customName)
            "hysteria" -> parseHysteria1(trimmed, customName)
            "wireguard", "wg" -> parseWireGuard(trimmed, customName)
            "socks", "socks5" -> parseSocks(trimmed, customName)
            "http", "https" -> parseHttp(trimmed, scheme == "https", customName)
            else -> throw IllegalArgumentException("Unsupported protocol scheme: $scheme://")
        }
    }

    fun detectProtocolBadge(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.startsWith("{")) return "JSON"
        return when (trimmed.substringBefore("://", "").lowercase()) {
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "ss" -> "SS"
            "hy2", "hysteria2" -> "HY2"
            "hysteria" -> "HY"
            "wireguard", "wg" -> "WG"
            "socks", "socks5" -> "SOCKS5"
            "http", "https" -> "HTTP"
            else -> "RAW"
        }
    }

    fun buildRuntimeConfig(
        rawUriOrJson: String,
        listenPort: Int = VpnConfig.SING_BOX_SOCKS_PORT
    ): Result<String> = runCatching {
        val parsed = parse(rawUriOrJson).getOrThrow()
        val root = if (parsed.isFullConfig) {
            JSONObject(parsed.outboundOrFullJson.toString()).apply {
                put("log", JSONObject().apply {
                    put("level", "info")
                    put("timestamp", false)
                })
                val existingInbounds = optJSONArray("inbounds")
                val newInbounds = JSONArray()
                newInbounds.put(buildMixedInbound(listenPort))
                if (existingInbounds != null) {
                    for (i in 0 until existingInbounds.length()) {
                        val ib = existingInbounds.optJSONObject(i) ?: continue
                        val type = ib.optString("type")
                        if (type != "mixed" && type != "socks" && type != "tun") {
                            newInbounds.put(ib)
                        }
                    }
                }
                put("inbounds", newInbounds)
            }
        } else {
            val outbound = JSONObject(parsed.outboundOrFullJson.toString()).apply {
                put("tag", "proxy")
            }
            JSONObject().apply {
                put("log", JSONObject().apply {
                    put("level", "info")
                    put("timestamp", false)
                })
                put("dns", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("tag", "dns-remote")
                            put("address", "1.1.1.1")
                            put("detour", "proxy")
                        })
                        put(JSONObject().apply {
                            put("tag", "dns-local")
                            put("address", "local")
                            put("detour", "direct")
                        })
                    })
                    put("strategy", "ipv4_only")
                })
                put("inbounds", JSONArray().apply {
                    put(buildMixedInbound(listenPort))
                })
                put("outbounds", JSONArray().apply {
                    put(outbound)
                    put(JSONObject().apply {
                        put("type", "direct")
                        put("tag", "direct")
                    })
                })
                put("route", JSONObject().apply {
                    put("final", "proxy")
                    put("auto_detect_interface", false)
                })
            }
        }
        root.toString(2)
    }

    private fun buildMixedInbound(listenPort: Int): JSONObject = JSONObject().apply {
        put("type", "mixed")
        put("tag", "mixed-in")
        put("listen", VpnConfig.LOOPBACK_HOST)
        put("listen_port", listenPort)
        put("sniff", true)
    }

    private fun parseRawJson(trimmed: String, customName: String?): ParsedSingBoxProfile {
        val obj = JSONObject(trimmed)
        val hasOutbounds = obj.has("outbounds") && obj.optJSONArray("outbounds") != null
        val hasType = obj.optString("type").isNotBlank()
        require(hasOutbounds || hasType) {
            "JSON must be a valid sing-box config (with 'outbounds') or an outbound object (with 'type')"
        }
        val detectedName = customName?.takeIf { it.isNotBlank() }
            ?: obj.optString("tag").takeIf { it.isNotBlank() }
            ?: "sing-box JSON"
        return ParsedSingBoxProfile(
            name = detectedName,
            protocol = "JSON",
            isFullConfig = hasOutbounds,
            outboundOrFullJson = obj
        )
    }

    private fun parseVless(uriStr: String, customName: String?): ParsedSingBoxProfile {
        val uri = parseUri(uriStr)
        val uuid = urlDecode(uri.userInfo.orEmpty())
        require(uuid.isNotBlank()) { "VLESS URI missing UUID" }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("VLESS URI missing host")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryParams(uri.rawQuery)
        val name = resolveName(customName, uri.rawFragment, "VLESS $host:$port")

        val outbound = JSONObject().apply {
            put("type", "vless")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            params["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
            put("packet_encoding", params["packetEncoding"]?.takeIf { it.isNotBlank() } ?: "xudp")

            buildTlsObject(params, defaultSni = host, forceTls = false)?.let { put("tls", it) }
            buildTransportObject(params)?.let { put("transport", it) }
        }

        return ParsedSingBoxProfile(name = name, protocol = "VLESS", isFullConfig = false, outboundOrFullJson = outbound)
    }

    private fun parseVmess(uriStr: String, customName: String?): ParsedSingBoxProfile {
        val encoded = uriStr.removePrefix("vmess://").substringBefore("#").trim()
        require(encoded.isNotBlank()) { "VMess URI missing payload" }
        val jsonStr = decodeBase64String(encoded)
        val v = JSONObject(jsonStr)

        val host = v.optString("add").trim()
        require(host.isNotBlank()) { "VMess JSON missing 'add' server" }
        val port = v.opt("port")?.toString()?.toIntOrNull()
            ?: throw IllegalArgumentException("VMess JSON missing valid 'port'")
        val uuid = v.optString("id").trim()
        require(uuid.isNotBlank()) { "VMess JSON missing 'id' UUID" }

        val ps = v.optString("ps").trim()
        val name = customName?.takeIf { it.isNotBlank() } ?: ps.ifBlank { "VMess $host:$port" }

        val params = mutableMapOf<String, String>()
        v.optString("net").takeIf { it.isNotBlank() }?.let { params["type"] = it }
        v.optString("host").takeIf { it.isNotBlank() }?.let { params["host"] = it }
        v.optString("path").takeIf { it.isNotBlank() }?.let { params["path"] = it }
        v.optString("tls").takeIf { it.isNotBlank() }?.let { params["security"] = it }
        v.optString("sni").takeIf { it.isNotBlank() }?.let { params["sni"] = it }
        v.optString("alpn").takeIf { it.isNotBlank() }?.let { params["alpn"] = it }
        v.optString("fp").takeIf { it.isNotBlank() }?.let { params["fp"] = it }

        val outbound = JSONObject().apply {
            put("type", "vmess")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("security", v.optString("scy").ifBlank { "auto" })
            put("alter_id", v.opt("aid")?.toString()?.toIntOrNull() ?: 0)

            buildTlsObject(params, defaultSni = v.optString("sni").ifBlank { host }, forceTls = false)
                ?.let { put("tls", it) }
            buildTransportObject(params)?.let { put("transport", it) }
        }

        return ParsedSingBoxProfile(name = name, protocol = "VMess", isFullConfig = false, outboundOrFullJson = outbound)
    }

    private fun parseTrojan(uriStr: String, customName: String?): ParsedSingBoxProfile {
        val uri = parseUri(uriStr)
        val password = urlDecode(uri.userInfo.orEmpty())
        require(password.isNotBlank()) { "Trojan URI missing password" }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Trojan URI missing host")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryParams(uri.rawQuery)
        val name = resolveName(customName, uri.rawFragment, "Trojan $host:$port")

        val outbound = JSONObject().apply {
            put("type", "trojan")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("password", password)

            val security = params["security"]?.lowercase()
            if (security != "none") {
                buildTlsObject(params, defaultSni = host, forceTls = true)?.let { put("tls", it) }
            }
            buildTransportObject(params)?.let { put("transport", it) }
        }

        return ParsedSingBoxProfile(name = name, protocol = "Trojan", isFullConfig = false, outboundOrFullJson = outbound)
    }

    private fun parseShadowsocks(uriStr: String, customName: String?): ParsedSingBoxProfile {
        val withoutScheme = uriStr.removePrefix("ss://")
        val fragment = withoutScheme.substringAfter("#", "").takeIf { withoutScheme.contains("#") }
        val mainPart = withoutScheme.substringBefore("#")
        val queryPart = mainPart.substringAfter("?", "").takeIf { mainPart.contains("?") }
        val authorityAndUser = mainPart.substringBefore("?")

        val method: String
        val password: String
        val host: String
        val port: Int

        if (authorityAndUser.contains("@")) {
            val userInfoRaw = authorityAndUser.substringBefore("@")
            val hostPortRaw = authorityAndUser.substringAfter("@")
            val decodedUser = if (userInfoRaw.contains(":")) {
                urlDecode(userInfoRaw)
            } else {
                decodeBase64String(userInfoRaw)
            }
            require(decodedUser.contains(":")) { "Shadowsocks userinfo must be method:password" }
            method = decodedUser.substringBefore(":")
            password = decodedUser.substringAfter(":")

            val hp = parseHostPort(hostPortRaw, defaultPort = 8388)
            host = hp.first
            port = hp.second
        } else {
            val decoded = decodeBase64String(authorityAndUser)
            require(decoded.contains("@") && decoded.contains(":")) { "Invalid legacy Shadowsocks URI" }
            val userPart = decoded.substringBefore("@")
            val hostPortRaw = decoded.substringAfter("@")
            method = userPart.substringBefore(":")
            password = userPart.substringAfter(":")
            val hp = parseHostPort(hostPortRaw, defaultPort = 8388)
            host = hp.first
            port = hp.second
        }

        val name = resolveName(customName, fragment, "SS $host:$port")
        val outbound = JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
        // Optional SIP003 plugin is ignored if empty; basic SS works out of box
        if (!queryPart.isNullOrBlank()) {
            val params = parseQueryParams(queryPart)
            params["plugin"]?.takeIf { it.isNotBlank() }?.let { pluginStr ->
                val parts = pluginStr.split(";", limit = 2)
                outbound.put("plugin", parts[0])
                if (parts.size > 1 && parts[1].isNotBlank()) {
                    outbound.put("plugin_opts", parts[1])
                }
            }
        }

        return ParsedSingBoxProfile(name = name, protocol = "SS", isFullConfig = false, outboundOrFullJson = outbound)
    }

    private fun parseHysteria2(uriStr: String, customName: String?): ParsedSingBoxProfile {
        val uri = parseUri(uriStr)
        val password = urlDecode(uri.userInfo.orEmpty())
        require(password.isNotBlank()) { "Hysteria2 URI missing password/auth" }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Hysteria2 URI missing host")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryParams(uri.rawQuery)
        val name = resolveName(customName, uri.rawFragment, "HY2 $host:$port")

        val outbound = JSONObject().apply {
            put("type", "hysteria2")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("password", password)
            params["upmbps"]?.toIntOrNull()?.let { put("up_mbps", it) }
            params["downmbps"]?.toIntOrNull()?.let { put("down_mbps", it) }

            val obfsType = params["obfs"]?.takeIf { it.isNotBlank() && it != "none" }
            val obfsPassword = params["obfs-password"]
            if (obfsType != null) {
                put("obfs", JSONObject().apply {
                    put("type", obfsType)
                    if (!obfsPassword.isNullOrBlank()) put("password", obfsPassword)
                })
            }

            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", params["sni"]?.takeIf { it.isNotBlank() } ?: host)
                put("insecure", isTruthy(params["insecure"]) || isTruthy(params["allowInsecure"]))
                params["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                    put("alpn", JSONArray(alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                }
            })
        }

        return ParsedSingBoxProfile(name = name, protocol = "HY2", isFullConfig = false, outboundOrFullJson = outbound)
    }

    private fun parseHysteria1(uriStr: String, customName: String?): ParsedSingBoxProfile {
        val uri = parseUri(uriStr)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Hysteria URI missing host")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryParams(uri.rawQuery)
        val name = resolveName(customName, uri.rawFragment, "HY $host:$port")

        val outbound = JSONObject().apply {
            put("type", "hysteria")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            params["auth"]?.takeIf { it.isNotBlank() }?.let { put("auth_str", it) }
            params["upmbps"]?.toIntOrNull()?.let { put("up_mbps", it) }
            params["downmbps"]?.toIntOrNull()?.let { put("down_mbps", it) }
            params["obfsParam"]?.takeIf { it.isNotBlank() }?.let { put("obfs", it) }
            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", params["peer"]?.takeIf { it.isNotBlank() } ?: params["sni"]?.takeIf { it.isNotBlank() } ?: host)
                put("insecure", isTruthy(params["insecure"]))
                params["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                    put("alpn", JSONArray(alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                }
            })
        }

        return ParsedSingBoxProfile(name = name, protocol = "HY", isFullConfig = false, outboundOrFullJson = outbound)
    }

    private fun parseWireGuard(uriStr: String, customName: String?): ParsedSingBoxProfile {
        val uri = parseUri(uriStr)
        val privateKey = urlDecode(uri.userInfo.orEmpty())
        require(privateKey.isNotBlank()) { "WireGuard URI missing private key" }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("WireGuard URI missing host")
        val port = if (uri.port > 0) uri.port else 51820
        val params = parseQueryParams(uri.rawQuery)
        val peerPublicKey = params["publickey"]?.takeIf { it.isNotBlank() }
            ?: params["public_key"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("WireGuard URI missing publickey parameter")

        val localAddresses = (params["address"] ?: "10.0.0.2/32")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val name = resolveName(customName, uri.rawFragment, "WG $host:$port")
        val outbound = JSONObject().apply {
            put("type", "wireguard")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("local_address", JSONArray(localAddresses))
            put("private_key", privateKey)
            put("peer_public_key", peerPublicKey)
            params["presharedkey"]?.takeIf { it.isNotBlank() }?.let { put("pre_shared_key", it) }
            params["mtu"]?.toIntOrNull()?.let { put("mtu", it) }
            params["reserved"]?.takeIf { it.isNotBlank() }?.let { resStr ->
                val bytes = resStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (bytes.size == 3) {
                    put("reserved", JSONArray(bytes))
                }
            }
        }

        return ParsedSingBoxProfile(name = name, protocol = "WG", isFullConfig = false, outboundOrFullJson = outbound)
    }

    private fun parseSocks(uriStr: String, customName: String?): ParsedSingBoxProfile {
        val uri = parseUri(uriStr)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("SOCKS URI missing host")
        val port = if (uri.port > 0) uri.port else 1080
        val name = resolveName(customName, uri.rawFragment, "SOCKS5 $host:$port")

        val outbound = JSONObject().apply {
            put("type", "socks")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("version", "5")
            val userInfo = uri.userInfo
            if (!userInfo.isNullOrBlank()) {
                val decoded = if (userInfo.contains(":")) urlDecode(userInfo) else decodeBase64String(userInfo)
                if (decoded.contains(":")) {
                    put("username", decoded.substringBefore(":"))
                    put("password", decoded.substringAfter(":"))
                } else {
                    put("username", decoded)
                }
            }
        }

        return ParsedSingBoxProfile(name = name, protocol = "SOCKS5", isFullConfig = false, outboundOrFullJson = outbound)
    }

    private fun parseHttp(uriStr: String, useTls: Boolean, customName: String?): ParsedSingBoxProfile {
        val uri = parseUri(uriStr)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("HTTP URI missing host")
        val port = if (uri.port > 0) uri.port else if (useTls) 443 else 8080
        val name = resolveName(customName, uri.rawFragment, "${if (useTls) "HTTPS" else "HTTP"} $host:$port")

        val outbound = JSONObject().apply {
            put("type", "http")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            val userInfo = uri.userInfo
            if (!userInfo.isNullOrBlank()) {
                val decoded = urlDecode(userInfo)
                if (decoded.contains(":")) {
                    put("username", decoded.substringBefore(":"))
                    put("password", decoded.substringAfter(":"))
                } else {
                    put("username", decoded)
                }
            }
            if (useTls) {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", host)
                })
            }
        }

        return ParsedSingBoxProfile(name = name, protocol = "HTTP", isFullConfig = false, outboundOrFullJson = outbound)
    }

    private fun buildTlsObject(
        params: Map<String, String>,
        defaultSni: String,
        forceTls: Boolean
    ): JSONObject? {
        val security = params["security"]?.lowercase().orEmpty()
        val isReality = security == "reality" || !params["pbk"].isNullOrBlank()
        val isTls = forceTls || security == "tls" || isReality
        if (!isTls) return null

        return JSONObject().apply {
            put("enabled", true)
            val sni = params["sni"]?.takeIf { it.isNotBlank() }
                ?: params["peer"]?.takeIf { it.isNotBlank() }
                ?: defaultSni
            if (sni.isNotBlank()) put("server_name", sni)
            if (isTruthy(params["allowInsecure"]) || isTruthy(params["insecure"])) {
                put("insecure", true)
            }
            params["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                put("alpn", JSONArray(alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            }
            val fp = params["fp"]?.takeIf { it.isNotBlank() } ?: if (isReality) "chrome" else null
            if (fp != null) {
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", fp)
                })
            }
            if (isReality) {
                val pbk = params["pbk"]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Reality profile missing public key (pbk)")
                put("reality", JSONObject().apply {
                    put("enabled", true)
                    put("public_key", pbk)
                    put("short_id", params["sid"].orEmpty())
                })
            }
        }
    }

    private fun buildTransportObject(params: Map<String, String>): JSONObject? {
        return when (params["type"]?.lowercase()) {
            "ws" -> JSONObject().apply {
                put("type", "ws")
                put("path", params["path"]?.takeIf { it.isNotBlank() } ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let { h ->
                    put("headers", JSONObject().apply { put("Host", h) })
                }
            }
            "grpc" -> JSONObject().apply {
                put("type", "grpc")
                val serviceName = params["serviceName"]?.takeIf { it.isNotBlank() }
                    ?: params["path"]?.takeIf { it.isNotBlank() }
                    ?: ""
                put("service_name", serviceName)
            }
            "httpupgrade" -> JSONObject().apply {
                put("type", "httpupgrade")
                put("path", params["path"]?.takeIf { it.isNotBlank() } ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            }
            "http", "h2" -> JSONObject().apply {
                put("type", "http")
                put("path", params["path"]?.takeIf { it.isNotBlank() } ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let { h ->
                    put("host", JSONArray(h.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                }
            }
            else -> null
        }
    }

    private fun parseUri(raw: String): URI {
        return try {
            URI(raw)
        } catch (_: Exception) {
            // Handle unencoded characters in fragment if needed
            val base = raw.substringBefore("#")
            URI(base)
        }
    }

    private fun parseHostPort(raw: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("[")) {
            val endBracket = trimmed.indexOf(']')
            require(endBracket > 1) { "Invalid IPv6 host: $trimmed" }
            val host = trimmed.substring(1, endBracket)
            val portPart = trimmed.substring(endBracket + 1).removePrefix(":")
            host to (portPart.toIntOrNull() ?: defaultPort)
        } else {
            val idx = trimmed.lastIndexOf(':')
            if (idx > 0) {
                trimmed.substring(0, idx) to (trimmed.substring(idx + 1).toIntOrNull() ?: defaultPort)
            } else {
                trimmed to defaultPort
            }
        }
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = linkedMapOf<String, String>()
        rawQuery.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = urlDecode(pair.substring(0, idx))
                val value = urlDecode(pair.substring(idx + 1))
                map[key] = value
            }
        }
        return map
    }

    private fun resolveName(customName: String?, rawFragment: String?, fallback: String): String {
        if (!customName.isNullOrBlank()) return customName.trim()
        if (!rawFragment.isNullOrBlank()) {
            val decoded = urlDecode(rawFragment).trim()
            if (decoded.isNotEmpty()) return decoded
        }
        return fallback
    }

    private fun urlDecode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun decodeBase64String(raw: String): String {
        val clean = raw.trim().replace('-', '+').replace('_', '/')
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }

    private fun isTruthy(value: String?): Boolean {
        return value == "1" || value.equals("true", ignoreCase = true)
    }
}
