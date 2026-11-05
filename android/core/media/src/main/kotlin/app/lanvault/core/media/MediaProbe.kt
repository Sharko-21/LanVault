package app.lanvault.core.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.lanvault.core.security.ResumableSha256
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val MAX_PROBE_BYTES = 32L * 1024 * 1024

class MediaProbe(private val context: Context) {
    suspend fun run(): String =
        withContext(Dispatchers.IO) {
            fun granted(permission: String) =
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            val partial =
                Build.VERSION.SDK_INT >= 34 &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            val fullImages =
                if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES)
                else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            val fullVideos =
                if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_VIDEO)
                else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (!fullImages && !fullVideos && !partial)
                throw SecurityException("Доступ к фотографиям и видео не предоставлен.")

            var images = 0L
            var videos = 0L
            var firstImage: Uri? = null
            val resolver = context.contentResolver
            for (volume in MediaStore.getExternalVolumeNames(context)) {
                ensureActive()
                if (fullImages || partial) {
                    val collection = MediaStore.Images.Media.getContentUri(volume)
                    resolver
                        .query(
                            collection,
                            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE),
                            null,
                            null,
                            "${MediaStore.Images.Media._ID} ASC",
                        )
                        ?.use { cursor ->
                            images += cursor.count
                            while (firstImage == null && cursor.moveToNext()) {
                                val size = cursor.getLong(1)
                                if (size in 1..MAX_PROBE_BYTES)
                                    firstImage =
                                        ContentUris.withAppendedId(collection, cursor.getLong(0))
                            }
                        }
                }
                if (fullVideos || partial) {
                    resolver
                        .query(
                            MediaStore.Video.Media.getContentUri(volume),
                            arrayOf(MediaStore.Video.Media._ID),
                            null,
                            null,
                            null,
                        )
                        ?.use { videos += it.count }
                }
            }
            val scope =
                if (fullImages && fullVideos) "Полный доступ к медиа"
                else "Ограниченный доступ к медиа"
            val summary = "$scope. Доступно фото: $images; видео: $videos."
            val uri =
                firstImage
                    ?: return@withContext "$summary Нет фотографии до 32 МиБ для проверки оригинальных байтов."
            if (!granted(Manifest.permission.ACCESS_MEDIA_LOCATION))
                return@withContext "$summary Для проверки неизменённого EXIF требуется доступ к данным местоположения медиа."

            val original = MediaStore.setRequireOriginal(uri)
            val opened =
                resolver.openFileDescriptor(original, "r")
                    ?: throw IOException("Не удалось открыть оригинал.")
            val originalFormat =
                try {
                    if (Build.VERSION.SDK_INT >= 31)
                        MediaStore.getOriginalMediaFormatFileDescriptor(context, opened)
                    else opened
                } catch (failure: Exception) {
                    opened.close()
                    throw failure
                }
            val hasher = ResumableSha256()
            try {
                ParcelFileDescriptor.AutoCloseInputStream(originalFormat).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PROBE_BYTES)
                            throw IOException(
                                "Источник изменился или превышает размер диагностической пробы."
                            )
                        hasher.update(buffer, 0, read)
                    }
                }
            } finally {
                if (originalFormat !== opened) opened.close()
            }
            "$summary Оригинал прочитан локально; SHA-256: ${hasher.hexDigest()}. Ничего не отправлено и не удалено."
        }
}
