package app.lanvault.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "probe_records")
data class ProbeRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val status: String,
    val detail: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "hash_probe_checkpoints")
data class HashProbeCheckpoint(
    @PrimaryKey val id: String,
    val sourceVersion: String,
    val bytesHashed: Long,
    val encodedState: ByteArray,
    val formatVersion: Int,
    val implementationVersion: String,
)

@Dao
interface ProbeDao {
    @Insert suspend fun insert(record: ProbeRecord)

    @Query("SELECT * FROM probe_records ORDER BY createdAtMillis DESC LIMIT 10")
    fun observeLatest(): Flow<List<ProbeRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(checkpoint: HashProbeCheckpoint)

    @Query("SELECT * FROM hash_probe_checkpoints WHERE id = :id")
    suspend fun checkpoint(id: String): HashProbeCheckpoint?
}

@Database(
    entities = [ProbeRecord::class, HashProbeCheckpoint::class],
    version = 1,
    exportSchema = true,
)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun probes(): ProbeDao

    companion object {
        @Volatile private var instance: ProbeDatabase? = null

        fun get(context: Context): ProbeDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                ProbeDatabase::class.java,
                                "lanvault-probes.db",
                            )
                            .build()
                            .also { instance = it }
                }
    }
}
