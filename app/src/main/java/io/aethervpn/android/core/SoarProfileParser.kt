package io.aethervpn.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

enum class SoarTransport(val cliValue: String, val label: String) {
    AUTO("auto", "AUTO"),
    UDP("udp", "UDP"),
    TCP("tcp", "TCP");

    companion object {
        fun fromString(raw: String?): SoarTransport {
            return when (raw?.trim()?.lowercase()) {
                "udp" -> UDP
                "tcp" -> TCP
                else -> AUTO
            }
        }
    }
}

enum class SoarConnectionMode(val label: String) {
    PROXY_ONLY("Proxy Only"),
    VPN_FULL_DEVICE("VPN (Full Device)");

    companion object {
        fun fromName(raw: String?): SoarConnectionMode {
            return entries.firstOrNull { it.name == raw } ?: PROXY_ONLY
        }
    }
}

enum class SoarPreset(
    val label: String,
    val transport: String,
    val maxAnswer: Int,
    val maxInflight: Int,
    val maxPathWindow: Int,
    val holdMs: Int,
    val sessionIdleSeconds: Int
) {
    BALANCED(
        label = "Balanced",
        transport = "auto",
        maxAnswer = 1232,
        maxInflight = 64,
        maxPathWindow = 24,
        holdMs = 300,
        sessionIdleSeconds = 120
    ),
    TURBO(
        label = "Turbo",
        transport = "udp",
        maxAnswer = 1232,
        maxInflight = 128,
        maxPathWindow = 40,
        holdMs = 150,
        sessionIdleSeconds = 90
    ),
    STABLE(
        label = "Stable",
        transport = "auto",
        maxAnswer = 512,
        maxInflight = 32,
        maxPathWindow = 12,
        holdMs = 400,
        sessionIdleSeconds = 180
    ),
    STRICT(
        label = "Strict",
        transport = "tcp",
        maxAnswer = 512,
        maxInflight = 32,
        maxPathWindow = 12,
        holdMs = 500,
        sessionIdleSeconds = 240
    )
}

data class SoarSettings(
    val transport: String = "auto",
    val discovery: Boolean = true,
    val country: String = "IR",
    val discoveryWant: Int = 16,
    val discoveryConcurrency: Int = 16,
    val discoveryPaceMs: Int = 20,
    val safeQName: Int = 101,
    val maxQName: Int = 253,
    val maxAnswer: Int = 1232,
    val holdMs: Int = 500,
    val maxInflight: Int = 128,
    val maxPathWindow: Int = 32,
    val maxStreams: Int = 64,
    val streamWindowKiB: Int = 256,
    val sendBufferKiB: Int = 64,
    val sessionIdleSeconds: Int = 90,
    val dialTimeoutSeconds: Int = 15
) {
    fun normalized(): SoarSettings {
        val normTransport = SoarTransport.fromString(transport).cliValue
        val normCountry = country.trim().uppercase().ifBlank { "IR" }
        val normMaxQName = maxQName.coerceIn(64, 253)
        val normSafeQName = safeQName.coerceIn(48, normMaxQName)
        return copy(
            transport = normTransport,
            country = normCountry,
            discoveryWant = discoveryWant.coerceIn(1, 128),
            discoveryConcurrency = discoveryConcurrency.coerceIn(1, 128),
            discoveryPaceMs = discoveryPaceMs.coerceIn(1, 5000),
            safeQName = normSafeQName,
            maxQName = normMaxQName,
            maxAnswer = maxAnswer.coerceIn(512, 4096),
            holdMs = holdMs.coerceIn(50, 10_000),
            maxInflight = maxInflight.coerceIn(1, 1024),
            maxPathWindow = maxPathWindow.coerceIn(1, 256),
            maxStreams = maxStreams.coerceIn(1, 256),
            streamWindowKiB = streamWindowKiB.coerceIn(16, 4096),
            sendBufferKiB = sendBufferKiB.coerceIn(16, 4096),
            sessionIdleSeconds = sessionIdleSeconds.coerceIn(10, 600),
            dialTimeoutSeconds = dialTimeoutSeconds.coerceIn(3, 120)
        )
    }

    fun badgeLabel(): String {
        val t = transport.uppercase()
        return if (discovery) {
            "$t • ${country.uppercase().ifBlank { "IR" }} Discovery"
        } else {
            "$t • Static Resolvers"
        }
    }

    fun withPreset(preset: SoarPreset): SoarSettings = copy(
        transport = preset.transport,
        maxAnswer = preset.maxAnswer,
        maxInflight = preset.maxInflight,
        maxPathWindow = preset.maxPathWindow,
        holdMs = preset.holdMs,
        sessionIdleSeconds = preset.sessionIdleSeconds
    ).normalized()

    fun matchingPreset(): SoarPreset? = SoarPreset.entries.firstOrNull { p ->
        transport.equals(p.transport, ignoreCase = true) &&
            maxAnswer == p.maxAnswer &&
            maxInflight == p.maxInflight &&
            maxPathWindow == p.maxPathWindow &&
            holdMs == p.holdMs &&
            sessionIdleSeconds == p.sessionIdleSeconds
    }
}

data class SoarProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val domain: String,
    val publicKey: String,
    val resolvers: List<String> = emptyList(),
    val soarSettings: SoarSettings = SoarSettings()
)

object SoarProfileParser {

    const val SCHEMA = "whitedns.profile"
    const val VERSION = 1
    const val URI_PREFIX = "soar://"

