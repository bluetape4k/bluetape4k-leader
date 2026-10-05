package io.bluetape4k.leader.k8s

import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.unwrapCompletionException
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 사용자 future 완료 스레드와 blocking lease cleanup 실행 컨텍스트를 분리합니다.
 *
 * caller executor와 독립된 virtual thread를 즉시 시작하므로 queue shutdown으로 cleanup이 유실되지 않습니다.
 * virtual thread handoff가 실패하면 결과를 terminal failure로 완료하며 caller thread에서 blocking cleanup을 실행하지 않습니다.
 */
internal object AsyncLeaseCleanupDispatcher: KLogging() {

    private val cleanupThreadFactory = Thread.ofVirtual()
        .name("bluetape4k-leader-k8s-cleanup-", 0)
        .factory()

    private val cleanupExecutor = Executor { task -> cleanupThreadFactory.newThread(task).start() }

    @Suppress("TooGenericExceptionCaught")
    fun <T, R> completeAfter(
        source: CompletableFuture<T>,
        cleanup: () -> Unit,
        transform: (T?, Throwable?) -> R,
    ): CompletableFuture<R> =
        completeAfter(source, cleanupExecutor, cleanup, transform = transform)

    fun <T> failAfter(
        failure: Throwable,
        cleanup: () -> Unit,
    ): CompletableFuture<T> =
        completeAfter(failedCompletableFutureOf<T>(failure), cleanup) { _, sourceFailure ->
            throw sourceFailure?.unwrapCompletionException() ?: failure
        }

    fun execute(cleanup: () -> Unit): CompletableFuture<Unit> =
        completeAfter(completableFutureOf(Unit), cleanup) { _, _ -> }

    @Suppress("TooGenericExceptionCaught")
    internal fun <T, R> completeAfter(
        source: CompletableFuture<T>,
        executor: Executor,
        cleanup: () -> Unit,
        fallbackExecutor: Executor = cleanupExecutor,
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
            } catch (dispatchFailure: Throwable) {
                log.debug(dispatchFailure) {
                    "Async lease cleanup executor failed; using backend-owned virtual-thread fallback."
                }
                try {
                    fallbackExecutor.execute(cleanupTask)
                } catch (fallbackFailure: Throwable) {
                    log.warn(fallbackFailure) {
                        "Async lease cleanup fallback failed; completing terminally without inline cleanup."
                    }
                    result.completeExceptionally(
                        terminalDispatchFailure(failure, dispatchFailure, fallbackFailure),
                    )
                }
            }
        }
        return result
    }

    private fun terminalDispatchFailure(
        sourceFailure: Throwable?,
        dispatchFailure: Throwable,
        fallbackFailure: Throwable,
    ): Throwable = sourceFailure?.unwrapCompletionException()?.also { original ->
        original.addSuppressed(dispatchFailure)
        original.addSuppressed(fallbackFailure)
    } ?: dispatchFailure.also { it.addSuppressed(fallbackFailure) }
}
