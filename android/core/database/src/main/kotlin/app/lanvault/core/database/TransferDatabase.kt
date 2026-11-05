package app.lanvault.core.database

import android.content.Context
import androidx.room.*
import app.lanvault.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "manual_transfers",
    indices = [Index(value = ["serverId", "datasetId", "sourceUri", "sourceVersion", "sha256"])],
)
data class TransferEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val datasetId: String,
    val sourceUri: String,
    val sourceVersion: String,
    val sha256: String,
    val state: String,
    val updatedAtMillis: Long,
    val payload: String,
)

@Dao
interface TransferDao {
    @Query(
        "SELECT * FROM manual_transfers WHERE serverId=:server AND datasetId=:dataset AND sourceUri=:uri AND sourceVersion=:version AND state='COMMITTED' ORDER BY updatedAtMillis DESC LIMIT 1"
    )
    suspend fun committed(
        server: String,
        dataset: String,
        uri: String,
        version: String,
    ): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(entity: TransferEntity)

    @Query(
        "SELECT * FROM manual_transfers WHERE serverId=:server AND datasetId=:dataset AND sourceUri=:uri AND sourceVersion=:version AND sha256=:hash AND state!='SUPERSEDED' ORDER BY updatedAtMillis DESC LIMIT 1"
    )
    suspend fun find(
        server: String,
        dataset: String,
        uri: String,
        version: String,
        hash: String,
    ): TransferEntity?

    @Query(
        "UPDATE manual_transfers SET state='INTERRUPTED',updatedAtMillis=:now WHERE state IN ('PREPARED','UPLOADING','VERIFIED','COMMITTING')"
    )
    suspend fun interruptPending(now: Long)

    @Query("SELECT * FROM manual_transfers ORDER BY updatedAtMillis DESC LIMIT 30")
    fun latest(): Flow<List<TransferEntity>>

    @Query(
        "SELECT DISTINCT sourceUri FROM manual_transfers WHERE serverId=:server AND state NOT IN ('COMMITTED','SUPERSEDED') ORDER BY updatedAtMillis"
    )
    suspend fun unfinished(server: String): List<String>
}

@Database(entities = [TransferEntity::class], version = 1, exportSchema = true)
abstract class TransferDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao

    companion object {
        fun open(context: Context, name: String = "lanvault-transfers.db"): TransferDatabase {
            require(name.matches(Regex("""[a-zA-Z0-9-]+\.db""")))
            return Room.databaseBuilder(
                    context.applicationContext,
                    TransferDatabase::class.java,
                    name,
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("PRAGMA synchronous=FULL")
                        }
                    }
                )
                .build()
        }

        @Volatile private var instance: TransferDatabase? = null

        fun get(context: Context) =
            instance
                ?: synchronized(this) {
                    instance ?: open(context).also { instance = it }
                }
    }
}

class RoomTransferJournal(private val dao: TransferDao) : TransferJournal {
    override suspend fun committed(
        serverId: String,
        datasetId: String,
        uri: String,
        version: String,
    ) = dao.committed(serverId, datasetId, uri, version)?.let(::decode)

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    private fun decode(entity: TransferEntity): TransferRecord {
        val record = json.decodeFromString<TransferRecord>(entity.payload)
        return record.copy(
            state = entity.state,
            updatedAtMillis = entity.updatedAtMillis,
            errorCode = if (entity.state == "INTERRUPTED") "INTERRUPTED" else record.errorCode,
        )
    }

    override suspend fun find(
        serverId: String,
        datasetId: String,
        uri: String,
        version: String,
        hash: String,
    ): TransferRecord? =
        dao.find(serverId, datasetId, uri, version, hash)?.let {
            decode(it)
        }

    override suspend fun put(record: TransferRecord) {
        dao.put(
            TransferEntity(
                record.id,
                record.serverId,
                record.descriptor.datasetId,
                record.sourceUri,
                record.sourceVersion,
                record.descriptor.sha256,
                record.state,
                record.updatedAtMillis,
                json.encodeToString(record),
            )
        )
    }

    fun latest(): Flow<List<TransferRecord>> = dao.latest().map { rows -> rows.map { decode(it) } }
}
