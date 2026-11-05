package app.lanvault.core.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Retry only transient transport failures; certificate, permission and protocol errors stay fatal.
 */
fun retryableTransferFailure(error: Exception): Boolean =
    error is ClientFailure &&
        error.code in
            setOf(
                "TCP_CONNECTION_TIMEOUT",
                "TCP_CONNECTION_FAILED",
                "TCP_NETWORK_FAILED",
                "TLS_HANDSHAKE_TIMEOUT",
                "TLS_CONNECTION_CLOSED",
                "DESKTOP_RESPONSE_TIMEOUT",
                "DESKTOP_CONNECTION_LOST",
                "BODY_TIMEOUT",
                "BODY_INTERRUPTED",
                "UPLOAD_BUSY",
                "HTTP_502",
                "HTTP_503",
                "HTTP_504",
            )

internal suspend fun <T> retryTransfer(
    onRetry: (Int) -> Unit = {},
    wait: suspend (Long) -> Unit = { delay(it) },
    action: suspend () -> T,
): T {
    for (attempt in 0..2) {
        currentCoroutineContext().ensureActive()
        try {
            return action()
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (attempt == 2 || !retryableTransferFailure(error)) throw error
            onRetry(attempt + 1)
            wait(if (attempt == 0) 1_000 else 3_000)
        }
    }
    error("Unreachable retry state")
}
