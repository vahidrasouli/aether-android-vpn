# CluvexStudio/Aether v2.4.0 — Android Feature Compatibility Matrix

Every row below is verified directly against `native/aether/aether/src/` (`cli.rs`, `routing.rs`, `sniff.rs`, `noize.rs`, `prober.rs`, `upstream.rs`, `lastconn.rs`, `api.rs`, `Cargo.toml`).

| Feature | Upstream Source Verification | Android App Status | Battery / Resource Impact |
|---|---|---|---|
| **MASQUE HTTP/3 (QUIC)** | `--protocol masque --h3` (`modes/masque.rs`, `quic.rs`) | **Supported (Default)** | Low; single UDP socket, fast 0/1-RTT resume |
| **MASQUE HTTP/2 (TCP)** | `--protocol masque --h2` (`masque_h2.rs`) | **Supported + 1-shot Auto Fallback** | Low; reliable when UDP/QUIC is throttled/blocked |
| **WireGuard (WARP)** | `--protocol wg` (`modes/wireguard.rs`, `boringtun`) | **Supported** (Connection tab) | Low; minimal framing overhead |
| **GOOL (WG over MASQUE)** | `--protocol gool` (`modes/gool.rs`) | **Supported** (Advanced) | Medium-High (double encapsulation & crypto) |
| **GOOL Classic (WG over WG)** | `--gool-classic` (`modes/gool.rs`) | **Supported** (Advanced) | Medium-High (nested WireGuard crypto) |
| **MIM (MASQUE over MASQUE)** | `--protocol mim` (`modes/mim.rs`) | **Supported** (Advanced) | High (nested TLS/QUIC encapsulation) |
| **Quick Reconnect & Gateway Reuse** | `--quick-reconnect` (`lastconn.rs`) | **Supported (Default ON)** | **Saves battery** by avoiding scans |
| **Endpoint Scanning Modes** | `--scan turbo\|balanced\|thorough\|verified\|ironclad` (`prober.rs`) | **Supported** (Default `turbo`) | Low (`turbo`) to High (`ironclad` probe burst) |
| **Manual Gateway Pinning** | `--peer`, `--wg-peer`, `--h2-peer` (`cli.rs`) | **Supported** (Connection tab) | **Lowest** (skips discovery completely) |
| **IPv4 / IPv6 Dual-Stack** | `--ip both\|v4\|v6` + `smoltcp` (`netstack.rs`) | **Supported (Default `both`)** | Low; `::/0` routed/blackholed to prevent IPv6 leaks |
| **Custom DNS** | `--dns` + `VpnService.Builder.addDnsServer()` | **Supported** (Default `1.1.1.1`/`1.0.0.1`) | Negligible |
| **Domain / IP / CIDR / Port / SNI Routing** | `--route-direct`, `--route-block` (`routing.rs`, `sniff.rs` TLS SNI peek) | **Supported** (Network tab) | Low (single-pass matcher on connection open) |
| **Traffic Obfuscation (Noize)** | `--noize off\|light\|balanced\|gfw` (`noize.rs`) | **Supported (Default `off`)** | Medium-High when enabled (junk/padding packets) |
| **TLS Fragmentation (H2)** | `--fragment`, `--fragment-size`, `--fragment-delay` (`fragment.rs`) | **Supported (Default OFF)** | Low-Medium (only affects H2 TLS handshake) |
| **MASQUE SNI & ECH & TLS Verify** | `--masque-sni`, `--ech`, `--tls-verify`, `--no-quic-v2` (`tls.rs`) | **Supported (Default OFF)** | Negligible (handshake-time only; ECH adds 1 DNS lookup) |
| **WireGuard Keepalive** | `--keepalive <secs>` (`cli.rs`) | **Supported (Default `0` = transport default)** | **High if set too low** (periodic radio wakeups) |
| **Upstream Proxy** | `--upstream socks5://\|http://` (`upstream.rs`) | **Supported** (Network tab) | Low-Medium (extra local/remote hop) |
| **WARP & Zero Trust Identity** | `--config`, `--team`, `IdentitySummary` (`account.rs`, `zerotrust.rs`) | **Supported** (Identity tab, non-secret summary + Reset) | Negligible |
| **Per-App Split Tunneling** | Android `VpnService.Builder` (`addAllowed`/`addDisallowed`) | **Supported** (`AppSplitTunnelActivity`) | Zero Rust overhead (kernel UID policy) |
| **Optional Tor (`arti-client`)** | Optional Cargo feature `tor` (`default = []` in `Cargo.toml`) | **Excluded by default** (opt-in build flag) | **Very High** (+8–15 MiB APK, +25–40 MiB RAM, continuous circuits) |
