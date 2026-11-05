package app.lanvault.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import app.lanvault.core.model.TrustedServer
import app.lanvault.core.transport.lanCandidates
import app.lanvault.core.transport.privateAddress
import java.net.Inet4Address
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveredEndpoint(val address: String, val port: Int)

/** Discovery is a hint, never a trust grant. Every endpoint still passes Wi-Fi and pinned TLS. */
class TrustedDiscovery(private val context: Context) {
    suspend fun find(
        server: TrustedServer,
        forceSubnetProbe: Boolean = false,
    ): List<DiscoveredEndpoint> {
        val discovered = findMdns(server)
        if (discovered.isNotEmpty()) return discovered
        return probeSubnet(server, forceSubnetProbe)
    }

    private suspend fun probeSubnet(
        server: TrustedServer,
        force: Boolean,
    ): List<DiscoveredEndpoint> =
        withContext(Dispatchers.IO) {
            val preferences =
                context.getSharedPreferences("lanvault-discovery", Context.MODE_PRIVATE)
            val key = "probe-${server.serverId}"
            val now = System.currentTimeMillis()
            if (!force && now - preferences.getLong(key, 0) in 0 until 900_000L)
                return@withContext emptyList()
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val addresses =
                manager.allNetworks
                    .flatMap { network ->
                        val caps = manager.getNetworkCapabilities(network)
                        if (
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        )
                            return@flatMap emptyList()
                        manager
                            .getLinkProperties(network)
                            ?.linkAddresses
                            .orEmpty()
                            .filter { it.address is Inet4Address }
                            .flatMap { link ->
                                runCatching {
                                        lanCandidates(link.address.hostAddress!!, link.prefixLength)
                                    }
                                    .getOrDefault(emptyList())
                            }
                    }
                    .distinct()
                    .take(253)
            if (addresses.isEmpty()) return@withContext emptyList()
            preferences.edit().putLong(key, now).apply()
            val index = AtomicInteger(0)
            val found = java.util.Collections.synchronizedList(mutableListOf<DiscoveredEndpoint>())
            coroutineScope {
                repeat(16) {
                    launch {
                        while (isActive && found.size < 8) {
                            val address = addresses.getOrNull(index.getAndIncrement()) ?: break
                            if (address == server.address) continue
                            try {
                                WifiConnection.open(context, address).use { wifi ->
                                    if (wifi.probe(server.port))
                                        found.add(DiscoveredEndpoint(address, server.port))
                                }
                            } catch (_: Exception) {
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    }
                }
            }
            found.toList().take(8)
        }

    private suspend fun findMdns(server: TrustedServer): List<DiscoveredEndpoint> {
        // The network-specific NSD API is available from API 33. Older Android uses the saved
        // endpoint; do not fall back to system-wide discovery through an arbitrary VPN network.
        if (Build.VERSION.SDK_INT < 33) return emptyList()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi =
            connectivity.allNetworks.firstOrNull {
                val caps = connectivity.getNetworkCapabilities(it)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } ?: return emptyList()
        return withTimeoutOrNull(8000) {
            callbackFlow<List<DiscoveredEndpoint>> {
                    val manager = context.getSystemService(NsdManager::class.java)
                    val resolving = AtomicBoolean(false)
                    val direct = Executor { it.run() }
                    val listener =
                        object : NsdManager.DiscoveryListener {
                            override fun onDiscoveryStarted(type: String) = Unit

                            override fun onDiscoveryStopped(type: String) = Unit

                            override fun onServiceLost(service: NsdServiceInfo) = Unit

                            override fun onStartDiscoveryFailed(type: String, code: Int) {
                                close()
                            }

                            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit

                            override fun onServiceFound(service: NsdServiceInfo) {
                                if (
                                    !service.serviceName.startsWith(
                                        "LanVault-${server.serverId}"
                                    ) || !resolving.compareAndSet(false, true)
                                )
                                    return
                                try {
                                    manager.resolveService(
                                        service,
                                        object : NsdManager.ResolveListener {
                                            override fun onResolveFailed(
                                                info: NsdServiceInfo,
                                                code: Int,
                                            ) {
                                                resolving.set(false)
                                            }

                                            override fun onServiceResolved(info: NsdServiceInfo) {
                                                resolving.set(false)
                                                if (
                                                    info.attributes["serverId"]?.toString(
                                                        Charsets.UTF_8
                                                    ) != server.serverId ||
                                                        info.attributes["protocolVersion"]
                                                            ?.toString(Charsets.UTF_8) != "1" ||
                                                        info.port !in 1024..65535
                                                )
                                                    return
                                                val addresses =
                                                    if (Build.VERSION.SDK_INT >= 34)
                                                        info.hostAddresses
                                                    else listOfNotNull(info.host)
                                                val endpoints =
                                                    addresses
                                                        .mapNotNull { host ->
                                                            val value =
                                                                host.hostAddress
                                                                    ?: return@mapNotNull null
                                                            if (
                                                                runCatching {
                                                                    privateAddress(value)
                                                                }
                                                                    .isFailure
                                                            )
                                                                null
                                                            else
                                                                DiscoveredEndpoint(value, info.port)
                                                        }
                                                        .distinct()
                                                        .take(4)
                                                if (endpoints.isNotEmpty()) trySend(endpoints)
                                            }
                                        },
                                    )
                                } catch (_: Exception) {
                                    resolving.set(false)
                                }
                            }
                        }
                    try {
                        manager.discoverServices(
                            SERVICE_TYPE,
                            NsdManager.PROTOCOL_DNS_SD,
                            wifi,
                            direct,
                            listener,
                        )
                    } catch (_: Exception) {
                        close()
                    }
                    awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
                }
                .firstOrNull()
        } ?: emptyList()
    }
}
