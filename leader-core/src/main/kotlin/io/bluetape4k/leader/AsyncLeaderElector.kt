package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `AsyncLeaderElector`는 CompletableFuture 기반 async leader election 실행자입니다.
 *
 */
interface AsyncLeaderElector: LeaderElectionState {

    companion object: KLogging()

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * 반환된 future를 취소하면 acquisition과 실행 중인 action에 취소를 전달하고 lease 정리를 시작합니다.
     * 취소는 협력적이며 `mayInterruptIfRunning=true`가 사용자 코드를 강제로 중단하거나 정리가 동기적으로 끝남을 보장하지 않습니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param executor lock 획득과 사용자 작업을 비동기로 실행할 executor입니다. 기본값은 virtual-thread executor입니다.
     * @param action lock 획득 후 호출되어 결과 future를 반환하는 사용자 작업입니다.
     * @return lock을 얻지 못하면 `null`, 얻으면 action future의 값을 완료하는 future입니다.
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?>

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * 반환된 future를 취소하면 acquisition과 실행 중인 action에 취소를 전달하고 lease 정리를 시작합니다.
     * 취소는 협력적이며 `mayInterruptIfRunning=true`가 사용자 코드를 강제로 중단하거나 정리가 동기적으로 끝남을 보장하지 않습니다.
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param executor lock 획득과 사용자 작업을 비동기로 실행할 executor입니다. 기본값은 virtual-thread executor입니다.
     * @param action lock 획득 후 호출되어 결과 future를 반환하는 사용자 작업입니다.
     * @return lock을 얻지 못하면 `null`, 얻으면 action future의 값을 완료하는 future입니다.
     */
    fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        log.debug { "runAsyncIfLeader slot=$slot" }
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runAsyncIfLeader(slot.lockName, executor, action)
    }

    /**
     * `runAsyncIfLeaderResult`는 async leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * 반환된 future를 취소하면 acquisition과 실행 중인 action에 취소를 전달하고 lease 정리를 시작합니다.
     * 취소는 협력적이며 `mayInterruptIfRunning=true`가 사용자 코드를 강제로 중단하거나 정리가 동기적으로 끝남을 보장하지 않습니다.
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param executor lock 획득과 사용자 작업을 비동기로 실행할 executor입니다. 기본값은 virtual-thread executor입니다.
     * @param action lock 획득 후 호출되어 결과 future를 반환하는 사용자 작업입니다.
     * @return 성공은 `Elected`, contention은 `Skipped`, action 실패는 `ActionFailed`로 나타내는 future입니다.
     */
    fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        log.debug { "runAsyncIfLeaderResult slot=$slot" }
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        val elected = AtomicBoolean(false)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()

        return LeaderFutureBridge
            .map(
                runAsyncIfLeader(slot.lockName, executor) {
                    elected.set(true)
                    cancellationRelay.invoke(action)
                },
                cancellationRelay
            ) { value, failure ->
                when {
                    failure != null && elected.get() -> failure.toActionFailedResult()
                    failure != null -> throw failure.asCompletionException()
                    elected.get() -> LeaderRunResult.Elected(value)
                    else -> LeaderRunResult.Skipped
                }
            }
    }
}
