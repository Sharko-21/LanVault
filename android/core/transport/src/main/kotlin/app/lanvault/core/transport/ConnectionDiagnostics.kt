package app.lanvault.core.transport

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake

/** Per-call phases distinguish an unreachable PC from a TLS or HTTP timeout. */
internal class ConnectionDiagnostics : EventListener() {
    private enum class Phase {
        TCP,
        TLS,
        RESPONSE,
    }

    private val phase = AtomicReference(Phase.TCP)

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        phase.set(Phase.TCP)
    }

    override fun secureConnectStart(call: Call) {
        phase.set(Phase.TLS)
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        phase.set(Phase.RESPONSE)
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        phase.set(Phase.RESPONSE)
    }

    fun failure(error: IOException): IOException {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        causes.filterIsInstance<ClientFailure>().firstOrNull()?.let {
            return it
        }
        // Preserve TLS exception types; callers must still fail certificate checks closed.
        if (causes.any { it is SSLException }) return error
        val timeout = causes.any { it is SocketTimeoutException }
        val code =
            when (phase.get()) {
                Phase.TCP ->
                    when {
                        timeout -> "TCP_CONNECTION_TIMEOUT"
                        causes.any { it is ConnectException } -> "TCP_CONNECTION_FAILED"
                        else -> "TCP_NETWORK_FAILED"
                    }
                Phase.TLS -> if (timeout) "TLS_HANDSHAKE_TIMEOUT" else "TLS_CONNECTION_CLOSED"
                Phase.RESPONSE ->
                    if (timeout) "DESKTOP_RESPONSE_TIMEOUT" else "DESKTOP_CONNECTION_LOST"
            }
        return ClientFailure(code, cause = error)
    }
}
