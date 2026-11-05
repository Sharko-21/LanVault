package app.lanvault.core.transport

import app.lanvault.core.model.*
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

interface OriginalSource {
    fun inspect(uri: String): MediaItem

    fun open(uri: String): InputStream
}

class ManualTransfer(private val source: OriginalSource, private val journal: TransferJournal) {
    suspend fun send(
        items: List<MediaItem>,
        trusted: TrustedServer,
        client: DesktopClient,
        progress: (TransferProgress) -> Unit,
        committed: (String) -> Unit,
        knownDataset: String? = null,
        parallelism: Int = MAX_TRANSFER_WORKERS,
    ): Unit =
        withContext(Dispatchers.IO) {
            failUnless(items.isNotEmpty(), "INVALID_SELECTION")
            val dataset = knownDataset ?: client.currentDataset()
            require(parallelism in 1..MAX_TRANSFER_WORKERS)
            failUnless(items.map { it.uri }.distinct().size == items.size, "INVALID_SELECTION")
            if (items.size > 1) {
                val gate = Any()
                var saved = 0
                transferWorkers(items, minOf(parallelism, client.maxConcurrentUploads)) { _, item ->
                    send(
                        listOf(item),
                        trusted,
                        client,
                        { current ->
                            synchronized(gate) {
                                if (current.stage == "COMMITTED") saved++
                                progress(current.copy(completed = saved, total = items.size))
                            }
                        },
                        { uri -> synchronized(gate) { committed(uri) } },
                        dataset,
                        1,
                    )
                }
                return@withContext
            }
            var completed = 0
            val jobContext = coroutineContext
            for (selected in items) {
                ensureActive()
                val item = source.inspect(selected.uri)
                failUnless(
                    item.version == selected.version && item.size in 1..1099511627776L,
                    "SOURCE_CHANGED",
                )
                var record: TransferRecord? = null
                var lastReport = 0L
                var lastStage = ""
                fun report(stage: String, bytes: Long, speed: Long = 0) {
                    val time = System.nanoTime()
                    if (
                        stage == lastStage && bytes != item.size && time - lastReport < 100_000_000L
                    )
                        return
                    lastReport = time
                    lastStage = stage
                    progress(
                        TransferProgress(
                            item.name,
                            stage,
                            bytes,
                            item.size,
                            completed,
                            items.size,
                            speed,
                        )
                    )
                }
                try {
                    report("HASHING", 0)
                    val hash = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val buffer = ByteArray(128 * 1024)
                    source.open(item.uri).use { input ->
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count = Math.addExact(count, read.toLong())
                            failUnless(count <= item.size, "SOURCE_CHANGED")
                            hash.update(buffer, 0, read)
                            report("HASHING", count)
                        }
                    }
                    failUnless(
                        count == item.size && source.inspect(item.uri).version == item.version,
                        "SOURCE_CHANGED",
                    )
                    val sha = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    val previous =
                        journal.find(trusted.serverId, dataset, item.uri, item.version, sha)
                    record =
                        previous
                            ?: TransferRecord(
                                UUID.randomUUID().toString(),
                                trusted.serverId,
                                item.uri,
                                item.version,
                                UploadDescriptor(
                                    datasetId = dataset,
                                    mediaId =
                                        UUID.nameUUIDFromBytes(
                                                "${item.volumeVersion}:${item.uri}".toByteArray()
                                            )
                                            .toString(),
                                    revisionId = UUID.randomUUID().toString(),
                                    uploadRequestId = UUID.randomUUID().toString(),
                                    originalName = item.name,
                                    size = item.size.toString(),
                                    sha256 = sha,
                                    mediaType = item.type,
                                    mimeType = item.mimeType,
                                    capturedAt = item.capturedAt,
                                    modifiedAt = item.modifiedAt,
                                    album = item.album,
                                ),
                            )
                    record.descriptor.validatedSize()
                    journal.put(
                        record.copy(
                            state = "PREPARED",
                            errorCode = null,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    )
                    var created =
                        try {
                            client.create(record.descriptor)
                        } catch (e: ClientFailure) {
                            if (e.code != "SAVED_FILE_UNAVAILABLE") throw e
                            journal.put(record.copy(state = "SUPERSEDED", errorCode = e.code))
                            record =
                                record.copy(
                                    id = UUID.randomUUID().toString(),
                                    descriptor =
                                        record.descriptor.copy(
                                            uploadRequestId = UUID.randomUUID().toString()
                                        ),
                                    state = "PREPARED",
                                    uploadId = null,
                                    receipt = null,
                                    errorCode = null,
                                )
                            journal.put(record)
                            client.create(record.descriptor)
                        }
                    if (created.state != "committed") {
                        val id = created.uploadId ?: throw ClientFailure("INVALID_UPLOAD_ID")
                        failUnless(canonicalUuid(id), "INVALID_UPLOAD_ID")
                        record =
                            record.copy(
                                uploadId = id,
                                state = "UPLOADING",
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        journal.put(record)
                        if (created.state in listOf("created", "writing", "failed")) {
                            failUnless(
                                source.inspect(item.uri).version == item.version,
                                "SOURCE_CHANGED",
                            )
                            val started = System.nanoTime()
                            created =
                                client.content(
                                    id,
                                    item.size,
                                    sha,
                                    { source.open(item.uri) },
                                    { jobContext.ensureActive() },
                                ) { sent ->
                                    val elapsed =
                                        ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                                    report("UPLOADING", sent, sent * 1000 / elapsed)
                                }
                            failUnless(created.state == "verified", "INVALID_UPLOAD_STATE")
                        } else
                            failUnless(
                                created.state in listOf("verified", "committing"),
                                "INVALID_UPLOAD_STATE",
                            )
                        failUnless(
                            source.inspect(item.uri).version == item.version,
                            "SOURCE_CHANGED",
                        )
                        record =
                            record.copy(
                                state = "COMMITTING",
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        journal.put(record)
                        report("COMMITTING", item.size)
                        created = client.complete(id)
                    }
                    val receipt = created.receipt ?: throw ClientFailure("INVALID_RECEIPT")
                    failUnless(created.state == "committed", "INVALID_RECEIPT")
                    validateReceipt(receipt, record.descriptor, trusted)
                    record =
                        record.copy(
                            state = "COMMITTED",
                            receipt = receipt,
                            errorCode = null,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    journal.put(record)
                    completed++
                    report("COMMITTED", item.size)
                    committed(item.uri)
                } catch (e: Exception) {
                    record
                        ?.takeUnless { it.state == "COMMITTED" }
                        ?.let { saved ->
                            withContext(NonCancellable) {
                                runCatching {
                                    journal.put(
                                        saved.copy(
                                            state =
                                                if (e is CancellationException) "INTERRUPTED"
                                                else "FAILED",
                                            errorCode =
                                                if (e is ClientFailure) e.code
                                                else if (e is CancellationException) "INTERRUPTED"
                                                else "TRANSFER_FAILED",
                                            updatedAtMillis = System.currentTimeMillis(),
                                        )
                                    )
                                }
                            }
                        }
                    throw e
                }
            }
        }
}
