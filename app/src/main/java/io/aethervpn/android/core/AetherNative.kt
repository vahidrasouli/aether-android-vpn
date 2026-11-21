package io.aethervpn.android.core

import android.os.Build
import android.os.Process

/**
 * Callback interface invoked from Rust (`aether::egress::apply`) on Android to protect
 * outbound TCP/UDP sockets via `VpnService.protect(fd)`.
 */
fun interface SocketProtectorCallback {
    fun protectSocket(fd: Int): Boolean
}

/**
 * JNI bridge to `libaether_jni.so` (native/aether-jni).
 * Every method maps 1:1 to `Java_io_aethervpn_android_core_AetherNative_*` in Rust.
 */
object AetherNative {
    @Volatile
    private var loaded: Boolean = false

    @Volatile
    var loadError: Throwable? = null
        private set

    @Volatile
    var diagnosticInfo: String? = null
        private set

    fun getRuntimeDiagnostics(): String {
        val sdk = Build.VERSION.SDK_INT
        val is64 = runCatching { Process.is64Bit() }.getOrDefault(false)
        val abis = Build.SUPPORTED_ABIS?.joinToString(", ") ?: "none"
        val arch = System.getProperty("os.arch") ?: "unknown"
        return "SDK=$sdk, 64bit=$is64, arch=$arch, abis=[$abis]"
    }

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return synchronized(this) {
            if (!loaded) {
                try {
                    // Step 1: Explicitly load NDK C++ runtime dependency
                    System.loadLibrary("c++_shared")

                    // Step 2: Load Aether JNI native bridge
                    System.loadLibrary("aether_jni")

                    // Step 3: Initialize native logging in separate call
                    nativeInitLogging()

                    loaded = true
                    loadError = null
                    diagnosticInfo = null
                } catch (t: Throwable) {
                    loaded = false
                    loadError = t
                    val diag = getRuntimeDiagnostics()
                    diagnosticInfo = "Failed loading native libraries ($diag): ${t.javaClass.simpleName}: ${t.message}"
                    DiagnosticsLog.record("Native", diagnosticInfo ?: t.message ?: "Unknown native load error")
                }
            }
            loaded
        }
    }

    @JvmStatic
    external fun nativeInitLogging()

    @JvmStatic
    external fun nativeRegisterProtector(protector: SocketProtectorCallback?): Boolean

    @JvmStatic
    external fun nativeVersion(): String

    @JvmStatic
    external fun nativeStartCore(argsJson: String): String

    @JvmStatic
    external fun nativePollJob(jobId: Long): String

    @JvmStatic
    external fun nativeCancelJob(jobId: Long): String

    @JvmStatic
    external fun nativeFreeJob(jobId: Long): String
}
