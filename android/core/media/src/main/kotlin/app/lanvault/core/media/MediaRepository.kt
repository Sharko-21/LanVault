package app.lanvault.core.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.lanvault.core.model.*
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class MediaAccess(
    val images: Boolean,
    val videos: Boolean,
    val selected: Boolean,
    val originalMetadata: Boolean,
) {
    val canRead
        get() = images || videos || selected

    val label
        get() =
            if (images && videos) "Все фотографии и видео"
            else if (selected) "Ограниченный доступ: только разрешённые файлы"
            else if (images) "Только фотографии"
            else if (videos) "Только видео" else "Нет доступа к медиатеке"
}

data class CollectionRef(
    val uri: String,
    val volume: String,
    val type: MediaType,
    val version: String,
)

data class LibraryCursor(
    val collections: List<CollectionRef>,
    val index: Int = 0,
    val beforeId: Long = Long.MAX_VALUE,
)

data class MediaPage(val items: List<MediaItem>, val next: LibraryCursor?)

class MediaUnavailableException(cause: Throwable? = null) :
    IOException("MEDIA_FILE_UNAVAILABLE", cause)

class MediaRepository(private val context: Context) {
    private val resolver = context.contentResolver

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun access() =
        MediaAccess(
            granted(
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
            ),
            granted(
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            ),
            Build.VERSION.SDK_INT >= 34 &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            granted(Manifest.permission.ACCESS_MEDIA_LOCATION),
        )

    /** Stable across new photos, invalidated by permission changes or a rebuilt media volume. */
    fun checkpointScope(): String =
        access().toString() +
            "|" +
            MediaStore.getExternalVolumeNames(context).sorted().joinToString("|") {
                it + ":" + MediaStore.getVersion(context, it)
            }

    suspend fun page(cursor: LibraryCursor? = null): MediaPage =
        withContext(Dispatchers.IO) {
            val access = access()
            if (!access.canRead) throw SecurityException("MEDIA_PERMISSION_REQUIRED")
            val start =
                cursor
                    ?: LibraryCursor(
                        MediaStore.getExternalVolumeNames(context).sorted().flatMap { volume ->
                            val version = MediaStore.getVersion(context, volume)
                            buildList {
                                if (access.images || access.selected)
                                    add(
                                        CollectionRef(
                                            MediaStore.Images.Media.getContentUri(volume)
                                                .toString(),
                                            volume,
                                            MediaType.photo,
                                            version,
                                        )
                                    )
                                if (access.videos || access.selected)
                                    add(
                                        CollectionRef(
                                            MediaStore.Video.Media.getContentUri(volume).toString(),
                                            volume,
                                            MediaType.video,
                                            version,
                                        )
                                    )
                            }
                        }
                    )
            val result = mutableListOf<MediaItem>()
            var index = start.index
            var before = start.beforeId
            while (result.size < 100 && index < start.collections.size) {
                ensureActive()
                val collection = start.collections[index]
                if (MediaStore.getVersion(context, collection.volume) != collection.version)
                    throw IOException("MEDIA_VOLUME_CHANGED")
                val selection = visibleSelection() + " AND ${MediaStore.MediaColumns._ID} < ?"
                val args =
                    Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putStringArray(
                            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(before.toString()),
                        )
                        putString(
                            ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                            "${MediaStore.MediaColumns._ID} DESC",
                        )
                        putInt(ContentResolver.QUERY_ARG_LIMIT, 100 - result.size)
                    }
                var count = 0
                var last = before
                val maximum = 100 - result.size
                val queried =
                    resolver.query(Uri.parse(collection.uri), projection(), args, null)
                        ?: throw IOException("MEDIA_QUERY_FAILED")
                queried.use { rows ->
                    while (count < maximum && rows.moveToNext()) {
                        ensureActive()
                        val id =
                            rows.getLong(rows.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        last = id
                        count++
                        val item =
                            fromRow(
                                rows,
                                ContentUris.withAppendedId(Uri.parse(collection.uri), id),
                                collection.type,
                                collection.version,
                            )
                        if (
                            item.size > 0 &&
                                item.mimeType.startsWith(
                                    if (item.type == MediaType.photo) "image/" else "video/"
                                )
                        )
                            result += item
                    }
                }
                if (count < maximum) {
                    index++
                    before = Long.MAX_VALUE
                } else before = last
            }
            MediaPage(
                result,
                if (index < start.collections.size) LibraryCursor(start.collections, index, before)
                else null,
            )
        }

