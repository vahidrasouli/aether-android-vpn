package io.aethervpn.android.core

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheHygieneTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun versionUpgradePurgesStaleCacheWithoutTouchingSavedProfiles() {
        val rootDir = tempFolder.newFolder("app_sandbox")
        val cacheDir = File(rootDir, "cache").apply { mkdirs() }
        val filesDir = File(rootDir, "files").apply { mkdirs() }

        // Populate stale cache directories and temp JSON/APK files
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        File(updatesDir, "aether-old.apk").writeText("old-apk")
        File(updatesDir, "aether-old.apk.part").writeText("partial")

        val singboxTestDir = File(cacheDir, "singbox-test").apply { mkdirs() }
        File(singboxTestDir, "test-12345.json").writeText("{}")

        val strayTempJson = File(cacheDir, "temp-config.json").apply { writeText("{}") }
        val strayTmp = File(cacheDir, "scratch.tmp").apply { writeText("tmp") }

        // Populate saved Sing-box profiles & settings in filesDir
        SingBoxProfileRepository.addProfile(
            filesDir,
            "vless://11111111-2222-3333-4444-555555555555@198.51.100.1:443?security=tls#KeepMe"
        ).getOrThrow()
        val settingsFile = File(filesDir, "vpn_config.json").apply { writeText("{\"singBoxEnabled\":true}") }

        val prefs = InMemorySharedPreferences()
        prefs.edit().putInt(CacheHygiene.KEY_LAST_VERSION_CODE, 10).apply()

        // Launch with new versionCode = 11 -> should purge cacheDir, preserve filesDir
        val purged = CacheHygiene.purgeStaleCacheOnVersionChange(
            prefs = prefs,
            currentVersionCode = 11,
            cacheDir = cacheDir
        )

        assertTrue(purged)
        assertEquals(11, prefs.getInt(CacheHygiene.KEY_LAST_VERSION_CODE, -1))
        assertFalse(updatesDir.exists())
        assertFalse(singboxTestDir.exists())
        assertFalse(strayTempJson.exists())
        assertFalse(strayTmp.exists())

        // Saved profiles and settings must remain untouched
        assertTrue(settingsFile.exists())
        val savedProfiles = SingBoxProfileRepository.load(filesDir).profiles
        assertEquals(1, savedProfiles.size)
        assertEquals("KeepMe", savedProfiles.first().name)

        // Re-launching with same versionCode = 11 should be a no-op
        val newTempJson = File(cacheDir, "runtime-keep.json").apply { writeText("{}") }
        val purgedAgain = CacheHygiene.purgeStaleCacheOnVersionChange(
            prefs = prefs,
            currentVersionCode = 11,
            cacheDir = cacheDir
        )
        assertFalse(purgedAgain)
        assertTrue(newTempJson.exists())
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                apply { if (key != null) pending[key] = values?.toSet() }

            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