    fun parseUri(rawUri: String): Result<SoarProfile> {
        val trimmed = rawUri.trim()
        if (!trimmed.startsWith(URI_PREFIX, ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("URI must start with $URI_PREFIX"))
        }
        val encoded = trimmed.substring(URI_PREFIX.length).trim()
        if (encoded.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty soar:// payload"))
        }
        val jsonText = decodeBase64(encoded).getOrElse { err ->
            return Result.failure(IllegalArgumentException("Invalid base64 payload: ${err.message}", err))
        }
        return parseJson(jsonText)
    }

    fun parseJson(jsonText: String): Result<SoarProfile> = runCatching {
        val root = JSONObject(jsonText)
        val schema = root.optString("schema", "")
        if (schema.isNotBlank() && schema != SCHEMA) {
            throw IllegalArgumentException("Unsupported schema: $schema")
        }
        val version = root.optInt("version", VERSION)
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported profile version: $version")
        }

        val profileObj = root.optJSONObject("profile") ?: root
        val serverObj = profileObj.optJSONObject("server")

        val domain = (serverObj?.optString("domain") ?: profileObj.optString("domain")).trim()
        val publicKey = (serverObj?.optString("public_key") ?: profileObj.optString("public_key")).trim()
        if (domain.isBlank()) {
            throw IllegalArgumentException("Missing server.domain")
        }
        if (publicKey.isBlank()) {
            throw IllegalArgumentException("Missing server.public_key")
        }

        val id = profileObj.optString("id", "").trim().ifBlank { UUID.randomUUID().toString() }
        val name = profileObj.optString("name", "").trim().ifBlank { domain }
        val resolvers = parseResolversArray(profileObj.optJSONArray("resolvers"))

        val sObj = profileObj.optJSONObject("soarSettings") ?: JSONObject()
        val defaults = SoarSettings()
        val settings = SoarSettings(
            transport = sObj.optString("transport", defaults.transport),
            discovery = if (sObj.has("discovery")) sObj.optBoolean("discovery", defaults.discovery) else defaults.discovery,
            country = sObj.optString("country", defaults.country),
            discoveryWant = sObj.optInt("discoveryWant", defaults.discoveryWant),
            discoveryConcurrency = sObj.optInt("discoveryConcurrency", defaults.discoveryConcurrency),
            discoveryPaceMs = sObj.optInt("discoveryPaceMs", defaults.discoveryPaceMs),
            safeQName = sObj.optInt("safeQName", defaults.safeQName),
            maxQName = sObj.optInt("maxQName", defaults.maxQName),
            maxAnswer = sObj.optInt("maxAnswer", defaults.maxAnswer),
            holdMs = sObj.optInt("holdMs", defaults.holdMs),
            maxInflight = sObj.optInt("maxInflight", defaults.maxInflight),
            maxPathWindow = sObj.optInt("maxPathWindow", defaults.maxPathWindow),
            maxStreams = sObj.optInt("maxStreams", defaults.maxStreams),
            streamWindowKiB = sObj.optInt("streamWindowKiB", defaults.streamWindowKiB),
            sendBufferKiB = sObj.optInt("sendBufferKiB", defaults.sendBufferKiB),
            sessionIdleSeconds = sObj.optInt("sessionIdleSeconds", defaults.sessionIdleSeconds),
            dialTimeoutSeconds = sObj.optInt("dialTimeoutSeconds", defaults.dialTimeoutSeconds)
        ).normalized()

        SoarProfile(
            id = id,
            name = name,
            domain = domain,
            publicKey = publicKey,
            resolvers = resolvers,
            soarSettings = settings
        )
    }

    fun toJson(profile: SoarProfile): String {
        val s = profile.soarSettings.normalized()
        val root = JSONObject().apply {
            put("schema", SCHEMA)
            put("version", VERSION)
            put("profile", JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("resolvers", JSONArray().apply {
                    profile.resolvers.forEach { put(it) }
                })
                put("server", JSONObject().apply {
                    put("domain", profile.domain.trim())
                    put("public_key", profile.publicKey.trim())
                })
                put("soarSettings", JSONObject().apply {
                    put("transport", s.transport)
                    put("discovery", s.discovery)
                    put("country", s.country)
                    put("discoveryWant", s.discoveryWant)
                    put("discoveryConcurrency", s.discoveryConcurrency)
                    put("discoveryPaceMs", s.discoveryPaceMs)
                    put("safeQName", s.safeQName)
                    put("maxQName", s.maxQName)
                    put("maxAnswer", s.maxAnswer)
                    put("holdMs", s.holdMs)
                    put("maxInflight", s.maxInflight)
                    put("maxPathWindow", s.maxPathWindow)
                    put("maxStreams", s.maxStreams)
                    put("streamWindowKiB", s.streamWindowKiB)
                    put("sendBufferKiB", s.sendBufferKiB)
                    put("sessionIdleSeconds", s.sessionIdleSeconds)
                    put("dialTimeoutSeconds", s.dialTimeoutSeconds)
                })
            })
        }
        return root.toString()
    }

    fun exportUri(profile: SoarProfile): String {
        val json = toJson(profile)
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        return "$URI_PREFIX$encoded"
    }

    fun parseResolversText(raw: String): List<String> {
        return raw.split(',', '\n', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun parseResolversArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optString(i, "").trim()
            if (item.isNotEmpty() && !out.contains(item)) {
                out.add(item)
            }
        }
        return out
    }

    private fun decodeBase64(encoded: String): Result<String> = runCatching {
        val cleaned = encoded.replace("\n", "").replace("\r", "").trim()
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        val bytes = try {
            Base64.getUrlDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            Base64.getDecoder().decode(padded)
        }
        String(bytes, Charsets.UTF_8)
    }
}
