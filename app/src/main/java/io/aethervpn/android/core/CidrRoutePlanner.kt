package io.aethervpn.android.core

import java.io.InputStream
import java.net.InetAddress

/**
 * Pure-Kotlin IPv4 CIDR parser and route inverter for kernel-level VPN bypass.
 *
 * - On Android 13+ (API 33+), `VpnService.Builder.excludeRoute(IpPrefix)` can exclude CIDRs directly.
 * - On Android 8-12 (API 26-32), `invertCidrs()` subtracts excluded CIDRs from `0.0.0.0/0`
 *   to produce the exact complementary set of IPv4 routes with zero extra RAM/CPU at runtime.
 */
data class CidrBlock(
    val network: Long,
    val prefix: Int
) {
    init {
        require(prefix in 0..32) { "Invalid IPv4 prefix: $prefix" }
        require((network and 0xFFFFFFFFL) == network) { "Invalid IPv4 network: $network" }
    }

    val start: Long
        get() = network and mask(prefix)

    val end: Long
        get() = start or (mask(prefix).inv() and 0xFFFFFFFFL)

    val bytes: ByteArray
        get() = byteArrayOf(
            ((start ushr 24) and 0xFF).toByte(),
            ((start ushr 16) and 0xFF).toByte(),
            ((start ushr 8) and 0xFF).toByte(),
            (start and 0xFF).toByte()
        )

    val addressString: String
        get() = "${(start ushr 24) and 0xFF}.${(start ushr 16) and 0xFF}.${(start ushr 8) and 0xFF}.${start and 0xFF}"

    fun toInetAddress(): InetAddress = InetAddress.getByAddress(bytes)

    override fun toString(): String = "$addressString/$prefix"

    companion object {
        fun mask(prefix: Int): Long {
            if (prefix == 0) return 0L
            return (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        }

        fun parse(raw: String): CidrBlock? {
            val trimmed = raw.substringBefore('#').trim()
            if (trimmed.isEmpty()) return null
            val slashIdx = trimmed.indexOf('/')
            if (slashIdx <= 0) return null
            val ipPart = trimmed.substring(0, slashIdx).trim()
            val prefix = trimmed.substring(slashIdx + 1).trim().toIntOrNull() ?: return null
            if (prefix !in 0..32) return null

            val octets = ipPart.split('.')
            if (octets.size != 4) return null
            var addr = 0L
            for (o in octets) {
                val v = o.toIntOrNull() ?: return null
                if (v !in 0..255) return null
                addr = (addr shl 8) or v.toLong()
            }
            val masked = addr and mask(prefix)
            return CidrBlock(masked, prefix)
        }
    }
}

object CidrRoutePlanner {

    fun parseCidrs(input: InputStream): List<CidrBlock> {
        return input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            parseCidrs(lines)
        }
    }

    fun parseCidrs(lines: Sequence<String>): List<CidrBlock> {
        return lines.mapNotNull { CidrBlock.parse(it) }.toList()
    }

    /**
     * Subtracts [excluded] IPv4 CIDRs from `0.0.0.0/0` (`0x00000000..0xFFFFFFFF`)
     * and returns the minimal aligned list of complementary `CidrBlock`s.
     */
    fun invertCidrs(excluded: List<CidrBlock>): List<CidrBlock> {
        if (excluded.isEmpty()) return listOf(CidrBlock(0L, 0))

        val sorted = excluded
            .map { it.start to it.end }
            .sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })

        // Merge overlapping or adjacent intervals
        val merged = mutableListOf<Pair<Long, Long>>()
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= curEnd + 1L) {
                if (e > curEnd) curEnd = e
            } else {
                merged += curStart to curEnd
                curStart = s
                curEnd = e
            }
        }
        merged += curStart to curEnd

        // Compute complement intervals within [0, 0xFFFFFFFF]
        val result = mutableListOf<CidrBlock>()
        var cursor = 0L
        val maxIp = 0xFFFFFFFFL
        for ((exStart, exEnd) in merged) {
            if (cursor < exStart) {
                appendRangeAsCidrs(cursor, exStart - 1L, result)
            }
            if (exEnd >= maxIp) {
                cursor = maxIp + 1L
                break
            }
            cursor = exEnd + 1L
        }
        if (cursor <= maxIp) {
            appendRangeAsCidrs(cursor, maxIp, result)
        }
        return result
    }

    private fun appendRangeAsCidrs(startIp: Long, endIp: Long, out: MutableList<CidrBlock>) {
        var cur = startIp
        while (cur <= endIp) {
            val maxAlignBits = if (cur == 0L) 32 else java.lang.Long.numberOfTrailingZeros(cur).coerceAtMost(32)
            val remaining = endIp - cur + 1L
            val maxSizeBits = 63 - java.lang.Long.numberOfLeadingZeros(remaining)
            val blockBits = minOf(maxAlignBits, maxSizeBits)
            val prefix = 32 - blockBits
            out += CidrBlock(cur, prefix)
            cur += 1L shl blockBits
        }
    }
}
