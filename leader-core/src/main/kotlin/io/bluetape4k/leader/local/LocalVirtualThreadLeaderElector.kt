package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM 프로세스 내부 lock을 사용하고 각 선거 작업을 virtual thread에서 실행합니다.
 * @property options node ID, 대기 시간, lease, 최소 유지 시간 및 자동 연장 설정
 */
class LocalVirtualThreadLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): AbstractLocalLeaderElector(options), VirtualThreadLeaderElector {

    companion object: KLogging()

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * @param lockName 획득할 JVM lock 이름
     * @param action lock을 보유한 동안 실행할 작업
     * @return lock을 얻으면 작업 결과, `options.waitTime` 안에 얻지 못하면 `null`로 완료되는 `VirtualFuture`
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            tryWithLeaderLock(lockName, options.waitTime, action)
        }

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * slot의 leader ID를 상태 기록과 결과에 보존합니다.
     * @param slot lock 이름과 audit leader ID를 담은 slot
     * @param action lock을 보유한 동안 실행할 작업
     * @return lock을 얻으면 작업 결과, 대기 시간 안에 얻지 못하면 `null`로 완료되는 `VirtualFuture`
     */
    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            tryWithLeaderLock(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
                waitTime = options.waitTime,
                action = action,
            )
        }

    /**
     * `runAsyncIfLeaderResult`는 async leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * action에서 발생한 `Exception`은 `ActionFailed`로 반환하고, lock 획득 오류와 취소는 future 실패로 전달합니다.
     * @param slot lock 이름과 결과에 포함할 leader ID를 담은 slot
     * @param action lock을 보유한 동안 실행할 작업
     * @return 획득 결과, contention skip, action 실패를 구분하는 `VirtualFuture<LeaderRunResult<T>>`
     */
    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> {
        log.debug { "runAsyncIfLeaderResult... slot=$slot" }

        val elected = AtomicBoolean(false)
        val source: VirtualFuture<T?> = virtualFuture {
            tryWithLeaderLock(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
                waitTime = options.waitTime,
            ) {
                elected.set(true)
                action()
            }
        }
        return LeaderFutureBridge.map(source) { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(
                    value,
                    leaderId = slot.leaderId
                ) as LeaderRunResult<T>
                else -> LeaderRunResult.Skipped as LeaderRunResult<T>
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
