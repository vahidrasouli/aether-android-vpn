package io.aethervpn.android

import io.aethervpn.android.core.AetherEngineManager
import io.aethervpn.android.core.AetherLogCallback
import io.aethervpn.android.core.AetherNative
import io.aethervpn.android.core.PerfProfile
import io.aethervpn.android.core.PsiphonMode
import io.aethervpn.android.core.SocketProtectorCallback
import io.aethervpn.android.core.VpnConfig
import hev.htproxy.TProxyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class PsiphonAndMobileOptimizationTest {

    @Test
    fun testPsiphonBinaryResolutionPrecedence() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: ".", "aether_test_bin_${System.nanoTime()}").apply { mkdirs() }
        val libDir = File(tempDir, "lib").apply { mkdirs() }
        val filesDir = File(tempDir, "files").apply { mkdirs() }

        try {
            // Mock-like Context using reflection/dynamic proxy or anonymous subclass if possible,
            // or test candidate resolution logic directly.
            val candidateSo = File(libDir, "libpsiphon-tunnel-core.so")
            val candidatePt = File(libDir, "psiphon-tunnel-core")

            assertFalse(candidateSo.exists())

            // Create executable .so candidate
            candidateSo.writeText("fake-elf-binary")
            candidateSo.setExecutable(true)
            assertTrue(candidateSo.exists() && candidateSo.canExecute())

            // Verify buildCoreArguments includes --psiphon-bin when provided
            val mgr = AetherEngineManager(tempDir)
            val cfg = VpnConfig(psiphonMode = PsiphonMode.WARP_TO_PSIPHON)
            val argsWithBin = mgr.buildCoreArguments(cfg, psiphonBinaryPath = candidateSo.absolutePath)
            assertTrue(argsWithBin.contains("--psiphon-bin"))
            assertEquals(candidateSo.absolutePath, argsWithBin[argsWithBin.indexOf("--psiphon-bin") + 1])

            // When binary path is null, --psiphon-bin is not emitted
            val argsWithoutBin = mgr.buildCoreArguments(cfg, psiphonBinaryPath = null)
            assertFalse(argsWithoutBin.contains("--psiphon-bin"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPsiphonModeOffZeroOverhead() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val cfgOff = VpnConfig(psiphonMode = PsiphonMode.OFF)

        val args = mgr.buildCoreArguments(cfgOff)
        assertFalse(args.contains("--psiphon"))
        assertFalse(args.contains("--psiphon-reverse"))
        assertFalse(args.contains("--psiphon-only"))
        assertFalse(args.contains("--psiphon-bin"))
        assertFalse(args.contains("--psiphon-region"))
        assertEquals(1819, cfgOff.effectiveSocksPort)
    }

    @Test
    fun testRustMobileRuntimeTuningDefaults() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val defaultCfg = VpnConfig()

        // Default perf profile must be LOW on mobile
        assertEquals(PerfProfile.LOW, defaultCfg.perfProfile)

        val args = mgr.buildCoreArguments(defaultCfg)
        assertTrue(args.contains("--perf"))
        assertEquals("low", args[args.indexOf("--perf") + 1])

        assertTrue(args.contains("--log-level"))
        assertEquals("info", args[args.indexOf("--log-level") + 1])
    }

    @Test
    fun testR8AndJniSymbolPreservationSignatures() {
        // 1. AetherNative methods required by JNI bridge in libaether_jni.so
        val nativeClass = AetherNative::class.java
        val expectedNativeMethods = listOf(
            "nativeInitLogging" to 0,
            "nativeRegisterLogCallback" to 1,
            "nativeRegisterProtector" to 1,
            "nativeVersion" to 0,
            "nativeStartCore" to 1,
            "nativePollJob" to 1,
            "nativeCancelJob" to 1,
            "nativeFreeJob" to 1
        )

        for ((methodName, paramCount) in expectedNativeMethods) {
            val methods = nativeClass.declaredMethods.filter { it.name == methodName }
            assertTrue("Method $methodName must exist in AetherNative", methods.isNotEmpty())
            val m = methods.first()
            assertTrue("Method $methodName must be native", Modifier.isNative(m.modifiers))
            assertEquals("Method $methodName parameter count", paramCount, m.parameterCount)
        }

        // 2. onAetherLog called back from Rust logger
        val onAetherLog = nativeClass.declaredMethods.find { it.name == "onAetherLog" }
        assertNotNull("AetherNative.onAetherLog callback method must exist for JNI", onAetherLog)
        assertTrue("AetherNative.onAetherLog must be static", Modifier.isStatic(onAetherLog!!.modifiers))

        // 3. SocketProtectorCallback interface method for protectSocket(fd: Int): Boolean
        val protectorMethod = SocketProtectorCallback::class.java.declaredMethods.find { it.name == "protectSocket" }
        assertNotNull("SocketProtectorCallback.protectSocket must exist", protectorMethod)
        assertEquals(1, protectorMethod!!.parameterCount)
        assertEquals(Int::class.javaPrimitiveType, protectorMethod.parameterTypes[0])
        assertEquals(Boolean::class.javaPrimitiveType, protectorMethod.returnType)

        // 4. AetherLogCallback interface method for onLog(level: Int, message: String): Unit
        val logMethod = AetherLogCallback::class.java.declaredMethods.find { it.name == "onLog" }
        assertNotNull("AetherLogCallback.onLog must exist", logMethod)
        assertEquals(2, logMethod!!.parameterCount)
        assertEquals(Int::class.javaPrimitiveType, logMethod.parameterTypes[0])
        assertEquals(String::class.java, logMethod.parameterTypes[1])

        // 5. hev.htproxy.TProxyService methods required by libhev-socks5-tunnel.so
        val hevClass = TProxyService::class.java
        val tproxyStart = hevClass.declaredMethods.find { it.name == "TProxyStartService" }
        assertNotNull("TProxyStartService must exist", tproxyStart)
        assertTrue("TProxyStartService must be native", Modifier.isNative(tproxyStart!!.modifiers))

        val tproxyStop = hevClass.declaredMethods.find { it.name == "TProxyStopService" }
        assertNotNull("TProxyStopService must exist", tproxyStop)
        assertTrue("TProxyStopService must be native", Modifier.isNative(tproxyStop!!.modifiers))
    }
}
