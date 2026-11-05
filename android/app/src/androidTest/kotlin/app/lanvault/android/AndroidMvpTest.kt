package app.lanvault.android

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lanvault.core.database.*
import app.lanvault.core.media.MediaRepository
import app.lanvault.core.model.*
import app.lanvault.core.security.KeystoreTrustStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMvpTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = "11111111-1111-4111-8111-111111111111"

    @Test
    fun keystoreEncryptsAndSurvivesReopening() {
        val namespace = "test-${UUID.randomUUID()}"
        val store = KeystoreTrustStore(context, namespace)
        val token = "synthetic-token-${UUID.randomUUID()}"
        val trusted =
            TrustedServer(
                id,
                "$id.lanvault.invalid",
                "synthetic-public-ca",
                "a".repeat(64),
                "192.168.10.3",
                43827,
                id,
                token,
            )
        try {
            store.write(TrustState(trusted))
            assertEquals(trusted, KeystoreTrustStore(context, namespace).read().trusted)
            val encrypted =
                File(context.noBackupFilesDir, "desktop-trust-$namespace.enc")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
            assertFalse(encrypted.contains(token))
            assertFalse(encrypted.contains("192.168.10.3"))
        } finally {
            store.reset()
        }
    }

    @Test
    fun keystoreRejectsModifiedCiphertext() {
        val namespace = "test-${UUID.randomUUID()}"
        val store = KeystoreTrustStore(context, namespace)
        try {
            store.write(TrustState())
            val file = File(context.noBackupFilesDir, "desktop-trust-$namespace.enc")
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertThrows(Exception::class.java) { KeystoreTrustStore(context, namespace).read() }
        } finally {
            store.reset()
        }
    }

    @Test
    fun transferJournalRetainsRequestIdentityAcrossDatabaseReopen() = runBlocking {
        val name = "test-journal-${UUID.randomUUID()}.db"
        val descriptor =
            UploadDescriptor(
                id,
                id,
                id,
                id,
                "synthetic.png",
                "42",
                "a".repeat(64),
                MediaType.photo,
                "image/png",
            )
        val record =
            TransferRecord(
                id,
                id,
                "content://media/test/images/media/1",
                "stable-source-version",
                descriptor,
                state = "UPLOADING",
            )
        try {
            val writer = TransferDatabase.open(context, name)
            try {
                RoomTransferJournal(writer.transfers()).put(record)
            } finally {
                writer.close()
            }
            val reader = TransferDatabase.open(context, name)
            try {
                reader.transfers().interruptPending(record.updatedAtMillis)
                val restored =
                    RoomTransferJournal(reader.transfers())
                        .find(id, id, record.sourceUri, record.sourceVersion, descriptor.sha256)
                assertEquals(
                    record.copy(state = "INTERRUPTED", errorCode = "INTERRUPTED"),
                    restored,
                )
                reader.openHelper.writableDatabase.query("PRAGMA synchronous").use {
                    it.moveToFirst()
                    assertEquals(2, it.getInt(0))
                }
            } finally {
                reader.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun mediaStoreListsPublishedImagesAndReadsExactOriginalBytes() = runBlocking {
        val permissions =
            if (Build.VERSION.SDK_INT >= 33)
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                )
            else
                listOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                )
        permissions.forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val name = "LanVault-synthetic-${UUID.randomUUID()}.png"
        val bitmap =
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(10, 100, 120))
            }
        val original =
            ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        bitmap.recycle()
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LanVaultTests")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri =
            checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(original) }
            val repository = MediaRepository(context)
            assertTrue(repository.page().items.none { it.name == name })
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            assertEquals(original.size.toLong(), repository.inspect(uri.toString()).size)
            assertArrayEquals(
                original,
                repository.openOriginal(uri.toString()).use { it.readBytes() },
            )
            // Manual sending opens the source separately for hashing and for upload.
            // Already-original PNGs must remain readable on both independent opens.
            assertArrayEquals(
                original,
                repository.openOriginal(uri.toString()).use { it.readBytes() },
            )
            assertTrue(repository.page().items.any { it.name == name })
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun androidOriginalReachesDesktopOverBoundWifi() = runBlocking {
        val encoded = InstrumentationRegistry.getArguments().getString("lanvaultQrB64")
        org.junit.Assume.assumeTrue("Supply an ephemeral test Desktop QR", encoded != null)
        val envelope =
            app.lanvault.core.transport.parseEnvelope(
                java.util.Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
            )
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        permissions.forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val namespace = "test-${UUID.randomUUID()}"
        val store = KeystoreTrustStore(context, namespace)
        val name = "test-e2e-${UUID.randomUUID()}.db"
        val database = TransferDatabase.open(context, name)
        val controller = app.lanvault.core.sync.ManualBackupController(context, store, database)
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "LanVault-android-e2e-${UUID.randomUUID()}.png",
                )
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LanVaultTests")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri =
            checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            val bitmap =
                Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.rgb(40, 120, 160))
                }
            val original =
                ByteArrayOutputStream()
                    .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    .toByteArray()
            bitmap.recycle()
            resolver.openOutputStream(uri, "w")!!.use { it.write(original) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            kotlinx.coroutines.delay(500)
            controller.pair(envelope) { code ->
                instrumentation.sendStatus(
                    2,
                    android.os.Bundle().apply { putString("lanvaultCode", code) },
                )
            }
            val item = controller.media.inspect(uri.toString())
            controller.send(listOf(item), {}, {})
            val record = controller.history.first().single()
            assertEquals("COMMITTED", record.state)
            val receipt = checkNotNull(record.receipt)
            assertEquals(app.lanvault.core.transport.digestHex(original), receipt.sha256)
            assertEquals(original.size.toString(), receipt.size)
            assertArrayEquals(
                original,
                controller.media.openOriginal(uri.toString()).use { it.readBytes() },
            )
            instrumentation.sendStatus(
                2,
                android.os.Bundle().apply {
                    putString("lanvaultReceiptId", receipt.receiptId)
                    putString("lanvaultHash", receipt.sha256)
                    putString("lanvaultSize", receipt.size)
                },
            )
        } finally {
            resolver.delete(uri, null, null)
            database.close()
            context.deleteDatabase(name)
            store.reset()
        }
    }
}
