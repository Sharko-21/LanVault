package app.lanvault.core.security

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableSha256Test {
    @Test
    fun standardKnownVector() {
        val hasher = ResumableSha256()
        hasher.update("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hasher.hexDigest(),
        )
    }

    @Test
    fun resumesFromUnalignedCheckpoint() {
        val data = ByteArray(1024 * 1024 + 31) { (it % 251).toByte() }
        val first = ResumableSha256()
        first.update(data, 0, 65539)
        val restored = ResumableSha256.restore(first.checkpoint())
        restored.update(data, 65539, data.size - 65539)
        val reference =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        assertEquals(reference, restored.hexDigest())
        assertEquals(data.size.toLong(), restored.checkpoint().bytesHashed)
    }

    @Test
    fun rejectsIncompatibleState() {
        val checkpoint = ResumableSha256().checkpoint().copy(formatVersion = 2)
        assertTrue(runCatching { ResumableSha256.restore(checkpoint) }.isFailure)
    }
}
