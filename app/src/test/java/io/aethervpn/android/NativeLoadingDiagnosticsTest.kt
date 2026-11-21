package io.aethervpn.android

import hev.htproxy.TProxyService
import io.aethervpn.android.core.AetherEngineManager
import io.aethervpn.android.core.AetherNative
import io.aethervpn.android.core.HevTunnelManager
import io.aethervpn.android.core.SocketProtectorCallback
import io.aethervpn.android.core.VpnConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeLoadingDiagnosticsTest {

    @Test
    fun runtimeDiagnosticsFormatsSdkAndArchitecture() {
        val diag = AetherNative.getRuntimeDiagnostics()
        assertTrue(diag.contains("SDK="))
        assertTrue(diag.contains("64bit="))
        assertTrue(diag.contains("arch="))
        assertTrue(diag.contains("abis="))
    }

    @Test
    fun engineManagerSurfacesDetailedNativeLinkerFailureInsteadOfHardcodedMissingString() = runBlocking {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val protector = SocketProtectorCallback { true }
        val result = mgr.start(VpnConfig(), protector)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        val message = ex?.message.orEmpty()

        // Verify that the error message contains the dynamic diagnostic prefix and ABI details,
        // rather than the old silent fallback "Native library libaether_jni.so is not present in this APK"
        assertTrue(message.startsWith("Native library loading failed:"))
        assertTrue(message.contains("SDK="))
        assertFalse(message == "Native library libaether_jni.so is not present in this APK")
    }

    @Test
    fun hevTunnelManagerSurfacesDetailedNativeLinkerFailure() {
        val mgr = HevTunnelManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val result = mgr.start(VpnConfig(), 10)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        val message = ex?.message.orEmpty()

        assertTrue(message.startsWith("HEV tunnel loading failed:"))
        assertFalse(message == "Native library libhev-socks5-tunnel.so is not present in this APK")
    }
}
