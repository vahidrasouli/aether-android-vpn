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
                    b[i] = (x and 0xFFL).toByte()
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
    fun multiVersionDeltaManifestMatchesUpTo10BuildsBehindAndFallsBackCleanly(): Unit = kotlinx.coroutines.runBlocking<Unit> {
        val targetBytes = "FULL_APK_BYTES_B66_TARGET".toByteArray()
        val targetSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(targetBytes)
            .joinToString("") { "%02x".format(it) }
        val wrongPatchSha = "f".repeat(64)

        val releaseJson = """
            {
              "tag_name": "debug-latest",
              "name": "v1.1.0-debug-b66",
              "prerelease": true,
              "html_url": "https://github.com/vahidrasouli/aether-android-vpn/releases/tag/debug-latest",
              "body": "Debug build 66",
              "assets": [
                {
                  "name": "aether-android-arm64-v1.1.0-debug-b66.apk",
                  "browser_download_url": "https://example.com/aether-android-arm64-v1.1.0-debug-b66.apk"
                },
                {
                  "name": "delta-b65-to-b66.patch",
                  "browser_download_url": "https://example.com/delta-b65-to-b66.patch"
                },
                {
                  "name": "delta-b61-to-b66.patch",
                  "browser_download_url": "https://example.com/delta-b61-to-b66.patch"
                },
                {
                  "name": "delta-b56-to-b66.patch",
                  "browser_download_url": "https://example.com/delta-b56-to-b66.patch"
                }
              ]
            }
        """.trimIndent()

        val manifestJson = """
            {
              "toVersionCode": 66,
              "toVersionName": "1.1.0-debug-b66",
              "fullAssetName": "aether-android-arm64-v1.1.0-debug-b66.apk",
              "fullSize": ${targetBytes.size},
              "targetApkSha256": "$targetSha",
              "patches": [
                {
                  "fromVersionCode": 65,
                  "patchAssetName": "delta-b65-to-b66.patch",
                  "patchSize": 500000,
                  "patchSha256": "$wrongPatchSha"
                },
                {
                  "fromVersionCode": 61,
                  "patchAssetName": "delta-b61-to-b66.patch",
                  "patchSize": 650000,
                  "patchSha256": "$wrongPatchSha"
                },
                {
                  "fromVersionCode": 56,
                  "patchAssetName": "delta-b56-to-b66.patch",
                  "patchSize": 800000,
                  "patchSha256": "$wrongPatchSha"
                }
              ]
            }
        """.trimIndent()

        // 1, 5, and 10 builds behind should each match their respective patch
        for ((fromVc, expectedPatchName) in listOf(65 to "delta-b65-to-b66.patch", 61 to "delta-b61-to-b66.patch", 56 to "delta-b56-to-b66.patch")) {
            val res = UpdateManager.parseReleaseResponse(
                jsonBody = releaseJson,
                currentVersion = "1.1.0-debug-b$fromVc",
                isDebugBuild = true,
                deltaManifestJson = manifestJson,
                currentVersionCode = fromVc
            ) as UpdateCheckResult.UpdateAvailable
            assertNotNull("Expected deltaManifest for b$fromVc", res.release.deltaManifest)
            assertNotNull("Expected matchedPatch for b$fromVc", res.release.deltaManifest!!.matchedPatch)
            assertEquals(expectedPatchName, res.release.deltaManifest!!.matchedPatch!!.patchAssetName)
        }

        // >10 builds behind (b40) should have deltaManifest with matchedPatch == null
        val farBehind = UpdateManager.parseReleaseResponse(
            jsonBody = releaseJson,
            currentVersion = "1.1.0-debug-b40",
            isDebugBuild = true,
            deltaManifestJson = manifestJson,
            currentVersionCode = 40
        ) as UpdateCheckResult.UpdateAvailable
        assertNotNull(farBehind.release.deltaManifest)
        assertNull("Expected matchedPatch to be null when >10 builds behind", farBehind.release.deltaManifest!!.matchedPatch)

        // Verify version-scoped .part cleanup and automatic full-APK fallback when patch SHA-256 mismatches
        val tempCacheDir = java.nio.file.Files.createTempDirectory("aether-upd-multi-").toFile()
        val updatesDir = java.io.File(tempCacheDir, "updates").apply { mkdirs() }
        val staleOldPart = java.io.File(updatesDir, "b64.patch.part").apply { writeText("stale_b64") }
        val baseApk = java.io.File(tempCacheDir, "base.apk").apply { writeText("OLD_APK") }

        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            try {
                while (!server.isClosed) {
                    server.accept().use { sock ->
                        val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                        val requestLine = inp.readLine() ?: ""
                        var line = inp.readLine()
                        while (!line.isNullOrEmpty()) {
                            line = inp.readLine()
                        }
                        val out = sock.getOutputStream()
                        val payload = if (requestLine.contains("/delta-")) {
                            "CORRUPT_PATCH_BYTES".toByteArray()
                        } else {
                            targetBytes
                        }
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray()
                        )
                        out.write(payload)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val matchB65 = UpdateManager.parseReleaseResponse(
                jsonBody = releaseJson,
                currentVersion = "1.1.0-debug-b65",
                isDebugBuild = true,
                deltaManifestJson = manifestJson,
                currentVersionCode = 65
            ) as UpdateCheckResult.UpdateAvailable

            val patchWithLocalUrl = matchB65.release.deltaManifest!!.matchedPatch!!.copy(
                patchDownloadUrl = "http://127.0.0.1:${server.localPort}/delta-b65-to-b66.patch"
            )
            val releaseWithLocalUrls = matchB65.release.copy(
                apkDownloadUrl = "http://127.0.0.1:${server.localPort}/aether-android-arm64-v1.1.0-debug-b66.apk",
                deltaManifest = matchB65.release.deltaManifest!!.copy(matchedPatch = patchWithLocalUrl)
            )

            val res = UpdateManager.downloadUpdate(
                release = releaseWithLocalUrls,
                baseApkPath = baseApk.absolutePath,
                currentVersionCode = 65,
                cacheDir = tempCacheDir
            ) {}
            assertTrue("Expected fallback to full APK to succeed", res.isSuccess)
            val downloaded = res.getOrThrow()
            assertTrue(downloaded.readBytes().contentEquals(targetBytes))
            assertFalse("Expected older b64.patch.part to be cleaned up", staleOldPart.exists())
        } finally {
            runCatching { server.close() }
            tempCacheDir.deleteRecursively()
        }
    }

    @Test
    fun myPackageReplacedReceiverIsRegisteredAndExportedSafelyInManifest() {
        val manifestFile = listOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.isFile }
        assertNotNull("AndroidManifest.xml not found", manifestFile)

        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile!!)

        val receivers = doc.getElementsByTagName("receiver")
        var foundReplacedReceiver = false
        var foundInstallReceiver = false
        val androidNs = "http://schemas.android.com/apk/res/android"

        for (i in 0 until receivers.length) {
            val node = receivers.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.getAttributeNS(androidNs, "name")
            val exported = node.getAttributeNS(androidNs, "exported")

            if (name == ".core.PackageReplacedReceiver") {
                foundReplacedReceiver = true
                assertEquals("PackageReplacedReceiver must be exported=false", "false", exported)
                val actions = node.getElementsByTagName("action")
                var hasMyPackageReplaced = false
                for (j in 0 until actions.length) {
                    val actionEl = actions.item(j) as? org.w3c.dom.Element ?: continue
                    if (actionEl.getAttributeNS(androidNs, "name") == "android.intent.action.MY_PACKAGE_REPLACED") {
                        hasMyPackageReplaced = true
                        break
                    }
                }
                assertTrue("PackageReplacedReceiver must declare MY_PACKAGE_REPLACED action", hasMyPackageReplaced)
            } else if (name == ".core.UpdateInstallReceiver") {
                foundInstallReceiver = true
                assertEquals("UpdateInstallReceiver must be exported=false", "false", exported)
            }
        }

        assertTrue("PackageReplacedReceiver missing from AndroidManifest.xml", foundReplacedReceiver)
        assertTrue("UpdateInstallReceiver missing from AndroidManifest.xml", foundInstallReceiver)
        assertEquals("io.aethervpn.android.ACTION_INSTALL_STATUS", UpdateManager.ACTION_INSTALL_STATUS)
    }

    @Test
    fun updateActivityIsRegisteredInManifest() {
        val manifestFile = listOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.isFile }
        assertNotNull("AndroidManifest.xml not found", manifestFile)

        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile!!)

        val activities = doc.getElementsByTagName("activity")
        var foundUpdateActivity = false
        val androidNs = "http://schemas.android.com/apk/res/android"

        for (i in 0 until activities.length) {
            val node = activities.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.getAttributeNS(androidNs, "name")
            if (name == ".ui.UpdateActivity") {
                foundUpdateActivity = true
                assertEquals("UpdateActivity must be exported=false", "false", node.getAttributeNS(androidNs, "exported"))
                assertEquals(
                    "UpdateActivity parentActivityName must be .ui.MainActivity",
                    ".ui.MainActivity",
                    node.getAttributeNS(androidNs, "parentActivityName")
                )
            }
        }

        assertTrue("UpdateActivity missing from AndroidManifest.xml", foundUpdateActivity)
    }

    @Test
    fun downloadProgressFormatsLiveSpeedWhenPositive() {
        val mbProgress = io.aethervpn.android.core.DownloadProgress(
            downloadedBytes = (4.2 * 1024 * 1024).toLong(),
            totalBytes = (7.1 * 1024 * 1024).toLong(),
            percent = 59,
            speedBytesPerSec = (1.4 * 1024 * 1024).toLong()
        )
        assertEquals("59% • 4.2 / 7.1 MB • 1.4 MB/s", mbProgress.formatLabel())

        val kbProgress = io.aethervpn.android.core.DownloadProgress(
            downloadedBytes = (0.5 * 1024 * 1024).toLong(),
            totalBytes = (2.0 * 1024 * 1024).toLong(),
            percent = 25,
            speedBytesPerSec = 512L * 1024L
        )
        assertEquals("25% • 0.5 / 2.0 MB • 512 KB/s", kbProgress.formatLabel())
    }

    @Test
    fun parallelFourChunkDownloadAssemblesExactSha256AcrossRedirect(): Unit = kotlinx.coroutines.runBlocking<Unit> {
        val payload = ByteArray(2_500_000)
        java.util.Random(42L).nextBytes(payload)
        val expectedSha = io.aethervpn.android.core.BsPatch.sha256Hex(
            java.nio.file.Files.createTempFile("aether-sha-", ".bin").toFile().apply {
                writeBytes(payload)
                deleteOnExit()
            }
        )

        val tempCacheDir = java.nio.file.Files.createTempDirectory("aether-upd-p4-").toFile()
        val updatesDir = java.io.File(tempCacheDir, "updates").apply { mkdirs() }
        val staleChunks = java.io.File(updatesDir, "b99.apk.part.chunks.json").apply { writeText("{}") }

        val server = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val pool = java.util.concurrent.Executors.newCachedThreadPool()
        val cdnGetCount = java.util.concurrent.atomic.AtomicInteger(0)
        val redirectGetCount = java.util.concurrent.atomic.AtomicInteger(0)

        val acceptThread = Thread {
            while (!server.isClosed) {
                try {
                    val sock = server.accept()
                    pool.execute {
                        sock.use { s ->
                            val inp = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream()))
                            val reqLine = inp.readLine() ?: return@use
                            var rangeHeader: String? = null
                            var line = inp.readLine()
                            while (!line.isNullOrEmpty()) {
                                if (line.startsWith("Range:", ignoreCase = true)) {
                                    rangeHeader = line.substringAfter(":").trim()
                                }
                                line = inp.readLine()
                            }
                            val parts = reqLine.split(" ")
                            val method = parts.getOrNull(0) ?: "GET"
                            val path = parts.getOrNull(1) ?: "/"
                            val out = s.getOutputStream()

                            if (path.startsWith("/redirect/")) {
                                if (method == "GET") redirectGetCount.incrementAndGet()
                                out.write(
                                    ("HTTP/1.1 302 Found\r\n" +
                                        "Location: http://127.0.0.1:${server.localPort}/cdn/update.apk\r\n" +
                                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                                )
                                out.flush()
                                return@use
                            }

                            if (method == "HEAD") {
                                out.write(
                                    ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Length: ${payload.size}\r\n" +
                                        "Accept-Ranges: bytes\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray()
                                )
                                out.flush()
                                return@use
                            }

                            cdnGetCount.incrementAndGet()
                            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                                val spec = rangeHeader.removePrefix("bytes=").split("-")
                                val start = spec[0].toInt()
                                val end = spec.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toInt() ?: (payload.size - 1)
                                val len = end - start + 1
                                out.write(
                                    ("HTTP/1.1 206 Partial Content\r\n" +
                                        "Content-Range: bytes $start-$end/${payload.size}\r\n" +
                                        "Content-Length: $len\r\n" +
                                        "Accept-Ranges: bytes\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray()
                                )
                                out.write(payload, start, len)
                                out.flush()
                            } else {
                                out.write(
                                    ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Length: ${payload.size}\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray()
                                )
                                out.write(payload)
                                out.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        try {
            val res = UpdateManager.downloadApk(
                downloadUrl = "http://127.0.0.1:${server.localPort}/redirect/update.apk",
                fileName = "aether-android-arm64-v1.1.0-debug-b100.apk",
                cacheDir = tempCacheDir,
                partFileNameOverride = "b100.apk.part",
                expectedTotalBytes = payload.size.toLong(),
                preservePartialOnCancel = true
            ) {}

            assertTrue("Expected parallel 4-chunk download to succeed", res.isSuccess)
            val downloaded = res.getOrThrow()
            assertEquals(expectedSha, io.aethervpn.android.core.BsPatch.sha256Hex(downloaded))
            assertEquals("Expected 4 parallel chunk GETs on resolved CDN URL", 4, cdnGetCount.get())
            assertEquals("Expected 0 GETs on redirect URL", 0, redirectGetCount.get())
            assertFalse("Expected stale .chunks.json to be deleted", staleChunks.exists())
            assertFalse(
                "Expected completed .chunks.json to be deleted",
                java.io.File(updatesDir, "b100.apk.part.chunks.json").exists()
            )
        } finally {
            runCatching { server.close() }
            pool.shutdownNow()
            tempCacheDir.deleteRecursively()
        }
    }

    @Test
    fun parallelChunkCancellationAndResumePreservesExactPercentageAndSha256(): Unit = kotlinx.coroutines.runBlocking<Unit> {
        val payload = ByteArray(2_097_152) // 2 MB -> 512 KB per chunk
        java.util.Random(77L).nextBytes(payload)
        val expectedSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val tempCacheDir = java.nio.file.Files.createTempDirectory("aether-upd-p4-resume-").toFile()
        val updatesDir = java.io.File(tempCacheDir, "updates").apply { mkdirs() }
        val fileName = "aether-android-arm64-v1.1.0-debug-b77.apk"

        val server = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val pool = java.util.concurrent.Executors.newCachedThreadPool()
        val throttleFirstPhase = java.util.concurrent.atomic.AtomicBoolean(true)
        val phase1BytesSentLatch = java.util.concurrent.CountDownLatch(4)
        val resumedRangeStarts = java.util.Collections.synchronizedList(mutableListOf<Int>())

        val acceptThread = Thread {
            while (!server.isClosed) {
                try {
                    val sock = server.accept()
                    pool.execute {
                        sock.use { s ->
                            val inp = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream()))
                            val reqLine = inp.readLine() ?: return@use
                            var rangeHeader: String? = null
                            var line = inp.readLine()
                            while (!line.isNullOrEmpty()) {
                                if (line.startsWith("Range:", ignoreCase = true)) {
                                    rangeHeader = line.substringAfter(":").trim()
                                }
                                line = inp.readLine()
                            }
                            val method = reqLine.split(" ").getOrNull(0) ?: "GET"
                            val out = s.getOutputStream()

                            if (method == "HEAD") {
                                out.write(
                                    ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Length: ${payload.size}\r\n" +
                                        "Accept-Ranges: bytes\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray()
                                )
                                out.flush()
                                return@use
                            }

                            val spec = rangeHeader!!.removePrefix("bytes=").split("-")
                            val start = spec[0].toInt()
                            val end = spec[1].toInt()
                            val len = end - start + 1
                            if (!throttleFirstPhase.get()) {
                                resumedRangeStarts.add(start)
                            }

                            out.write(
                                ("HTTP/1.1 206 Partial Content\r\n" +
                                    "Content-Range: bytes $start-$end/${payload.size}\r\n" +
                                    "Content-Length: $len\r\n" +
                                    "Accept-Ranges: bytes\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray()
                            )
                            if (throttleFirstPhase.get()) {
                                val firstSlice = minOf(131_072, len)
                                out.write(payload, start, firstSlice)
                                out.flush()
                                phase1BytesSentLatch.countDown()
                                Thread.sleep(4_000)
                            } else {
                                out.write(payload, start, len)
                                out.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        try {
            val url = "http://127.0.0.1:${server.localPort}/update.apk"
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                UpdateManager.downloadApk(
                    downloadUrl = url,
                    fileName = fileName,
                    cacheDir = tempCacheDir,
                    partFileNameOverride = "b77.apk.part",
                    expectedTotalBytes = payload.size.toLong(),
                    preservePartialOnCancel = true
                ) {}
            }

            assertTrue(phase1BytesSentLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(200)
            job.cancel()
            job.join()

            val savedBytes = UpdateManager.getPartialDownloadSize(
                fileName = fileName,
                cacheDir = tempCacheDir,
                targetVersionCode = 77,
                isPatch = false
            )
            assertTrue("Expected partial bytes > 0 after cancel, got $savedBytes", savedBytes > 0L)
            assertTrue("Expected partial bytes < total, got $savedBytes", savedBytes < payload.size)
            val expectedResumePct = ((savedBytes * 100L) / payload.size).toInt().coerceIn(0, 100)
            assertTrue("Expected resume percentage > 0, got $expectedResumePct", expectedResumePct > 0)

            throttleFirstPhase.set(false)
            val resumeProgress = java.util.Collections.synchronizedList(
                mutableListOf<io.aethervpn.android.core.DownloadProgress>()
            )
            val resumeRes = UpdateManager.downloadApk(
                downloadUrl = url,
                fileName = fileName,
                cacheDir = tempCacheDir,
                partFileNameOverride = "b77.apk.part",
                expectedTotalBytes = payload.size.toLong(),
                preservePartialOnCancel = true
            ) { p ->
                resumeProgress.add(p)
            }

            assertTrue("Expected resumed download to succeed", resumeRes.isSuccess)
            assertTrue("Expected progress events on resume", resumeProgress.isNotEmpty())
            assertEquals(
                "Resume progress must start at exact prior percentage",
                expectedResumePct,
                resumeProgress.first().percent
            )
            assertTrue(
                "Resume progress must never reset below prior percentage",
                resumeProgress.all { it.percent >= expectedResumePct }
            )
            assertEquals("Resume progress must finish at 100%", 100, resumeProgress.last().percent)
            assertTrue(
                "Expected resumed chunks to start at non-zero offsets",
                resumedRangeStarts.all { it > 0 }
            )

            val finalFile = resumeRes.getOrThrow()
            assertEquals(expectedSha, io.aethervpn.android.core.BsPatch.sha256Hex(finalFile))
            assertFalse(java.io.File(updatesDir, "b77.apk.part.chunks.json").exists())
        } finally {
            runCatching { server.close() }
            pool.shutdownNow()
            tempCacheDir.deleteRecursively()
        }
    }

    @Test
    fun installStateStoreLocksDuringInstallingAndUnlocksOnFailureAborted() {
        val store = io.aethervpn.android.core.UpdateInstallStateStore
        store.reset()
        assertEquals(io.aethervpn.android.core.UpdateInstallPhase.IDLE, store.state.value.phase)
        assertFalse(store.state.value.isInstallLocked)

        store.beginInstalling("Installing update…")
        assertEquals(io.aethervpn.android.core.UpdateInstallPhase.INSTALLING, store.state.value.phase)
        assertTrue("Install button must be locked during INSTALLING", store.state.value.isInstallLocked)

        store.onInstallStatus(android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION)
        assertEquals(io.aethervpn.android.core.UpdateInstallPhase.INSTALLING, store.state.value.phase)
        assertTrue("Install button must remain locked while awaiting user confirmation", store.state.value.isInstallLocked)
        assertTrue(store.state.value.awaitingUserConfirmation)

        store.onInstallStatus(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(io.aethervpn.android.core.UpdateInstallPhase.FAILED, store.state.value.phase)
        assertFalse("Install button must unlock on STATUS_FAILURE_ABORTED", store.state.value.isInstallLocked)

        store.beginInstalling()
        assertTrue(store.state.value.isInstallLocked)
        store.onInstallStatus(android.content.pm.PackageInstaller.STATUS_FAILURE, "Invalid APK")
        assertEquals(io.aethervpn.android.core.UpdateInstallPhase.FAILED, store.state.value.phase)
        assertFalse("Install button must unlock on STATUS_FAILURE", store.state.value.isInstallLocked)

        store.reset()
    }

    @Test
    fun discardAllPartialDownloadsDeletesAllPartPatchAndMetaFiles() {
        val tempCacheDir = java.nio.file.Files.createTempDirectory("aether-upd-discard-").toFile()
        try {
            val updatesDir = java.io.File(tempCacheDir, "updates").apply { mkdirs() }
            val release = io.aethervpn.android.core.ReleaseInfo(
                tagName = "debug-latest",
                versionName = "1.1.0-debug-b99",
                releaseNotes = "",
                apkDownloadUrl = "https://example.com/app.apk",
                apkFileName = "aether-android-arm64-v1.1.0-debug-b99.apk",
                htmlUrl = "https://example.com",
                deltaManifest = io.aethervpn.android.core.DeltaManifest(
                    toVersionCode = 99,
                    toVersionName = "1.1.0-debug-b99",
                    fullAssetName = "aether-android-arm64-v1.1.0-debug-b99.apk",
                    fullSize = 10_000_000L,
                    targetApkSha256 = "abc",
                    matchedPatch = io.aethervpn.android.core.DeltaPatchEntry(
                        fromVersionCode = 98,
                        patchAssetName = "delta-98-to-99.patch",
                        patchSize = 2_000_000L,
                        patchSha256 = "def",
                        patchDownloadUrl = "https://example.com/delta.patch"
                    )
                )
            )
            java.io.File(updatesDir, "b99.patch.part").writeText("partial-patch")
            java.io.File(updatesDir, "b99.patch.part.chunks.json").writeText("{}")
            java.io.File(updatesDir, "stale.tmp").writeText("tmp")
            java.io.File(updatesDir, "stale.apk").writeText("apk")

            assertTrue(UpdateManager.getPartialDownloadSize(release, tempCacheDir) > 0L)

            UpdateManager.discardAllPartialDownloads(release, tempCacheDir)

            assertEquals(0L, UpdateManager.getPartialDownloadSize(release, tempCacheDir))
            assertTrue("All partial and temp files must be deleted", updatesDir.listFiles().isNullOrEmpty())
        } finally {
            tempCacheDir.deleteRecursively()
        }
    }

    @Test
    fun gitHubActionsStatusCheckerParsesAllStatesAndHandles304NotModified(): Unit = kotlinx.coroutines.runBlocking<Unit> {
        val inProgJson = """
            {"workflow_runs":[{"id":101,"status":"in_progress","conclusion":null,"display_title":"feat(soar): upgrade","run_started_at":"2026-11-28T10:00:00Z","html_url":"https://github.com/run/101"}]}
        """.trimIndent()
        val inProg = io.aethervpn.android.core.GitHubActionsStatusChecker.parseWorkflowRunsJson(inProgJson)
        assertTrue(inProg is io.aethervpn.android.core.CiBuildState.InProgress)
        assertEquals(101L, (inProg as io.aethervpn.android.core.CiBuildState.InProgress).runId)

        val successJson = """
            {"workflow_runs":[{"id":101,"status":"completed","conclusion":"success","display_title":"feat(soar): upgrade","html_url":"https://github.com/run/101"}]}
        """.trimIndent()
        val succeeded = io.aethervpn.android.core.GitHubActionsStatusChecker.parseWorkflowRunsJson(successJson)
        assertTrue(succeeded is io.aethervpn.android.core.CiBuildState.Succeeded)

        val failedJson = """
            {"workflow_runs":[{"id":102,"status":"completed","conclusion":"failure","display_title":"broken","html_url":"https://github.com/run/102"}]}
        """.trimIndent()
        val failed = io.aethervpn.android.core.GitHubActionsStatusChecker.parseWorkflowRunsJson(failedJson)
        assertTrue(failed is io.aethervpn.android.core.CiBuildState.Failed)
        assertEquals("failure", (failed as io.aethervpn.android.core.CiBuildState.Failed).conclusion)

        val server = java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
        val receivedIfNoneMatch = java.util.concurrent.atomic.AtomicReference<String?>()
        val thread = Thread {
            try {
                // Request 1: 200 OK with ETag
                server.accept().use { sock ->
                    val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                    while (!inp.readLine().isNullOrEmpty()) { }
                    val body = successJson.toByteArray()
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nETag: \"etag-101\"\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                }
                // Request 2: 304 Not Modified
                server.accept().use { sock ->
                    val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                    var line = inp.readLine()
                    while (!line.isNullOrEmpty()) {
                        if (line.startsWith("If-None-Match:", ignoreCase = true)) {
                            receivedIfNoneMatch.set(line.substringAfter(":").trim())
                        }
                        line = inp.readLine()
                    }
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 304 Not Modified\r\nETag: \"etag-101\"\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { server.close() }
            }
        }
        thread.isDaemon = true
        thread.start()

        try {
            val url = "http://127.0.0.1:${server.localPort}/actions/runs?per_page=1"
            val first = io.aethervpn.android.core.GitHubActionsStatusChecker.fetchLatestRunStatus(null, url).getOrThrow()
            assertFalse(first.notModified)
            assertEquals("\"etag-101\"", first.etag)
            assertTrue(first.state is io.aethervpn.android.core.CiBuildState.Succeeded)

            val second = io.aethervpn.android.core.GitHubActionsStatusChecker.fetchLatestRunStatus(first.etag, url).getOrThrow()
            assertTrue(second.notModified)
            assertNull(second.state)
            assertEquals("\"etag-101\"", receivedIfNoneMatch.get())
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun checkForUpdatesFetchesDirectCdnManifestFirstWithoutCallingGitHubApi(): Unit = kotlinx.coroutines.runBlocking<Unit> {
        val targetSha = "a".repeat(64)
        val patchSha = "b".repeat(64)
        val manifestJson = """
            {
              "toVersionCode": 102,
              "toVersionName": "1.1.0-debug-b102",
              "fullAssetName": "aether-android-arm64-v1.1.0-debug-b102.apk",
              "fullSize": 18000000,
              "targetApkSha256": "$targetSha",
              "patches": [
                {
                  "fromVersionCode": 101,
                  "patchAssetName": "delta-b101-to-b102.patch",
                  "patchSize": 250000,
                  "patchSha256": "$patchSha"
                }
              ]
            }
        """.trimIndent()

        val cdnCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val apiCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val return404FromCdn = java.util.concurrent.atomic.AtomicBoolean(false)

        val apiReleaseJson = """
            {
              "tag_name": "debug-latest",
              "name": "v1.1.0-debug-b102",
              "html_url": "https://github.com/vahidrasouli/aether-android-vpn/releases/tag/debug-latest",
              "assets": [
                {
                  "name": "aether-android-arm64-v1.1.0-debug-b102.apk",
                  "browser_download_url": "https://example.com/aether-android-arm64-v1.1.0-debug-b102.apk"
                }
              ]
            }
        """.trimIndent()

        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptThread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { sock ->
                        val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                        val reqLine = inp.readLine() ?: return@use
                        while (!inp.readLine().isNullOrEmpty()) { }
                        val path = reqLine.split(" ").getOrNull(1) ?: "/"
                        val out = sock.getOutputStream()
                        if (path.contains("/releases/download/debug-latest/")) {
                            cdnCalls.incrementAndGet()
                            if (return404FromCdn.get()) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val bytes = manifestJson.toByteArray()
                                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(bytes)
                            }
                        } else {
                            apiCalls.incrementAndGet()
                            val bytes = apiReleaseJson.toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(bytes)
                        }
                        out.flush()
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        try {
            val cdnUrl = "http://127.0.0.1:${server.localPort}/releases/download/debug-latest/delta-manifest.json"
            val apiUrl = "http://127.0.0.1:${server.localPort}/repos/vahidrasouli/aether-android-vpn/releases/tags/debug-latest"

            val res = UpdateManager.checkForUpdates(
                currentVersion = "1.1.0-debug-b101",
                isDebugBuild = true,
                apiUrl = apiUrl,
                currentVersionCode = 101,
                directManifestUrl = cdnUrl
            )
            assertTrue(res is UpdateCheckResult.UpdateAvailable)
            val available = res as UpdateCheckResult.UpdateAvailable
            assertEquals("1.1.0-debug-b102", available.release.versionName)
            assertEquals(1, cdnCalls.get())
            assertEquals("api.github.com fallback must NOT be called when CDN succeeds", 0, apiCalls.get())
            assertEquals(
                "http://127.0.0.1:${server.localPort}/releases/download/debug-latest/delta-b101-to-b102.patch",
                available.release.deltaManifest?.matchedPatch?.patchDownloadUrl
            )

            // Verify 404 on CDN falls back to API
            return404FromCdn.set(true)
            val fallbackRes = UpdateManager.checkForUpdates(
                currentVersion = "1.1.0-debug-b101",
                isDebugBuild = true,
                apiUrl = apiUrl,
                currentVersionCode = 101,
                directManifestUrl = cdnUrl
            )
            assertTrue(fallbackRes is UpdateCheckResult.UpdateAvailable)
            assertEquals(1, apiCalls.get())
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun gitHubActionsStatusCheckerClampsProgressAt95AndStopsSilentlyOnRateLimit(): Unit = kotlinx.coroutines.runBlocking<Unit> {
        assertEquals(45_000L, io.aethervpn.android.core.GitHubActionsStatusChecker.POLL_INTERVAL_ACTIVE_MS)

        val inProgJson = """
            {"workflow_runs":[{"id":555,"run_number":102,"status":"in_progress","conclusion":null,"display_title":"fix: update","run_started_at":"2026-11-28T10:00:00Z","html_url":"https://github.com/run/555"}]}
        """.trimIndent()
        val state = io.aethervpn.android.core.GitHubActionsStatusChecker.parseWorkflowRunsJson(inProgJson)
            as io.aethervpn.android.core.CiBuildState.InProgress
        assertEquals(102, state.runNumber)

        // Clamps at 95% even after 60 minutes while in_progress
        val oneHourLater = state.startedAtMs + 60 * 60_000L
        assertEquals(
            95,
            io.aethervpn.android.core.GitHubActionsStatusChecker.estimateBuildProgressPercent(state, oneHourLater)
        )

        // Hits 100% on completed/success
        val succeeded = io.aethervpn.android.core.CiBuildState.Succeeded(555L, "fix: update", null, 102)
        assertEquals(
            100,
            io.aethervpn.android.core.GitHubActionsStatusChecker.estimateBuildProgressPercent(succeeded)
        )

        // HTTP 403 / 429 returns rateLimited=true without throwing
        val server = java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { sock ->
                    val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                    while (!inp.readLine().isNullOrEmpty()) { }
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { server.close() }
            }
        }
        thread.isDaemon = true
        thread.start()

        try {
            val poll = io.aethervpn.android.core.GitHubActionsStatusChecker.fetchLatestRunStatus(
                previousEtag = null,
                apiUrl = "http://127.0.0.1:${server.localPort}/actions/runs?per_page=1"
            ).getOrThrow()
            assertTrue(poll.rateLimited)
            assertNull(poll.state)
        } finally {
            runCatching { server.close() }
        }
    }
}