    fun inspect(uriString: String): MediaItem {
        val uri = Uri.parse(uriString)
        require(
            uri.scheme == "content" &&
                uri.authority == MediaStore.AUTHORITY &&
                uri.pathSegments.size >= 4
        )
        val volume = uri.pathSegments[0]
        val type =
            when (uri.pathSegments[1]) {
                "images" -> MediaType.photo
                "video" -> MediaType.video
                else -> throw IOException("INVALID_MEDIA_URI")
            }
        val cursor =
            resolver.query(uri, projection(), visibleSelection(), null, null)
                ?: throw MediaUnavailableException()
        return cursor.use {
            if (!it.moveToFirst()) throw MediaUnavailableException()
            fromRow(it, uri, type, MediaStore.getVersion(context, volume))
        }
    }

    fun openOriginal(uriString: String): InputStream {
        if (!access().originalMetadata)
            throw SecurityException("ORIGINAL_METADATA_PERMISSION_REQUIRED")
        val uri = MediaStore.setRequireOriginal(Uri.parse(uriString))
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // Ask for original bytes on the first open. Converting an existing descriptor
                // via getOriginalMediaFormatFileDescriptor can reject an already-original PNG.
                val options =
                    Bundle().apply {
                        putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true)
                    }
                val asset =
                    resolver.openTypedAssetFileDescriptor(uri, "*/*", options)
                        ?: throw MediaUnavailableException()
                return try {
                    asset.createInputStream()
                } catch (e: Exception) {
                    runCatching { asset.close() }
                    throw e
                }
            }
            val descriptor =
                resolver.openFileDescriptor(uri, "r") ?: throw MediaUnavailableException()
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        } catch (e: FileNotFoundException) {
            throw MediaUnavailableException(e)
        }
    }

    private fun projection() = buildList {
        addAll(
            listOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        )
        if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.GENERATION_MODIFIED)
    }
        .toTypedArray()

    private fun visibleSelection() =
        "${MediaStore.MediaColumns.IS_PENDING}=0" +
            if (Build.VERSION.SDK_INT >= 30) " AND ${MediaStore.MediaColumns.IS_TRASHED}=0" else ""

    private fun fromRow(
        row: android.database.Cursor,
        uri: Uri,
        type: MediaType,
        volumeVersion: String,
    ): MediaItem {
        fun text(column: String) =
            row.getColumnIndex(column).takeIf { it >= 0 && !row.isNull(it) }?.let(row::getString)
        fun number(column: String) =
            row.getColumnIndex(column).takeIf { it >= 0 && !row.isNull(it) }?.let(row::getLong)
                ?: 0L
        val size = number(MediaStore.MediaColumns.SIZE)
        val modified = number(MediaStore.MediaColumns.DATE_MODIFIED)
        val captured = number(MediaStore.MediaColumns.DATE_TAKEN)
        val name =
            text(MediaStore.MediaColumns.DISPLAY_NAME)?.takeIf { it.isNotEmpty() } ?: "media.bin"
        val generation =
            if (Build.VERSION.SDK_INT >= 30) number(MediaStore.MediaColumns.GENERATION_MODIFIED)
            else 0L
        val mime =
            text(MediaStore.MediaColumns.MIME_TYPE)?.lowercase()
                ?: if (type == MediaType.photo) "image/jpeg" else "video/mp4"
        return MediaItem(
            uri.toString(),
            name,
            size,
            type,
            mime,
            "$volumeVersion:$generation:$modified:$captured:$size:$name:$mime:${text(MediaStore.MediaColumns.RELATIVE_PATH)}",
            volumeVersion,
            if (captured > 0) Instant.ofEpochMilli(captured).toString() else null,
            if (modified > 0) Instant.ofEpochSecond(modified).toString() else null,
            text(MediaStore.MediaColumns.RELATIVE_PATH)?.take(255),
        )
    }
}
