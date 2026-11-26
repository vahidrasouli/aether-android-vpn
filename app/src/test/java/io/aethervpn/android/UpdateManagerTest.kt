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
    fun downloadApkCancellationDeletesAllPartialFiles(): Unit = kotlinx.coroutines.runBlocking<Unit> {
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
    fun downloadApkFailureCleansUpPartialFiles(): Unit = kotlinx.coroutines.runBlocking<Unit> {
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

    @Test
    fun downloadApkResumesPartialDownloadWithHttp206() = kotlinx.coroutines.runBlocking<Unit> {
        val tempCacheDir = java.nio.file.Files.createTempDirectory("aether-upd-resume-").toFile()
        val updatesDir = java.io.File(tempCacheDir, "updates").apply { mkdirs() }
        val fileName = "aether-android-arm64-v1.1.0-debug-b42.apk"
        val partFile = java.io.File(updatesDir, "$fileName.part")
        val firstHalf = "HELLO_".toByteArray()
        val secondHalf = "WORLD_APK".toByteArray()
        partFile.writeBytes(firstHalf)

        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        var receivedRangeHeader: String? = null
        val serverThread = Thread {
            try {
                server.accept().use { sock ->
                    val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                    var line = inp.readLine()
                    while (!line.isNullOrEmpty()) {
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            receivedRangeHeader = line.substringAfter(":").trim()
                        }
                        line = inp.readLine()
                    }
                    val total = firstHalf.size + secondHalf.size
                    val out = sock.getOutputStream()
                    out.write(
                        ("HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Range: bytes ${firstHalf.size}-${total - 1}/$total\r\n" +
                            "Content-Length: ${secondHalf.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray()
                    )
                    out.write(secondHalf)
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
                fileName = fileName,
                cacheDir = tempCacheDir
            ) {}
            assertTrue(res.isSuccess)
            assertEquals("bytes=${firstHalf.size}-", receivedRangeHeader)
            val downloaded = res.getOrThrow()
            assertEquals(fileName, downloaded.name)
            assertEquals("HELLO_WORLD_APK", downloaded.readText())
            assertFalse(partFile.exists())
        } finally {
            runCatching { server.close() }
            tempCacheDir.deleteRecursively()
        }
    }

    @Test
    fun cleanupInstalledApksDeletesLeftoverApksOnStartup() {
        val tempCacheDir = java.nio.file.Files.createTempDirectory("aether-upd-clean-").toFile()
        val updatesDir = java.io.File(tempCacheDir, "updates").apply { mkdirs() }
        val oldApk = java.io.File(updatesDir, "old.apk").apply { writeText("apk") }
        val oldTmp = java.io.File(updatesDir, "old.apk.tmp").apply { writeText("tmp") }
        val activePart = java.io.File(updatesDir, "next.apk.part").apply { writeText("part") }

        try {
            UpdateManager.cleanupInstalledApks(tempCacheDir)
            assertFalse(oldApk.exists())
            assertFalse(oldTmp.exists())
            assertTrue(activePart.exists())
        } finally {
            tempCacheDir.deleteRecursively()
        }
    }

    @Test
    fun bsPatchRoundTripReproducesExactTargetBytesAndSha256() {
        val tempDir = java.nio.file.Files.createTempDirectory("aether-bspatch-").toFile()
        try {
            val oldBytes = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray(Charsets.UTF_8)
            val newBytes = "ABCDE12345FGHIJ_EXTRA_UVWXY!".toByteArray(Charsets.UTF_8)

            // Build a valid BSDIFF40 patch:
            // Tuple 1: add 5 ("ABCDE", diff 0), extra 5 ("12345"), seek 0 (oldPos -> 5)
            // Tuple 2: add 5 ("FGHIJ", diff 0), extra 7 ("_EXTRA_"), seek 10 (oldPos -> 20)
            // Tuple 3: add 6 ("UVWXYZ" + diff for '!' at index 5: '!' - 'Z'), extra 0, seek 0
            val ctrlRaw = java.io.ByteArrayOutputStream()
            fun writeOfft(out: java.io.OutputStream, v: Long) {
                var x = if (v < 0) -v else v
                val b = ByteArray(8)
                for (i in 0..7) {
                    b[i] = (x and 0xFF).toByte()
                    x = x ushr 8
                }
                if (v < 0) b[7] = (b[7].toInt() or 0x80).toByte()
                out.write(b)
            }
            writeOfft(ctrlRaw, 5); writeOfft(ctrlRaw, 5); writeOfft(ctrlRaw, 0)
            writeOfft(ctrlRaw, 5); writeOfft(ctrlRaw, 7); writeOfft(ctrlRaw, 10)
            writeOfft(ctrlRaw, 6); writeOfft(ctrlRaw, 0); writeOfft(ctrlRaw, 0)

            val diffRaw = ByteArray(16)
            diffRaw[15] = ('!'.code - 'Z'.code).toByte()
            val extraRaw = "12345_EXTRA_".toByteArray(Charsets.UTF_8)

            fun bzip2(data: ByteArray): ByteArray {
                val baos = java.io.ByteArrayOutputStream()
                org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(baos).use {
                    it.write(data)
                }
                return baos.toByteArray()
            }

            val ctrlBz2 = bzip2(ctrlRaw.toByteArray())
            val diffBz2 = bzip2(diffRaw)
            val extraBz2 = bzip2(extraRaw)

            val patchFile = java.io.File(tempDir, "test.patch")
            java.io.FileOutputStream(patchFile).use { fos ->
                fos.write("BSDIFF40".toByteArray(Charsets.US_ASCII))
                writeOfft(fos, ctrlBz2.size.toLong())
                writeOfft(fos, diffBz2.size.toLong())
                writeOfft(fos, newBytes.size.toLong())
                fos.write(ctrlBz2)
                fos.write(diffBz2)
                fos.write(extraBz2)
            }

            val oldFile = java.io.File(tempDir, "old.apk").apply { writeBytes(oldBytes) }
            val outFile = java.io.File(tempDir, "new.apk")

            io.aethervpn.android.core.BsPatch.patch(oldFile, patchFile, outFile)

            assertTrue(outFile.exists())
            assertTrue(outFile.readBytes().contentEquals(newBytes))

            val expectedSha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(newBytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedSha, io.aethervpn.android.core.BsPatch.sha256Hex(outFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun deltaManifestMismatchedFromVersionCodeFallsBackToFullApk(): Unit = kotlinx.coroutines.runBlocking<Unit> {
        val releaseJson = """
            {
              "tag_name": "debug-latest",
              "name": "v1.1.0-debug-b57",
              "prerelease": true,
              "html_url": "https://github.com/vahidrasouli/aether-android-vpn/releases/tag/debug-latest",
              "body": "Debug build 57",
              "assets": [
                {
                  "name": "aether-android-arm64-v1.1.0-debug-b57.apk",
                  "browser_download_url": "https://example.com/aether-android-arm64-v1.1.0-debug-b57.apk"
                },
                {
                  "name": "delta-b55-to-b57.patch",
                  "browser_download_url": "https://example.com/delta-b55-to-b57.patch"
                }
              ]
            }
        """.trimIndent()
        val manifestJson = """
            {
              "fromVersionCode": 55,
              "toVersionCode": 57,
              "patchAssetName": "delta-b55-to-b57.patch",
              "patchSize": 600000,
              "fullAssetName": "aether-android-arm64-v1.1.0-debug-b57.apk",
              "fullSize": 18000000,
              "targetApkSha256": "${"a".repeat(64)}"
            }
        """.trimIndent()

        val mismatchResult = UpdateManager.parseReleaseResponse(
            jsonBody = releaseJson,
            currentVersion = "1.1.0-debug-b56",
            isDebugBuild = true,
            deltaManifestJson = manifestJson,
            currentVersionCode = 56
        ) as UpdateCheckResult.UpdateAvailable
        assertNull("Expected deltaManifest to be null when fromVersionCode != currentVersionCode", mismatchResult.release.deltaManifest)

        val matchResult = UpdateManager.parseReleaseResponse(
            jsonBody = releaseJson,
            currentVersion = "1.1.0-debug-b55",
            isDebugBuild = true,
            deltaManifestJson = manifestJson,
            currentVersionCode = 55
        ) as UpdateCheckResult.UpdateAvailable
        assertNotNull("Expected deltaManifest when fromVersionCode == currentVersionCode", matchResult.release.deltaManifest)
        assertEquals(600000L, matchResult.release.deltaManifest!!.patchSize)

        // Verify downloadUpdate falls back cleanly to full APK when fromVersionCode mismatches
        val tempCacheDir = java.nio.file.Files.createTempDirectory("aether-upd-fallback-").toFile()
        val baseApk = java.io.File(tempCacheDir, "base.apk").apply { writeText("OLD_APK") }
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val fullApkPayload = "FULL_APK_BYTES_B57".toByteArray()
        val serverThread = Thread {
            try {
                server.accept().use { sock ->
                    val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                    var line = inp.readLine()
                    while (!line.isNullOrEmpty()) {
                        line = inp.readLine()
                    }
                    val out = sock.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${fullApkPayload.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray()
                    )
                    out.write(fullApkPayload)
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
            val releaseWithMismatchedDelta = mismatchResult.release.copy(
                apkDownloadUrl = "http://127.0.0.1:${server.localPort}/full.apk"
            )
            val res = UpdateManager.downloadUpdate(
                release = releaseWithMismatchedDelta,
                baseApkPath = baseApk.absolutePath,
                currentVersionCode = 56,
                cacheDir = tempCacheDir
            ) {}
            assertTrue(res.isSuccess)
            val downloaded = res.getOrThrow()
            assertEquals("FULL_APK_BYTES_B57", downloaded.readText())
        } finally {
            runCatching { server.close() }
            tempCacheDir.deleteRecursively()
        }
    }
}
