package app.lanvault.core.transport

/** Never expand a discovery probe beyond the phone's own /24, or its smaller real subnet. */
fun lanCandidates(localAddress: String, prefix: Int): List<String> {
    failUnless(prefix in 8..30, "DIRECT_LAN_REQUIRED")
    val local =
        privateAddress(localAddress).address.fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255) }
    val effectivePrefix = prefix.coerceAtLeast(24)
    val mask = (0xffffffffL shl (32 - effectivePrefix)) and 0xffffffffL
    val network = local and mask
    val broadcast = network or (mask xor 0xffffffffL)
    return ((network + 1) until broadcast)
        .filter { it != local }
        .map { n ->
            listOf(24, 16, 8, 0).joinToString(".") { shift -> ((n shr shift) and 255).toString() }
        }
}
