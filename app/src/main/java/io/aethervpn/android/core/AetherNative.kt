package io.aethervpn.android.core

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

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return synchronized(this) {
            if (!loaded) {
                try {
                    System.loadLibrary("aether_jni")
                    nativeInitLogging()
                    loaded = true
                } catch (_: UnsatisfiedLinkError) {
                    loaded = false
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
