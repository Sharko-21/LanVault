package app.lanvault.core.transport

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

internal const val MAX_TRANSFER_WORKERS = 4

/** Respect negotiated Desktop capacity; keep queues bounded to one MediaStore page. */
internal suspend fun <T> transferWorkers(
    items: List<T>,
    parallelism: Int,
    action: suspend (Int, T) -> Unit,
) = coroutineScope {
    require(parallelism in 1..MAX_TRANSFER_WORKERS)
    val next = AtomicInteger()
    List(minOf(parallelism, items.size)) {
            async(Dispatchers.IO) {
                while (true) {
                    ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= items.size) break
                    action(index, items[index])
                }
            }
        }
        .awaitAll()
    Unit
}
