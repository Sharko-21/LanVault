package app.lanvault.android

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lanvault.core.media.CollectionRef
import app.lanvault.core.media.LibraryCursor
import app.lanvault.core.model.LibraryProgress
import app.lanvault.core.model.MediaType
import app.lanvault.core.sync.PreferenceLibraryCheckpoints
import app.lanvault.core.transport.LibraryCheckpoint
import app.lanvault.core.transport.LibraryRunKey
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryContinuationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun key() =
        LibraryRunKey(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            false,
        )

    private fun checkpoint() =
        LibraryCheckpoint(
            LibraryCursor(
                listOf(
                    CollectionRef(
                        MediaStore.Images.Media.getContentUri("external_primary").toString(),
                        "external_primary",
                        MediaType.photo,
                        "volume-v1",
                    )
                ),
                beforeId = Long.MAX_VALUE - 42,
            ),
            LibraryProgress(scanned = 14_000, saved = 2, skipped = 13_998),
            System.currentTimeMillis(),
        )

    @Test
    fun progressAndExactCursorSurviveStoreRecreation() = runBlocking {
        val name = "test-library-${UUID.randomUUID()}"
        try {
            val key = key()
            val expected = checkpoint()
            PreferenceLibraryCheckpoints(context, "same-library", name).save(key, expected)
            assertEquals(
                expected,
                PreferenceLibraryCheckpoints(context, "same-library", name).load(key),
            )
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun deviceDatasetModeAndPermissionChangesCannotReuseProgress() = runBlocking {
        val name = "test-library-${UUID.randomUUID()}"
        try {
            val key = key()
            val store = PreferenceLibraryCheckpoints(context, "full-access", name)
            store.save(key, checkpoint())
            assertNull(store.load(key.copy(deviceId = UUID.randomUUID().toString())))
            assertNull(store.load(key.copy(datasetId = UUID.randomUUID().toString())))
            assertNull(store.load(key.copy(verifying = true)))
            assertNotNull(store.load(key))
            assertNull(PreferenceLibraryCheckpoints(context, "limited-access", name).load(key))
            assertNull(store.load(key))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun invalidStoredCursorFallsBackToFullScan() = runBlocking {
        val name = "test-library-${UUID.randomUUID()}"
        try {
            val key = key()
            val store = PreferenceLibraryCheckpoints(context, "scope", name)
            store.save(key, checkpoint())
            val preferences =
                context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
            val entry = preferences.all.keys.single()
            val corrupt = JSONObject(preferences.getString(entry, null)!!).put("beforeId", "0")
            check(preferences.edit().putString(entry, corrupt.toString()).commit())
            assertNull(store.load(key))
            assertFalse(preferences.contains(entry))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
