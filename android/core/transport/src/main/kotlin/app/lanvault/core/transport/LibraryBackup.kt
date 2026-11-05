package app.lanvault.core.transport

import app.lanvault.core.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded pages, durable receipts, no total-library or selection limit. */
class LibraryBackup(private val source: OriginalSource, private val journal: TransferJournal) {
    suspend fun <C> run(
        trusted: TrustedServer,
        client: DesktopClient,
        page: suspend (C?) -> LibraryBatch<C>,
        progress: (LibraryProgress) -> Unit,
        unavailable: (Exception) -> Boolean = { false },
        now: () -> Long = System::currentTimeMillis,
        parallelism: Int = MAX_TRANSFER_WORKERS,
        verifyCopies: Boolean = false,
        checkpoints: LibraryCheckpointStore<C>? = null,
    ): LibraryProgress {
        val dataset = client.currentDataset()
        val transfer = ManualTransfer(source, journal)
        val key = LibraryRunKey(trusted.serverId, trusted.deviceId, dataset, verifyCopies)
        val restored =
            checkpoints?.load(key)?.takeIf {
                now() - it.updatedAtMillis in 0 until 86_400_000L
            }
        if (restored == null) checkpoints?.clear(key)
        var cursor: C? = restored?.cursor
        var result =
            restored?.progress?.copy(current = null, activeTransfers = emptyList())
                ?: LibraryProgress()
        if (restored != null) progress(result)
        val gate = Any()
        val active = linkedMapOf<Int, TransferProgress>()
        fun update(change: (LibraryProgress) -> LibraryProgress) =
            synchronized(gate) {
                result =
                    change(result)
                        .copy(
                            current = active.values.firstOrNull(),
                            activeTransfers = active.values.toList(),
                        )
                progress(result)
            }
        do {
            currentCoroutineContext().ensureActive()
            val batch = page(cursor)
            require(parallelism in 1..MAX_TRANSFER_WORKERS)
            transferWorkers(batch.items, minOf(parallelism, client.maxConcurrentUploads)) {
                index,
                item ->
                currentCoroutineContext().ensureActive()
                update { it.copy(scanned = Math.addExact(it.scanned, 1)) }
                try {
                    val previous =
                        journal.committed(trusted.serverId, dataset, item.uri, item.version)
                    val receipt = previous?.receipt?.takeIf { it.deviceId == trusted.deviceId }
                    var saved = false
                    if (previous != null && receipt != null) {
                        validateReceipt(receipt, previous.descriptor, trusted)
                        saved =
                            !verifyCopies && now() - previous.updatedAtMillis in 0 until 86_400_000L
                        if (!saved) {
                            try {
                                val checked = retryTransfer { client.create(previous.descriptor) }
                                failUnless(checked.state == "committed", "INVALID_RECEIPT")
                                validateReceipt(
                                    checked.receipt ?: throw ClientFailure("INVALID_RECEIPT"),
                                    previous.descriptor,
                                    trusted,
                                )
                                journal.put(previous.copy(updatedAtMillis = now()))
                                saved = true
                            } catch (e: ClientFailure) {
                                if (e.code != "SAVED_FILE_UNAVAILABLE") throw e
                            }
                        }
                    }
                    if (saved) update { it.copy(skipped = it.skipped + 1) }
                    else {
                        transfer.send(
                            listOf(item),
                            trusted,
                            client,
                            { current ->
                                update {
                                    active[index] = current
                                    it
                                }
                            },
                            {},
                            dataset,
                        )
                        update {
                            active.remove(index)
                            it.copy(saved = it.saved + 1)
                        }
                    }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!unavailable(e)) throw e
                    update {
                        active.remove(index)
                        it.copy(unavailable = it.unavailable + 1)
                    }
                }
            }
            val next = batch.next
            require(next == null || next != cursor) { "Library cursor did not advance" }
            currentCoroutineContext().ensureActive()
            if (next == null) checkpoints?.clear(key)
            else
                checkpoints?.save(
                    key,
                    LibraryCheckpoint(
                        next,
                        result.copy(current = null, activeTransfers = emptyList()),
                        now(),
                    ),
                )
            cursor = next
        } while (cursor != null)
        return result
    }
}
