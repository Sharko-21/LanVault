package app.lanvault.core.transport

import org.junit.Assert.*
import org.junit.Test

class LanCandidatesTest {
    @Test
    fun probesOnlyOwnPrivate24AndExcludesSelfNetworkAndBroadcast() {
        val result = lanCandidates("192.168.1.64", 24)
        assertEquals(253, result.size)
        assertTrue(result.all { it.startsWith("192.168.1.") })
        assertFalse(result.contains("192.168.1.64"))
        assertFalse(result.contains("192.168.1.0"))
        assertFalse(result.contains("192.168.1.255"))
    }

    @Test
    fun broadPrivatePrefixNeverExpandsTheProbeAndSmallSubnetStaysSmall() {
        assertEquals(253, lanCandidates("10.5.6.7", 8).size)
        assertTrue(lanCandidates("10.5.6.7", 8).all { it.startsWith("10.5.6.") })
        assertEquals(listOf("192.168.1.66"), lanCandidates("192.168.1.65", 30))
    }

    @Test
    fun rejectsPublicAndUnsupportedNetworks() {
        listOf("8.8.8.8" to 24, "100.64.0.1" to 24, "192.168.1.65" to 31).forEach {
            (address, prefix) ->
            assertThrows(ClientFailure::class.java) { lanCandidates(address, prefix) }
        }
    }
}
