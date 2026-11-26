package io.aethervpn.android.core

import android.content.SharedPreferences
import java.io.File

/**
 * Cleans stale temporary files inside `cacheDir` whenever a new app versionCode
 * is launched, preventing leftover update APKs, sing-box test configs, or temp JSONs
 * from persisting across Debug/Release upgrades while preserving user profiles and settings.
 */
object CacheHygiene {
    const val PREFS_NAME = "aether_cache_hygiene"
    const val KEY_LAST_VERSION_CODE = "last_launched_version_code"

    private val STALE_CACHE_SUBDIRS = listOf("updates", "singbox-test")

    /**
     * Compares [currentVersionCode] against [KEY_LAST_VERSION_CODE] in [prefs].
     * If different, purges stale temporary files in [cacheDir], updates the stored
     * version code, and returns `true`.
     */
    fun purgeStaleCacheOnVersionChange(
        prefs: SharedPreferences,
        currentVersionCode: Int,
        cacheDir: File
    ): Boolean {
        val lastVersionCode = prefs.getInt(KEY_LAST_VERSION_CODE, -1)
        if (lastVersionCode == currentVersionCode) {
            return false
        }
        purgeStaleCache(cacheDir)
        prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
        return true
    }

    /**
     * Deletes stale temporary directories (`updates/`, `singbox-test/`) and stray
     * temp `.json` / `.tmp` / `.apk` files inside [cacheDir].
     */
    fun purgeStaleCache(cacheDir: File) {
        if (!cacheDir.exists() || !cacheDir.isDirectory) return

        for (dirName in STALE_CACHE_SUBDIRS) {
            val subDir = File(cacheDir, dirName)
            if (subDir.exists()) {
                runCatching { subDir.deleteRecursively() }
            }
        }

        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && isStaleTempFile(file.name)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun isStaleTempFile(name: String): Boolean {
        return name.endsWith(".json", ignoreCase = true) ||
            name.endsWith(".tmp", ignoreCase = true) ||
            name.endsWith(".apk", ignoreCase = true)
    }
}
