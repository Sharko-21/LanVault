package app.lanvault.core.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import androidx.core.content.ContextCompat
import app.lanvault.core.model.*
import app.lanvault.core.transport.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/** Each connection belongs to one existing Wi-Fi Network, including LANs without WAN. */
class WifiConnection
private constructor(
    private val context: Context,
    private val manager: ConnectivityManager,
    private val network: Network,
    private val peer: InetAddress,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    @Volatile private var cancel: () -> Unit = {}
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (lost == network) invalidate()
            }

            override fun onCapabilitiesChanged(
                changed: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (changed == network && !directWifi(capabilities)) invalidate()
            }

            override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
                if (changed == network && !sameSubnet(properties, peer)) invalidate()
            }
        }

    init {
        guard()
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            callback,
        )
    }

    private fun invalidate() {
        closed.set(true)
        cancel()
    }

    // Bound sockets cannot migrate to another network. Callbacks invalidate the
    // session on transport/subnet loss; retain the permission check per chunk.
    private fun guardContent() {
        failUnless(!closed.get(), "WIFI_LOST")
        if (
            Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED
        )
            throw ClientFailure("LOCAL_NETWORK_PERMISSION_REQUIRED")
    }

    fun guard() {
        guardContent()
        val capabilities = manager.getNetworkCapabilities(network)
        failUnless(directWifi(capabilities), "WIFI_REQUIRED")
        failUnless(sameSubnet(manager.getLinkProperties(network), peer), "DIRECT_LAN_REQUIRED")
    }

    private val sockets =
        object : SocketFactory() {
            override fun createSocket(): Socket {
                guard()
                return network.socketFactory.createSocket()
            }

            private fun checkPeer(address: InetAddress) {
                guard()
                failUnless(address == peer, "ADDRESS_NOT_ALLOWED")
            }

            override fun createSocket(host: String, port: Int): Socket =
                createSocket(privateAddress(host), port)

            override fun createSocket(
                host: String,
                port: Int,
                local: InetAddress,
                localPort: Int,
            ): Socket = createSocket(privateAddress(host), port, local, localPort)

            override fun createSocket(address: InetAddress, port: Int): Socket {
                checkPeer(address)
                return network.socketFactory.createSocket(address, port)
            }

            override fun createSocket(
                address: InetAddress,
                port: Int,
                local: InetAddress,
                localPort: Int,
            ): Socket {
                checkPeer(address)
                return network.socketFactory.createSocket(address, port, local, localPort)
            }
        }

    fun probe(port: Int): Boolean {
        failUnless(port in 1024..65535, "INVALID_PORT")
        sockets.createSocket().use { socket ->
            socket.connect(InetSocketAddress(peer, port), 450)
            guard()
            return true
        }
    }

    fun pairing(envelope: TrustEnvelope): DesktopClient =
        DesktopClient(
                envelope.serverId,
                envelope.hostname,
                envelope.caCertificate,
                envelope.spkiSha256,
                peer.hostAddress!!,
                envelope.port,
                sockets,
                ::guard,
                chunkGuard = ::guardContent,
            )
            .also { client ->
                cancel = client::close
                guard()
            }

    fun trusted(server: TrustedServer): DesktopClient =
        DesktopClient(
                server.serverId,
                server.hostname,
                server.caCertificate,
                server.spkiSha256,
                peer.hostAddress!!,
                server.port,
                sockets,
                ::guard,
                server.token,
                ::guardContent,
            )
            .also { client ->
                cancel = client::close
                guard()
            }

    override fun close() {
        closed.set(true)
        cancel()
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    companion object {
        private fun directWifi(capabilities: NetworkCapabilities?): Boolean =
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        fun open(context: Context, address: String): WifiConnection {
            if (
                Build.VERSION.SDK_INT >= 37 &&
                    ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) !=
                        PackageManager.PERMISSION_GRANTED
            )
                throw ClientFailure("LOCAL_NETWORK_PERMISSION_REQUIRED")
            val peer = privateAddress(address)
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network =
                manager.allNetworks.firstOrNull { candidate ->
                    val caps = manager.getNetworkCapabilities(candidate)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        sameSubnet(manager.getLinkProperties(candidate), peer)
                } ?: throw ClientFailure("WIFI_REQUIRED")
            return WifiConnection(context.applicationContext, manager, network, peer)
        }

        private fun sameSubnet(properties: LinkProperties?, peer: InetAddress): Boolean {
            if (peer !is Inet4Address) return false
            val remote = peer.address.fold(0L) { value, b -> (value shl 8) or (b.toLong() and 255) }
            return properties?.linkAddresses?.any { link ->
                if (link.address !is Inet4Address || link.prefixLength !in 8..30) return@any false
                val local =
                    link.address.address.fold(0L) { value, b ->
                        (value shl 8) or (b.toLong() and 255)
                    }
                val mask = (0xffffffffL shl (32 - link.prefixLength)) and 0xffffffffL
                val network = local and mask
                val broadcast = network or (mask xor 0xffffffffL)
                remote and mask == network && remote != network && remote != broadcast
            } == true
        }
    }
}
