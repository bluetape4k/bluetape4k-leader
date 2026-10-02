package io.bluetape4k.leader.k8s

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 획득 완료와 cleanup 요청의 race를 닫고 cleanup 완료 future를 단일 소유합니다. */
internal class AsyncLeaseCleanupBarrier<T: Any>(
    private val cleanup: (T) -> Unit,
) {
    private val acquired = AtomicReference<T?>()
    private val acquisitionCompleted = AtomicBoolean()
    private val cleanupRequested = AtomicBoolean()
    private val cleanupStarted = AtomicBoolean()
    private val completion = CompletableFuture<Unit>()

    fun completeAcquisition(value: T?) {
        if (value != null) acquired.set(value)
        acquisitionCompleted.set(true)
        dispatchIfReady()
    }

    fun request(): CompletableFuture<Unit> {
        cleanupRequested.set(true)
        dispatchIfReady()
        return completion
    }

    private fun dispatchIfReady() {
        if (cleanupRequested.get() &&
            acquisitionCompleted.get() &&
            cleanupStarted.compareAndSet(false, true)
        ) {
            val value = acquired.get()
            if (value == null) {
                completion.complete(Unit)
            } else {
                AsyncLeaseCleanupDispatcher.execute { cleanup(value) }
                    .whenComplete { _, failure ->
                        if (failure == null) completion.complete(Unit)
                        else completion.completeExceptionally(failure)
                    }
            }
        }
    }
}
