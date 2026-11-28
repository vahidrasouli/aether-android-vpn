package io.aethervpn.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ResolverPack(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val resolvers: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val metrics: Map<String, ResolverBenchmarkMetrics> = emptyMap()
) {
    fun previewSummary(): String {
        if (resolvers.isEmpty()) return "Empty pack"
        val head = resolvers.take(2).joinToString(", ")
        val remaining = resolvers.size - 2
        return if (remaining > 0) "$head (+$remaining more)" else head
    }
}

data class SoarResolverPackStoreState(
    val packs: List<ResolverPack> = emptyList(),
    val activePackId: String? = null
) {
    val activePack: ResolverPack?
        get() = packs.firstOrNull { it.id == activePackId }
}

/**
 * Persists named DNS Resolver Packs (`soar-resolver-packs.json`) for Soar.
 */
object SoarResolverPackStore {

    private const val STORE_FILENAME = "soar-resolver-packs.json"

    @Synchronized
    fun load(filesDir: File): SoarResolverPackStoreState {
        val file = File(filesDir, STORE_FILENAME)
        if (!file.exists()) return SoarResolverPackStoreState()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val activeId = if (root.isNull("activePackId")) {
                null
            } else {
                root.optString("activePackId", "").trim().ifBlank { null }
            }
            val arr = root.optJSONArray("packs") ?: JSONArray()
            val list = mutableListOf<ResolverPack>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim().ifBlank { UUID.randomUUID().toString() }
                val name = obj.optString("name", "").trim()
                if (name.isBlank()) continue
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                val rArr = obj.optJSONArray("resolvers") ?: JSONArray()
                val resolvers = mutableListOf<String>()
                val seen = HashSet<String>()
                for (j in 0 until rArr.length()) {
                    val item = rArr.optString(j, "").trim()
                    if (item.isNotEmpty() && seen.add(item)) {
                        resolvers.add(item)
                    }
                }
                val metricsObj = obj.optJSONObject("metrics")
                val metricsMap = mutableMapOf<String, ResolverBenchmarkMetrics>()
                if (metricsObj != null) {
                    val keys = metricsObj.keys()
                    while (keys.hasNext()) {
                        val ip = keys.next()
                        val mObj = metricsObj.optJSONObject(ip) ?: continue
                        metricsMap[ip] = ResolverBenchmarkMetrics(
                            ip = ip,
                            avgPingMs = mObj.optInt("avgPingMs", Int.MAX_VALUE),
                            jitterMs = mObj.optInt("jitterMs", Int.MAX_VALUE),
                            lossPercent = mObj.optInt("lossPercent", 100),
                            successCount = mObj.optInt("successCount", 0),
                            totalProbes = mObj.optInt("totalProbes", ResolverBenchmarkMetrics.DEFAULT_PROBES_PER_IP)
                        )
                    }
                }
                list.add(
                    ResolverPack(
                        id = id,
                        name = name,
                        resolvers = resolvers,
                        createdAt = createdAt,
                        metrics = metricsMap
                    )
                )
            }
            val validActive = activeId?.takeIf { id -> list.any { it.id == id } }
            SoarResolverPackStoreState(packs = list, activePackId = validActive)
        }.getOrDefault(SoarResolverPackStoreState())
    }

    @Synchronized
    fun save(filesDir: File, state: SoarResolverPackStoreState) {
        filesDir.mkdirs()
        val file = File(filesDir, STORE_FILENAME)
        val root = JSONObject().apply {
            put("activePackId", state.activePackId ?: "")
            put("packs", JSONArray().apply {
                state.packs.forEach { pack ->
                    put(JSONObject().apply {
                        put("id", pack.id)
                        put("name", pack.name)
                        put("createdAt", pack.createdAt)
                        put("resolvers", JSONArray().apply {
                            pack.resolvers.forEach { put(it) }
                        })
                        if (pack.metrics.isNotEmpty()) {
                            put("metrics", JSONObject().apply {
                                pack.metrics.forEach { (ip, m) ->
                                    put(ip, JSONObject().apply {
                                        put("avgPingMs", m.avgPingMs)
                                        put("jitterMs", m.jitterMs)
                                        put("lossPercent", m.lossPercent)
                                        put("successCount", m.successCount)
                                        put("totalProbes", m.totalProbes)
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    @Synchronized
    fun upsertPack(
        filesDir: File,
        pack: ResolverPack,
        setActive: Boolean = false
    ): SoarResolverPackStoreState {
        val current = load(filesDir)
        val cleanResolvers = pack.resolvers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val cleanResolverSet = cleanResolvers.toHashSet()
        val cleanPack = pack.copy(
            name = pack.name.trim().ifBlank { "Resolver Pack" },
            resolvers = cleanResolvers,
            metrics = pack.metrics.filterKeys { it in cleanResolverSet }
        )
        val idx = current.packs.indexOfFirst { it.id == cleanPack.id }
        val updated = current.packs.toMutableList()
        if (idx >= 0) {
            updated[idx] = cleanPack
        } else {
            updated.add(0, cleanPack)
        }
        val nextActive = when {
            setActive -> cleanPack.id
            current.activePackId != null && updated.any { it.id == current.activePackId } -> current.activePackId
            else -> null
        }
        val next = SoarResolverPackStoreState(packs = updated, activePackId = nextActive)
        save(filesDir, next)
        return next
    }

    @Synchronized
    fun renamePack(filesDir: File, packId: String, newName: String): SoarResolverPackStoreState {
        val trimmed = newName.trim()
        val current = load(filesDir)
        if (trimmed.isEmpty()) return current
        val updated = current.packs.map { pack ->
            if (pack.id == packId) pack.copy(name = trimmed) else pack
        }
        val next = current.copy(packs = updated)
        save(filesDir, next)
        return next
    }

    @Synchronized
    fun deletePack(filesDir: File, packId: String): SoarResolverPackStoreState {
        val current = load(filesDir)
        val updated = current.packs.filterNot { it.id == packId }
        val nextActive = if (current.activePackId == packId) null else current.activePackId
        val next = SoarResolverPackStoreState(packs = updated, activePackId = nextActive)
        save(filesDir, next)
        return next
    }

    @Synchronized
    fun setActivePack(filesDir: File, packId: String?): SoarResolverPackStoreState {
        val current = load(filesDir)
        val validId = packId?.takeIf { id -> current.packs.any { it.id == id } }
        val next = current.copy(activePackId = validId)
        save(filesDir, next)
        return next
    }

    /**
     * Auto-extracts resolvers from an imported `soar://` profile into a named pack
     * (`<ProfileName> Default`). No-op if the profile has no resolvers.
     */
    @Synchronized
    fun extractDefaultPackFromProfile(
        filesDir: File,
        profile: SoarProfile,
        setActiveIfNone: Boolean = true
    ): SoarResolverPackStoreState {
        val cleanResolvers = profile.resolvers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val current = load(filesDir)
        if (cleanResolvers.isEmpty()) return current

        val defaultName = "${profile.name.trim().ifBlank { profile.domain }} Default"
        val existing = current.packs.firstOrNull { it.name.equals(defaultName, ignoreCase = true) }
        val pack = if (existing != null) {
            existing.copy(name = defaultName, resolvers = cleanResolvers)
        } else {
            ResolverPack(name = defaultName, resolvers = cleanResolvers)
        }
        val shouldActivate = setActiveIfNone && current.activePackId == null
        return upsertPack(filesDir, pack, setActive = shouldActivate)
    }

    fun exportPackToText(pack: ResolverPack): String {
        return pack.resolvers.joinToString("\n")
    }
}
