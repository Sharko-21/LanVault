package app.lanvault.core.transport

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TransferWorkersTest {
    @Test
    fun fourWorkersAreJoinedWhenOneIsCancelled() = runBlocking {
        val started = AtomicInteger()
        val active = AtomicInteger()
        val all = CompletableDeferred<Unit>()
        try {
            transferWorkers((1..1000).toList(), 4) { index, _ ->
                started.incrementAndGet()
                if (active.incrementAndGet() == 4) all.complete(Unit)
                try {
                    withTimeout(5000) { all.await() }
                    if (index == 0) throw CancellationException("synthetic stop")
                    awaitCancellation()
                } finally {
                    active.decrementAndGet()
                }
            }
            fail("Cancellation ignored")
        } catch (_: CancellationException) {}
        assertEquals(4, started.get())
        assertEquals(0, active.get())
    }

    @Test
    fun cancellationStopsAndJoinsBothWorkers() = runBlocking {
        val started = AtomicInteger()
        val active = AtomicInteger()
        val both = CompletableDeferred<Unit>()
        try {
            transferWorkers((1..1000).toList(), 2) { index, _ ->
                started.incrementAndGet()
                if (active.incrementAndGet() == 2) both.complete(Unit)
                try {
                    withTimeout(5000) { both.await() }
                    if (index == 0) throw CancellationException("synthetic stop")
                    awaitCancellation()
                } finally {
                    active.decrementAndGet()
                }
            }
            fail("Cancellation ignored")
        } catch (_: CancellationException) {}
        assertEquals(2, started.get())
        assertEquals(0, active.get())
    }

    @Test
    fun failureCancelsTheOtherWorker() = runBlocking {
        val finished = AtomicInteger()
        val both = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        try {
            transferWorkers(listOf(1, 2, 3), 2) { index, _ ->
                if (active.incrementAndGet() == 2) both.complete(Unit)
                try {
                    withTimeout(5000) { both.await() }
                    if (index == 0) error("synthetic network failure")
                    awaitCancellation()
                } finally {
                    finished.incrementAndGet()
                }
            }
            fail("Failure ignored")
        } catch (_: IllegalStateException) {}
        assertEquals(2, finished.get())
    }
}
