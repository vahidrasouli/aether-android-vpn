package io.aethervpn.android.core

/**
 * Validated per-app routing plan for Android's `VpnService.Builder`.
 *
 * Enforces Android's strict invariant: `addAllowedApplication` and `addDisallowedApplication`
 * are mutually exclusive on the same `VpnService.Builder`. Calling both throws
 * `IllegalArgumentException` and causes split tunneling to fail silently.
 */
data class PerAppRoutingPlan(
    val allowedPackages: List<String> = emptyList(),
    val disallowedPackages: List<String> = emptyList()
) {
    init {
        require(allowedPackages.isEmpty() || disallowedPackages.isEmpty()) {
            "allowedPackages and disallowedPackages are mutually exclusive on Android VpnService.Builder"
        }
    }
}

object SplitTunnelPlanner {

    val LOCAL_PROXY_PACKAGES: Set<String> = linkedSetOf(
        "com.v2ray.ang",
        "com.v2ray.actinium",
        "io.nekohasekai.sfa",
        "org.hiddify.app",
        "moe.nb4a",
        "com.matsuridayo.matsuri",
        "com.github.shadowsocks",
        "com.github.kr328.clash",
        "com.metacubed.clash.meta"
    )

    fun isLoopbackUpstream(upstreamProxy: String): Boolean {
        val raw = upstreamProxy.trim()
        if (raw.isEmpty()) return false
        val withoutScheme = raw.substringAfter("://", raw)
        val authority = withoutScheme.substringAfterLast('@').substringBefore('/')
        val host = when {
            authority.startsWith("[") -> authority.substringAfter("[").substringBefore("]")
            authority.count { it == ':' } == 1 -> authority.substringBefore(':')
            else -> authority
        }.trim().lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    fun plan(
        mode: SplitTunnelMode,
        selectedPackages: Set<String>,
        selfPackageName: String,
        upstreamProxy: String = "",
        proxyBypassApps: Set<String> = emptySet()
    ): PerAppRoutingPlan {
        val excludeLocalProxies = isLoopbackUpstream(upstreamProxy)
        val autoExcluded = if (excludeLocalProxies) LOCAL_PROXY_PACKAGES else emptySet()
        val userProxyBypass = proxyBypassApps
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != selfPackageName }
            .toSet()
        val mandatoryBypass = autoExcluded + userProxyBypass

        val cleaned = selectedPackages
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != selfPackageName && it !in mandatoryBypass }
            .distinct()
            .sorted()
            .toList()

        val baseDisallowed = (listOf(selfPackageName) + mandatoryBypass).distinct()

        return when (mode) {
            SplitTunnelMode.ALL_APPS -> PerAppRoutingPlan(
                disallowedPackages = baseDisallowed
            )

            SplitTunnelMode.BYPASS_SELECTED -> PerAppRoutingPlan(
                disallowedPackages = (baseDisallowed + cleaned).distinct()
            )

            SplitTunnelMode.ONLY_SELECTED -> {
                if (cleaned.isEmpty()) {
                    // If user chose ONLY_SELECTED with 0 apps, fall back to excluding self (+ local proxies)
                    // rather than creating an empty allowlist that blocks/loops traffic.
                    PerAppRoutingPlan(disallowedPackages = baseDisallowed)
                } else {
                    // Do NOT include selfPackageName or local proxy apps in allowedPackages and NEVER
                    // add them to disallowedPackages: Android excludes omitted apps automatically when allowlist is active.
                    PerAppRoutingPlan(allowedPackages = cleaned)
                }
            }
        }
    }
}
