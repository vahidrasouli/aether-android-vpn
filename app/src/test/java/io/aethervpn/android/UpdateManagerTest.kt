package io.aethervpn.android

import io.aethervpn.android.core.DebugBuildVersion
import io.aethervpn.android.core.SemVer
import io.aethervpn.android.core.UpdateCheckResult
import io.aethervpn.android.core.UpdateManager
import kotlinx.coroutines.launch
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

        val remoteB39 = DebugBuildVersion.parse("1.1.0-debug-b39")
        val remoteB40 = DebugBuildVersion.parse("aether-android-arm64-v1.1.0-debug-b40.apk")
        assertNotNull(remoteB39)
        assertNotNull(remoteB40)
        assertEquals(39, remoteB39!!.buildNumber)
        assertEquals(40, remoteB40!!.buildNumber)
        assertTrue(remoteB40.isNewerThan("1.1.0-debug-b39"))
        assertTrue(remoteB39.isNewerThan("1.1.0-debug-local"))
        assertFalse(remoteB39.isNewerThan("1.1.0-debug-b40"))
    }

    @Test
    fun downloadApkCancellationDeletesAllPartialFiles() = kotlinx.coroutines.runBlocking {
        val tempCacheDir = java.nio.file.Files.createTempDirectory("aether-upd-cancel-").toFile()
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptedLatch = java.util.concurrent.CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.accept().use { sock ->
                    acceptedLatch.countDown()
                    val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                    var line = inp.readLine()
                    while (!line.isNullOrEmpty()) {
                        line = inp.readLine()
                    }
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(ByteArray(4096) { 0x41 })
                    out.flush()
                    Thread.sleep(5_000)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { server.close() }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                UpdateManager.downloadApk(
                    downloadUrl = "http://127.0.0.1:${server.localPort}/update.apk",
                    fileName = "aether-android-arm64-v1.1.0-debug-b40.apk",
                    cacheDir = tempCacheDir
                ) {}
            }
            assertTrue(acceptedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(150)
            job.cancel()
            job.join()

            val updatesDir = java.io.File(tempCacheDir, "updates")
            val remaining = updatesDir.listFiles()?.toList().orEmpty()
            assertTrue("Expected zero partial files after cancel, found: $remaining", remaining.isEmpty())
        } finally {
            runCatching { server.close() }
            tempCacheDir.deleteRecursively()
        }
    }

    @Test
    fun downloadApkFailureCleansUpPartialFiles() = kotlinx.coroutines.runBlocking {
        val tempCacheDir = java.nio.file.Files.createTempDirectory("aether-upd-fail-").toFile()
        val updatesDir = java.io.File(tempCacheDir, "updates").apply { mkdirs() }
        java.io.File(updatesDir, "stale.apk.tmp").writeText("stale")

        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            try {
                server.accept().use { sock ->
                    val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                    var line = inp.readLine()
                    while (!line.isNullOrEmpty()) {
                        line = inp.readLine()
                    }
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { server.close() }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val res = UpdateManager.downloadApk(
                downloadUrl = "http://127.0.0.1:${server.localPort}/update.apk",
                fileName = "aether-android-arm64-v1.1.0-debug-b40.apk",
                cacheDir = tempCacheDir
            ) {}
            assertTrue(res.isFailure)
            val remaining = updatesDir.listFiles()?.toList().orEmpty()
            assertTrue("Expected zero files after failure, found: $remaining", remaining.isEmpty())
        } finally {
            runCatching { server.close() }
            tempCacheDir.deleteRecursively()
        }
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
