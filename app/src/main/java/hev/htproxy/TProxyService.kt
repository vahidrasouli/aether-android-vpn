package hev.htproxy

/**
 * Direct JNI binding matching upstream heiher/hev-socks5-tunnel (MIT License).
 * Native library: libhev-socks5-tunnel.so (built via scripts/build-natives.sh).
 *
 * Keeping the upstream package/class name `hev.htproxy.TProxyService` allows using either
 * the NDK-compiled shared library or upstream prebuilt AAR without per-package rebuilds.
 */
object TProxyService {
    @Volatile
    private var loaded: Boolean = false

    @Volatile
    var loadError: Throwable? = null
        private set

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return synchronized(this) {
            if (!loaded) {
                try {
                    System.loadLibrary("hev-socks5-tunnel")
                    loaded = true
                    loadError = null
                } catch (t: Throwable) {
                    loaded = false
                    loadError = t
                }
            }
            loaded
        }
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    /**
     * Returns [txPackets, txBytes, rxPackets, rxBytes] from native atomic counters
     * without allocating background polling threads.
     */
    @JvmStatic
    external fun TProxyGetStats(): LongArray?
}
