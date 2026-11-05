package app.lanvault.android

import android.app.Application
import android.database.ContentObserver
import android.net.*
import android.os.Handler
import android.os.Looper
import app.lanvault.core.sync.*
import kotlinx.coroutines.*

class LanVaultApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    private fun changed() {
        pending?.cancel()
        pending = scope.launch {
            delay(3000)
            SyncScheduler.request(this@LanVaultApplication)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.ensure(this)
        val manager = getSystemService(ConnectivityManager::class.java)
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    changed()
                }

                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    changed()
                }
            },
        )
        runCatching {
            contentResolver.registerContentObserver(
                Uri.parse("content://media"),
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        changed()
                    }
                },
            )
        }
    }
}
