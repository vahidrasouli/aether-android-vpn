package io.aethervpn.android

import io.aethervpn.android.core.AetherEngineManager
import io.aethervpn.android.core.CidrBlock
import io.aethervpn.android.core.CidrRoutePlanner
import io.aethervpn.android.core.PerAppRoutingPlan
import io.aethervpn.android.core.SplitTunnelMode
import io.aethervpn.android.core.SplitTunnelPlanner
import io.aethervpn.android.core.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoutingAndSplitTunnelTest {

    @Test
    fun maxClientsIsRaisedTo512ToPreventStarvation() {
        assertEquals(512, AetherEngineManager.MAX_CLIENTS)
    }

    @Test
    fun splitTunnelOnlySelectedNeverCallsDisallowedAndExcludesSelf() {
        val plan = SplitTunnelPlanner.plan(
            mode = SplitTunnelMode.ONLY_SELECTED,
            selectedPackages = setOf("com.android.chrome", "io.aethervpn.android", "com.google.android.youtube"),
            selfPackageName = "io.aethervpn.android"
        )

        assertTrue("allowedPackages must not be empty", plan.allowedPackages.isNotEmpty())
        assertTrue("disallowedPackages must be empty when allowedPackages is used", plan.disallowedPackages.isEmpty())
        assertEquals(listOf("com.android.chrome", "com.google.android.youtube"), plan.allowedPackages)
        assertFalse(plan.allowedPackages.contains("io.aethervpn.android"))
    }

    @Test
    fun splitTunnelBypassSelectedAlwaysExcludesSelfAndSelected() {
        val plan = SplitTunnelPlanner.plan(
            mode = SplitTunnelMode.BYPASS_SELECTED,
            selectedPackages = setOf("com.android.chrome"),
            selfPackageName = "io.aethervpn.android"
        )

        assertTrue("allowedPackages must be empty", plan.allowedPackages.isEmpty())
        assertEquals(listOf("io.aethervpn.android", "com.android.chrome"), plan.disallowedPackages)
    }

    @Test
    fun splitTunnelOnlySelectedWithZeroAppsFallsBackSafelyToSelfBypass() {
        val plan = SplitTunnelPlanner.plan(
            mode = SplitTunnelMode.ONLY_SELECTED,
            selectedPackages = emptySet(),
            selfPackageName = "io.aethervpn.android"
        )

        assertTrue(plan.allowedPackages.isEmpty())
        assertEquals(listOf("io.aethervpn.android"), plan.disallowedPackages)
    }

    @Test(expected = IllegalArgumentException::class)
    fun perAppRoutingPlanRejectsBothAllowedAndDisallowed() {
        PerAppRoutingPlan(
            allowedPackages = listOf("com.android.chrome"),
            disallowedPackages = listOf("io.aethervpn.android")
        )
    }

    @Test
    fun splitTunnelModeAndBypassIranPersistThroughBackupJsonRoundtrip() {
        val original = VpnConfig(
            splitTunnelMode = SplitTunnelMode.ONLY_SELECTED,
            selectedPackages = setOf("com.android.chrome", "com.google.android.youtube"),
            bypassIran = true,
            exitLocation = "DE"
        )
        val json = original.toSafeBackupJson()
        val restored = VpnConfig.fromBackupJson(json).getOrThrow()

        assertEquals(SplitTunnelMode.ONLY_SELECTED, restored.splitTunnelMode)
        assertEquals(setOf("com.android.chrome", "com.google.android.youtube"), restored.selectedPackages)
        assertTrue(restored.bypassIran)
        assertEquals("DE", restored.exitLocation)

        // Verify legacy enum names still parse cleanly
        assertEquals(SplitTunnelMode.ONLY_SELECTED, VpnConfig.parseSplitTunnelMode("INCLUDE_ONLY_SELECTED"))
        assertEquals(SplitTunnelMode.BYPASS_SELECTED, VpnConfig.parseSplitTunnelMode("EXCLUDE_SELECTED"))
    }

    @Test
    fun cidrInversionProducesExactComplementOfExcludedRange() {
        val excluded = listOf(CidrBlock.parse("192.168.0.0/16")!!)
        val allowed = CidrRoutePlanner.invertCidrs(excluded)

        // Sum of excluded + allowed IPs must equal 2^32 (4294967296)
        val excludedCount = excluded.sumOf { it.end - it.start + 1L }
        val allowedCount = allowed.sumOf { it.end - it.start + 1L }
        assertEquals(1L shl 32, excludedCount + allowedCount)

        // 192.168.1.1 must not be covered by any allowed block
        val testIp = (192L shl 24) or (168L shl 16) or (1L shl 8) or 1L
        assertFalse(allowed.any { testIp in it.start..it.end })

        // 1.1.1.1 must be covered by an allowed block
        val cfIp = (1L shl 24) or (1L shl 16) or (1L shl 8) or 1L
        assertTrue(allowed.any { cfIp in it.start..it.end })
    }

    @Test
    fun bundledIranCidrsParseAndInvertWithoutOverlap() {
        val candidates = listOf(
            File("src/main/res/raw/iran_cidrs.txt"),
            File("app/src/main/res/raw/iran_cidrs.txt")
        )
        val cidrFile = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate iran_cidrs.txt")

        val iranCidrs = cidrFile.inputStream().use { CidrRoutePlanner.parseCidrs(it) }
        assertTrue("Expected at least 100 Iranian CIDRs", iranCidrs.size >= 100)

        val inverted = CidrRoutePlanner.invertCidrs(iranCidrs)
        assertTrue("Inverted routes should be non-empty", inverted.isNotEmpty())

        // Verify no inverted block overlaps any Iranian CIDR
        for (inv in inverted) {
            for (ir in iranCidrs) {
                val overlaps = inv.start <= ir.end && ir.start <= inv.end
                assertFalse("Overlap detected between $inv and $ir", overlaps)
            }
        }
    }
}
