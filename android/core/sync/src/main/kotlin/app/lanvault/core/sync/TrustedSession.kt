package app.lanvault.core.sync

import android.content.Context
import app.lanvault.core.model.*
import app.lanvault.core.network.*
import app.lanvault.core.transport.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class TrustedSession(
    val trusted: TrustedServer,
    val client: DesktopClient,
    private val wifi: WifiConnection,
) : AutoCloseable {
    override fun close() {
        client.close()
        wifi.close()
    }

    companion object {
        suspend fun open(
            context: Context,
            store: TrustStore,
            forceDiscovery: Boolean = false,
        ): TrustedSession {
            val saved = store.read().trusted ?: throw ClientFailure("NOT_PAIRED")
            suspend fun attempt(server: TrustedServer): TrustedSession {
                val wifi = WifiConnection.open(context, server.address)
                var client: DesktopClient? = null
                try {
                    client = wifi.trusted(server)
                    client.currentDataset()
                    PairingWorkflow(store).confirmSaved(client)
                    if (server != saved) store.write(store.read().copy(trusted = server))
                    return TrustedSession(server, client, wifi)
                } catch (e: Exception) {
                    client?.close()
                    wifi.close()
                    throw e
                }
            }
            val first =
                try {
                    return attempt(saved)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (
                        e is ClientFailure &&
                            e.code in
                                listOf(
                                    "LOCAL_NETWORK_PERMISSION_REQUIRED",
                                    "SERVER_PAUSED",
                                    "UNAUTHORIZED",
                                )
                    )
                        throw e
                    e
                }
            for (endpoint in TrustedDiscovery(context).find(saved, forceDiscovery)) {
                if (endpoint.address == saved.address && endpoint.port == saved.port) continue
                currentCoroutineContext().ensureActive()
                try {
                    return attempt(saved.copy(address = endpoint.address, port = endpoint.port))
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                }
            }
            throw first
        }
    }
}
