package io.bluetape4k.leader.mongodb

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 사용자 future 완료 스레드와 blocking MongoDB lease cleanup 실행 컨텍스트를 분리합니다.
 *
 * caller executor 종료나 future 취소와 독립적으로 backend 소유 virtual thread에 cleanup을 예약합니다.
 * 예약에 성공한 cleanup은 최대 한 번 실행됩니다.
 * virtual thread handoff가 실패하면 결과를 terminal failure로 완료하며 caller thread에서 blocking cleanup을
 * 실행하지 않습니다.
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
        completeAfter(source, cleanupExecutor, cleanup, transform = transform)

    fun <T> failAfter(
        failure: Throwable,
        cleanup: () -> Unit,
    ): CompletableFuture<T> =
        completeAfter(CompletableFuture.failedFuture<T>(failure), cleanup) { _, sourceFailure ->
            throw sourceFailure?.unwrapCompletionCause() ?: failure
        }

    fun execute(cleanup: () -> Unit): CompletableFuture<Unit> =
        completeAfter(CompletableFuture.completedFuture(Unit), cleanup) { _, _ -> }

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
            } catch (dispatchFailure: Throwable) {
                log.debug(dispatchFailure) {
                    "Async MongoDB lease cleanup executor failed; " +
                        "using backend-owned virtual-thread fallback."
                }
                try {
                    fallbackExecutor.execute(cleanupTask)
                } catch (fallbackFailure: Throwable) {
                    log.warn(fallbackFailure) {
                        "Async MongoDB lease cleanup fallback failed; " +
                            "completing terminally without inline cleanup."
                    }
                    result.completeExceptionally(
                        terminalDispatchFailure(failure, dispatchFailure, fallbackFailure),
                    )
                }
            }
        }
        return result
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this

    private fun terminalDispatchFailure(
        sourceFailure: Throwable?,
        dispatchFailure: Throwable,
        fallbackFailure: Throwable,
    ): Throwable = sourceFailure?.unwrapCompletionCause()?.also { original ->
        original.addSuppressed(dispatchFailure)
        original.addSuppressed(fallbackFailure)
    } ?: dispatchFailure.also { it.addSuppressed(fallbackFailure) }
}
