package io.aethervpn.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class SingBoxExportAndQrTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun bulkExportFormatsAllUrisAndRoundTripsThroughBulkImporter() {
        val exportSourceDir = tempFolder.newFolder("export_src")
        val importTargetDir = tempFolder.newFolder("import_dst")

        val vmessJson = """{"v":"2","ps":"VMessNode","add":"198.51.100.3","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","tls":"tls"}"""
        val vmessUri = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray(Charsets.UTF_8))

        val uris = listOf(
            "vless://11111111-2222-3333-4444-555555555555@198.51.100.1:443?security=tls#VlessNode",
            "trojan://secret@198.51.100.2:443#TrojanNode",
            vmessUri,
            "ss://YWVzLTEyOC1nY206cGFzc3dvcmQ=@198.51.100.4:8388#SsNode",
            "hy2://password@198.51.100.5:443#Hy2Node"
        )

        uris.forEach { uri ->
            SingBoxProfileRepository.addProfile(exportSourceDir, uri).getOrThrow()
        }

        val saved = SingBoxProfileRepository.load(exportSourceDir).profiles
        assertEquals(uris.size, saved.size)

        val exportedText = SingBoxProfileRepository.exportAllUris(saved)
        assertEquals(uris.size, exportedText.lines().size)

        val (reimported, skipped) = SingBoxProfileRepository.addProfilesBulk(importTargetDir, exportedText).getOrThrow()
        assertEquals(0, skipped)
        assertEquals(uris.size, reimported.size)
        assertEquals(saved.map { it.name }, reimported.map { it.name })
        assertEquals(saved.map { it.protocol }, reimported.map { it.protocol })
        assertEquals(saved.map { it.rawUriOrJson }, reimported.map { it.rawUriOrJson })
    }

    @Test
    fun qrCodeMatrixGenerationSucceedsForStandardSingBoxUris() {
        val sampleUris = listOf(
            "vless://11111111-2222-3333-4444-555555555555@198.51.100.1:443?security=tls&sni=example.com#Node1",
            "vmess://eyJ2IjoiMiIsInBzIjoiTm9kZTIiLCJhZGQiOiIxOTguNTEuMTAwLjIiLCJwb3J0IjoiNDQzIn0=",
            "trojan://pass@198.51.100.3:443#Node3",
            "ss://YWVzLTEyOC1nY206cGFzcw==@198.51.100.4:8388#Node4",
            "hy2://pass@198.51.100.5:443#Node5",
            "wg://privatekey@198.51.100.6:51820?publickey=pubkey#Node6"
        )

        sampleUris.forEach { uri ->
            val matrixRes = QrCodeHelper.encodeQrMatrix(uri, sizePx = 320)
            assertTrue("QR encoding failed for $uri", matrixRes.isSuccess)
            val matrix = matrixRes.getOrThrow()
            assertEquals(320, matrix.width)
            assertEquals(320, matrix.height)
        }

        assertTrue(QrCodeHelper.encodeQrMatrix("   ").isFailure)
    }
}
