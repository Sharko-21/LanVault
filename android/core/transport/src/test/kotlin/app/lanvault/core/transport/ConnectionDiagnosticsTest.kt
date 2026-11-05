package app.lanvault.core.transport

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class ConnectionDiagnosticsTest {
    private fun call() =
        OkHttpClient().newCall(Request.Builder().url("https://example.invalid").build())

    @Test
    fun distinguishesTcpTlsAndResponseTimeouts() {
        val trace = ConnectionDiagnostics()
        val error = SocketTimeoutException("synthetic-sensitive-message")
        assertEquals("TCP_CONNECTION_TIMEOUT", (trace.failure(error) as ClientFailure).code)
        trace.secureConnectStart(call())
        assertEquals("TLS_HANDSHAKE_TIMEOUT", (trace.failure(error) as ClientFailure).code)
        trace.secureConnectEnd(call(), null)
        assertEquals("DESKTOP_RESPONSE_TIMEOUT", (trace.failure(error) as ClientFailure).code)
    }

    @Test
    fun detectsWrappedConnectTimeoutsWithoutExposingMessagesAsCodes() {
        val error =
            ConnectException("synthetic-token").apply { initCause(SocketTimeoutException()) }
        assertEquals(
            "TCP_CONNECTION_TIMEOUT",
            (ConnectionDiagnostics().failure(error) as ClientFailure).code,
        )
        assertEquals(
            "TCP_CONNECTION_FAILED",
            (ConnectionDiagnostics().failure(ConnectException()) as ClientFailure).code,
        )
        assertEquals(
            "TCP_NETWORK_FAILED",
            (ConnectionDiagnostics().failure(IOException()) as ClientFailure).code,
        )
    }

    @Test
    fun preservesTrustFailuresAndPolicyCodes() {
        val tls = SSLHandshakeException("synthetic")
        assertSame(tls, ConnectionDiagnostics().failure(tls))
        val policy = ClientFailure("WIFI_LOST")
        assertSame(policy, ConnectionDiagnostics().failure(IOException(policy)))
    }
}
