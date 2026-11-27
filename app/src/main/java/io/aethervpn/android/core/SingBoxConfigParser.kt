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

data class BulkSingBoxParseResult(
    val parsedItems: List<Pair<String, ParsedSingBoxProfile>>,
    val skippedCount: Int
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
        val nonEmptyLines = trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        if (nonEmptyLines.size > 1) return "${nonEmptyLines.size} LINES"
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

    fun parseBulk(rawInput: String, customName: String? = null): BulkSingBoxParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return BulkSingBoxParseResult(emptyList(), 0)
        if (trimmed.startsWith("{")) {
            val single = parse(trimmed, customName).getOrNull()
            return if (single != null) {
                BulkSingBoxParseResult(listOf(trimmed to single), 0)
            } else {
                BulkSingBoxParseResult(emptyList(), 1)
            }
        }
        val lines = trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        if (lines.isEmpty()) return BulkSingBoxParseResult(emptyList(), 0)

        val valid = mutableListOf<Pair<String, ParsedSingBoxProfile>>()
        var skipped = 0
        val useCustomName = if (lines.size == 1) customName else null
        for (line in lines) {
            val parsed = parse(line, useCustomName).getOrNull()
            if (parsed != null) {
                valid.add(line to parsed)
            } else {
                skipped++
            }
        }
        return BulkSingBoxParseResult(valid, skipped)
    }

    /**
     * Parses a remote subscription payload that may be either plain multi-line URIs/JSON
     * or a Base64-encoded (standard or URL-safe) multi-line URI list.
     */
    fun parseSubscriptionPayload(rawPayload: String): BulkSingBoxParseResult {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) return BulkSingBoxParseResult(emptyList(), 0)

        val direct = parseBulk(trimmed)
        if (direct.parsedItems.isNotEmpty() || trimmed.startsWith("{") || trimmed.contains("://")) {
            return direct
        }

        val compact = trimmed.replace(Regex("\\s+"), "")
        val decoded = runCatching { decodeBase64String(compact) }.getOrNull()?.trim().orEmpty()
        if (decoded.isEmpty()) return direct

        return parseBulk(decoded)
    }

    internal fun parseDomainSuffixes(rawDomains: String): List<String> {
        if (rawDomains.isBlank()) return emptyList()
        return rawDomains.split(Regex("[,;\\s]+"))
            .map { it.trim().lowercase().removePrefix("*") }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun extractServerPreview(rawUriOrJson: String): String {
        val parsed = parse(rawUriOrJson).getOrNull() ?: return "Invalid config"
        if (!parsed.isFullConfig) {
            val host = parsed.outboundOrFullJson.optString("server").trim()
            val port = parsed.outboundOrFullJson.optInt("server_port", 0)
            return when {
                host.isNotBlank() && port > 0 -> "$host:$port"
                host.isNotBlank() -> host
                else -> parsed.protocol
            }
        }
        val outbounds = parsed.outboundOrFullJson.optJSONArray("outbounds")
        if (outbounds != null) {
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                if (ob.optString("type").lowercase() !in SYSTEM_OUTBOUND_TYPES) {
                    val host = ob.optString("server").trim()
                    val port = ob.optInt("server_port", 0)
                    if (host.isNotBlank() && port > 0) return "$host:$port"
                    if (host.isNotBlank()) return host
                }
            }
        }
        return "Full JSON config"
    }

    internal fun normalizeShadowsocksMethod(rawMethod: String): String {
        val normalized = rawMethod.trim().lowercase()
        return when (normalized) {
            "chacha20-poly1305", "xchacha20-poly1305" -> "chacha20-ietf-poly1305"
            else -> normalized
        }
    }

    fun buildRuntimeConfig(
        rawUriOrJson: String,
        listenPort: Int = VpnConfig.SING_BOX_SOCKS_PORT,
        upstreamSocksPort: Int? = null,
        config: VpnConfig = VpnConfig()
    ): Result<String> = runCatching {
        val effectiveUpstreamSocksPort = upstreamSocksPort
            ?: if (config.isByeDpiActive) VpnConfig.BYEDPI_SOCKS_PORT else null
        val parsed = parse(rawUriOrJson).getOrThrow()
        val root = if (parsed.isFullConfig) {
            JSONObject(parsed.outboundOrFullJson.toString()).apply {
                put("log", buildLogObject(config))
                val existingInbounds = optJSONArray("inbounds")
                val newInbounds = JSONArray()
                newInbounds.put(buildSocksInbound(listenPort))
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

                optJSONObject("route")?.put("auto_detect_interface", false)
                optJSONObject("dns")?.put("strategy", "ipv4_only")

                if (effectiveUpstreamSocksPort != null) {
                    val existingOutbounds = optJSONArray("outbounds") ?: JSONArray()
                    val finalTag = optJSONObject("route")?.optString("final")?.takeIf { it.isNotBlank() }
                    val newOutbounds = JSONArray()
                    var detourApplied = false
                    for (i in 0 until existingOutbounds.length()) {
                        val ob = existingOutbounds.optJSONObject(i) ?: continue
                        val tag = ob.optString("tag")
                        val type = ob.optString("type")
                        if (tag == UPSTREAM_SOCKS_TAG) continue
                        val isSystemType = type in SYSTEM_OUTBOUND_TYPES
                        if (!isSystemType && (finalTag == null || tag == finalTag || !ob.has("detour"))) {
                            ob.put("detour", UPSTREAM_SOCKS_TAG)
                            detourApplied = true
                        }
                        if (config.sbTlsFragment && !isSystemType) {
                            applyTlsFragmentIfEnabled(ob)
                        }
                        newOutbounds.put(ob)
                    }
                    if (!detourApplied) {
                        for (i in 0 until newOutbounds.length()) {
                            val ob = newOutbounds.optJSONObject(i) ?: continue
                            if (ob.optString("type") !in SYSTEM_OUTBOUND_TYPES) {
                                ob.put("detour", UPSTREAM_SOCKS_TAG)
                                break
                            }
                        }
                    }
                    newOutbounds.put(buildUpstreamSocksOutbound(effectiveUpstreamSocksPort))
                    put("outbounds", newOutbounds)
                } else if (config.sbTlsFragment) {
                    val existingOutbounds = optJSONArray("outbounds")
                    if (existingOutbounds != null) {
                        for (i in 0 until existingOutbounds.length()) {
                            val ob = existingOutbounds.optJSONObject(i) ?: continue
                            if (ob.optString("type") !in SYSTEM_OUTBOUND_TYPES) {
                                applyTlsFragmentIfEnabled(ob)
                            }
                        }
                    }
                }
            }
        } else {
            val outbound = JSONObject(parsed.outboundOrFullJson.toString()).apply {
                put("tag", "proxy")
                put("connect_timeout", "${config.sbConnectTimeoutSec.coerceIn(3, 60)}s")
                if (config.sbTcpFastOpen) {
                    put("tcp_fast_open", true)
                }
                if (effectiveUpstreamSocksPort != null) {
                    put("detour", UPSTREAM_SOCKS_TAG)
                }
                if (optString("type").equals("shadowsocks", ignoreCase = true) && has("method")) {
                    put("method", normalizeShadowsocksMethod(optString("method")))
                }
                if (config.sbMultiplexEnabled && optString("type").lowercase() in MULTIPLEX_SUPPORTED_TYPES) {
                    put("multiplex", JSONObject().apply {
                        put("enabled", true)
                        put("protocol", "h2mux")
                        put("max_connections", 4)
                    })
                }
                if (config.sbTlsFragment) {
                    applyTlsFragmentIfEnabled(this)
                }
            }
            val isTcpOutbound = outbound.optString("type").lowercase() in TCP_BASED_OUTBOUND_TYPES
            val shouldBlockQuic = when (config.sbBlockQuic) {
                SingBoxBlockQuicMode.ALWAYS -> true
                SingBoxBlockQuicMode.AUTO_TCP_OUTBOUNDS -> isTcpOutbound
                SingBoxBlockQuicMode.OFF -> false
            }
            JSONObject().apply {
                put("log", buildLogObject(config))
                val effectiveStrategy = "ipv4_only"
                put("dns", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(buildRemoteDnsServer(config.sbRemoteDns))
                        put(JSONObject().apply {
                            put("tag", "dns-local")
                            put("type", "udp")
                            put("server", config.sbDirectDns.ifBlank { "1.1.1.1" })
                        })
                    })
                    put("rules", JSONArray().apply {
                        put(JSONObject().apply {
                            put("query_type", JSONArray().apply {
                                put("HTTPS")
                                put("SVCB")
                            })
                            put("action", "predefined")
                            put("rcode", "NOERROR")
                        })
                        if (effectiveStrategy == "ipv4_only") {
                            put(JSONObject().apply {
                                put("query_type", JSONArray().apply {
                                    put("AAAA")
                                })
                                put("action", "predefined")
                                put("rcode", "NOERROR")
                            })
                        }
                    })
                    put("final", "dns-remote")
                    put("strategy", effectiveStrategy)
                })
                put("inbounds", JSONArray().apply {
                    put(buildSocksInbound(listenPort))
                })
                put("outbounds", JSONArray().apply {
                    put(outbound)
                    if (effectiveUpstreamSocksPort != null) {
                        put(buildUpstreamSocksOutbound(effectiveUpstreamSocksPort))
                    }
                    put(JSONObject().apply {
                        put("type", "direct")
                        put("tag", "direct")
                    })
                })
                put("route", JSONObject().apply {
                    put("rules", JSONArray().apply {
                        put(JSONObject().apply {
                            put("port", 53)
                            put("action", "hijack-dns")
                        })
                        put(JSONObject().apply {
                            put("protocol", "dns")
                            put("action", "hijack-dns")
                        })
                        if (config.sbBypassIrDomains) {
                            put(JSONObject().apply {
                                put("domain_suffix", JSONArray().apply { put(".ir") })
                                put("outbound", "direct")
                            })
                        }
                        val customBypassDomains = parseDomainSuffixes(config.sbCustomBypassDomains)
                        if (customBypassDomains.isNotEmpty()) {
                            put(JSONObject().apply {
                                put("domain_suffix", JSONArray(customBypassDomains))
                                put("outbound", "direct")
                            })
                        }
                        if (shouldBlockQuic) {
                            put(JSONObject().apply {
                                put("network", "udp")
                                put("port", 443)
                                put("action", "reject")
                            })
                        }
                    })
                    put("default_domain_resolver", "dns-local")
                    put("final", "proxy")
                    put("auto_detect_interface", false)
                })
            }
        }
        root.toString(2)
    }

    /**
     * Builds a minimal sing-box config with ONLY `log`, `inbounds: [socks]`, and
     * `outbounds: [proxy]` (no `dns`, no `route`, no `sniff`) to isolate JSON/routing
     * issues from core proxy/runtime behavior during VPN-OFF diagnostics.
     */
    fun buildBareBonesConfig(
        rawUriOrJson: String,
        listenPort: Int = VpnConfig.SING_BOX_SOCKS_PORT,
        logLevel: String = "debug"
    ): Result<String> = runCatching {
        val parsed = parse(rawUriOrJson).getOrThrow()
        val outbound = if (parsed.isFullConfig) {
            val outbounds = parsed.outboundOrFullJson.optJSONArray("outbounds")
                ?: throw IllegalStateException("No outbounds array in config")
            var found: JSONObject? = null
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                if (ob.optString("type").lowercase() !in SYSTEM_OUTBOUND_TYPES) {
                    found = JSONObject(ob.toString()).apply { put("tag", "proxy") }
                    break
                }
            }
            found ?: throw IllegalStateException("No proxy outbound found in config")
        } else {
            JSONObject(parsed.outboundOrFullJson.toString()).apply {
                put("tag", "proxy")
                if (optString("type").equals("shadowsocks", ignoreCase = true) && has("method")) {
                    put("method", normalizeShadowsocksMethod(optString("method")))
                }
            }
        }
        JSONObject().apply {
            put("log", JSONObject().apply {
                put("disabled", false)
                put("level", logLevel.ifBlank { "debug" })
                put("timestamp", false)
            })
            put("inbounds", JSONArray().apply {
                put(buildSocksInbound(listenPort))
            })
            put("outbounds", JSONArray().apply {
                put(outbound)
            })
        }.toString(2)
    }

    /**
     * Builds a minimal sing-box config with ONLY `log`, `inbounds: [socks]`, and
     * `outbounds: [{ "type": "direct", "tag": "direct" }]` (no remote proxy, no DNS, no rules).
     */
    fun buildDirectOutboundConfig(
        listenPort: Int = VpnConfig.SING_BOX_SOCKS_PORT,
        logLevel: String = "warn"
    ): String = JSONObject().apply {
        put("log", JSONObject().apply {
            put("disabled", false)
            put("level", logLevel.ifBlank { "warn" })
            put("timestamp", false)
        })
        put("inbounds", JSONArray().apply {
            put(buildSocksInbound(listenPort))
        })
        put("outbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
        })
    }.toString(2)

    fun formatOutboundDescriptor(rawUriOrJson: String, fallbackProtocol: String = "sing-box"): String {
        val parsed = parse(rawUriOrJson).getOrNull()
            ?: return fallbackProtocol.trim().ifBlank { "sing-box" }.lowercase()
        val target = if (parsed.isFullConfig) {
            val outbounds = parsed.outboundOrFullJson.optJSONArray("outbounds")
            var chosen: JSONObject? = null
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val t = ob.optString("type").lowercase()
                    if (ob.optString("tag") == "proxy" || (t.isNotBlank() && t !in SYSTEM_OUTBOUND_TYPES && ob.optString("tag") != UPSTREAM_SOCKS_TAG)) {
                        chosen = ob
                        break
                    }
                }
            }
            chosen ?: return parsed.protocol.lowercase()
        } else {
            parsed.outboundOrFullJson
        }

        val proto = target.optString("type", parsed.protocol).lowercase().ifBlank { parsed.protocol.lowercase() }
        val transportType = target.optJSONObject("transport")?.optString("type")?.trim()?.lowercase().orEmpty()
        val transport = when {
            transportType.isNotBlank() -> transportType
            proto in setOf("hysteria", "hysteria2", "tuic", "wireguard") -> "udp"
            else -> "tcp"
        }
        val tlsObj = target.optJSONObject("tls")
        val tls = if (tlsObj?.optBoolean("enabled", false) == true) {
            val isReality = tlsObj.optJSONObject("reality")?.optBoolean("enabled", false) == true
            if (isReality) "reality" else "tls"
        } else {
            null
        }
        val details = if (tls != null) "$transport+$tls" else transport
        return "$proto ($details)"
    }

    /**
     * Formats a sleek, human-readable protocol + transport + security badge for profile cards,
     * e.g. `VLESS • WS + TLS`, `VLESS • TCP + Reality`, `VMess • WS + TLS`,
     * `Trojan • gRPC + TLS`, `SS • TCP`.
     */
    fun formatRichProtocolBadge(rawUriOrJson: String, fallbackProtocol: String = "RAW"): String {
        val parsed = parse(rawUriOrJson).getOrNull()
            ?: return fallbackProtocol.trim().ifBlank { "RAW" }
        val target = if (parsed.isFullConfig) {
            val outbounds = parsed.outboundOrFullJson.optJSONArray("outbounds")
            var chosen: JSONObject? = null
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val t = ob.optString("type").lowercase()
                    if (ob.optString("tag") == "proxy" || (t.isNotBlank() && t !in SYSTEM_OUTBOUND_TYPES && ob.optString("tag") != UPSTREAM_SOCKS_TAG)) {
                        chosen = ob
                        break
                    }
                }
            }
            chosen ?: return parsed.protocol
        } else {
            parsed.outboundOrFullJson
        }

        val rawProto = target.optString("type", parsed.protocol).lowercase().ifBlank { parsed.protocol.lowercase() }
        val protoLabel = when (rawProto) {
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "shadowsocks", "ss" -> "SS"
            "hysteria2", "hy2" -> "HY2"
            "hysteria" -> "HY"
            "wireguard", "wg" -> "WG"
            "tuic" -> "TUIC"
            "socks", "socks5" -> "SOCKS5"
            "http" -> "HTTP"
            "ssh" -> "SSH"
            else -> parsed.protocol.ifBlank { rawProto.uppercase() }
        }

        val transportType = target.optJSONObject("transport")?.optString("type")?.trim()?.lowercase().orEmpty()
        val transportLabel = when {
            transportType == "ws" -> "WS"
            transportType == "grpc" -> "gRPC"
            transportType == "httpupgrade" -> "HTTPUpgrade"
            transportType == "http" || transportType == "h2" -> "H2"
            transportType == "quic" -> "QUIC"
            transportType.isNotBlank() -> transportType.uppercase()
            rawProto in setOf("hysteria", "hysteria2", "tuic", "wireguard") -> "UDP"
            else -> "TCP"
        }

        val tlsObj = target.optJSONObject("tls")
        val tlsLabel = if (tlsObj?.optBoolean("enabled", false) == true) {
            val isReality = tlsObj.optJSONObject("reality")?.optBoolean("enabled", false) == true
            if (isReality) "Reality" else "TLS"
        } else {
            null
        }

        return if (tlsLabel != null) {
            "$protoLabel • $transportLabel + $tlsLabel"
        } else {
            "$protoLabel • $transportLabel"
        }
    }

    /**
     * Generates the exact pretty-printed (2-space indented) Sing-box runtime JSON
     * reflecting the current [VpnConfig] (DNS, TLS Fragment, multiplex, etc.).
     */
    fun exportPrettyRuntimeConfig(
        rawUriOrJson: String,
        config: VpnConfig = VpnConfig()
    ): Result<String> = buildRuntimeConfig(
        rawUriOrJson = rawUriOrJson,
        config = config
    )

    private val KNOWN_COUNTRY_CODES = setOf(
        "AE", "AR", "AT", "AU", "BE", "BG", "BR", "CA", "CH", "CL", "CN", "CZ",
        "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HK", "HU", "ID", "IE",
        "IL", "IN", "IR", "IS", "IT", "JP", "KR", "KZ", "LT", "LU", "LV", "MD",
        "MX", "MY", "NL", "NO", "NZ", "PH", "PL", "PT", "RO", "RS", "RU", "SA",
        "SE", "SG", "SK", "TH", "TR", "TW", "UA", "UK", "US", "VN", "ZA"
    )

    private val COUNTRY_NAME_TO_CODE = listOf(
        "germany" to "DE",
        "frankfurt" to "DE",
        "netherlands" to "NL",
        "amsterdam" to "NL",
        "holland" to "NL",
        "united states" to "US",
        "america" to "US",
        "usa" to "US",
        "united kingdom" to "GB",
        "london" to "GB",
        "britain" to "GB",
        "singapore" to "SG",
        "japan" to "JP",
        "tokyo" to "JP",
        "france" to "FR",
        "paris" to "FR",
        "finland" to "FI",
        "helsinki" to "FI",
        "canada" to "CA",
        "toronto" to "CA",
        "turkey" to "TR",
        "istanbul" to "TR",
        "sweden" to "SE",
        "stockholm" to "SE",
        "switzerland" to "CH",
        "zurich" to "CH",
        "poland" to "PL",
        "hong kong" to "HK",
        "korea" to "KR",
        "seoul" to "KR",
        "australia" to "AU",
        "sydney" to "AU",
        "india" to "IN",
        "dubai" to "AE",
        "emirates" to "AE",
        "uae" to "AE",
        "austria" to "AT",
        "vienna" to "AT",
        "italy" to "IT",
        "spain" to "ES",
        "norway" to "NO",
        "romania" to "RO",
        "russia" to "RU",
        "iran" to "IR"
    )

    fun countryCodeToFlagEmoji(rawCode: String): String {
        val normalized = rawCode.trim().uppercase()
        val iso = if (normalized == "UK") "GB" else normalized
        if (iso.length != 2 || iso[0] !in 'A'..'Z' || iso[1] !in 'A'..'Z') return ""
        val first = Character.toChars(0x1F1E6 + (iso[0].code - 'A'.code))
        val second = Character.toChars(0x1F1E6 + (iso[1].code - 'A'.code))
        return String(first) + String(second)
    }

    /**
     * Extracts a compact country badge (e.g. `🇩🇪 DE`) from [lastCountry],
     * [name] flag emojis / country tags / city names, or server host ccTLD.
     */
    fun extractCountryBadge(
        name: String,
        lastCountry: String? = null,
        serverPreview: String = ""
    ): String? {
        // 1. Live trace colo/country if 2-letter ISO
        val traced = lastCountry?.trim()?.uppercase()
        if (traced != null && traced.length == 2 && traced in KNOWN_COUNTRY_CODES) {
            val iso = if (traced == "UK") "GB" else traced
            val flag = countryCodeToFlagEmoji(iso)
            return if (flag.isNotEmpty()) "$flag $iso" else iso
        }

        // 2. Regional indicator flag emoji in profile name
        var i = 0
        while (i < name.length) {
            val cp1 = Character.codePointAt(name, i)
            val c1Len = Character.charCount(cp1)
            if (cp1 in 0x1F1E6..0x1F1FF && i + c1Len < name.length) {
                val cp2 = Character.codePointAt(name, i + c1Len)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    val ch1 = ('A'.code + (cp1 - 0x1F1E6)).toChar()
                    val ch2 = ('A'.code + (cp2 - 0x1F1E6)).toChar()
                    val iso = "$ch1$ch2"
                    val flag = countryCodeToFlagEmoji(iso)
                    return if (flag.isNotEmpty()) "$flag $iso" else iso
                }
            }
            i += c1Len
        }

        // 3. Country/city keyword in name
        val lowerName = name.lowercase()
        for ((keyword, code) in COUNTRY_NAME_TO_CODE) {
            if (lowerName.contains(keyword)) {
                val flag = countryCodeToFlagEmoji(code)
                return if (flag.isNotEmpty()) "$flag $code" else code
            }
        }

        // 4. Delimited 2-letter ISO code in name (e.g. [DE], (US), DE-01, FR 1)
        val tokenRegex = Regex("(?:^|[\\s\\[\\(\\-_|•])([A-Z]{2})(?=$|[\\s\\]\\)\\-_:|•0-9])")
        for (match in tokenRegex.findAll(name.uppercase())) {
            val code = match.groupValues[1]
            if (code in KNOWN_COUNTRY_CODES) {
                val iso = if (code == "UK") "GB" else code
                val flag = countryCodeToFlagEmoji(iso)
                return if (flag.isNotEmpty()) "$flag $iso" else iso
            }
        }

        // 5. Server host ccTLD (skip generic TLDs)
        val host = serverPreview.substringBeforeLast(":").trim().lowercase()
        val tld = host.substringAfterLast(".", "").uppercase()
        if (tld.length == 2 && tld in KNOWN_COUNTRY_CODES) {
            val iso = if (tld == "UK") "GB" else tld
            val flag = countryCodeToFlagEmoji(iso)
            return if (flag.isNotEmpty()) "$flag $iso" else iso
        }

        return null
    }

    fun summarizeRuntimeConfig(runtimeJson: String): String {
        return runCatching {
            val root = JSONObject(runtimeJson)
            val outbounds = root.optJSONArray("outbounds") ?: return@runCatching "Outbound shape: none"
            var target: JSONObject? = null
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                val type = ob.optString("type").lowercase()
                if (ob.optString("tag") == "proxy" || (type.isNotBlank() && type !in SYSTEM_OUTBOUND_TYPES && ob.optString("tag") != UPSTREAM_SOCKS_TAG)) {
                    target = ob
                    break
                }
            }
            if (target == null) return@runCatching "Outbound shape: none"

            val proto = target.optString("type", "unknown")
            val transportObj = target.optJSONObject("transport")
            val transportStr = if (transportObj == null) {
                "tcp"
            } else {
                val tType = transportObj.optString("type", "unknown")
                if (tType == "ws") {
                    val ed = transportObj.optInt("max_early_data", 0)
                    val hasHost = transportObj.optJSONObject("headers")?.optString("Host")?.isNotBlank() == true
                    "ws(ed=$ed,host=$hasHost)"
                } else {
                    tType
                }
            }
            val tlsObj = target.optJSONObject("tls")
            val tlsStr = if (tlsObj?.optBoolean("enabled", false) == true) {
                val isReality = tlsObj.optJSONObject("reality")?.optBoolean("enabled", false) == true
                if (isReality) "reality" else "tls"
            } else {
                "none"
            }
            val fragStr = if (tlsObj?.optBoolean("enabled", false) == true && tlsObj.optBoolean("fragment", false)) {
                "on"
            } else {
                "off"
            }
            val utlsStr = tlsObj?.optJSONObject("utls")
                ?.takeIf { it.optBoolean("enabled", false) }
                ?.optString("fingerprint")
                ?.takeIf { it.isNotBlank() }
                ?: "none"
            val alpnArr = tlsObj?.optJSONArray("alpn")
            val alpnStr = if (alpnArr != null && alpnArr.length() > 0) {
                (0 until alpnArr.length()).map { alpnArr.getString(it) }.joinToString(",")
            } else {
                "none"
            }
            val flow = target.optString("flow").takeIf { it.isNotBlank() }
            val pkt = target.optString("packet_encoding").takeIf { it.isNotBlank() }

            buildString {
                append("Outbound shape: proto=").append(proto)
                append(", transport=").append(transportStr)
                append(", tls=").append(tlsStr)
                append(", frag=").append(fragStr)
                append(", utls=").append(utlsStr)
                append(", alpn=[").append(alpnStr).append(']')
                if (flow != null) append(", flow=").append(flow)
                if (pkt != null) append(", pkt=").append(pkt)
            }
        }.getOrElse { "Outbound shape: unavailable" }
    }

    fun redactRuntimeConfigJson(runtimeJson: String): String {
        return runCatching {
            val root = JSONObject(runtimeJson)
            redactJsonObject(root)
            root.toString(2)
        }.getOrElse { "<unable to redact runtime config JSON>" }
    }

    private fun redactJsonObject(obj: JSONObject) {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            val value = obj.opt(key)
            if (key.lowercase() in REDACTED_JSON_KEYS) {
                when (value) {
                    is JSONArray -> {
                        val masked = JSONArray()
                        for (i in 0 until value.length()) {
                            masked.put("***")
                        }
                        obj.put(key, masked)
                    }
                    null, JSONObject.NULL -> Unit
                    else -> obj.put(key, "***")
                }
            } else {
                when (value) {
                    is JSONObject -> redactJsonObject(value)
                    is JSONArray -> redactJsonArray(value)
                }
            }
        }
    }

    private fun redactJsonArray(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is JSONObject -> redactJsonObject(item)
                is JSONArray -> redactJsonArray(item)
            }
        }
    }

    private val REDACTED_JSON_KEYS = setOf("uuid", "password", "private_key", "short_id")
    private const val UPSTREAM_SOCKS_TAG = "upstream-socks"
    private val SYSTEM_OUTBOUND_TYPES = setOf("direct", "block", "dns", "selector", "urltest")
    private val TCP_BASED_OUTBOUND_TYPES = setOf("vmess", "vless", "trojan", "shadowsocks", "socks", "http")
    private val MULTIPLEX_SUPPORTED_TYPES = setOf("vmess", "vless", "trojan", "shadowsocks")

    private fun buildLogObject(config: VpnConfig): JSONObject = JSONObject().apply {
        put("disabled", false)
        put("level", config.sbLogLevel.ifBlank { "warn" })
        put("timestamp", false)
    }

    private fun buildRemoteDnsServer(rawRemoteDns: String): JSONObject {
        val trimmed = rawRemoteDns.trim().ifBlank { "1.1.1.1" }
        return if (trimmed.startsWith("https://", ignoreCase = true)) {
            val parsedUri = runCatching { URI(trimmed) }.getOrNull()
            val host = parsedUri?.host?.takeIf { it.isNotBlank() } ?: "1.1.1.1"
            val path = parsedUri?.rawPath?.takeIf { it.isNotBlank() && it != "/dns-query" }
            JSONObject().apply {
                put("tag", "dns-remote")
                put("type", "https")
                put("server", host)
                if (path != null) {
                    put("path", path)
                }
                put("detour", "proxy")
            }
        } else {
            JSONObject().apply {
                put("tag", "dns-remote")
                put("type", "https")
                put("server", trimmed)
                put("server_port", 443)
                put("path", "/dns-query")
                put("detour", "proxy")
            }
        }
    }

    private fun buildUpstreamSocksOutbound(upstreamSocksPort: Int): JSONObject = JSONObject().apply {
        put("type", "socks")
        put("tag", UPSTREAM_SOCKS_TAG)
        put("server", VpnConfig.LOOPBACK_HOST)
        put("server_port", upstreamSocksPort)
        put("version", "5")
    }

    private fun buildSocksInbound(listenPort: Int): JSONObject = JSONObject().apply {
        put("type", "socks")
        put("tag", "socks-in")
        put("listen", VpnConfig.LOOPBACK_HOST)
        put("listen_port", listenPort)
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

        val normalizedMethod = normalizeShadowsocksMethod(method)
        val name = resolveName(customName, fragment, "SS $host:$port")
        val outbound = JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("method", normalizedMethod)
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
                ?: params["host"]?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
                ?: defaultSni
            if (sni.isNotBlank()) put("server_name", sni)
            if (isTruthy(params["allowInsecure"]) || isTruthy(params["insecure"])) {
                put("insecure", true)
            }
            params["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                put("alpn", JSONArray(alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            }
            params["fp"]?.takeIf { it.isNotBlank() }?.let { fp ->
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
                val rawPath = params["path"]?.takeIf { it.isNotBlank() } ?: "/"
                var cleanPath = rawPath
                var maxEarlyData: Int? = null
                val qIdx = rawPath.indexOf('?')
                if (qIdx >= 0) {
                    val basePath = rawPath.substring(0, qIdx).ifEmpty { "/" }
                    val query = rawPath.substring(qIdx + 1)
                    val remainingParams = mutableListOf<String>()
                    query.split("&").forEach { part ->
                        if (part.startsWith("ed=", ignoreCase = true)) {
                            val edVal = part.substringAfter("=").toIntOrNull()
                            if (edVal != null && edVal > 0) {
                                maxEarlyData = edVal
                            }
                        } else if (part.isNotEmpty()) {
                            remainingParams.add(part)
                        }
                    }
                    cleanPath = if (remainingParams.isEmpty()) {
                        basePath
                    } else {
                        "$basePath?${remainingParams.joinToString("&")}"
                    }
                }
                put("path", cleanPath)
                if (maxEarlyData != null) {
                    put("max_early_data", maxEarlyData)
                    put("early_data_header_name", "Sec-WebSocket-Protocol")
                }
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

    private fun applyTlsFragmentIfEnabled(outbound: JSONObject) {
        val tls = outbound.optJSONObject("tls") ?: return
        if (!tls.optBoolean("enabled", false)) return
        tls.put("fragment", true)
        tls.put("fragment_fallback_delay", "500ms")
    }

    private fun isTruthy(value: String?): Boolean {
        return value == "1" || value.equals("true", ignoreCase = true)
    }
}
