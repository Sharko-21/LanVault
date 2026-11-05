package app.lanvault.core.transport

import app.lanvault.core.model.LibraryProgress

data class LibraryRunKey(
    val serverId: String,
    val deviceId: String,
    val datasetId: String,
    val verifying: Boolean,
)

data class LibraryCheckpoint<C>(
    val cursor: C,
    val progress: LibraryProgress,
    val updatedAtMillis: Long,
)

/**
 * Advance only after the entire bounded page has durable receipts or a known unavailable source.
 */
interface LibraryCheckpointStore<C> {
    suspend fun load(key: LibraryRunKey): LibraryCheckpoint<C>?

    suspend fun save(key: LibraryRunKey, checkpoint: LibraryCheckpoint<C>)

    suspend fun clear(key: LibraryRunKey)
}
