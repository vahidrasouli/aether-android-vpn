# Aether Android Native VPN (Low-Resource Client)

A lightweight, native full-device Android VPN client powered by the original Rust-based [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) engine (v2.4.0) and [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

## Architecture

```
Android Applications
        │
Android VpnService (Dual-Stack IPv4/IPv6 TUN, MTU 1280, Per-App Routing)
        │
HEV SOCKS5 Tunnel (libhev-socks5-tunnel.so via JNI, bounded buffers, UDP-in-UDP)
        │
Local Loopback SOCKS5 (127.0.0.1:1819 — never exposed to LAN)
        │
Aether Rust Engine (libaether_jni.so via JNI, 2-worker Tokio RT, VpnService.protect(fd))
        │
Cloudflare WARP (MASQUE HTTP/3 default, HTTP/2 fallback)
        │
Internet
```

## Quick Build (ARM64)

See `docs/BUILD.md` for full prerequisites, local commands, and GitHub Actions CI instructions:

```bash
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/26.3.11579264
bash scripts/build-natives.sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

## Documentation

- `docs/ARCHITECTURE.md` — Phase 1 repository audit, Phase 2 protocol & routing validation, and low-resource engineering design.
- `docs/BUILD.md` — Exact local and CI build instructions for ARM64 native libraries, Debug APK, and Release APK.
- `docs/VERIFICATION_AND_LIMITATIONS.md` — Verified static checks, hardware test matrix, benchmarking procedure (`scripts/benchmark-device.sh`), and known environment limitations.
- `LICENSE`, `MODIFICATIONS.md`, `THIRD_PARTY_NOTICES.md`, `TRADEMARK_NOTICE.md` — AGPL-3.0 and trademark compliance.
