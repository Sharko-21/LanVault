package app.lanvault.core.transport

import app.lanvault.core.model.*
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PairingWorkflow(private val store: TrustStore) {
    suspend fun connect(
        envelope: TrustEnvelope,
        client: DesktopClient,
        name: String,
        onCode: (String) -> Unit,
    ): TrustedServer =
        withContext(Dispatchers.IO) {
            val before = store.read()
            failUnless(before.trusted == null, "ALREADY_PAIRED")
            val pending =
                before.pending?.takeIf { it.envelope.pairingId == envelope.pairingId }
                    ?: PendingPairing(envelope, randomClaim())
            store.write(before.copy(pending = pending))
            val requestId =
                pending.requestId
                    ?: run {
                        parseEnvelope(WireJson.encodeToString(TrustEnvelope.serializer(), envelope))
                        val request = client.pair(envelope, pending.claim, name)
                        store.write(
                            store.read().copy(pending = pending.copy(requestId = request.requestId))
                        )
                        request.requestId
                    }
            failUnless(canonicalUuid(requestId), "INVALID_PAIRING_RESPONSE")
            onCode(digestHex(requestId.toByteArray()).take(6).uppercase())
            val deadline = envelope.expiresAt * 1000 + if (pending.requestId != null) 300000 else 0
            val remaining = (deadline - System.currentTimeMillis()).coerceAtMost(120000)
            failUnless(remaining > 0, "PAIRING_EXPIRED")
            withTimeout(remaining) {
                while (true) {
                    when (val result = client.poll(requestId, pending.claim)) {
                        else ->
                            when (result.state) {
                                "pending" -> delay(1500)
                                "approved" -> {
                                    val credentials =
                                        result.credentials
                                            ?: throw ClientFailure("INVALID_CREDENTIALS")
                                    failUnless(
                                        credentials.serverId == envelope.serverId &&
                                            canonicalUuid(credentials.deviceId) &&
                                            runCatching {
                                                    Base64.getUrlDecoder()
                                                        .decode(credentials.token)
                                                        .size == 32
                                                }
                                                .getOrDefault(false),
                                        "INVALID_CREDENTIALS",
                                    )
                                    val trusted =
                                        TrustedServer(
                                            envelope.serverId,
                                            envelope.hostname,
                                            envelope.caCertificate,
                                            envelope.spkiSha256,
                                            envelope.addresses.first(),
                                            envelope.port,
                                            credentials.deviceId,
                                            credentials.token,
                                        )
                                    // Persist ciphertext durably before acknowledging receipt of
                                    // credentials.
                                    store.write(
                                        TrustState(trusted, pending.copy(requestId = requestId))
                                    )
                                    client.confirm(requestId, pending.claim)
                                    store.write(TrustState(trusted))
                                    return@withTimeout trusted
                                }
                                "rejected" -> throw ClientFailure("PAIRING_REJECTED")
                                else -> throw ClientFailure("PAIRING_EXPIRED")
                            }
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        }

    suspend fun confirmSaved(client: DesktopClient) =
        withContext(Dispatchers.IO) {
            val state = store.read()
            val pending = state.pending
            if (state.trusted != null && pending?.requestId != null) {
                client.confirm(requireNotNull(pending.requestId), pending.claim)
                store.write(state.copy(pending = null))
            }
        }
}
