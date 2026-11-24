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

    fun plan(
        mode: SplitTunnelMode,
        selectedPackages: Set<String>,
        selfPackageName: String
    ): PerAppRoutingPlan {
        val cleaned = selectedPackages
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != selfPackageName }
            .distinct()
            .sorted()
            .toList()

        return when (mode) {
            SplitTunnelMode.ALL_APPS -> PerAppRoutingPlan(
                disallowedPackages = listOf(selfPackageName)
            )

            SplitTunnelMode.BYPASS_SELECTED -> PerAppRoutingPlan(
                disallowedPackages = (listOf(selfPackageName) + cleaned).distinct()
            )

            SplitTunnelMode.ONLY_SELECTED -> {
                if (cleaned.isEmpty()) {
                    // If user chose ONLY_SELECTED with 0 apps, fall back to excluding only self
                    // rather than creating an empty allowlist that blocks/loops traffic.
                    PerAppRoutingPlan(disallowedPackages = listOf(selfPackageName))
                } else {
                    // Do NOT include selfPackageName in allowedPackages and NEVER add it to
                    // disallowedPackages: Android excludes omitted apps automatically when allowlist is active.
                    PerAppRoutingPlan(allowedPackages = cleaned)
                }
            }
        }
    }
}
