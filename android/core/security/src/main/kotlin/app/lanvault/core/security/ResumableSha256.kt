package app.lanvault.core.security

import org.bouncycastle.crypto.digests.SHA256Digest

data class HashCheckpoint(
    val bytesHashed: Long,
    val encodedState: ByteArray,
    val formatVersion: Int = 1,
    val implementationVersion: String = "bc-1.85",
)

/** Checkpoints are internal state and must never be accepted from the network. */
class ResumableSha256
private constructor(
    private val digest: SHA256Digest,
    private var count: Long,
) {
    constructor() : this(SHA256Digest(), 0)

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        val nextCount = Math.addExact(count, length.toLong())
        digest.update(bytes, offset, length)
        count = nextCount
    }

    fun checkpoint(): HashCheckpoint = HashCheckpoint(count, digest.encodedState.copyOf())

    /** Finishes a copy, leaving this instance available for further checkpoints. */
    fun hexDigest(): String {
        val copy = SHA256Digest(digest)
        val result = ByteArray(copy.digestSize)
        copy.doFinal(result, 0)
        return result.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        fun restore(checkpoint: HashCheckpoint): ResumableSha256 {
            require(checkpoint.formatVersion == 1 && checkpoint.implementationVersion == "bc-1.85")
            require(checkpoint.bytesHashed >= 0 && checkpoint.encodedState.size in 53..128)
            return ResumableSha256(
                SHA256Digest(checkpoint.encodedState.copyOf()),
                checkpoint.bytesHashed,
            )
        }
    }
}
