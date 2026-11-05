package app.lanvault.core.sync

import android.content.Context
import android.os.Build
import app.lanvault.core.database.*
import app.lanvault.core.media.LibraryCursor
import app.lanvault.core.media.MediaRepository
import app.lanvault.core.media.MediaUnavailableException
import app.lanvault.core.model.*
import app.lanvault.core.network.WifiConnection
import app.lanvault.core.security.KeystoreTrustStore
import app.lanvault.core.transport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object BackupOperations {
    val mutex = Mutex()
}

class ManualBackupController(
    private val context: Context,
    injectedStore: TrustStore? = null,
    injectedDatabase: TransferDatabase? = null,
) {
    val media = MediaRepository(context)
    private val store: TrustStore = injectedStore ?: KeystoreTrustStore(context)
    private val dao = (injectedDatabase ?: TransferDatabase.get(context)).transfers()
    private val journal = RoomTransferJournal(dao)
    val history = journal.latest()

    private suspend fun <T> locked(action: suspend () -> T): T =
        withContext(Dispatchers.IO) { BackupOperations.mutex.withLock { action() } }

    suspend fun recover() {
        if (BackupOperations.mutex.tryLock()) {
            try {
                dao.interruptPending(System.currentTimeMillis())
            } finally {
                BackupOperations.mutex.unlock()
            }
        }
    }

    suspend fun trust() = withContext(Dispatchers.IO) { store.read() }

    suspend fun forget() = locked {
        if (store is KeystoreTrustStore) store.reset() else store.write(TrustState())
    }

    suspend fun pair(envelope: TrustEnvelope, onCode: (String) -> Unit): TrustedServer = locked {
        WifiConnection.open(context, envelope.addresses.first()).use { wifi ->
            wifi.pairing(envelope).use { client ->
                PairingWorkflow(store)
                    .connect(
                        envelope,
                        client,
                        Build.MODEL.filterNot(Char::isISOControl).take(80).ifBlank {
                            "Android"
                        },
                        onCode,
                    )
            }
        }
    }

    suspend fun check(forceDiscovery: Boolean = true): String = locked {
        TrustedSession.open(context, store, forceDiscovery).use { it.client.currentDataset() }
    }

    suspend fun unfinished(): List<MediaItem> = locked {
        val trusted = store.read().trusted ?: throw ClientFailure("NOT_PAIRED")
        dao.unfinished(trusted.serverId).distinct().map { media.inspect(it) }
    }

    suspend fun send(
        items: List<MediaItem>,
        progress: (TransferProgress) -> Unit,
        committed: (String) -> Unit,
    ) = locked {
        if (!media.access().originalMetadata)
            throw ClientFailure("ORIGINAL_METADATA_PERMISSION_REQUIRED")
        TrustedSession.open(context, store, true).use { session ->
            val source =
                object : OriginalSource {
                    override fun inspect(uri: String) = media.inspect(uri)

                    override fun open(uri: String) = media.openOriginal(uri)
                }
            ManualTransfer(source, journal)
                .send(items, session.trusted, session.client, progress, committed)
        }
    }

    suspend fun syncAll(
        forceDiscovery: Boolean = false,
        verifyCopies: Boolean = false,
        progress: (LibraryProgress) -> Unit,
    ): LibraryProgress = locked {
        if (!media.access().originalMetadata)
            throw ClientFailure("ORIGINAL_METADATA_PERMISSION_REQUIRED")
        dao.interruptPending(System.currentTimeMillis())
        var lastProgress = LibraryProgress()
        repeat(2) { attempt ->
            try {
                TrustedSession.open(context, store, forceDiscovery || attempt > 0).use { session ->
                    val source =
                        object : OriginalSource {
                            override fun inspect(uri: String) = media.inspect(uri)

                            override fun open(uri: String) = media.openOriginal(uri)
                        }
                    return@locked LibraryBackup(source, journal)
                        .run<LibraryCursor>(
                            session.trusted,
                            session.client,
                            { cursor ->
                                media.page(cursor).let { LibraryBatch(it.items, it.next) }
                            },
                            {
                                lastProgress = it
                                progress(it)
                            },
                            {
                                it is MediaUnavailableException ||
                                    it is ClientFailure && it.code == "SOURCE_CHANGED"
                            },
                            verifyCopies = verifyCopies,
                            checkpoints =
                                PreferenceLibraryCheckpoints(context, media.checkpointScope()),
                        )
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt != 0 || !retryableTransferFailure(error)) throw error
                progress(
                    lastProgress.copy(
                        reconnecting = true,
                        current = null,
                        activeTransfers = emptyList(),
                    )
                )
                delay(2_000)
            }
        }
        error("Unreachable reconnect state")
    }
}
