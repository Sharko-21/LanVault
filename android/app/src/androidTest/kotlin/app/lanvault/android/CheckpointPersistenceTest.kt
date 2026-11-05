package app.lanvault.android

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lanvault.core.database.HashProbeCheckpoint
import app.lanvault.core.database.ProbeDatabase
import app.lanvault.core.security.HashCheckpoint
import app.lanvault.core.security.ResumableSha256
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CheckpointPersistenceTest {
    @Test
    fun survivesDatabaseCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "checkpoint-test-${UUID.randomUUID()}.db"
        val data = ByteArray(1024 * 1024 + 19) { (it % 241).toByte() }
        val first = ResumableSha256()
        first.update(data, 0, 65539)
        val state = first.checkpoint()
        try {
            val writer = Room.databaseBuilder(context, ProbeDatabase::class.java, name).build()
            try {
                writer
                    .probes()
                    .saveCheckpoint(
                        HashProbeCheckpoint(
                            "probe",
                            "immutable-test-source-v1",
                            state.bytesHashed,
                            state.encodedState,
                            state.formatVersion,
                            state.implementationVersion,
                        )
                    )
            } finally {
                writer.close()
            }
            val reader = Room.databaseBuilder(context, ProbeDatabase::class.java, name).build()
            val saved =
                try {
                    checkNotNull(reader.probes().checkpoint("probe"))
                } finally {
                    reader.close()
                }
            assertEquals("immutable-test-source-v1", saved.sourceVersion)
            val restored =
                ResumableSha256.restore(
                    HashCheckpoint(
                        saved.bytesHashed,
                        saved.encodedState,
                        saved.formatVersion,
                        saved.implementationVersion,
                    )
                )
            restored.update(data, saved.bytesHashed.toInt(), data.size - saved.bytesHashed.toInt())
            val expected =
                MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
                    "%02x".format(it.toInt() and 0xff)
                }
            assertEquals(expected, restored.hexDigest())
        } finally {
            context.deleteDatabase(name)
        }
    }
}
