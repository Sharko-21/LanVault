package app.lanvault.core.sync

import android.content.Context
import android.provider.MediaStore
import app.lanvault.core.media.CollectionRef
import app.lanvault.core.media.LibraryCursor
import app.lanvault.core.model.LibraryProgress
import app.lanvault.core.model.MediaType
import app.lanvault.core.transport.LibraryCheckpoint
import app.lanvault.core.transport.LibraryCheckpointStore
import app.lanvault.core.transport.LibraryRunKey
import org.json.JSONArray
import org.json.JSONObject

/** Small atomic checkpoints, separate from Keystore trust and the existing transfer journal. */
class PreferenceLibraryCheckpoints(
    context: Context,
    private val scope: String,
    name: String = "lanvault-library-progress",
) : LibraryCheckpointStore<LibraryCursor> {
    init {
        require(name.matches(Regex("[a-zA-Z0-9-]{1,100}")))
    }

    private val preferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun storageKey(key: LibraryRunKey) =
        "${key.serverId}:${key.deviceId}:${key.datasetId}:${key.verifying}"

    override suspend fun load(key: LibraryRunKey): LibraryCheckpoint<LibraryCursor>? {
        val encoded = preferences.getString(storageKey(key), null) ?: return null
        val restored = runCatching {
            require(encoded.length <= 65536)
            val value = JSONObject(encoded)
            require(value.getInt("format") == 1 && value.getString("scope") == scope)
            val rows = value.getJSONArray("collections")
            require(rows.length() in 1..64)
            val collections =
                (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    val type = MediaType.valueOf(row.getString("type"))
                    val volume = row.getString("volume")
                    val expected =
                        when (type) {
                            MediaType.photo -> MediaStore.Images.Media.getContentUri(volume)
                            MediaType.video -> MediaStore.Video.Media.getContentUri(volume)
                        }.toString()
                    require(row.getString("uri") == expected)
                    CollectionRef(expected, volume, type, row.getString("version"))
                }
            val index = value.getInt("index")
            val before = value.getString("beforeId").toLong()
            require(index in collections.indices && before > 0)
            val progress =
                LibraryProgress(
                    scanned = value.getInt("scanned"),
                    saved = value.getInt("saved"),
                    skipped = value.getInt("skipped"),
                    unavailable = value.getInt("unavailable"),
                )
            require(
                listOf(progress.scanned, progress.saved, progress.skipped, progress.unavailable)
                    .all { it >= 0 }
            )
            require(
                progress.saved.toLong() + progress.skipped + progress.unavailable ==
                    progress.scanned.toLong()
            )
            LibraryCheckpoint(
                LibraryCursor(collections, index, before),
                progress,
                value.getString("updatedAtMillis").toLong(),
            )
        }
            .getOrNull()
        if (restored == null) clear(key)
        return restored
    }

    override suspend fun save(key: LibraryRunKey, checkpoint: LibraryCheckpoint<LibraryCursor>) {
        val cursor = checkpoint.cursor
        val progress = checkpoint.progress
        val value =
            JSONObject().apply {
                put("format", 1)
                put("scope", scope)
                put("index", cursor.index)
                put("beforeId", cursor.beforeId.toString())
                put("updatedAtMillis", checkpoint.updatedAtMillis.toString())
                put("scanned", progress.scanned)
                put("saved", progress.saved)
                put("skipped", progress.skipped)
                put("unavailable", progress.unavailable)
                put(
                    "collections",
                    JSONArray().apply {
                        cursor.collections.forEach { collection ->
                            put(
                                JSONObject().apply {
                                    put("uri", collection.uri)
                                    put("volume", collection.volume)
                                    put("type", collection.type.name)
                                    put("version", collection.version)
                                }
                            )
                        }
                    },
                )
            }
        check(preferences.edit().putString(storageKey(key), value.toString()).commit()) {
            "LIBRARY_CHECKPOINT_WRITE_FAILED"
        }
    }

    override suspend fun clear(key: LibraryRunKey) {
        if (preferences.contains(storageKey(key)))
            check(preferences.edit().remove(storageKey(key)).commit()) {
                "LIBRARY_CHECKPOINT_WRITE_FAILED"
            }
    }
}
