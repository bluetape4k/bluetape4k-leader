package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.leader.internal.LeaderFutureBridge
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `AsyncLeaderGroupElector`는 여러 slot을 허용하는 async group leader election 실행자입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface AsyncLeaderGroupElector: LeaderGroupElectionState {

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * 반환된 future를 취소하면 acquisition과 실행 중인 action에 취소를 전달하고 slot 정리를 시작합니다.
     * 취소는 협력적이며 `mayInterruptIfRunning=true`가 사용자 코드를 강제로 중단하거나 정리가 동기적으로 끝남을 보장하지 않습니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param executor `executor` 호출 또는 상태 계산에 필요한 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?>

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * 반환된 future를 취소하면 acquisition과 실행 중인 action에 취소를 전달하고 slot 정리를 시작합니다.
     * 취소는 협력적이며 `mayInterruptIfRunning=true`가 사용자 코드를 강제로 중단하거나 정리가 동기적으로 끝남을 보장하지 않습니다.
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param executor `executor` 호출 또는 상태 계산에 필요한 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runAsyncIfLeader(slot.lockName, executor, action)
    }

    /**
     * `runAsyncIfLeaderResult`는 async leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * 반환된 future를 취소하면 acquisition과 실행 중인 action에 취소를 전달하고 slot 정리를 시작합니다.
     * 취소는 협력적이며 `mayInterruptIfRunning=true`가 사용자 코드를 강제로 중단하거나 정리가 동기적으로 끝남을 보장하지 않습니다.
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param executor `executor` 호출 또는 상태 계산에 필요한 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        val elected = AtomicBoolean(false)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.map(runAsyncIfLeader(slot.lockName, executor) {
            elected.set(true)
            cancellationRelay.invoke(action)
        }, cancellationRelay) { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(value)
                else -> LeaderRunResult.Skipped
            }
        }
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this

    private fun Throwable.toActionFailedResult(): LeaderRunResult.ActionFailed {
        val cause = unwrapCompletionCause()
        if (cause is CancellationException) {
            throw cause
        }
        return LeaderRunResult.ActionFailed(cause)
    }

    private fun Throwable.asCompletionException(): CompletionException =
        this as? CompletionException ?: CompletionException(this)
}
