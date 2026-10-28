package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.leader.internal.LeaderFutureBridge
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `VirtualThreadLeaderElector`는 virtual thread 기반 leader election 실행자입니다.
 *
 * 작업은 lock을 획득한 경우에만 실행되며 `VirtualFuture`로 결과와 실패를 전달합니다.
 */
interface VirtualThreadLeaderElector: LeaderElectionState {

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return lock을 획득해 실행한 작업의 결과를 담으며, 획득에 실패하면 `null`로 완료되는 future
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        action: () -> T,
    ): VirtualFuture<T?>

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return lock을 획득해 실행한 작업의 결과를 담으며, 획득에 실패하면 `null`로 완료되는 future
     */
    fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<T?> {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runAsyncIfLeader(slot.lockName, action)
    }

    /**
     * `runAsyncIfLeaderResult`는 async leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 획득 성공은 `Elected`, lock contention은 `Skipped`, 실행 중 예외는 `ActionFailed`로 전달하는 future입니다. cancellation은
     * 예외 완료됩니다.
     */
    fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        val elected = AtomicBoolean(false)
        val source: VirtualFuture<T?> = runAsyncIfLeader(slot.lockName) {
            elected.set(true)
            action()
        }
        return LeaderFutureBridge.map(source) { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get()   -> LeaderRunResult.Elected(value) as LeaderRunResult<T>
                else            -> LeaderRunResult.Skipped as LeaderRunResult<T>
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
