package io.bluetape4k.leader.internal

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

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
        mapper: (T?, Throwable?) -> R,
    ): CompletableFuture<R> {
        val transformed = source.handle { value, failure -> mapper(value, failure) }
        return mirror(transformed, source)
    }

    /**
     * 원본 future의 terminal state를 비동기 cleanup stage로 변환하면서 caller cancellation을 원본에 전달합니다.
     */
    fun <T, R> flatMap(
        source: CompletableFuture<T>,
        mapper: (T?, Throwable?) -> CompletableFuture<R>,
    ): CompletableFuture<R> {
        val transformed = source.handle { value, failure -> mapper(value, failure) }.thenCompose { it }
        return mirror(transformed, source)
    }

    /**
     * 원본 future 변환과 함께 caller cancellation을 실제 action future까지 전달합니다.
     */
    fun <T, R> map(
        source: CompletableFuture<T>,
        cancellationRelay: CancellationRelay,
        mapper: (T?, Throwable?) -> R,
    ): CompletableFuture<R> {
        val transformed = source.handle { value, failure -> mapper(value, failure) }
        return mirror(transformed, source, cancellationRelay::cancel)
    }

    /**
     * Result adapter가 아직 생성되지 않았거나 실행 중인 action future를 추적하는 relay를 만듭니다.
     */
    fun cancellationRelay(): CancellationRelay = CancellationRelay()

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
        mapper: (T?, Throwable?) -> R,
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
        onCancellation: (Boolean) -> Unit = {},
    ): CompletableFuture<T> {
        val mirrored = CancellationPropagatingFuture<T>(cancellationTarget, onCancellation)
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
        private val onCancellation: (Boolean) -> Unit,
    ) : CompletableFuture<T>() {

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val cancelled = super.cancel(mayInterruptIfRunning)
            if (cancelled) {
                cancellationTarget.cancel(mayInterruptIfRunning)
                onCancellation(mayInterruptIfRunning)
            }
            return cancelled
        }
    }

    /**
     * caller cancellation과 action future 생성 사이의 race를 닫는 내부 lifecycle relay입니다.
     */
    class CancellationRelay internal constructor() {
        private val state = AtomicReference(State.READY)
        private val actionFuture = AtomicReference<CompletableFuture<*>?>()

        /**
         * action을 실행하고 그 future를 현재 cancellation lifecycle에 연결합니다.
         */
        fun <T> invoke(action: () -> CompletableFuture<T>): CompletableFuture<T> {
            if (!state.compareAndSet(State.READY, State.INVOKED)) {
                return CompletableFuture.failedFuture(CancellationException("leader result future was cancelled"))
            }
            return action().also { future ->
                actionFuture.set(future)
                if (state.get() == State.CANCELLED) future.cancel(false)
            }
        }

        internal fun cancel(mayInterruptIfRunning: Boolean) {
            state.set(State.CANCELLED)
            actionFuture.get()?.cancel(mayInterruptIfRunning)
        }

        private enum class State {
            READY,
            INVOKED,
            CANCELLED,
        }
    }
}
