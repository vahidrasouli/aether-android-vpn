# Aether Android Native VPN — Architecture & Validation Report

## 1. Phase 1 Repository Audit (`CluvexStudio/Aether` v2.4.0)

Inspection of the attached upstream repository (`CluvexStudio/Aether`, commit `ef06971101ba3d4b30f0bb317a2bba81c9b55ca2`) confirmed:

- **Existing Android integration**: None in upstream `CluvexStudio/Aether` (only `quiche/tools/android/build_android_ndk19.sh` from vendored Cloudflare `quiche`).
- **Rust crate entry points**: `aether/Cargo.toml` already declares `crate-type = ["lib", "staticlib", "cdylib"]` with `default = []` (Arti/Tor is an optional feature `tor`, excluded by default).
- **Existing C FFI**: `aether/src/ffi.rs` already exports `aether_version`, `aether_core_start`, `aether_job_poll`, `aether_job_cancel`, `aether_job_free`, and `aether_string_free`.
- **SOCKS5 interface**: `aether/src/socks.rs` binds `127.0.0.1:1819` by default (`--bind`) and implements both RFC 1928 `CMD_CONNECT` (`0x01`, TCP) and `CMD_UDP_ASSOCIATE` (`0x03`, UDP via `StackHandle::open_udp()`).
- **Dual-stack netstack**: `aether/src/netstack.rs` uses `smoltcp` with both `proto-ipv4` and `proto-ipv6` enabled.
- **Centralized outbound sockets**: `aether/src/egress.rs` routes outbound TCP/UDP sockets (`quic.rs`, `masque_h2.rs`, `https.rs`, `prober.rs`, `dns.rs`) through `egress::apply(SockRef)`.
- **MASQUE transports**: `aether/src/modes/masque.rs` supports MASQUE over HTTP/3 QUIC (`--h3`, default) and HTTP/2 TCP (`--h2`), plus `--quick-reconnect` to reuse the assigned or cached gateway before scanning.
- **Reference comparison**: [QW-AI-Code/Aether](https://github.com/QW-AI-Code/Aether) validates the `VpnService -> TUN -> hev-socks5-tunnel -> 127.0.0.1:1819 -> Aether` data plane, but bundles Tor, Psiphon, Xray, and Jetpack Compose. This project deliberately excludes all four to minimize APK size, RAM, and battery drain.
- **Licensing**: Upstream Aether is `AGPL-3.0-only` with `TRADEMARK.md` restricting unauthorized branding; [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) is MIT-licensed.

## 2. Phase 2 Architecture Validation

| Concern | Validation Finding & Design Enforcement |
|---|---|
| **TUN → HEV → SOCKS5** | Valid. `AetherVpnService` creates a non-blocking dual-stack TUN (`198.18.0.1/30`, `fd00:ae7::1/126`, MTU `1280`) and passes `tunInterface.fd` to `hev.htproxy.TProxyService.TProxyStartService(yamlPath, fd)`. |
| **TCP & UDP Support** | Valid. HEV converts TUN TCP/UDP via lwIP into RFC 1928 `CONNECT` and `UDP ASSOCIATE` (`udp: 'udp'`), matching `aether/src/socks.rs` (`CMD_CONNECT` & `CMD_UDP_ASSOCIATE`). |
| **DNS & IPv6 Leak Prevention** | Valid. Routes `0.0.0.0/0` and `::/0` into TUN with DNS `1.1.1.1`, `1.0.0.1`, and `2606:4700:4700::1111`. When IPv6 is toggled off, `::/0` still routes to TUN to blackhole IPv6 rather than leaking outside the VPN. |
| **Routing Loop Prevention** | Dual-layer defense: (1) `VpnService.Builder.addDisallowedApplication(packageName)` (or omission in allowlist mode) bypasses TUN at the kernel UID policy layer; (2) `aether::egress::apply()` invokes Android `VpnService.protect(fd)` via `libaether_jni.so` so even Android Lockdown/Always-On mode cannot loop. |
| **Loopback-Only Exposure** | Enforced in `VpnConfig.LOOPBACK_HOST = "127.0.0.1"`; local SOCKS5 never binds `0.0.0.0` or LAN interfaces. |
| **End-to-End Verification** | `TunnelValidator.verifyThroughSocks()` queries `https://www.cloudflare.com/cdn-cgi/trace` through `127.0.0.1:1819` and verifies HTTP 200, non-empty `ip=`, and `warp=on|plus`. On failure, `AetherVpnService.failClosed()` tears down HEV, TUN, and Aether immediately. |
| **Per-App Routing** | Implemented strictly in `AetherVpnService.applyPerAppRouting()` via `VpnService.Builder` (`addDisallowedApplication` / `addAllowedApplication`), never inside Rust. |

## 3. Low-Resource Engineering Design

- **CPU**: Event-driven `StateFlow` and `ConnectivityManager.NetworkCallback`; zero continuous polling or busy-wait loops; `--quick-reconnect` avoids continuous endpoint scans; Android Tokio runtime capped to 2 worker threads and 4 blocking threads (`native/aether/aether/src/ffi.rs`).
- **RAM**: Single managed `AetherEngineManager` job + single `HevTunnelManager` instance; bounded HEV YAML (`task-stack-size: 20480`, `tcp-buffer-size: 65536`, `max-session-count: 512`); default Cargo features (no Tor/Psiphon/Xray); native XML ViewBinding UI (no Jetpack Compose runtime).
- **Battery**: Zero wake locks; foreground notification uses `FOREGROUND_SERVICE_SPECIAL_USE`; exponential backoff reconnect (`2s -> 4s -> 8s -> 16s -> 30s`); UI stats loop runs only while `MainActivity` is visible AND connected (3s interval reading native atomic counters via `TProxyGetStats()`).
