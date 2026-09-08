package io.bluetape4k.leader.mongodb

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 사용자 future 완료 스레드와 blocking MongoDB lease cleanup 실행 컨텍스트를 분리합니다.
 *
 * caller executor가 종료되거나 caller가 future를 취소해도 cleanup은 backend-owned virtual thread에서
 * exactly once 실행됩니다.
 */
internal object AsyncLeaseCleanupDispatcher : KLogging() {

    private val cleanupThreadFactory = Thread.ofVirtual()
        .name("bluetape4k-leader-mongodb-cleanup-", 0)
        .factory()
    private val cleanupExecutor = Executor { task -> cleanupThreadFactory.newThread(task).start() }

    @Suppress("TooGenericExceptionCaught")
    fun <T, R> completeAfter(
        source: CompletableFuture<T>,
        cleanup: () -> Unit,
        transform: (T?, Throwable?) -> R,
    ): CompletableFuture<R> =
        completeAfter(source, cleanupExecutor, cleanup, transform)

    fun <T> failAfter(
        failure: Throwable,
        cleanup: () -> Unit,
    ): CompletableFuture<T> =
        completeAfter(CompletableFuture.failedFuture<T>(failure), cleanup) { _, sourceFailure ->
            throw sourceFailure?.unwrapCompletionCause() ?: failure
        }

    fun execute(cleanup: () -> Unit) {
        completeAfter(CompletableFuture.completedFuture(Unit), cleanup) { _, _ -> Unit }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun <T, R> completeAfter(
        source: CompletableFuture<T>,
        executor: Executor,
        cleanup: () -> Unit,
        transform: (T?, Throwable?) -> R,
    ): CompletableFuture<R> {
        val result = CompletableFuture<R>()
        source.whenComplete { value, failure ->
            val started = AtomicBoolean()
            val cleanupTask = Runnable {
                if (started.compareAndSet(false, true)) {
                    val cleanupFailure = runCatching(cleanup).exceptionOrNull()
                    if (cleanupFailure != null) {
                        val originalFailure = failure?.unwrapCompletionCause()
                        if (originalFailure != null) {
                            originalFailure.addSuppressed(cleanupFailure)
                            result.completeExceptionally(originalFailure)
                        } else {
                            result.completeExceptionally(cleanupFailure)
                        }
                    } else {
                        try {
                            result.complete(transform(value, failure))
                        } catch (error: Throwable) {
                            result.completeExceptionally(error)
                        }
                    }
                }
            }

            try {
                executor.execute(cleanupTask)
            } catch (_: RejectedExecutionException) {
                log.debug {
                    "Async MongoDB lease cleanup executor rejected task; " +
                        "using backend-owned virtual-thread fallback."
                }
                try {
                    cleanupExecutor.execute(cleanupTask)
                } catch (fallbackError: RejectedExecutionException) {
                    log.warn(fallbackError) {
                        "Async MongoDB lease cleanup fallback rejected task; " +
                            "running cleanup inline."
                    }
                    cleanupTask.run()
                }
            }
        }
        return result
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this
}
