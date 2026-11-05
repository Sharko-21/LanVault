package app.lanvault.core.transport

import app.lanvault.core.model.*
import java.io.*
import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Same shipping client and transfer coordinator, against the real Windows server. Only this test
 * SocketFactory maps the simulated private address to its loopback listener.
 */
class DesktopInteropTest {
    private class RedirectSockets : SocketFactory() {
        override fun createSocket() =
            object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    val port = (endpoint as InetSocketAddress).port
                    super.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        timeout,
                    )
                }
            }

        override fun createSocket(h: String, p: Int) =
            createSocket().apply { connect(InetSocketAddress(h, p)) }

        override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int) = createSocket(h, p)

        override fun createSocket(h: InetAddress, p: Int) =
            createSocket().apply { connect(InetSocketAddress(h, p)) }

        override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int) =
            createSocket(h, p)
    }

    private class Backend(executable: String) : AutoCloseable {
        val root: Path = Files.createTempDirectory("LanVault-kotlin-interop-")
        val archive: Path = Files.createDirectory(root.resolve("archive"))
        private val errors = root.resolve("server.stderr").toFile()
        private val process =
            ProcessBuilder(
                    executable,
                    "--state-dir",
                    root.resolve("state").toString(),
                    "--backup-dir",
                    archive.toString(),
                    "--loopback-test",
                    "--reserve-bytes",
                    "0",
                )
                .redirectError(errors)
                .start()
        private val reader = process.inputStream.bufferedReader()
        private val writer = process.outputStream.bufferedWriter()
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "server-admin-reader").apply { isDaemon = true }
        }
        val ready = read()

        private fun read(): JsonObject {
            val line = executor.submit<String?> { reader.readLine() }.get(30, TimeUnit.SECONDS)
            checkNotNull(line) { "server ended before reply" }
            return WireJson.parseToJsonElement(line).jsonObject
        }

        @Synchronized
        fun admin(command: String, field: String? = null, value: String? = null): JsonElement {
            val input = buildJsonObject {
                put("command", command)
                if (field != null) put(field, value)
            }
            writer.write(input.toString())
            writer.newLine()
            writer.flush()
            val response = read()
            check("error" !in response) { "administrative test action failed" }
            return response.getValue("result")
        }

        fun qr(): TrustEnvelope {
            val raw = admin("pair").jsonObject
            return parseEnvelope(
                JsonObject(
                        raw.filterKeys { it != "qrDataUrl" } +
                            mapOf("addresses" to buildJsonArray { add("192.168.88.2") })
                    )
                    .toString()
            )
        }

        override fun close() {
            try {
                if (process.isAlive) {
                    writer.write("{\"command\":\"quit\"}\n")
                    writer.flush()
                    if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
                }
            } finally {
                executor.shutdownNow()
                reader.close()
                writer.close()
                val realRoot = root.toRealPath()
                check(
                    realRoot.startsWith(
                        Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
                    ) && realRoot.fileName.toString().startsWith("LanVault-kotlin-interop-")
                )
                realRoot.toFile().deleteRecursively()
            }
        }
    }

    private class Store : TrustStore {
        var state = TrustState()
        var writes = 0
        var failTrustedSave = false

        override fun read() = state

        override fun write(state: TrustState) {
            if (failTrustedSave && state.trusted != null) {
                failTrustedSave = false
                throw IOException("synthetic local save failure")
            }
            this.state = state
            writes++
        }
    }

    private class Journal : TransferJournal {
        val records = ConcurrentHashMap<String, TransferRecord>()

        override suspend fun committed(
            serverId: String,
            datasetId: String,
            uri: String,
            version: String,
        ) =
            records.values.lastOrNull {
                it.serverId == serverId &&
                    it.descriptor.datasetId == datasetId &&
                    it.sourceUri == uri &&
                    it.sourceVersion == version &&
                    it.state == "COMMITTED"
            }

        override suspend fun find(
            serverId: String,
            datasetId: String,
            uri: String,
            version: String,
            hash: String,
        ) =
            records.values.lastOrNull {
                it.serverId == serverId &&
                    it.descriptor.datasetId == datasetId &&
                    it.sourceUri == uri &&
                    it.sourceVersion == version &&
                    it.descriptor.sha256 == hash &&
                    it.state != "SUPERSEDED"
            }

        override suspend fun put(record: TransferRecord) {
            records[record.id] = record
        }
    }

    private class Source(val bytes: ByteArray) : OriginalSource {
        var opens = 0
        var mutateBody = false
        val item =
            MediaItem(
                "content://media/test/images/media/1",
                "original.heic",
                bytes.size.toLong(),
                MediaType.photo,
                "image/heic",
                "fixed-revision-v1",
                "volume-v1",
            )

        override fun inspect(uri: String) = item

        override fun open(uri: String): InputStream {
            opens++
            val data = bytes.copyOf()
            if (mutateBody && opens % 2 == 0) data[data.lastIndex] = (data.last() + 1).toByte()
            return data.inputStream()
        }
    }

    private fun exe(): String =
        System.getProperty("lanvault.server.exe", "").also {
            assumeTrue(
                "Set -PlanvaultServerExe to run Windows interop",
                it.isNotEmpty() && File(it).isFile,
            )
        }

    private fun client(
        envelope: TrustEnvelope,
        token: String? = null,
        guard: () -> Unit = {},
        chunkGuard: (() -> Unit)? = null,
    ): DesktopClient =
        DesktopClient(
            envelope.serverId,
            envelope.hostname,
            envelope.caCertificate,
            envelope.spkiSha256,
            envelope.addresses.first(),
            envelope.port,
            RedirectSockets(),
            guard,
            token,
            chunkGuard ?: guard,
        )

    private suspend fun paired(server: Backend): Pair<TrustEnvelope, TrustedServer> {
        val qr = server.qr()
        val store = Store()
        val trusted =
            client(qr).use { connection ->
                PairingWorkflow(store).connect(qr, connection, "Synthetic Kotlin phone") { code ->
                    val pending =
                        server.admin("status").jsonObject.getValue("pendingPairing").jsonObject
                    assertEquals(code, pending.getValue("verificationCode").jsonPrimitive.content)
                    assertNull(store.state.trusted)
                    server.admin(
                        "approve",
                        "requestId",
                        pending.getValue("id").jsonPrimitive.content,
                    )
                }
            }
        assertEquals(trusted, store.state.trusted)
        assertNull(store.state.pending)
        assertTrue(store.writes >= 4)
        return qr to trusted
    }

    @Test
    fun approvedPairingAndOriginalUploadUseRealTlsAndDurableReceipt() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val source = Source(ByteArray(2 * 1024 * 1024 + 19) { (it % 251).toByte() })
            val journal = Journal()
            client(qr, trusted.token).use { connection ->
                ManualTransfer(source, journal)
                    .send(listOf(source.item), trusted, connection, {}, {})
            }
            val record = journal.records.values.single()
            assertEquals("COMMITTED", record.state)
            assertNotNull(record.receipt)
            assertArrayEquals(
                source.bytes,
                Files.readAllBytes(server.archive.resolve(record.receipt!!.relativePath)),
            )
            assertEquals(2, source.opens)
        }
    }

    @Test
    fun retryAndDedupDoNotResendCompletedBytes() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val source = Source(ByteArray(256 * 1024) { 7 })
            val journal = Journal()
            val transfer = ManualTransfer(source, journal)
            client(qr, trusted.token).use { connection ->
                transfer.send(listOf(source.item), trusted, connection, {}, {})
                val first = journal.records.values.single().descriptor.uploadRequestId
                transfer.send(listOf(source.item), trusted, connection, {}, {})
                assertEquals(first, journal.records.values.single().descriptor.uploadRequestId)
            }
            assertEquals(3, source.opens)
            assertEquals(1, journal.records.size)
        }
    }

    @Test
    fun externallyRemovedCopyGetsANewUploadWithoutFalseCachedSuccess() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val source = Source(ByteArray(8192) { 9 })
            val journal = Journal()
            val transfer = ManualTransfer(source, journal)
            client(qr, trusted.token).use { connection ->
                transfer.send(listOf(source.item), trusted, connection, {}, {})
                val first = journal.records.values.single()
                Files.delete(server.archive.resolve(first.receipt!!.relativePath))
                transfer.send(listOf(source.item), trusted, connection, {}, {})
            }
            assertEquals(2, journal.records.size)
            assertEquals(1, journal.records.values.count { it.state == "SUPERSEDED" })
            val repaired = journal.records.values.single { it.state == "COMMITTED" }
            assertArrayEquals(
                source.bytes,
                Files.readAllBytes(server.archive.resolve(repaired.receipt!!.relativePath)),
            )
        }
    }

    @Test
    fun changedOriginalNeverBecomesCommitted() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val source = Source(ByteArray(262144) { 11 }).apply { mutateBody = true }
            val journal = Journal()
            client(qr, trusted.token).use { connection ->
                try {
                    ManualTransfer(source, journal)
                        .send(listOf(source.item), trusted, connection, {}, {})
                    fail("changed original accepted")
                } catch (_: IOException) {}
            }
            assertTrue(journal.records.values.none { it.state == "COMMITTED" })
            assertTrue(journal.records.values.all { it.receipt == null })
        }
    }

    @Test
    fun cancellationRetainsRequestIdentityAndCanRetry() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val source = Source(ByteArray(4 * 1024 * 1024) { 31 })
            val journal = Journal()
            val transfer = ManualTransfer(source, journal)
            val reference = AtomicReference<Job>()
            client(qr, trusted.token).use { connection ->
                val task =
                    launch(start = CoroutineStart.LAZY) {
                        transfer.send(
                            listOf(source.item),
                            trusted,
                            connection,
                            {
                                if (it.stage == "UPLOADING" && it.bytes >= 131072)
                                    reference.get().cancel()
                            },
                            {},
                        )
                    }
                reference.set(task)
                task.start()
                task.join()
            }
            assertEquals("INTERRUPTED", journal.records.values.single().state)
            val requestId = journal.records.values.single().descriptor.uploadRequestId
            delay(300)
            client(qr, trusted.token).use { connection ->
                transfer.send(listOf(source.item), trusted, connection, {}, {})
            }
            assertEquals("COMMITTED", journal.records.values.single().state)
            assertEquals(requestId, journal.records.values.single().descriptor.uploadRequestId)
        }
    }

    @Test
    fun revokedDeviceCannotStartAnotherUpload() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            server.admin("revoke", "deviceId", trusted.deviceId)
            client(qr, trusted.token).use { connection ->
                try {
                    connection.currentDataset()
                    fail("revoked token accepted")
                } catch (e: ClientFailure) {
                    assertEquals(401, e.httpStatus)
                }
            }
        }
    }

    @Test
    fun anotherCaIsNotTrustedEvenWithAValidPinForThatOtherCa() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            Backend(exe()).use { other ->
                val ca = other.qr()
                client(
                        qr.copy(caCertificate = ca.caCertificate, spkiSha256 = ca.spkiSha256),
                        trusted.token,
                    )
                    .use { connection ->
                        try {
                            connection.currentDataset()
                            fail("untrusted CA accepted")
                        } catch (_: javax.net.ssl.SSLException) {}
                    }
            }
        }
    }

    @Test
    fun hostnameVerificationCannotBeBypassedByQrAddress() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val wrong = "22222222-2222-4222-8222-222222222222"
            client(qr.copy(serverId = wrong, hostname = "$wrong.lanvault.invalid"), trusted.token)
                .use { connection ->
                    try {
                        connection.currentDataset()
                        fail("wrong hostname accepted")
                    } catch (_: javax.net.ssl.SSLException) {}
                }
        }
    }

    @Test
    fun wholeLibraryTraversesPagesResumesAndSkipsConfirmedFilesBeyond100() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val journal = Journal()
            val items =
                (1..137)
                    .map { n ->
                        MediaItem(
                            "content://media/external_primary/images/media/$n",
                            "original-$n.png",
                            32,
                            MediaType.photo,
                            "image/png",
                            "revision-v1",
                            "volume-v1",
                        )
                    }
                    .toMutableList()
            val opens = AtomicInteger()
            val source =
                object : OriginalSource {
                    override fun inspect(uri: String) = items.single { it.uri == uri }

                    override fun open(uri: String): InputStream {
                        opens.incrementAndGet()
                        val item = inspect(uri)
                        return ByteArray(32) { i ->
                                (item.uri.hashCode() + item.version.hashCode() + i).toByte()
                            }
                            .inputStream()
                    }
                }
            suspend fun page(cursor: Int?): LibraryBatch<Int> {
                val start = cursor ?: 0
                val end = (start + 37).coerceAtMost(items.size)
                return LibraryBatch(
                    items.subList(start, end).toList(),
                    end.takeIf { it < items.size },
                )
            }
            client(qr, trusted.token).use { connection ->
                try {
                    LibraryBackup(source, journal)
                        .run<Int>(
                            trusted,
                            connection,
                            ::page,
                            { state ->
                                if (state.saved == 110)
                                    throw java.util.concurrent.CancellationException(
                                        "synthetic stop after durable receipts"
                                    )
                            },
                            parallelism = 1,
                        )
                    fail("synthetic cancellation ignored")
                } catch (_: java.util.concurrent.CancellationException) {}
                assertEquals(110, journal.records.values.count { it.state == "COMMITTED" })
                val resumed =
                    LibraryBackup(source, journal).run<Int>(trusted, connection, ::page, {})
                assertEquals(137, resumed.scanned)
                assertEquals(110, resumed.skipped)
                assertEquals(27, resumed.saved)
                val previousOpens = opens.get()
                val repeated =
                    LibraryBackup(source, journal).run<Int>(trusted, connection, ::page, {})
                assertEquals(137, repeated.skipped)
                assertEquals(0, repeated.saved)
                assertEquals(previousOpens, opens.get())
                // The ordinary manual path also accepts a selection larger than one page.
                ManualTransfer(source, journal).send(items.toList(), trusted, connection, {}, {})
                assertEquals(137, journal.records.values.count { it.state == "COMMITTED" })
                items[12] = items[12].copy(version = "revision-v2")
                val changed =
                    LibraryBackup(source, journal).run<Int>(trusted, connection, ::page, {})
                assertEquals(1, changed.saved)
                assertEquals(136, changed.skipped)
                assertEquals(138, journal.records.values.count { it.state == "COMMITTED" })
            }
        }
    }

    @Test
    fun streamingUsesFastPolicyAndStillStopsWhenItIsRevoked() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val bytes = ByteArray(2 * 1024 * 1024) { (it % 239).toByte() }
            val template = Source(bytes).item
            val readingBody = AtomicBoolean()
            val chunks = AtomicInteger()
            val opens = AtomicInteger()
            val source =
                object : OriginalSource {
                    override fun inspect(uri: String) = template

                    override fun open(uri: String): InputStream {
                        val body = opens.incrementAndGet() % 2 == 0
                        if (body) readingBody.set(true)
                        return object : ByteArrayInputStream(bytes) {
                            override fun close() {
                                readingBody.set(false)
                                super.close()
                            }
                        }
                    }
                }
            val journal = Journal()
            client(
                    qr,
                    trusted.token,
                    { failUnless(!readingBody.get(), "FULL_POLICY_CALLED_INSIDE_BODY") },
                    { chunks.incrementAndGet() },
                )
                .use { connection ->
                    ManualTransfer(source, journal)
                        .send(listOf(template), trusted, connection, {}, {})
                }
            assertTrue(chunks.get() > 1)
            assertEquals("COMMITTED", journal.records.values.single().state)
            val cancelled = Journal()
            chunks.set(0)
            // Different content: do not let server deduplication skip the body.
            val interrupted = Source(ByteArray(2 * 1024 * 1024) { (it % 233).toByte() })
            client(
                    qr,
                    trusted.token,
                    chunkGuard = {
                        if (chunks.incrementAndGet() >= 3) throw ClientFailure("WIFI_LOST")
                    },
                )
                .use { connection ->
                    try {
                        ManualTransfer(interrupted, cancelled)
                            .send(listOf(interrupted.item), trusted, connection, {}, {})
                        fail("Policy loss ignored")
                    } catch (error: ClientFailure) {
                        assertEquals("WIFI_LOST", error.code)
                    }
                }
            assertTrue(cancelled.records.values.none { it.state == "COMMITTED" })
            assertTrue(cancelled.records.values.all { it.receipt == null })
        }
    }

    @Test
    fun twoTransfersOverlapStayBoundedAndSaveOriginals() = runBlocking {
        parallelTransfers(2)
    }

    @Test
    fun fourTransfersOverlapStayBoundedAndSaveOriginals() = runBlocking {
        parallelTransfers(4)
    }

    private suspend fun parallelTransfers(parallelism: Int) {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val journal = Journal()
            val items =
                (1..6).map { n ->
                    MediaItem(
                        "content://media/test/$n",
                        "parallel-$n.png",
                        512 * 1024,
                        MediaType.photo,
                        "image/png",
                        "v1",
                        "volume-v1",
                    )
                }
            val opens = ConcurrentHashMap<String, AtomicInteger>()
            val bodies = AtomicInteger()
            val peak = AtomicInteger()
            val firstBodies = CountDownLatch(parallelism)
            fun bytes(uri: String) = ByteArray(512 * 1024) { (it + uri.hashCode()).toByte() }
            val source =
                object : OriginalSource {
                    override fun inspect(uri: String) = items.single { it.uri == uri }

                    override fun open(uri: String): InputStream {
                        val body =
                            opens.computeIfAbsent(uri) { AtomicInteger() }.incrementAndGet() == 2
                        if (body) {
                            peak.accumulateAndGet(bodies.incrementAndGet(), ::maxOf)
                            firstBodies.countDown()
                            check(firstBodies.await(10, TimeUnit.SECONDS)) {
                                "Transfers remained serial"
                            }
                        }
                        return object : ByteArrayInputStream(bytes(uri)) {
                            override fun close() {
                                if (body) bodies.decrementAndGet()
                                super.close()
                            }
                        }
                    }
                }
            client(qr, trusted.token).use { connection ->
                val counts = mutableListOf<Int>()
                val result =
                    LibraryBackup(source, journal)
                        .run<Int>(
                            trusted,
                            connection,
                            { LibraryBatch(items, null) },
                            { state ->
                                assertTrue(state.activeTransfers.size <= parallelism)
                                counts.add(state.saved)
                            },
                            parallelism = parallelism,
                        )
                assertEquals(6, result.saved)
                assertEquals(6, result.scanned)
                assertTrue(result.activeTransfers.isEmpty())
                assertEquals(parallelism, peak.get())
                assertEquals(0, bodies.get())
                assertEquals(counts.sorted(), counts)
                for (record in journal.records.values) {
                    assertEquals("COMMITTED", record.state)
                    assertArrayEquals(
                        bytes(record.sourceUri),
                        Files.readAllBytes(server.archive.resolve(record.receipt!!.relativePath)),
                    )
                }
                val repeated =
                    LibraryBackup(source, journal)
                        .run<Int>(
                            trusted,
                            connection,
                            { LibraryBatch(items, null) },
                            {},
                        )
                assertEquals(6, repeated.skipped)
                assertEquals(0, repeated.saved)
                assertTrue(opens.values.all { it.get() == 2 })
            }
        }
    }

    @Test
    fun committedProgressFailurePreservesDurableReceipt() = runBlocking {
        Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val journal = Journal()
            val source = Source(ByteArray(32) { it.toByte() })
            client(qr, trusted.token).use { connection ->
                try {
                    ManualTransfer(source, journal)
                        .send(
                            listOf(source.item),
                            trusted,
                            connection,
                            {
                                if (it.stage == "COMMITTED")
                                    throw java.util.concurrent.CancellationException("UI stopped")
                            },
                            {},
                        )
                    fail("Synthetic stop ignored")
                } catch (_: java.util.concurrent.CancellationException) {}
                assertEquals("COMMITTED", journal.records.values.single().state)
                val repeated =
                    LibraryBackup(source, journal)
                        .run<Int>(
                            trusted,
                            connection,
                            { LibraryBatch(listOf(source.item), null) },
                            {},
                        )
                assertEquals(1, repeated.skipped)
                assertEquals(0, repeated.saved)
                assertEquals(2, source.opens)
            }
        }
    }

    @Test
    fun newPairingCannotReuseReceiptsFromAnOldDeviceIdentity() = runBlocking {
        Backend(exe()).use { server ->
            val source = Source(ByteArray(32) { it.toByte() })
            val journal = Journal()
            val (firstQr, firstTrust) = paired(server)
            client(firstQr, firstTrust.token).use { connection ->
                ManualTransfer(source, journal)
                    .send(listOf(source.item), firstTrust, connection, {}, {})
            }
            val (newQr, newTrust) = paired(server)
            assertNotEquals(firstTrust.deviceId, newTrust.deviceId)
            client(newQr, newTrust.token).use { connection ->
                val result =
                    LibraryBackup(source, journal)
                        .run<Int>(
                            newTrust,
                            connection,
                            { LibraryBatch(listOf(source.item), null) },
                            {},
                        )
                assertEquals(1, result.saved)
                assertEquals(0, result.skipped)
                assertEquals(newTrust.deviceId, journal.records.values.single().receipt!!.deviceId)
            }
        }
    }

    @Test
    fun failedLocalCredentialSaveCanResumeEvenAfterQrExpiry() = runBlocking {
        Backend(exe()).use { server ->
            val qr = server.qr()
            val store = Store().apply { failTrustedSave = true }
            var code = ""
            client(qr).use { connection ->
                try {
                    PairingWorkflow(store).connect(qr, connection, "Synthetic recovery phone") {
                        comparison ->
                        code = comparison
                        val pending =
                            server.admin("status").jsonObject.getValue("pendingPairing").jsonObject
                        server.admin(
                            "approve",
                            "requestId",
                            pending.getValue("id").jsonPrimitive.content,
                        )
                    }
                    fail("synthetic write failure was ignored")
                } catch (_: IOException) {}
                assertNull(store.state.trusted)
                val pending = checkNotNull(store.state.pending)
                assertEquals(
                    "approved",
                    connection.poll(checkNotNull(pending.requestId), pending.claim).state,
                )
            }
            val expired = qr.copy(expiresAt = System.currentTimeMillis() / 1000 - 1)
            store.state = store.state.copy(pending = store.state.pending!!.copy(envelope = expired))
            client(expired).use { connection ->
                val recovered =
                    PairingWorkflow(store).connect(
                        expired,
                        connection,
                        "Synthetic recovery phone",
                    ) {
                        assertEquals(code, it)
                    }
                assertEquals(recovered, store.state.trusted)
                assertNull(store.state.pending)
            }
        }
    }

    @Test
    fun explicitVerificationRepairsRecentDeletedAndCorruptedCopies() = runBlocking {
        for (corrupt in listOf(false, true)) Backend(exe()).use { server ->
            val (qr, trusted) = paired(server)
            val source = Source(ByteArray(32) { it.toByte() })
            val journal = Journal()
            client(qr, trusted.token).use { connection ->
                suspend fun run(verify: Boolean) =
                    LibraryBackup(source, journal)
                        .run<Int>(
                            trusted,
                            connection,
                            { LibraryBatch(listOf(source.item), null) },
                            {},
                            verifyCopies = verify,
                        )
                assertEquals(1, run(false).saved)
                val first = journal.records.values.single().receipt!!
                val path = server.archive.resolve(first.relativePath)
                val previousOpens = source.opens
                assertEquals(1, run(true).skipped)
                assertEquals(
                    previousOpens,
                    source.opens,
                ) // A healthy PC copy needs no phone re-read.
                if (corrupt) Files.write(path, ByteArray(32) { 99 }) else Files.delete(path)
                assertEquals(
                    1,
                    run(false).skipped,
                ) // Normal background mode retains its fast cache.
                val repaired = run(true)
                assertEquals(1, repaired.saved)
                val receipt = journal.records.values.single { it.state == "COMMITTED" }.receipt!!
                assertNotEquals(first.relativePath, receipt.relativePath)
                assertArrayEquals(
                    ByteArray(32) { it.toByte() },
                    Files.readAllBytes(server.archive.resolve(receipt.relativePath)),
                )
                if (corrupt)
                    assertArrayEquals(
                        ByteArray(32) { 99 },
                        Files.readAllBytes(path),
                    ) // Never overwrite.
            }
        }
    }
}
