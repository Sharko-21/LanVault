package app.lanvault.core.transport

import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TransferRetryTest {
    @Test
    fun transientFailuresRetryWithBoundedBackoff() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val value =
            retryTransfer(wait = { waits += it }) {
                if (++calls < 3) throw ClientFailure("DESKTOP_RESPONSE_TIMEOUT")
                "saved"
            }
        assertEquals("saved", value)
        assertEquals(listOf(1_000L, 3_000L), waits)
        assertEquals(3, calls)
    }

    @Test
    fun persistentFailureStopsAfterThreeAttempts() = runBlocking {
        var calls = 0
        try {
            retryTransfer(wait = {}) {
                calls++
                throw ClientFailure("DESKTOP_CONNECTION_LOST")
            }
            fail("Failure was hidden")
        } catch (error: ClientFailure) {
            assertEquals("DESKTOP_CONNECTION_LOST", error.code)
        }
        assertEquals(3, calls)
    }

    @Test
    fun certificatePermissionAndReceiptFailuresNeverRetry() = runBlocking {
        for (failure in
            listOf(
                SSLHandshakeException("certificate mismatch"),
                ClientFailure("INVALID_RECEIPT"),
                ClientFailure("UNAUTHORIZED"),
                ClientFailure("NETWORK_POLICY_UNAVAILABLE"),
                ClientFailure("SOURCE_CHANGED"),
            )) {
            var calls = 0
            try {
                retryTransfer(wait = { fail("Security failure must not wait") }) {
                    calls++
                    throw failure
                }
                fail("Security failure was ignored")
            } catch (error: Exception) {
                assertSame(failure, error)
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun stoppingDuringRetryCancelsImmediately() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            retryTransfer(
                wait = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            ) {
                calls++
                throw ClientFailure("DESKTOP_RESPONSE_TIMEOUT")
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
    }
}
