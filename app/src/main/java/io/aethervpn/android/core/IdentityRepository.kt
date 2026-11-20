package io.aethervpn.android.core

import java.io.File

data class IdentityInfo(
    val provisioned: Boolean = false,
    val deviceId: String = "",
    val ipv4: String = "",
    val ipv6: String = "",
    val organization: String = "",
    val assignedEndpoint: String = "",
    val cachedGateway: String = "",
    val cachedCarrier: String = ""
)

/**
 * Reads safe, non-secret WARP identity and last-known-good gateway metadata
 * persisted by CluvexStudio/Aether inside app-private `filesDir/aether/`.
 * Never reads or returns private keys or access tokens.
 */
object IdentityRepository {

    fun load(privateDataDir: File): IdentityInfo {
        val dir = File(privateDataDir, "aether")
        if (!dir.exists()) return IdentityInfo()

        val identityFile = listOf("aether.masque.toml", "aether.warp.toml", "aether.toml")
            .map { File(dir, it) }
            .firstOrNull { it.exists() && it.length() > 0 }

        val lastConnFile = dir.listFiles()
            ?.firstOrNull { it.name.contains("lastconn") && it.length() > 0 }

        val idFields = identityFile?.let(::parseSimpleToml) ?: emptyMap()
        val lcFields = lastConnFile?.let(::parseSimpleToml) ?: emptyMap()

        val deviceId = idFields["device_id"].orEmpty()
        return IdentityInfo(
            provisioned = deviceId.isNotBlank(),
            deviceId = deviceId,
            ipv4 = idFields["ipv4"].orEmpty(),
            ipv6 = idFields["ipv6"].orEmpty(),
            organization = idFields["organization"].orEmpty(),
            assignedEndpoint = idFields["assigned_endpoint"].orEmpty(),
            cachedGateway = lcFields["peer"].orEmpty(),
            cachedCarrier = lcFields["carrier"].orEmpty()
        )
    }

    fun resetIdentity(privateDataDir: File): Boolean {
        val dir = File(privateDataDir, "aether")
        if (!dir.exists()) return true
        return dir.listFiles()?.all { it.delete() } ?: true
    }

    private fun parseSimpleToml(file: File): Map<String, String> {
        val allowedKeys = setOf(
            "device_id", "ipv4", "ipv6", "organization",
            "assigned_endpoint", "peer", "carrier"
        )
        val out = mutableMapOf<String, String>()
        runCatching {
            file.useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).trim()
                    if (key !in allowedKeys) return@forEach
                    val value = line.substring(idx + 1).trim().trim('"', '\'')
                    out[key] = value
                }
            }
        }
        return out
    }
}
