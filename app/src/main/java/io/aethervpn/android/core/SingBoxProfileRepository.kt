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
    val lastCountry: String? = null,
    val subscriptionId: String? = null,
    val subscriptionName: String? = null
)

data class SingBoxSubscription(
    val id: String,
    val name: String,
    val url: String? = null
)

data class SingBoxProfileStoreState(
    val activeProfileId: String? = null,
    val profiles: List<SingBoxProfile> = emptyList(),
    val subscriptions: List<SingBoxSubscription> = emptyList()
) {
    val activeProfile: SingBoxProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
}

/**
 * Clean JSON persistence for Sing-box profiles in `<filesDir>/singbox_profiles.json`.
 */
object SingBoxProfileRepository {
    private const val STORE_FILENAME = "singbox_profiles.json"
    const val TAB_ALL_ID = "__ALL__"
    const val TAB_MANUAL_ID = "__MANUAL__"

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
                            lastCountry = o.optString("lastCountry").takeIf { it.isNotBlank() },
                            subscriptionId = o.optString("subscriptionId").takeIf { it.isNotBlank() },
                            subscriptionName = o.optString("subscriptionName").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            val subArr = root.optJSONArray("subscriptions") ?: JSONArray()
            val subs = buildList {
                for (i in 0 until subArr.length()) {
                    val s = subArr.optJSONObject(i) ?: continue
                    val id = s.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val name = s.optString("name").takeIf { it.isNotBlank() } ?: "Subscription"
                    val url = s.optString("url").takeIf { it.isNotBlank() }
                    add(SingBoxSubscription(id = id, name = name, url = url))
                }
            }
            SingBoxProfileStoreState(activeProfileId = activeId, profiles = list, subscriptions = subs)
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
                if (!p.subscriptionId.isNullOrBlank()) put("subscriptionId", p.subscriptionId)
                if (!p.subscriptionName.isNullOrBlank()) put("subscriptionName", p.subscriptionName)
            })
        }
        val subArr = JSONArray()
        state.subscriptions.forEach { s ->
            subArr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                if (!s.url.isNullOrBlank()) put("url", s.url)
            })
        }
        val root = JSONObject().apply {
            put("activeProfileId", state.activeProfileId ?: "")
            put("profiles", arr)
            if (state.subscriptions.isNotEmpty()) {
                put("subscriptions", subArr)
            }
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
        rawPayload: String,
        subscriptionId: String? = null,
        subscriptionName: String? = null,
        subscriptionUrl: String? = null
    ): Result<Pair<List<SingBoxProfile>, Int>> = runCatching {
        val bulk = SingBoxConfigParser.parseSubscriptionPayload(rawPayload)
        require(bulk.parsedItems.isNotEmpty()) {
            "No valid Sing-box profiles found in subscription"
        }
        val resolvedSubId = subscriptionId?.takeIf { it.isNotBlank() }
            ?: if (!subscriptionName.isNullOrBlank() || !subscriptionUrl.isNullOrBlank()) {
                UUID.randomUUID().toString()
            } else {
                null
            }
        val resolvedSubName = subscriptionName?.trim()?.takeIf { it.isNotBlank() }
            ?: subscriptionUrl?.let { defaultSubscriptionNameFromUrl(it) }
            ?: if (resolvedSubId != null) "Subscription" else null

        saveParsedBulk(
            filesDir = filesDir,
            bulk = bulk,
            subscriptionId = resolvedSubId,
            subscriptionName = resolvedSubName,
            subscriptionUrl = subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    fun replaceSubscriptionProfiles(
        filesDir: File,
        subscriptionId: String,
        rawPayload: String
    ): Result<Pair<List<SingBoxProfile>, Int>> = runCatching {
        val bulk = SingBoxConfigParser.parseSubscriptionPayload(rawPayload)
        require(bulk.parsedItems.isNotEmpty()) {
            "No valid Sing-box profiles found in subscription"
        }
        val current = load(filesDir)
        val subMeta = current.subscriptions.firstOrNull { it.id == subscriptionId }
        val subName = subMeta?.name
            ?: current.profiles.firstOrNull { it.subscriptionId == subscriptionId }?.subscriptionName
            ?: "Subscription"

        val replacementProfiles = bulk.parsedItems.map { (rawLine, parsed) ->
            SingBoxProfile(
                id = UUID.randomUUID().toString(),
                name = parsed.name,
                protocol = parsed.protocol,
                rawUriOrJson = rawLine,
                subscriptionId = subscriptionId,
                subscriptionName = subName
            )
        }
        val keptProfiles = current.profiles.filterNot { it.subscriptionId == subscriptionId }
        val nextProfiles = keptProfiles + replacementProfiles
        val nextActive = if (keptProfiles.any { it.id == current.activeProfileId }) {
            current.activeProfileId
        } else {
            replacementProfiles.firstOrNull()?.id ?: nextProfiles.firstOrNull()?.id
        }
        save(
            filesDir,
            current.copy(
                activeProfileId = nextActive,
                profiles = nextProfiles
            )
        )
        replacementProfiles to bulk.skippedCount
    }

    private fun saveParsedBulk(
        filesDir: File,
        bulk: BulkSingBoxParseResult,
        subscriptionId: String? = null,
        subscriptionName: String? = null,
        subscriptionUrl: String? = null
    ): Pair<List<SingBoxProfile>, Int> {
        val newProfiles = bulk.parsedItems.map { (rawLine, parsed) ->
            SingBoxProfile(
                id = UUID.randomUUID().toString(),
                name = parsed.name,
                protocol = parsed.protocol,
                rawUriOrJson = rawLine,
                subscriptionId = subscriptionId,
                subscriptionName = subscriptionName
            )
        }
        val current = load(filesDir)
        val nextSubs = if (subscriptionId != null && subscriptionName != null) {
            val filtered = current.subscriptions.filterNot { it.id == subscriptionId }
            filtered + SingBoxSubscription(id = subscriptionId, name = subscriptionName, url = subscriptionUrl)
        } else {
            current.subscriptions
        }
        val updated = current.copy(
            activeProfileId = current.activeProfileId ?: newProfiles.first().id,
            profiles = current.profiles + newProfiles,
            subscriptions = nextSubs
        )
        save(filesDir, updated)
        return newProfiles to bulk.skippedCount
    }

    fun filterProfilesByTab(profiles: List<SingBoxProfile>, tabId: String): List<SingBoxProfile> = when (tabId) {
        TAB_ALL_ID -> profiles
        TAB_MANUAL_ID -> profiles.filter { it.subscriptionId.isNullOrBlank() }
        else -> profiles.filter { it.subscriptionId == tabId }
    }

    fun extractSubscriptions(state: SingBoxProfileStoreState): List<SingBoxSubscription> {
        val fromProfiles = linkedMapOf<String, SingBoxSubscription>()
        state.subscriptions.forEach { sub ->
            if (state.profiles.any { it.subscriptionId == sub.id }) {
                fromProfiles[sub.id] = sub
            }
        }
        state.profiles.forEach { p ->
            val sid = p.subscriptionId?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!fromProfiles.containsKey(sid)) {
                fromProfiles[sid] = SingBoxSubscription(
                    id = sid,
                    name = p.subscriptionName?.takeIf { it.isNotBlank() } ?: "Subscription"
                )
            }
        }
        return fromProfiles.values.toList()
    }

    fun defaultSubscriptionNameFromUrl(url: String): String {
        val host = runCatching { java.net.URL(url.trim()).host.orEmpty() }.getOrDefault("")
        val clean = host.removePrefix("www.").trim()
        return clean.ifBlank { "Subscription" }
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
