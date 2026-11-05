package app.lanvault.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TrustEnvelope(
    val protocolVersion: Int,
    val serverId: String,
    val hostname: String,
    val caCertificate: String,
    val spkiSha256: String,
    val pairingId: String,
    val secret: String,
    val expiresAt: Long,
    val addresses: List<String>,
    val port: Int,
) {
    override fun toString() = "TrustEnvelope($serverId, secret=redacted)"
}

@Serializable
data class TrustedServer(
    val serverId: String,
    val hostname: String,
    val caCertificate: String,
    val spkiSha256: String,
    val address: String,
    val port: Int,
    val deviceId: String,
    val token: String,
) {
    override fun toString() = "TrustedServer($serverId, token=redacted)"
}

@Serializable
data class PendingPairing(
    val envelope: TrustEnvelope,
    val claim: String,
    val requestId: String? = null,
) {
    override fun toString() = "PendingPairing(${envelope.serverId}, secrets=redacted)"
}

@Serializable
data class TrustState(val trusted: TrustedServer? = null, val pending: PendingPairing? = null)

interface TrustStore {
    fun read(): TrustState

    fun write(state: TrustState)
}

@Serializable
data class BackupReceipt(
    val receiptId: String,
    val serverId: String,
    val datasetId: String,
    val deviceId: String,
    val mediaId: String,
    val revisionId: String,
    val sha256: String,
    val size: String,
    val relativePath: String,
    val committedAt: String,
)

@Serializable
data class UploadResult(
    val state: String,
    val uploadId: String? = null,
    val receipt: BackupReceipt? = null,
)

@Serializable
data class MediaItem(
    val uri: String,
    val name: String,
    val size: Long,
    val type: MediaType,
    val mimeType: String,
    val version: String,
    val volumeVersion: String,
    val capturedAt: String? = null,
    val modifiedAt: String? = null,
    val album: String? = null,
)

@Serializable
data class TransferRecord(
    val id: String,
    val serverId: String,
    val sourceUri: String,
    val sourceVersion: String,
    val descriptor: UploadDescriptor,
    val state: String = "PREPARED",
    val uploadId: String? = null,
    val receipt: BackupReceipt? = null,
    val errorCode: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

interface TransferJournal {
    suspend fun committed(
        serverId: String,
        datasetId: String,
        uri: String,
        version: String,
    ): TransferRecord? = null

    suspend fun find(
        serverId: String,
        datasetId: String,
        uri: String,
        version: String,
        hash: String,
    ): TransferRecord?

    suspend fun put(record: TransferRecord)
}

data class TransferProgress(
    val name: String,
    val stage: String,
    val bytes: Long,
    val size: Long,
    val completed: Int,
    val total: Int,
    val bytesPerSecond: Long = 0,
)

data class LibraryBatch<C>(val items: List<MediaItem>, val next: C?)

data class LibraryProgress(
    val scanned: Int = 0,
    val saved: Int = 0,
    val skipped: Int = 0,
    val unavailable: Int = 0,
    val current: TransferProgress? = null,
    val activeTransfers: List<TransferProgress> = emptyList(),
    val reconnecting: Boolean = false,
)
