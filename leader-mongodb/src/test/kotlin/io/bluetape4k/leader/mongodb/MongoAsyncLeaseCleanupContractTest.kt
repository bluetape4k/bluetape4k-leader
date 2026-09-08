package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.contract.AbstractAsyncLeaseCleanupContractTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class MongoAsyncLeaseCleanupContractTest : AbstractAsyncLeaseCleanupContractTest() {
    override fun <T, R> completeAfter(
        source: CompletableFuture<T>,
        executor: Executor?,
        cleanup: () -> Unit,
        fallbackExecutor: Executor?,
        transform: (T?, Throwable?) -> R,
    ): CompletableFuture<R> = when {
        executor == null -> AsyncLeaseCleanupDispatcher.completeAfter(source, cleanup, transform)
        fallbackExecutor == null -> AsyncLeaseCleanupDispatcher.completeAfter(source, executor, cleanup, transform = transform)
        else -> AsyncLeaseCleanupDispatcher.completeAfter(source, executor, cleanup, fallbackExecutor, transform)
    }
}

