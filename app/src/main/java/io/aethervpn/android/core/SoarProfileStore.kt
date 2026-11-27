package io.aethervpn.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SoarProfileStoreState(
    val profiles: List<SoarProfile> = emptyList(),
    val activeProfileId: String? = null,
    val connectionMode: SoarConnectionMode = SoarConnectionMode.PROXY_ONLY
) {
    val activeProfile: SoarProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
}

object SoarProfileStore {

    private const val STORE_FILENAME = "soar-profiles.json"

    @Synchronized
    fun load(filesDir: File): SoarProfileStoreState {
        val file = File(filesDir, STORE_FILENAME)
        if (!file.exists()) return SoarProfileStoreState()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val activeId = root.optString("activeProfileId", "").ifBlank { null }
            val mode = SoarConnectionMode.fromName(root.optString("connectionMode", ""))
            val arr = root.optJSONArray("profiles") ?: JSONArray()
            val list = mutableListOf<SoarProfile>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                SoarProfileParser.parseJson(item.toString()).getOrNull()?.let { list.add(it) }
            }
            SoarProfileStoreState(
                profiles = list,
                activeProfileId = activeId ?: list.firstOrNull()?.id,
                connectionMode = mode
            )
        }.getOrDefault(SoarProfileStoreState())
    }

    @Synchronized
    fun save(filesDir: File, state: SoarProfileStoreState) {
        filesDir.mkdirs()
        val file = File(filesDir, STORE_FILENAME)
        val root = JSONObject().apply {
            put("activeProfileId", state.activeProfileId ?: "")
            put("connectionMode", state.connectionMode.name)
            put("profiles", JSONArray().apply {
                state.profiles.forEach { profile ->
                    put(JSONObject(SoarProfileParser.toJson(profile)))
                }
            })
        }
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    @Synchronized
    fun upsertProfile(filesDir: File, profile: SoarProfile, setActive: Boolean = true): SoarProfileStoreState {
        val current = load(filesDir)
        val idx = current.profiles.indexOfFirst { it.id == profile.id }
        val updatedList = current.profiles.toMutableList()
        if (idx >= 0) {
            updatedList[idx] = profile
        } else {
            updatedList.add(0, profile)
        }
        val next = current.copy(
            profiles = updatedList,
            activeProfileId = if (setActive || current.activeProfileId == null) profile.id else current.activeProfileId
        )
        save(filesDir, next)
        return next
    }

    @Synchronized
    fun deleteProfile(filesDir: File, profileId: String): SoarProfileStoreState {
        val current = load(filesDir)
        val updatedList = current.profiles.filterNot { it.id == profileId }
        val nextActive = if (current.activeProfileId == profileId) {
            updatedList.firstOrNull()?.id
        } else {
            current.activeProfileId
        }
        val next = current.copy(profiles = updatedList, activeProfileId = nextActive)
        save(filesDir, next)
        return next
    }

    @Synchronized
    fun setActiveProfile(filesDir: File, profileId: String): SoarProfileStoreState {
        val current = load(filesDir)
        if (current.profiles.none { it.id == profileId }) return current
        val next = current.copy(activeProfileId = profileId)
        save(filesDir, next)
        return next
    }

    @Synchronized
    fun setConnectionMode(filesDir: File, mode: SoarConnectionMode): SoarProfileStoreState {
        val current = load(filesDir)
        val next = current.copy(connectionMode = mode)
        save(filesDir, next)
        return next
    }
}
