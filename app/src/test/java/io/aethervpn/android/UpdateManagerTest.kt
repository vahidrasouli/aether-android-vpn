package io.aethervpn.android

import io.aethervpn.android.core.DebugBuildVersion
import io.aethervpn.android.core.SemVer
import io.aethervpn.android.core.UpdateCheckResult
import io.aethervpn.android.core.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun semVerParsingHandlesPrefixesAndPrereleases() {
        assertEquals(SemVer(1, 1, 0), SemVer.parse("1.1.0"))
        assertEquals(SemVer(1, 1, 0), SemVer.parse("v1.1.0"))
        assertEquals(SemVer(2, 0, 1, "beta.1"), SemVer.parse("V2.0.1-beta.1+build42"))
        assertNull(SemVer.parse("not-a-version"))
    }

    @Test
    fun semVerComparisonDetectsNewerReleasesCorrectly() {
        assertTrue(SemVer.isNewerVersion("v1.2.0", "1.1.0"))
        assertTrue(SemVer.isNewerVersion("1.1.1", "1.1.0"))
        assertTrue(SemVer.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(SemVer.isNewerVersion("1.1.0", "1.1.0-rc.1"))

        assertFalse(SemVer.isNewerVersion("v1.1.0", "1.1.0"))
        assertFalse(SemVer.isNewerVersion("1.0.9", "1.1.0"))
        assertFalse(SemVer.isNewerVersion("1.1.0-beta.1", "1.1.0"))
    }

    @Test
    fun parseReleaseResponseSelectsArm64ApkAndDetectsUpdate() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/vahidrasouli/aether-android-vpn/releases/tag/v1.2.0",
              "body": "- Improved MASQUE H2 upstream support",
              "assets": [
                {
                  "name": "aether-android-arm64-v1.2.0-debug.apk",
                  "browser_download_url": "https://example.com/aether-android-arm64-v1.2.0-debug.apk"
                },
                {
                  "name": "aether-android-arm64-v1.2.0.apk",
                  "browser_download_url": "https://example.com/aether-android-arm64-v1.2.0.apk"
                }
              ]
            }
        """.trimIndent()

        val result = UpdateManager.parseReleaseResponse(json, "1.1.0")
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals("1.2.0", available.release.versionName)
        assertEquals("aether-android-arm64-v1.2.0.apk", available.release.apkFileName)
        assertNotNull(available.release.apkDownloadUrl)

        val upToDate = UpdateManager.parseReleaseResponse(json, "1.2.0")
        assertTrue(upToDate is UpdateCheckResult.UpToDate)
    }

    @Test
    fun downloadProgressFormatsPercentageAndMbCounter() {
        val progress = io.aethervpn.android.core.DownloadProgress(
            downloadedBytes = (7.8 * 1024 * 1024).toLong(),
            totalBytes = (18.4 * 1024 * 1024).toLong(),
            percent = 42
        )
        assertEquals("42% • 7.8 / 18.4 MB", progress.formatLabel())
    }

    @Test
    fun channelEndpointSelectionIsolatesDebugAndRelease() {
        assertEquals(
            "https://api.github.com/repos/vahidrasouli/aether-android-vpn/releases/tags/debug-latest",
            UpdateManager.apiUrlForChannel(isDebugBuild = true)
        )
        assertEquals(
            "https://api.github.com/repos/vahidrasouli/aether-android-vpn/releases/latest",
            UpdateManager.apiUrlForChannel(isDebugBuild = false)
        )
    }

    @Test
    fun debugBuildVersionComparisonHandlesBuildNumbersLocalAndSemVer() {
        val remoteB38 = DebugBuildVersion.parse("v1.1.0-debug.b38-abc1234")
        assertNotNull(remoteB38)
        assertTrue(remoteB38!!.isNewerThan("1.1.0-debug.b33-d544774"))
        assertFalse(remoteB38.isNewerThan("1.1.0-debug.b38-abc1234"))
        assertFalse(remoteB38.isNewerThan("1.1.0-debug.b40-fff0000"))

        val remoteB1 = DebugBuildVersion.parse("1.1.0-debug.b1-abc1234")
        assertNotNull(remoteB1)
        assertTrue(remoteB1!!.isNewerThan("1.1.0-debug.local"))

        val higherSemVer = DebugBuildVersion.parse("v1.2.0-debug.b1-1112223")
        assertNotNull(higherSemVer)
        assertTrue(higherSemVer!!.isNewerThan("1.1.0-debug.b99-9998887"))
    }

    @Test
    fun parseDebugLatestReleaseJsonSelectsDebugApkAndDetectsUpdate() {
        val json = """
            {
              "tag_name": "debug-latest",
              "name": "v1.1.0-debug.b38-abc1234",
              "prerelease": true,
              "html_url": "https://github.com/vahidrasouli/aether-android-vpn/releases/tag/debug-latest",
              "body": "Rolling automated ARM64 Debug build from main (abc1234).",
              "assets": [
                {
                  "name": "SHA256SUMS.txt",
                  "browser_download_url": "https://example.com/SHA256SUMS.txt"
                },
                {
                  "name": "aether-android-arm64-v1.1.0-debug.b38-abc1234.apk",
                  "browser_download_url": "https://example.com/aether-android-arm64-v1.1.0-debug.b38-abc1234.apk"
                }
              ]
            }
        """.trimIndent()

        val update = UpdateManager.parseReleaseResponse(
            jsonBody = json,
            currentVersion = "1.1.0-debug.b33-d544774",
            isDebugBuild = true
        )
        assertTrue(update is UpdateCheckResult.UpdateAvailable)
        val available = update as UpdateCheckResult.UpdateAvailable
        assertEquals("debug-latest", available.release.tagName)
        assertEquals("1.1.0-debug.b38-abc1234", available.release.versionName)
        assertEquals(
            "aether-android-arm64-v1.1.0-debug.b38-abc1234.apk",
            available.release.apkFileName
        )
        assertEquals(
            "https://example.com/aether-android-arm64-v1.1.0-debug.b38-abc1234.apk",
            available.release.apkDownloadUrl
        )

        val upToDate = UpdateManager.parseReleaseResponse(
            jsonBody = json,
            currentVersion = "1.1.0-debug.b38-abc1234",
            isDebugBuild = true
        )
        assertTrue(upToDate is UpdateCheckResult.UpToDate)

        val fromLocal = UpdateManager.parseReleaseResponse(
            jsonBody = json,
            currentVersion = "1.1.0-debug.local",
            isDebugBuild = true
        )
        assertTrue(fromLocal is UpdateCheckResult.UpdateAvailable)
    }
}
