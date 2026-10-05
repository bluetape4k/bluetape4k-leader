package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.contract.AbstractAsyncLeaseCleanupContractTest
import io.bluetape4k.logging.KLogging
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class EtcdAsyncLeaseCleanupContractTest: AbstractAsyncLeaseCleanupContractTest() {

    companion object: KLogging()

    override fun <T, R> completeAfter(
        source: CompletableFuture<T>,
        executor: Executor?,
        cleanup: () -> Unit,
        fallbackExecutor: Executor?,
        transform: (T?, Throwable?) -> R,
    ): CompletableFuture<R> = when {
        executor == null -> AsyncLeaseCleanupDispatcher.completeAfter(source, cleanup, transform)
        fallbackExecutor == null -> AsyncLeaseCleanupDispatcher.completeAfter(
            source,
            executor,
            cleanup,
            transform = transform
        )
        else -> AsyncLeaseCleanupDispatcher.completeAfter(
            source,
            executor,
            cleanup,
            fallbackExecutor,
            transform
        )
    }
}
