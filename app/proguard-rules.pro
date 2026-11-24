# Preserve JNI classes and native methods called from Rust (libaether_jni.so) and C (libhev-socks5-tunnel.so)
-keep class io.aethervpn.android.core.AetherNative { *; }
-keep class io.aethervpn.android.core.SocketProtectorCallback { *; }
-keep class io.aethervpn.android.core.AetherLogCallback { *; }
-keep class hev.htproxy.TProxyService { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}
