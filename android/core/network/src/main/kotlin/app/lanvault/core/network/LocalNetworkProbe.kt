package app.lanvault.core.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
const val SERVICE_TYPE = "_lanvault._tcp."

class LocalNetworkProbe(private val context: Context) {
    suspend fun run(): String {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) ==
                PackageManager.PERMISSION_GRANTED
        )
        if (
            Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Разрешите доступ к локальной сети.")
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi =
            connectivity.allNetworks.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } ?: return "Wi-Fi не подключён. Сканирование не запускалось."
        val internetValidated =
            connectivity
                .getNetworkCapabilities(wifi)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val services = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= 33)
            withTimeoutOrNull(5000) { discoveries(wifi).collect { services += it } }
        return "Wi-Fi доступен. Интернет: ${if (internetValidated) "подтверждён" else "не требуется / не подтверждён"}. Найдено объявлений LanVault: ${services.size}. Подключения к серверам не выполнялись."
    }

    @androidx.annotation.RequiresApi(33)
    private fun discoveries(network: Network) =
        callbackFlow<String> {
            val manager = context.getSystemService(NsdManager::class.java)
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) = Unit

                    override fun onDiscoveryStopped(type: String) = Unit

                    override fun onServiceFound(service: NsdServiceInfo) {
                        trySend(service.serviceName)
                    }

                    override fun onServiceLost(service: NsdServiceInfo) = Unit

                    override fun onStartDiscoveryFailed(type: String, code: Int) {
                        close(IOException("NSD discovery failed: $code"))
                    }

                    override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                }
            manager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                network,
                java.util.concurrent.Executor { it.run() },
                listener,
            )
            awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
        }
}
