package io.bluetape4k.leader.internal

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import java.util.concurrent.CompletableFuture

/**
 * 결과 bridge가 반환한 future의 취소를 원본 lifecycle future로 전달하는 내부 adapter입니다.
 *
 * `CompletableFuture.handle`와 `whenComplete`가 만드는 dependent future는 취소가 원본으로
 * 자동 전파되지 않으므로, leader lease와 action lifecycle이 caller 취소 뒤에도 남지 않도록
 * 명시적으로 연결합니다.
 */
object LeaderFutureBridge {

    /**
     * 원본 future를 변환하면서 반환 future의 취소를 원본으로 전파합니다.
     */
    fun <T, R> map(
        source: CompletableFuture<T>,
        mapper: (T, Throwable?) -> R,
    ): CompletableFuture<R> {
        val transformed = source.handle { value, failure -> mapper(value, failure) }
        return mirror(transformed, source)
    }

    /**
     * 원본 future를 관찰하면서 반환 future의 취소를 원본으로 전파합니다.
     */
    fun <T> observe(
        source: CompletableFuture<T>,
        observer: (T?, Throwable?) -> Unit,
    ): CompletableFuture<T> {
        val observed = source.whenComplete { value, failure -> observer(value, failure) }
        return mirror(observed, source)
    }

    /**
     * [VirtualFuture] 결과 bridge에도 동일한 cancellation propagation을 적용합니다.
     */
    fun <T, R> map(
        source: VirtualFuture<T>,
        mapper: (T, Throwable?) -> R,
    ): VirtualFuture<R> = VirtualFuture(map(source.toCompletableFuture(), mapper))

    /**
     * [VirtualFuture]의 성공·실패 결과는 변경하지 않고 반환 future의 취소만 원본으로 전파합니다.
     */
    fun <T> propagateCancellation(source: VirtualFuture<T>): VirtualFuture<T> {
        val completable = source.toCompletableFuture()
        return VirtualFuture(mirror(completable, completable))
    }

    private fun <T> mirror(
        source: CompletableFuture<T>,
        cancellationTarget: CompletableFuture<*>,
    ): CompletableFuture<T> {
        val mirrored = CancellationPropagatingFuture<T>(cancellationTarget)
        source.whenComplete { value, failure ->
            if (failure == null) {
                mirrored.complete(value)
            } else {
                mirrored.completeExceptionally(failure)
            }
        }
        return mirrored
    }

    private class CancellationPropagatingFuture<T>(
        private val cancellationTarget: CompletableFuture<*>,
    ) : CompletableFuture<T>() {

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val cancelled = super.cancel(mayInterruptIfRunning)
            if (cancelled) {
                cancellationTarget.cancel(mayInterruptIfRunning)
            }
            return cancelled
        }
    }
}
