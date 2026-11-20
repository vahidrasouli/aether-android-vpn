# Third-Party Notices & Attribution

This Android VPN client is released under the **GNU Affero General Public License v3.0 (AGPL-3.0)** (see `LICENSE`) in compliance with the upstream Aether engine license.

## Upstream & Third-Party Components

1. **Aether (`CluvexStudio/Aether` v2.4.0)**
   - Source: https://github.com/CluvexStudio/Aether
   - License: GNU Affero General Public License v3.0 (`AGPL-3.0-only`)
   - Usage: Core Rust WARP/MASQUE networking engine (`native/aether/`), preserved with minimal Android `egress::apply` socket-protection and Tokio runtime thread-bound hooks (documented in `MODIFICATIONS.md`).

2. **HevSocks5Tunnel (`heiher/hev-socks5-tunnel`)**
   - Source: https://github.com/heiher/hev-socks5-tunnel
   - License: MIT License
   - Usage: Lightweight C TUN-to-SOCKS5 forwarding library (`libhev-socks5-tunnel.so`) bridged via `hev.htproxy.TProxyService`.

3. **Cloudflare `quiche` (vendored inside `native/aether/quiche`)**
   - Source: https://github.com/cloudflare/quiche
   - License: BSD-2-Clause License
   - Usage: QUIC / HTTP/3 transport for MASQUE.

4. **Architectural Reference (`QW-AI-Code/Aether`)**
   - Source: https://github.com/QW-AI-Code/Aether
   - License: AGPL-3.0
   - Usage: Consulted as a reference for Android `VpnService` + `hev-socks5-tunnel` + `CluvexStudio/Aether` integration patterns. No Tor, Psiphon, Xray, or Jetpack Compose components were imported.

5. **AndroidX & Kotlin Coroutines**
   - License: Apache License 2.0
   - Usage: `androidx.core:core-ktx`, `androidx.appcompat:appcompat`, `com.google.android.material:material`, `kotlinx-coroutines-android`.
