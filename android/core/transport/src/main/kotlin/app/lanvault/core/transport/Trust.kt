package app.lanvault.core.transport

import app.lanvault.core.model.*
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.OffsetDateTime
import java.util.Base64
import kotlinx.serialization.json.Json

val WireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

class ClientFailure(val code: String, val httpStatus: Int? = null, cause: Throwable? = null) :
    IOException(code, cause)

fun failUnless(condition: Boolean, code: String) {
    if (!condition) throw ClientFailure(code)
}

fun canonicalUuid(value: String): Boolean =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)

fun digestHex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 255)
    }

fun randomClaim(): String =
    ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

fun privateAddress(value: String): InetAddress {
    val parts = value.split('.')
    failUnless(
        parts.size == 4 &&
            parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 },
        "INVALID_LAN_ADDRESS",
    )
    val bytes = parts.map(String::toInt)
    failUnless(
        bytes[0] == 10 ||
            bytes[0] == 172 && bytes[1] in 16..31 ||
            bytes[0] == 192 && bytes[1] == 168,
        "PRIVATE_IPV4_REQUIRED",
    )
    return InetAddress.getByAddress(bytes.map(Int::toByte).toByteArray())
}

fun verifiedCa(
    serverId: String,
    hostname: String,
    encoded: String,
    fingerprint: String,
): X509Certificate {
    failUnless(
        canonicalUuid(serverId) && hostname == "$serverId.lanvault.invalid",
        "INVALID_SERVER_IDENTITY",
    )
    failUnless(
        encoded.length in 64..16384 && fingerprint.matches(Regex("[0-9a-f]{64}")),
        "INVALID_CA",
    )
    val bytes =
        try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw ClientFailure("INVALID_CA", cause = e)
        }
    val certificate =
        try {
            CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream())
                as X509Certificate
        } catch (e: Exception) {
            throw ClientFailure("INVALID_CA", cause = e)
        }
    try {
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
    } catch (e: Exception) {
        throw ClientFailure("INVALID_CA", cause = e)
    }
    failUnless(
        certificate.basicConstraints >= 0 && certificate.keyUsage?.getOrNull(5) == true,
        "INVALID_CA",
    )
    failUnless(
        MessageDigest.isEqual(
            digestHex(certificate.publicKey.encoded).toByteArray(),
            fingerprint.toByteArray(),
        ),
        "CA_PIN_MISMATCH",
    )
    return certificate
}

fun parseEnvelope(raw: String, now: Long = System.currentTimeMillis() / 1000): TrustEnvelope {
    failUnless(raw.toByteArray().size <= 32768, "QR_TOO_LARGE")
    val value =
        try {
            WireJson.decodeFromString<TrustEnvelope>(raw)
        } catch (e: Exception) {
            throw ClientFailure("INVALID_QR", cause = e)
        }
    failUnless(
        value.protocolVersion == PROTOCOL_VERSION && canonicalUuid(value.pairingId),
        "UNSUPPORTED_PROTOCOL",
    )
    failUnless(value.expiresAt > now && value.expiresAt <= now + 300, "PAIRING_EXPIRED")
    failUnless(value.port in 1024..65535 && value.addresses.size in 1..4, "INVALID_QR")
    value.addresses.forEach(::privateAddress)
    failUnless(
        runCatching { Base64.getUrlDecoder().decode(value.secret).size == 32 }.getOrDefault(false),
        "INVALID_QR",
    )
    verifiedCa(value.serverId, value.hostname, value.caCertificate, value.spkiSha256)
    return value
}

fun validateReceipt(receipt: BackupReceipt, expected: UploadDescriptor, trusted: TrustedServer) {
    failUnless(
        canonicalUuid(receipt.receiptId) &&
            receipt.serverId == trusted.serverId &&
            receipt.deviceId == trusted.deviceId &&
            receipt.datasetId == expected.datasetId &&
            receipt.mediaId == expected.mediaId &&
            receipt.revisionId == expected.revisionId &&
            receipt.sha256 == expected.sha256 &&
            receipt.size == expected.size,
        "INVALID_RECEIPT",
    )
    failUnless(
        receipt.relativePath.length in 1..1024 &&
            !receipt.relativePath.startsWith('/') &&
            receipt.relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            receipt.relativePath.none { it.isISOControl() || it == '\\' || it == ':' },
        "INVALID_RECEIPT",
    )
    try {
        OffsetDateTime.parse(receipt.committedAt)
    } catch (e: Exception) {
        throw ClientFailure("INVALID_RECEIPT", cause = e)
    }
}
