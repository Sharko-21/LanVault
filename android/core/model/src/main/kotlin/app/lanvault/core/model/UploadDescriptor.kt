package app.lanvault.core.model

import java.time.OffsetDateTime
import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION: Int = 1
const val DEFAULT_CHUNK_BYTES: Long = 8L * 1024 * 1024

@Serializable
enum class MediaType {
    photo,
    video,
}

@Serializable
data class UploadDescriptor(
    val datasetId: String,
    val mediaId: String,
    val revisionId: String,
    val uploadRequestId: String,
    val originalName: String,
    val size: String,
    val sha256: String,
    val mediaType: MediaType,
    val mimeType: String,
    val capturedAt: String? = null,
    val modifiedAt: String? = null,
    val album: String? = null,
) {
    /** Metadata validation does not authorize a filesystem destination. */
    fun validatedSize(): Long {
        val uuid =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        require(listOf(datasetId, mediaId, revisionId, uploadRequestId).all(uuid::matches))
        require(originalName.codePointCount(0, originalName.length) in 1..255)
        require(album == null || album.codePointCount(0, album.length) <= 255)
        require(Regex("^[a-f0-9]{64}$").matches(sha256))
        require(
            mimeType.length <= 127 &&
                Regex("^[a-z0-9!#\u0024&^_.+-]+/[a-z0-9!#\u0024&^_.+-]+$").matches(mimeType)
        )
        listOfNotNull(capturedAt, modifiedAt).forEach { OffsetDateTime.parse(it) }
        return parsePositiveByteCount(size)
    }
}

fun parsePositiveByteCount(value: String): Long {
    require(Regex("^[1-9][0-9]{0,18}$").matches(value))
    return requireNotNull(value.toLongOrNull())
}
