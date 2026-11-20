# Modifications Log (AGPL-3.0 Section 5(a))

This project incorporates the original Rust-based Aether engine (`CluvexStudio/Aether` v2.4.0, commit `ef06971101ba3d4b30f0bb317a2bba81c9b55ca2`) licensed under the GNU Affero General Public License v3.0 (`AGPL-3.0-only`).

## Summary of Modifications to Upstream `CluvexStudio/Aether` (2026-11-20)

All original Aether networking protocols (MASQUE HTTP/3, MASQUE HTTP/2, WireGuard, `smoltcp` dual-stack netstack, RFC 1928 SOCKS5 TCP/UDP relay, and WARP provisioning) are preserved intact without rewriting the engine. Only two surgical, Android-scoped additions were made:

1. **`native/aether/aether/src/egress.rs`**:
   - Added an optional `SocketProtector` C callback (`set_socket_protector`) stored in an `AtomicPtr`.
   - Under `#[cfg(target_os = "android")]`, `egress::apply()` invokes the registered protector callback with `socket.as_raw_fd()` so Android's `VpnService.protect(fd)` shields every outbound Aether TCP/UDP socket from VPN routing loops (including Android Always-On Lockdown mode).
2. **`native/aether/aether/src/ffi.rs`**:
   - Under `#[cfg(target_os = "android")]`, bounded the Tokio multi-threaded runtime in `runtime()` to `worker_threads(2)`, `max_blocking_threads(4)`, and `thread_stack_size(512 * 1024)` to reduce RAM usage and context switching on mobile devices.

## Additions Outside Upstream Aether

- `native/aether-jni/`: Thin Rust `cdylib` JNI bridge wrapping `aether::ffi` and registering the Android `VpnService.protect(fd)` callback.
- `app/`: Native Android Kotlin `VpnService` client, `hev.htproxy.TProxyService` JNI binding, low-memory HEV YAML manager, end-to-end WARP validator, and lightweight XML ViewBinding UI.
- `scripts/` & `.github/workflows/`: Reproducible ARM64 native build, device benchmark, and CI workflows.
