package app.lanvault.core.transport

import app.lanvault.core.model.*
import java.security.cert.CertificateFactory
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class TrustTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val publicCa =
        checkNotNull(javaClass.getResourceAsStream("/test-ca.der")).use { it.readBytes() }
    private val certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(publicCa.inputStream())
    private val pin = digestHex(certificate.publicKey.encoded)

    private fun qr() =
        TrustEnvelope(
            1,
            id,
            "$id.lanvault.invalid",
            Base64.getEncoder().encodeToString(publicCa),
            pin,
            id,
            randomClaim(),
            System.currentTimeMillis() / 1000 + 120,
            listOf("192.168.10.3"),
            43827,
        )

    private fun reject(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (e: ClientFailure) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun acceptsOnlyLiteralRfc1918() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.2", "192.168.5.7").forEach {
            assertEquals(it, privateAddress(it).hostAddress)
        }
        listOf("127.0.0.1", "8.8.8.8", "100.64.0.1", "169.254.1.2", "224.0.0.1", "172.32.0.1")
            .forEach { reject("PRIVATE_IPV4_REQUIRED") { privateAddress(it) } }
    }

    @Test
    fun rejectsDnsUrlsAndAmbiguousAddressSpellings() {
        listOf(
                "localhost",
                "192.168.01.2",
                "192.168.1.2:80",
                "192.168.1.2/24",
                "https://192.168.1.2",
                "0xc0.168.1.2",
                "192.168.1.999",
            )
            .forEach { reject("INVALID_LAN_ADDRESS") { privateAddress(it) } }
    }

    @Test
    fun validQrChecksCaPinAndExpiry() {
        assertEquals(id, parseEnvelope(WireJson.encodeToString(qr())).serverId)
    }

    @Test
    fun expiredQrCannotStartPairing() {
        reject("PAIRING_EXPIRED") {
            parseEnvelope(WireJson.encodeToString(qr().copy(expiresAt = 1)))
        }
    }

    @Test
    fun farFutureQrCannotExtendPairingLifetime() {
        reject("PAIRING_EXPIRED") {
            parseEnvelope(
                WireJson.encodeToString(
                    qr().copy(expiresAt = System.currentTimeMillis() / 1000 + 900)
                )
            )
        }
    }

    @Test
    fun unsupportedVersionCannotConnect() {
        reject("UNSUPPORTED_PROTOCOL") {
            parseEnvelope(WireJson.encodeToString(qr().copy(protocolVersion = 2)))
        }
    }

    @Test
    fun hostnameIsBoundToServerUuid() {
        reject("INVALID_SERVER_IDENTITY") {
            parseEnvelope(WireJson.encodeToString(qr().copy(hostname = "example.org")))
        }
    }

    @Test
    fun wrongCaPinIsRejected() {
        reject("CA_PIN_MISMATCH") {
            parseEnvelope(WireJson.encodeToString(qr().copy(spkiSha256 = "0".repeat(64))))
        }
    }

    @Test
    fun invalidCertificateIsRejected() {
        reject("INVALID_CA") {
            parseEnvelope(WireJson.encodeToString(qr().copy(caCertificate = "A".repeat(80))))
        }
    }

    @Test
    fun oversizedQrIsRejectedBeforeParsing() {
        reject("QR_TOO_LARGE") { parseEnvelope("x".repeat(32769)) }
    }

    @Test
    fun randomClaimsHave256BitsAndAreDifferent() {
        val a = randomClaim()
        assertEquals(32, Base64.getUrlDecoder().decode(a).size)
        assertNotEquals(a, randomClaim())
    }

    @Test
    fun diagnosticStringsRedactCredentials() {
        val value = qr()
        assertFalse(value.toString().contains(value.secret))
        val trusted =
            TrustedServer(
                id,
                value.hostname,
                value.caCertificate,
                pin,
                value.addresses[0],
                value.port,
                id,
                value.secret,
            )
        assertFalse(trusted.toString().contains(value.secret))
        assertFalse(
            TrustState(trusted, PendingPairing(value, value.secret))
                .toString()
                .contains(value.secret)
        )
    }

    private fun descriptor() =
        UploadDescriptor(
            id,
            id,
            id,
            id,
            "test.heic",
            "4294967297",
            "a".repeat(64),
            MediaType.photo,
            "image/heic",
        )

    private fun trusted() =
        qr().let {
            TrustedServer(
                id,
                it.hostname,
                it.caCertificate,
                pin,
                it.addresses[0],
                it.port,
                id,
                randomClaim(),
            )
        }

    private fun receipt() =
        BackupReceipt(
            id,
            id,
            id,
            id,
            id,
            id,
            "a".repeat(64),
            "4294967297",
            "$id/Photos/2026/11/original.heic",
            "2026-11-04T00:00:00Z",
        )

    @Test
    fun validReceiptPreservesLargeByteCount() {
        validateReceipt(receipt(), descriptor(), trusted())
        assertEquals("4294967297", receipt().size)
    }

    @Test
    fun receiptMustMatchAllIdentityAndContentFields() {
        val receipt = receipt()
        val wrong = "22222222-2222-4222-8222-222222222222"
        listOf(
                receipt.copy(serverId = wrong),
                receipt.copy(deviceId = wrong),
                receipt.copy(datasetId = wrong),
                receipt.copy(mediaId = wrong),
                receipt.copy(revisionId = wrong),
                receipt.copy(sha256 = "b".repeat(64)),
                receipt.copy(size = "1"),
            )
            .forEach { bad ->
                reject("INVALID_RECEIPT") { validateReceipt(bad, descriptor(), trusted()) }
            }
    }

    @Test
    fun receiptCannotContainTraversalOrInvalidDate() {
        listOf("../outside", "/absolute", "safe/../outside", "C:\\outside", "safe//empty")
            .forEach { path ->
                reject("INVALID_RECEIPT") {
                    validateReceipt(receipt().copy(relativePath = path), descriptor(), trusted())
                }
            }
        reject("INVALID_RECEIPT") {
            validateReceipt(receipt().copy(committedAt = "yesterday"), descriptor(), trusted())
        }
    }

    @Test
    fun livePolicyGateRunsBeforeCreatingAnySocket() = runBlocking {
        val value = qr()
        var sockets = 0
        val factory =
            object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket {
                    sockets++
                    throw AssertionError("socket created")
                }

                override fun createSocket(h: String, p: Int) = createSocket()

                override fun createSocket(h: String, p: Int, l: java.net.InetAddress, lp: Int) =
                    createSocket()

                override fun createSocket(h: java.net.InetAddress, p: Int) = createSocket()

                override fun createSocket(
                    h: java.net.InetAddress,
                    p: Int,
                    l: java.net.InetAddress,
                    lp: Int,
                ) = createSocket()
            }
        DesktopClient(
                id,
                value.hostname,
                value.caCertificate,
                pin,
                value.addresses[0],
                value.port,
                factory,
                { throw ClientFailure("WIFI_LOST") },
            )
            .use { client ->
                try {
                    client.currentDataset()
                    fail("policy gate ignored")
                } catch (e: ClientFailure) {
                    assertEquals("WIFI_LOST", e.code)
                }
            }
        assertEquals(0, sockets)
    }
}
