package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.leader.AsyncLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 프로세스 내부 lock을 대상으로 `CompletableFuture` 방식의 단일 leader election을 제공합니다.
 *
 * @property options local lock의 대기 시간, lease TTL, 최소 보유 시간 및 연장 정책입니다.
 */
class LocalAsyncLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): AbstractLocalLeaderElector(options), AsyncLeaderElector {

    companion object: KLogging()

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param executor lock 획득과 사용자 작업을 비동기로 실행할 executor입니다.
     * @param action lock 획득 후 호출되어 결과 future를 반환하는 사용자 작업입니다.
     * @return lock을 얻지 못하면 `null`, 얻으면 action future의 값을 완료하는 future입니다.
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.propagateCancellation(
            futureOf(executor) {
                tryWithLeaderLock(lockName, options.waitTime) {
                    cancellationRelay.invoke(action).join()
                }
            },
            cancellationRelay,
        )
    }

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param executor lock 획득과 사용자 작업을 비동기로 실행할 executor입니다.
     * @param action lock 획득 후 호출되어 결과 future를 반환하는 사용자 작업입니다.
     * @return lock을 얻지 못하면 `null`, 얻으면 action future의 값을 완료하는 future입니다.
     */
    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        log.debug { "runAsyncIfLeader... slot=$slot" }

        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.propagateCancellation(
            futureOf(executor) {
                tryWithLeaderLock(
                    lockName = slot.lockName,
                    auditLeaderId = slot.leaderId,
                    nodeId = options.nodeId,
                    waitTime = options.waitTime,
                ) { cancellationRelay.invoke(action).join() }
            },
            cancellationRelay,
        )
    }

    /**
     * `runAsyncIfLeaderResult`는 async leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param executor lock 획득과 사용자 작업을 비동기로 실행할 executor입니다.
     * @param action lock 획득 후 호출되어 결과 future를 반환하는 사용자 작업입니다.
     * @return 성공·건너뜀·작업 실패를 구분하는 `LeaderRunResult`를 완료하는 future입니다.
     */
    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        log.debug { "runAsyncIfLeaderResult... slot=$slot" }

        val elected = AtomicBoolean(false)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.map(
            futureOf(executor) {
                tryWithLeaderLock(
                    lockName = slot.lockName,
                    auditLeaderId = slot.leaderId,
                    nodeId = options.nodeId,
                    waitTime = options.waitTime,
                ) {
                    elected.set(true)
                    cancellationRelay.invoke(action).join()
                }
            },
            cancellationRelay
        ) { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
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
