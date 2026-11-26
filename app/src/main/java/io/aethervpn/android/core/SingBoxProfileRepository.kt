package io.aethervpn.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SingBoxProfile(
    val id: String,
    val name: String,
    val protocol: String,
    val rawUriOrJson: String,
    val lastPingMs: Long? = null,
    val lastExitIp: String? = null,
    val lastCountry: String? = null
)

data class SingBoxProfileStoreState(
    val activeProfileId: String? = null,
    val profiles: List<SingBoxProfile> = emptyList()
) {
    val activeProfile: SingBoxProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
}

/**
 * Clean JSON persistence for Sing-box profiles in `<filesDir>/singbox_profiles.json`.
 */
object SingBoxProfileRepository {
    private const val STORE_FILENAME = "singbox_profiles.json"

    fun load(filesDir: File): SingBoxProfileStoreState {
        val file = File(filesDir, STORE_FILENAME)
        if (!file.exists()) return SingBoxProfileStoreState()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val activeId = root.optString("activeProfileId").takeIf { it.isNotBlank() }
            val arr = root.optJSONArray("profiles") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val raw = o.optString("rawUriOrJson").takeIf { it.isNotBlank() } ?: continue
                    add(
                        SingBoxProfile(
                            id = id,
                            name = o.optString("name").ifBlank { "Profile" },
                            protocol = o.optString("protocol").ifBlank { "RAW" },
                            rawUriOrJson = raw,
                            lastPingMs = if (o.has("lastPingMs") && !o.isNull("lastPingMs")) o.optLong("lastPingMs") else null,
                            lastExitIp = o.optString("lastExitIp").takeIf { it.isNotBlank() },
                            lastCountry = o.optString("lastCountry").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            SingBoxProfileStoreState(activeProfileId = activeId, profiles = list)
        }.getOrDefault(SingBoxProfileStoreState())
    }

    fun save(filesDir: File, state: SingBoxProfileStoreState) {
        filesDir.mkdirs()
        val file = File(filesDir, STORE_FILENAME)
        val arr = JSONArray()
        state.profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("protocol", p.protocol)
                put("rawUriOrJson", p.rawUriOrJson)
                if (p.lastPingMs != null) put("lastPingMs", p.lastPingMs)
                if (!p.lastExitIp.isNullOrBlank()) put("lastExitIp", p.lastExitIp)
                if (!p.lastCountry.isNullOrBlank()) put("lastCountry", p.lastCountry)
            })
        }
        val root = JSONObject().apply {
            put("activeProfileId", state.activeProfileId ?: "")
            put("profiles", arr)
        }
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    fun addProfile(
        filesDir: File,
        rawUriOrJson: String,
        customName: String? = null
    ): Result<SingBoxProfile> = runCatching {
        val parsed = SingBoxConfigParser.parse(rawUriOrJson, customName).getOrThrow()
        val profile = SingBoxProfile(
            id = UUID.randomUUID().toString(),
            name = parsed.name,
            protocol = parsed.protocol,
            rawUriOrJson = rawUriOrJson.trim()
        )
        val current = load(filesDir)
        val updated = current.copy(
            activeProfileId = current.activeProfileId ?: profile.id,
            profiles = current.profiles + profile
        )
        save(filesDir, updated)
        profile
    }

    fun addProfilesBulk(
        filesDir: File,
        rawInput: String,
        customName: String? = null
    ): Result<Pair<List<SingBoxProfile>, Int>> = runCatching {
        val bulk = SingBoxConfigParser.parseBulk(rawInput, customName)
        require(bulk.parsedItems.isNotEmpty()) {
            "No valid Sing-box profiles found in input"
        }
        saveParsedBulk(filesDir, bulk)
    }

    fun addSubscriptionPayload(
        filesDir: File,
        rawPayload: String
    ): Result<Pair<List<SingBoxProfile>, Int>> = runCatching {
        val bulk = SingBoxConfigParser.parseSubscriptionPayload(rawPayload)
        require(bulk.parsedItems.isNotEmpty()) {
            "No valid Sing-box profiles found in subscription"
        }
        saveParsedBulk(filesDir, bulk)
    }

    private fun saveParsedBulk(
        filesDir: File,
        bulk: BulkSingBoxParseResult
    ): Pair<List<SingBoxProfile>, Int> {
        val newProfiles = bulk.parsedItems.map { (rawLine, parsed) ->
            SingBoxProfile(
                id = UUID.randomUUID().toString(),
                name = parsed.name,
                protocol = parsed.protocol,
                rawUriOrJson = rawLine
            )
        }
        val current = load(filesDir)
        val updated = current.copy(
            activeProfileId = current.activeProfileId ?: newProfiles.first().id,
            profiles = current.profiles + newProfiles
        )
        save(filesDir, updated)
        return newProfiles to bulk.skippedCount
    }

    fun updateProfile(
        filesDir: File,
        profileId: String,
        rawUriOrJson: String,
        customName: String? = null
    ): Result<SingBoxProfile> = runCatching {
        val trimmedRaw = rawUriOrJson.trim()
        val parsed = SingBoxConfigParser.parse(trimmedRaw, customName).getOrThrow()
        val current = load(filesDir)
        var updatedProfile: SingBoxProfile? = null
        val updatedList = current.profiles.map { existing ->
            if (existing.id == profileId) {
                val uriChanged = existing.rawUriOrJson.trim() != trimmedRaw
                val next = existing.copy(
                    name = parsed.name,
                    protocol = parsed.protocol,
                    rawUriOrJson = trimmedRaw,
                    lastPingMs = if (uriChanged) null else existing.lastPingMs,
                    lastExitIp = if (uriChanged) null else existing.lastExitIp,
                    lastCountry = if (uriChanged) null else existing.lastCountry
                )
                updatedProfile = next
                next
            } else {
                existing
            }
        }
        val resultProfile = updatedProfile ?: throw IllegalArgumentException("Profile not found")
        save(filesDir, current.copy(profiles = updatedList))
        resultProfile
    }

    fun sortProfilesByPing(filesDir: File): SingBoxProfileStoreState {
        val current = load(filesDir)
        val sorted = current.profiles.sortedWith(
            compareBy<SingBoxProfile> { it.lastPingMs == null }
                .thenBy { it.lastPingMs ?: Long.MAX_VALUE }
        )
        val updated = current.copy(profiles = sorted)
        save(filesDir, updated)
        return updated
    }

    fun deleteProfile(filesDir: File, profileId: String): SingBoxProfileStoreState {
        val current = load(filesDir)
        val remaining = current.profiles.filterNot { it.id == profileId }
        val nextActive = if (current.activeProfileId == profileId) {
            remaining.firstOrNull()?.id
        } else {
            current.activeProfileId
        }
        val updated = SingBoxProfileStoreState(activeProfileId = nextActive, profiles = remaining)
        save(filesDir, updated)
        return updated
    }

    fun setActiveProfile(filesDir: File, profileId: String): SingBoxProfileStoreState {
        val current = load(filesDir)
        val exists = current.profiles.any { it.id == profileId }
        val updated = if (exists) current.copy(activeProfileId = profileId) else current
        save(filesDir, updated)
        return updated
    }

    fun updateTestResult(
        filesDir: File,
        profileId: String,
        pingMs: Long?,
        exitIp: String?,
        country: String?
    ): SingBoxProfileStoreState {
        val current = load(filesDir)
        val updatedList = current.profiles.map { p ->
            if (p.id == profileId) {
                p.copy(
                    lastPingMs = pingMs,
                    lastExitIp = exitIp,
                    lastCountry = country
                )
            } else {
                p
            }
        }
        val updated = current.copy(profiles = updatedList)
        save(filesDir, updated)
        return updated
    }

    /**
     * Formats all saved profiles as newline-separated URIs (or compacted single-line JSON)
     * suitable for clipboard/file export and round-trip import via [addProfilesBulk].
     */
    fun exportAllUris(profiles: List<SingBoxProfile>): String {
        return profiles.mapNotNull { profile ->
            val raw = profile.rawUriOrJson.trim()
            if (raw.isEmpty()) {
                null
            } else if (raw.startsWith("{")) {
                runCatching { JSONObject(raw).toString() }
                    .getOrDefault(raw.replace(Regex("\\s+"), " ").trim())
            } else {
                raw
            }
        }.joinToString("\n")
    }
}
