package io.aethervpn.android

import io.aethervpn.android.core.AetherEngineManager
import io.aethervpn.android.core.AetherLogCallback
import io.aethervpn.android.core.AetherNative
import io.aethervpn.android.core.AetherProtocol
import io.aethervpn.android.core.PerfProfile
import io.aethervpn.android.core.PsiphonMode
import io.aethervpn.android.core.PsiphonProtocol
import io.aethervpn.android.core.SocketProtectorCallback
import io.aethervpn.android.core.VpnConfig
import io.aethervpn.android.vpn.AetherVpnService
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

    @Test
    fun testPsiphonEgressReadinessSkipsSocks5ProbeOn1821() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))

        // WARP -> Psiphon and Psiphon Only must NOT probe 1821 (avoids socks5ReadCommand: EOF)
        for (mode in listOf(PsiphonMode.WARP_TO_PSIPHON, PsiphonMode.PSIPHON_ONLY)) {
            val cfg = VpnConfig(psiphonMode = mode)
            var probeCalled = false
            val notReady = mgr.isEndpointReady(
                config = cfg,
                logStartIndex = 1,
                logEntriesProvider = { listOf("12:00:00 [Aether] Starting Aether") },
                socksProbe = { probeCalled = true; true }
            )
            assertFalse("Should not be ready before psiphon ready log for $mode", notReady)
            assertFalse("Must not probe port 1821 for $mode", probeCalled)

            val ready = mgr.isEndpointReady(
                config = cfg,
                logStartIndex = 1,
                logEntriesProvider = {
                    listOf(
                        "12:00:00 [Aether] Starting Aether",
                        "12:00:05 [Aether] [+] psiphon is ready; 127.0.0.1:1821 leaves through psiphon"
                    )
                },
                socksProbe = { probeCalled = true; true }
            )
            assertTrue("Should be ready once psiphon ready log appears for $mode", ready)
            assertFalse("Must not probe port 1821 even when ready for $mode", probeCalled)
        }

        // OFF and Psiphon -> WARP use Aether's Rust SOCKS5 listener on 1819
        for (mode in listOf(PsiphonMode.OFF, PsiphonMode.PSIPHON_TO_WARP)) {
            val cfg = VpnConfig(psiphonMode = mode)
            var probedPort = -1
            val ready = mgr.isEndpointReady(
                config = cfg,
                logStartIndex = 0,
                logEntriesProvider = { emptyList() },
                socksProbe = { port -> probedPort = port; true }
            )
            assertTrue(ready)
            assertEquals(1819, probedPort)
        }
    }

    @Test
    fun testUpstreamProxyPassedWithMasqueH2() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val cfg = VpnConfig(
            protocol = io.aethervpn.android.core.AetherProtocol.MASQUE_H2,
            upstreamProxy = "socks5://127.0.0.1:10808"
        )
        val args = mgr.buildCoreArguments(cfg)
        val idx = args.indexOf("--upstream")
        assertTrue(idx >= 0)
        assertEquals("socks5://127.0.0.1:10808", args[idx + 1])
        assertTrue(args.contains("--h2"))
    }

    @Test
    fun testPsiphonStartupTimeoutBudgets() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))

        // PSIPHON_ONLY must receive at least 120s for initial Meek/CDN dial and tactics fetch
        assertEquals(
            120_000L,
            mgr.calculateStartupTimeoutMs(
                VpnConfig(
                    protocol = io.aethervpn.android.core.AetherProtocol.MASQUE_H3,
                    psiphonMode = PsiphonMode.PSIPHON_ONLY
                )
            )
        )

        // PSIPHON_TO_WARP must receive at least 150s for Psiphon bootstrap + WARP chaining
        assertEquals(
            150_000L,
            mgr.calculateStartupTimeoutMs(
                VpnConfig(
                    protocol = io.aethervpn.android.core.AetherProtocol.MASQUE_H3,
                    psiphonMode = PsiphonMode.PSIPHON_TO_WARP
                )
            )
        )

        // Larger scan budgets (e.g., THOROUGH = 310s) must still be preserved
        assertEquals(
            310_000L,
            mgr.calculateStartupTimeoutMs(
                VpnConfig(
                    protocol = io.aethervpn.android.core.AetherProtocol.MASQUE_H2,
                    scanMode = io.aethervpn.android.core.ScanMode.THOROUGH,
                    psiphonMode = PsiphonMode.PSIPHON_TO_WARP
                )
            )
        )
    }

    @Test
    fun testPsiphonOnlySkipsAllWarpAndMasqueFlags() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val cfg = VpnConfig(
            protocol = io.aethervpn.android.core.AetherProtocol.MASQUE_H3,
            psiphonMode = PsiphonMode.PSIPHON_ONLY,
            psiphonRegion = "DE",
            upstreamProxy = "socks5://127.0.0.1:10808",
            echEnabled = true,
            tlsFragmentation = true,
            masqueSni = "example.com"
        )
        val args = mgr.buildCoreArguments(cfg, psiphonBinaryPath = "/data/app/libpsiphon.so")
        assertTrue(args.contains("--psiphon-only"))
        assertTrue(args.contains("--perf"))
        assertEquals("low", args[args.indexOf("--perf") + 1])
        assertTrue(args.contains("--upstream"))
        assertTrue(args.contains("--psiphon-region"))
        assertTrue(args.contains("--psiphon-bin"))
        assertFalse(args.contains("--psiphon-protocol"))
        assertFalse(args.contains("--protocol"))
        assertFalse(args.contains("--scan"))
        assertFalse(args.contains("--h3"))
        assertFalse(args.contains("--h2"))
        assertFalse(args.contains("--ech"))
        assertFalse(args.contains("--fragment"))
        assertFalse(args.contains("--masque-sni"))
    }

    @Test
    fun testPsiphonProtocolSelectorCliMappingAndPerfPreserved() {
        val mgr = AetherEngineManager(File(System.getProperty("java.io.tmpdir") ?: "."))
        val cfgOnly = VpnConfig(
            psiphonMode = PsiphonMode.PSIPHON_ONLY,
            psiphonProtocol = PsiphonProtocol.FRONTED_MEEK_CDN_OSSH,
            perfProfile = PerfProfile.LOW
        )
        val argsOnly = mgr.buildCoreArguments(cfgOnly)
        assertTrue(argsOnly.contains("--perf"))
        assertEquals("low", argsOnly[argsOnly.indexOf("--perf") + 1])
        assertTrue(argsOnly.contains("--psiphon-protocol"))
        assertEquals("FRONTED-MEEK-CDN-OSSH", argsOnly[argsOnly.indexOf("--psiphon-protocol") + 1])

        val cfgChain = VpnConfig(
            psiphonMode = PsiphonMode.WARP_TO_PSIPHON,
            psiphonProtocol = PsiphonProtocol.OSSH
        )
        val argsChain = mgr.buildCoreArguments(cfgChain)
        assertTrue(argsChain.contains("--psiphon-protocol"))
        assertEquals("OSSH", argsChain[argsChain.indexOf("--psiphon-protocol") + 1])
    }

    @Test
    fun testUpstreamProxyFallbackPolicyForAllUdpWarpProtocols() {
        val udpProtocols = listOf(
            AetherProtocol.MASQUE_H3,
            AetherProtocol.WIREGUARD,
            AetherProtocol.GOOL,
            AetherProtocol.GOOL_CLASSIC,
            AetherProtocol.MIM
        )
        for (proto in udpProtocols) {
            val cfgWithProxy = VpnConfig(
                protocol = proto,
                autoFallbackToH2 = false,
                upstreamProxy = "socks5://127.0.0.1:10808"
            )
            assertTrue(
                "UDP protocol $proto with upstream proxy must fall back to MASQUE_H2",
                AetherVpnService.shouldFallbackToMasqueH2(cfgWithProxy, proto, userRequestedStop = false)
            )
        }

        val cfgH2 = VpnConfig(
            protocol = AetherProtocol.MASQUE_H2,
            upstreamProxy = "socks5://127.0.0.1:10808"
        )
        assertFalse(
            AetherVpnService.shouldFallbackToMasqueH2(cfgH2, AetherProtocol.MASQUE_H2, userRequestedStop = false)
        )

        val cfgPsiphonOnly = VpnConfig(
            protocol = AetherProtocol.WIREGUARD,
            psiphonMode = PsiphonMode.PSIPHON_ONLY,
            upstreamProxy = "socks5://127.0.0.1:10808"
        )
        assertFalse(
            AetherVpnService.shouldFallbackToMasqueH2(cfgPsiphonOnly, AetherProtocol.WIREGUARD, userRequestedStop = false)
        )
    }
}
