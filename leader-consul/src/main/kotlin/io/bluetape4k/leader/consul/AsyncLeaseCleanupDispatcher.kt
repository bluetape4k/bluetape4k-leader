package io.bluetape4k.leader.consul

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 사용자 future 완료 스레드와 blocking lease cleanup 실행 컨텍스트를 분리합니다.
 *
 * caller executor와 독립된 virtual thread를 즉시 시작하므로 queue shutdown으로 cleanup이 유실되지 않습니다.
 * thread 시작이 거부되어도 마지막 inline 실행으로 exactly-once cleanup을 보장합니다.
 */
internal object AsyncLeaseCleanupDispatcher : KLogging() {

    private val cleanupThreadFactory = Thread.ofVirtual()
        .name("bluetape4k-leader-consul-cleanup-", 0)
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
            throw sourceFailure?.unwrapCompletionException() ?: failure
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
                        val originalFailure = failure?.unwrapCompletionException()
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
                log.debug { "Async lease cleanup executor rejected task; using backend-owned virtual-thread fallback." }
                try {
                    cleanupExecutor.execute(cleanupTask)
                } catch (fallbackError: RejectedExecutionException) {
                    log.warn(fallbackError) { "Async lease cleanup fallback rejected task; running cleanup inline." }
                    cleanupTask.run()
                }
            }
        }
        return result
    }

    private fun Throwable.unwrapCompletionException(): Throwable =
        (this as? CompletionException)?.cause ?: this
}
