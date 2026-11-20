# Verification Status, Modified Files & Known Issues (Pre-Build Stabilization)

## 1. Verification Matrix (Strictly Categorized)

### Verified by Source Inspection
- **Upstream CLI parity (`CluvexStudio/Aether` v2.4.0, commit `ef069711`)**: Every flag emitted by `AetherEngineManager.buildCoreArguments()` (`--protocol`, `--h3`, `--h2`, `--gool-classic`, `--bind`, `--config`, `--scan`, `--ip`, `--quick-reconnect`, `--no-quick-reconnect`, `--peer`, `--wg-peer`, `--h2-peer`, `--dns`, `--route-direct`, `--route-block`, `--noize`, `--fragment`, `--fragment-size`, `--fragment-delay`, `--masque-sni`, `--ech auto`, `--tls-verify`, `--no-quic-v2`, `--keepalive`, `--upstream`, `--team`, `--log-level`) was verified against `native/aether/aether/src/cli.rs`, `tls.rs`, `routing.rs`, `noize.rs`, `prober.rs`, `upstream.rs`, and `sniff.rs`.
- **JNI & HEV integration**: Verified 1:1 symbol parity between `AetherNative.kt` and `native/aether-jni/src/lib.rs`, `SocketProtectorCallback.protectSocket(I)Z` JNI signature, `(&*socket).as_raw_fd()` in `egress.rs`, and upstream `hev.htproxy.TProxyService` signatures (`TProxyStartService`, `TProxyStopService`, `TProxyIsRunning`, `TProxyGetStats`).
- **VPN lifecycle & leak prevention**: Verified dual-stack TUN (`198.18.0.1/30`, `fd00:ae7::1/126`, `0.0.0.0/0`, `::/0`, MTU `1280`), custom IPv4/IPv6 DNS parsing, per-app routing via `VpnService.Builder` (always excluding self), `VpnService.protect(fd)` callback, network-loss recovery (`networkLostDuringSession`), health-checked network transitions, and fail-closed teardown order (`hevManager.stop()` -> `closeTun()` -> `engineManager.stop()`).
- **Reproducible dependency pinning & CI**: Verified `scripts/fetch-hev.sh` pins `heiher/hev-socks5-tunnel` `2.20.0` with SHA-256 `bc2ce746a0beeb71030d3ffe299f5a9d48e2895902d3883c2cac1a551ee128e9`; `gradle/wrapper/gradle-wrapper.properties` pins Gradle `8.9`; `.github/workflows/android-build.yml` pins SDK 35, NDK `26.3.11579264`, `cargo-ndk` `3.5.4`, bounded workers (`2`), caching, and tag-gated draft GitHub Release (`v*`).
- **Licensing**: Verified verbatim AGPL-3.0 `LICENSE`, `MODIFICATIONS.md`, `THIRD_PARTY_NOTICES.md`, and `TRADEMARK_NOTICE.md`.

### Verified by Automated Tests
- **Executed in sandbox (`verify_and_package.py`)**: All 10 Android XML files parsed cleanly; all 7 Rust↔Kotlin JNI symbols and 4 HEV JNI methods matched; security/anti-bloat checks passed (`arm64-v8a` only, `default-features = false`, `allowBackup="false"`, `usesCleartextTraffic="false"`, zero Compose/Tor/Psiphon/Xray); `bash -n` passed on `scripts/fetch-hev.sh`, `scripts/build-natives.sh`, `scripts/benchmark-device.sh`, and `gradlew`.
- **Written, awaiting Gradle host execution**: JUnit tests in `app/src/test/java/io/aethervpn/android/VpnConfigTest.kt` and `HevConfigGeneratorTest.kt` (could not execute without Gradle/JDK compiler in this offline sandbox).

### Verified by Successful Compilation
- **None in this offline sandbox.** `rustc`, `cargo`, `javac`, `gradle`, Android SDK, and Android NDK are not installed in this VM and outbound internet is disabled. No native `.so` or `.apk` compilation has been executed yet.

### Requires Physical ARM64 Device Testing
- Full-device IPv4, IPv6, TCP, UDP, and DNS routing & leak verification (`tun0` active, `https://www.cloudflare.com/cdn-cgi/trace` returning `warp=on|plus`).
- Real `VpnService.protect(fd)` behavior under standard mode and Android Always-On Lockdown mode.
- Wi-Fi ↔ cellular switching, full network-loss recovery, and exponential backoff reconnection.
- Scenario A–D measurements (`scripts/benchmark-device.sh A|B|C|D`): RAM PSS, CPU %, wakeups, battery drain (`dumpsys batterystats`), throughput, latency, and final APK size.

### Not Implemented or Not Verified
- **Optional Tor (`arti-client`)**: Intentionally excluded by default (`default-features = false`) for APK/RAM/battery efficiency.
- **`gradle/wrapper/gradle-wrapper.jar` binary**: Not bundled offline; CI runs pinned Gradle `8.9` via `gradle/actions/setup-gradle@v4`, and local hosts can bootstrap with `gradle wrapper --gradle-version 8.9`.
- **Multi-language UI strings**: Only default English strings are bundled; no broken language picker is exposed.
- **Interactive Zero Trust email-code enrollment**: `--team` is supported; interactive email OTP / service-token flows are not yet wired in UI.

## 2. Modified Files (Stabilization Pass)

| File | Why Changed |
|---|---|
| `app/src/main/java/io/aethervpn/android/core/AetherEngineManager.kt` | Fixed `--ech on` defect → `--ech auto` (upstream `src/tls.rs:1285` rejects `"on"` as invalid base64). |
| `app/src/main/java/io/aethervpn/android/vpn/AetherVpnService.kt` | Fixed network-loss recovery bug (`networkLostDuringSession` so `onAvailable()` reconnects after `onLost()`) and added custom IPv6 DNS parsing in `establishTun()`. |
| `native/aether/aether/src/egress.rs` | Changed `socket.as_raw_fd()` to unambiguous `(&*socket).as_raw_fd()` for `socket2::SockRef`. |
| `app/src/test/java/io/aethervpn/android/VpnConfigTest.kt` | Added assertion verifying `--ech auto`. |
| `scripts/fetch-hev.sh` | Pinned `heiher/hev-socks5-tunnel` to release `2.20.0` with SHA-256 verification instead of unpinned `main`. |
| `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat` | Added standard Gradle Wrapper pinned to Gradle `8.9` (compatible with AGP `8.7.3` + JDK 17). |
| `.github/workflows/android-build.yml` | Added Rust/Gradle/HEV caching, pinned SDK/NDK/`cargo-ndk`, ARM64 ELF verification, SHA-256 checksums, and tag-gated (`v*`) draft GitHub Release job. |

## 3. Exact Next Steps (Antigravity / GitHub Actions Stage)

1. Push the unzipped `aether-android-vpn/` tree to GitHub (or open in Antigravity with JDK 17 + Android SDK 35 + NDK `26.3.11579264` + Rust `aarch64-linux-android`).
2. Optionally run `gradle wrapper --gradle-version 8.9` once to commit `gradle/wrapper/gradle-wrapper.jar` if local `./gradlew` without pre-installed Gradle is desired.
3. Run `.github/workflows/android-build.yml` (or locally `bash scripts/build-natives.sh && gradle --max-workers=2 :app:testDebugUnitTest :app:assembleDebug`) to verify Rust/C/Kotlin compilation and unit tests.
4. Install `aether-android-arm64-debug.apk` on a physical ARM64 Android device and run `bash scripts/benchmark-device.sh A|B|C|D` to collect real hardware validation & battery measurements before tagging `v1.0.0` for a GitHub Release.
