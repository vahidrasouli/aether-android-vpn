# Safe R8 shrinking: keep all class, method, and field names intact for JSON serialization,
# reflection, and readable stack traces.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Preserve JNI classes and native methods called from Rust (libaether_jni.so) and C (libhev-socks5-tunnel.so)
-keep class io.aethervpn.android.core.AetherNative { *; }
-keep class io.aethervpn.android.core.SocketProtectorCallback { *; }
-keep class io.aethervpn.android.core.AetherLogCallback { *; }
-keep class hev.htproxy.TProxyService { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve Android entry points (VpnService, Services, Receivers, Application)
-keep class * extends android.net.VpnService { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Application { *; }

# Preserve custom XML views inflated from layouts
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Soar data models and enums
-keep class io.aethervpn.android.core.SoarPhase { *; }
-keep class io.aethervpn.android.core.SoarRuntimeState { *; }
-keep class io.aethervpn.android.core.SoarTransport { *; }
-keep class io.aethervpn.android.core.SoarConnectionMode { *; }
-keep class io.aethervpn.android.core.SoarSettings { *; }
-keep class io.aethervpn.android.core.SoarProfile { *; }
-keep class io.aethervpn.android.core.SoarProfileStoreState { *; }
-keep class io.aethervpn.android.core.ResolverPack { *; }
-keep class io.aethervpn.android.core.SoarResolverPackStoreState { *; }
-keep class io.aethervpn.android.core.ScannerStatus { *; }
-keep class io.aethervpn.android.core.ResolverScanState { *; }

# Sing-box data models and enums
-keep class io.aethervpn.android.core.ParsedSingBoxProfile { *; }
-keep class io.aethervpn.android.core.BulkSingBoxParseResult { *; }
-keep class io.aethervpn.android.core.SingBoxTuningPreset { *; }
-keep class io.aethervpn.android.core.SingBoxBlockQuicMode { *; }

# Aether, VpnConfig, VpnState, Chain, Updater, and UI models/enums
-keep class io.aethervpn.android.core.VpnConfig { *; }
-keep class io.aethervpn.android.core.VpnConfig$* { *; }
-keep class io.aethervpn.android.core.ProfileLevel { *; }
-keep class io.aethervpn.android.core.AetherProtocol { *; }
-keep class io.aethervpn.android.core.ScanMode { *; }
-keep class io.aethervpn.android.core.IpFamily { *; }
-keep class io.aethervpn.android.core.PerfProfile { *; }
-keep class io.aethervpn.android.core.HevMode { *; }
-keep class io.aethervpn.android.core.PsiphonMode { *; }
-keep class io.aethervpn.android.core.ChainMode { *; }
-keep class io.aethervpn.android.core.PsiphonProtocol { *; }
-keep class io.aethervpn.android.core.NoizeProfile { *; }
-keep class io.aethervpn.android.core.SplitTunnelMode { *; }
-keep class io.aethervpn.android.core.AppThemeMode { *; }
-keep class io.aethervpn.android.core.EngineLabPreset { *; }
-keep class io.aethervpn.android.core.TunnelPhase { *; }
-keep class io.aethervpn.android.core.TrafficStats { *; }
-keep class io.aethervpn.android.core.TunnelSnapshot { *; }
-keep class io.aethervpn.android.core.VpnUiState { *; }
-keep class io.aethervpn.android.core.VpnUiAction { *; }
-keep class io.aethervpn.android.core.ChainExecutionPlan { *; }
-keep class io.aethervpn.android.core.CidrBlock { *; }
-keep class io.aethervpn.android.core.IdentityInfo { *; }
-keep class io.aethervpn.android.core.PerAppRoutingPlan { *; }
-keep class io.aethervpn.android.core.ValidationResult { *; }
-keep class io.aethervpn.android.core.UpstreamProxyProtocol { *; }
-keep class io.aethervpn.android.core.UpstreamProxyPreset { *; }
-keep class io.aethervpn.android.core.StructuredUpstreamProxy { *; }
-keep class io.aethervpn.android.core.CloudflareTraceInfo { *; }
-keep class io.aethervpn.android.core.ProxyTestResult { *; }
-keep class io.aethervpn.android.core.ProxyTestResult$* { *; }
-keep class io.aethervpn.android.core.SemVer { *; }
-keep class io.aethervpn.android.core.DebugBuildVersion { *; }
-keep class io.aethervpn.android.core.DeltaPatchEntry { *; }
-keep class io.aethervpn.android.core.DeltaManifest { *; }
-keep class io.aethervpn.android.core.ReleaseInfo { *; }
-keep class io.aethervpn.android.core.DownloadProgress { *; }
-keep class io.aethervpn.android.core.UpdateCheckResult { *; }
-keep class io.aethervpn.android.core.UpdateCheckResult$* { *; }
-keep class io.aethervpn.android.core.UpdateInstallPhase { *; }
-keep class io.aethervpn.android.core.UpdateInstallSnapshot { *; }
-keep class io.aethervpn.android.core.ChunkState { *; }
-keep class io.aethervpn.android.ui.InstalledAppEntry { *; }
-keep class io.aethervpn.android.ui.LogSeverity { *; }

# Serialization & bsdiff/updater classes
-keep class io.aethervpn.android.core.BsPatch { *; }
-keep class io.aethervpn.android.core.UpdateManager { *; }
-keep class io.aethervpn.android.core.SingBoxConfigParser { *; }
-keep class io.aethervpn.android.core.SoarProfileParser { *; }
-keep class io.aethervpn.android.core.SoarProfileStore { *; }
-keep class io.aethervpn.android.core.SoarResolverPackStore { *; }

# Apache Commons Compress optional compression codecs (only BZip2 is used for bsdiff)
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.lang.model.**
