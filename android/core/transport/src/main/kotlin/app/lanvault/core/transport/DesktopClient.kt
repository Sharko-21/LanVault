package app.lanvault.core.transport

import app.lanvault.core.model.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** Android supplies a Wi-Fi-bound factory and a live policy gate. No process-wide binding. */
class DesktopClient(
    private val serverId: String,
    private val hostname: String,
    ca: String,
    pin: String,
    address: String,
    port: Int,
    socketFactory: SocketFactory,
    private val guard: () -> Unit,
    private val token: String? = null,
    private val chunkGuard: () -> Unit = guard,
    private val readTimeoutMillis: Long = 30_000,
    private val processingReadTimeoutMillis: Long = 600_000,
) : AutoCloseable {
    private val base: String
    private val client: OkHttpClient
    @Volatile
    var maxConcurrentUploads: Int = 2
        private set

    init {
        require(readTimeoutMillis > 0 && processingReadTimeoutMillis >= readTimeoutMillis)
        val ip = privateAddress(address)
        failUnless(port in 1024..65535, "INVALID_PORT")
        val certificate = verifiedCa(serverId, hostname, ca, pin)
        val store =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("desktop-ca", certificate)
            }
        val manager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(store) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val pinBase64 =
            Base64.getEncoder()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
                )
        base = "https://$hostname:$port"
        client =
            OkHttpClient.Builder()
                .socketFactory(socketFactory)
                .sslSocketFactory(ssl.socketFactory, manager)
                .dns(
                    object : Dns {
                        override fun lookup(queried: String): List<java.net.InetAddress> {
                            safeGuard()
                            failUnless(queried == hostname, "DNS_NOT_ALLOWED")
                            return listOf(ip)
                        }
                    }
                )
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .certificatePinner(
                    CertificatePinner.Builder().add(hostname, "sha256/$pinBase64").build()
                )
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    safeGuard()
                    chain.proceed(chain.request())
                }
                .build()
    }

    suspend fun pair(envelope: TrustEnvelope, claim: String, name: String): PairRequested {
        val payload = buildJsonObject {
            put("pairingId", envelope.pairingId)
            put("secret", envelope.secret)
            put("claimSecret", claim)
            put("clientName", name)
        }
        val result =
            WireJson.decodeFromJsonElement<PairRequested>(
                request("POST", "/v1/pairing/requests", jsonBody(payload))
            )
        failUnless(
            canonicalUuid(result.requestId) &&
                result.state == "pending" &&
                result.verificationCode ==
                    digestHex(result.requestId.toByteArray()).take(6).uppercase(),
            "INVALID_PAIRING_RESPONSE",
        )
        return result
    }

    suspend fun poll(id: String, claim: String): PairResult =
        WireJson.decodeFromJsonElement(
            request("GET", "/v1/pairing/requests/${checkedId(id)}", claim = claim)
        )

    suspend fun confirm(id: String, claim: String) {
        request(
            "POST",
            "/v1/pairing/requests/${checkedId(id)}/confirm-received",
            emptyBody(),
            claim,
        )
    }

    suspend fun currentDataset(): String {
        val info = request("GET", "/v1/server")
        failUnless(
            info["serverId"]?.jsonPrimitive?.content == serverId &&
                info["protocolVersion"]?.jsonPrimitive?.int == 1,
            "SERVER_IDENTITY_MISMATCH",
        )
        failUnless(info["paused"]?.jsonPrimitive?.boolean == false, "SERVER_PAUSED")
        maxConcurrentUploads = advertisedTransferCapacity(info)
        val dataset =
            request("GET", "/v1/datasets/current")["datasetId"]?.jsonPrimitive?.content.orEmpty()
        failUnless(canonicalUuid(dataset), "INVALID_DATASET")
        return dataset
    }

    suspend fun create(descriptor: UploadDescriptor): UploadResult {
        descriptor.validatedSize()
        return WireJson.decodeFromJsonElement(
            request(
                "POST",
                "/v1/uploads",
                WireJson.encodeToString(descriptor).toRequestBody(JSON),
                longRead = true,
            )
        )
    }

    suspend fun content(
        id: String,
        size: Long,
        expectedHash: String,
        open: () -> InputStream,
        checkCancelled: () -> Unit,
        progress: (Long) -> Unit,
    ): UploadResult {
        val body =
            object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun contentLength() = size

                override fun isOneShot() = true

                override fun writeTo(sink: BufferedSink) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    val buffer = ByteArray(128 * 1024)
                    try {
                        open().use { input ->
                            while (true) {
                                try {
                                    checkCancelled()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw java.io.IOException("TRANSFER_CANCELLED", e)
                                }
                                safeGuard(chunkGuard)
                                val read = input.read(buffer)
                                if (read < 0) break
                                bytes = Math.addExact(bytes, read.toLong())
                                failUnless(bytes <= size, "SOURCE_CHANGED")
                                digest.update(buffer, 0, read)
                                sink.write(buffer, 0, read)
                                progress(bytes)
                            }
                        }
                        failUnless(
                            bytes == size &&
                                digest.digest().joinToString("") {
                                    "%02x".format(it.toInt() and 255)
                                } == expectedHash,
                            "SOURCE_CHANGED",
                        )
                    } catch (e: java.io.IOException) {
                        throw e
                    } catch (e: Exception) {
                        throw ClientFailure("SOURCE_UNAVAILABLE", cause = e)
                    }
                }
            }
        return WireJson.decodeFromJsonElement(
            request("PUT", "/v1/uploads/${checkedId(id)}/content", body, longRead = true)
        )
    }

    suspend fun complete(id: String): UploadResult =
        WireJson.decodeFromJsonElement(
            request("POST", "/v1/uploads/${checkedId(id)}/complete", emptyBody(), longRead = true)
        )

    private fun safeGuard(check: () -> Unit = guard) {
        try {
            check()
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            throw ClientFailure("NETWORK_POLICY_UNAVAILABLE", cause = e)
        }
    }

    private fun checkedId(value: String): String {
        failUnless(canonicalUuid(value), "INVALID_UPLOAD_ID")
        return value
    }

    private fun emptyBody() = ByteArray(0).toRequestBody(null)

    private fun jsonBody(value: JsonObject) = value.toString().toRequestBody(JSON)

    private suspend fun request(
        method: String,
        path: String,
        body: RequestBody? = null,
        claim: String? = null,
        longRead: Boolean = false,
    ): JsonObject {
        safeGuard()
        val builder =
            Request.Builder()
                .url(base + path)
                .method(method, body)
                .header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        claim?.let { builder.header("X-Pairing-Claim", it) }
        val diagnostics = ConnectionDiagnostics()
        val transport =
            client
                .newBuilder()
                .eventListener(diagnostics)
                .apply {
                    if (longRead) readTimeout(processingReadTimeoutMillis, TimeUnit.MILLISECONDS)
                }
                .build()
        val call = transport.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        if (continuation.isActive)
                            continuation.resumeWithException(diagnostics.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use { current ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                current.body?.byteStream()?.use { input ->
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        failUnless(output.size() + n <= 65536, "RESPONSE_TOO_LARGE")
                                        output.write(buffer, 0, n)
                                    }
                                }
                                val parsed = runCatching {
                                    WireJson.parseToJsonElement(output.toString("UTF-8")).jsonObject
                                }
                                    .getOrNull()
                                if (current.code != 200) {
                                    val code =
                                        parsed?.get("code")?.jsonPrimitive?.content?.takeIf {
                                            it.matches(Regex("[A-Z_]{1,64}"))
                                        } ?: "HTTP_${current.code}"
                                    throw ClientFailure(code, current.code)
                                }
                                parsed ?: throw ClientFailure("INVALID_RESPONSE")
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive)
                                continuation.resumeWithException(
                                    if (e is java.io.IOException) diagnostics.failure(e) else e
                                )
                        }
                    }
                }
            )
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun advertisedTransferCapacity(info: JsonObject): Int =
    (((info["capabilities"] as? JsonObject)?.get("maxConcurrentUploads") as? JsonPrimitive)
            ?.intOrNull ?: 2)
        .coerceIn(1, MAX_TRANSFER_WORKERS)

@kotlinx.serialization.Serializable
data class PairRequested(val requestId: String, val verificationCode: String, val state: String)

@kotlinx.serialization.Serializable
data class DeviceCredentials(val serverId: String, val deviceId: String, val token: String) {
    override fun toString() = "DeviceCredentials(redacted)"
}

@kotlinx.serialization.Serializable
data class PairResult(val state: String, val credentials: DeviceCredentials? = null)
