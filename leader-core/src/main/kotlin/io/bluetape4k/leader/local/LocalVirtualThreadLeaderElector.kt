package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderElector
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * [ReentrantLock]과 Virtual Thread를 이용한 로컬(단일 JVM) 리더 선출 구현체입니다.
 *
 * ## 동작
 * - [VirtualThreadLeaderElector]을 구현하며, `action`은 `() -> T` 람다로 결과를 직접 반환합니다.
 * - [VirtualFuture]를 통해 비동기로 실행되며, Virtual Thread가 락 대기 시 carrier thread를 반납합니다.
 * - [ReentrantLock]을 사용하여 동일 `lockName`에 대해 직렬 실행을 보장합니다.
 * - [ReentrantLock] 특성상 동일 스레드에서 동일 `lockName`으로 중첩 호출(재진입)이 가능합니다.
 * - 분산 환경이 아닌 단일 JVM 프로세스 내 비동기 실행 직렬화에 적합합니다.
 *
 * ## [LocalAsyncLeaderElector] 과의 차이
 * - `action`이 `() -> T`로 단순하며, [java.util.concurrent.CompletableFuture] 래핑이 불필요합니다.
 * - 반환이 [VirtualFuture]로 `await()` API가 명시적입니다.
 * - Virtual Thread 기반이므로 I/O 블로킹 작업에 carrier thread를 소모하지 않습니다.
 *
 * ```kotlin
 * val election = LocalVirtualThreadLeaderElector()
 * val result = election.runAsyncIfLeader("job-lock") { "done" }.await()
 * // result == "done"
 * ```
 */
class LocalVirtualThreadLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): AbstractLocalLeaderElector(options), VirtualThreadLeaderElector {

    /**
     * [lockName]에 대한 [ReentrantLock]을 Virtual Thread에서 획득하고 [action]을 실행합니다.
     *
     * - Virtual Thread는 [ReentrantLock.lock] 블로킹 시 carrier thread를 반납합니다.
     * - [action] 예외 발생 시에도 락이 안전하게 해제됩니다.
     * - 동일 스레드에서 동일 `lockName`으로 중첩 호출(재진입)이 가능합니다.
     *
     * ```kotlin
     * val election = LocalVirtualThreadLeaderElector()
     * val result = election.runAsyncIfLeader("job-lock") { "done" }.await()
     * // result == "done"
     * ```
     *
     * @param lockName 리더 선출에 사용할 락 이름
     * @param action 리더 획득 성공 시 실행할 작업
     * @return [action] 실행 결과를 담은 [VirtualFuture]. 리더 획득 실패 시 `null`로 완료됨
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            tryWithLeaderLock(lockName, options.waitTime, action)
        }

    /**
     * Slot-aware override — stamps [LeaderSlot.leaderId] as `LeaderLease.auditLeaderId`
     * and `LeaderLockHandle.Real.auditLeaderId` for audit traceability.
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
     * Slot-aware override — returns [LeaderRunResult.Elected] with [LeaderSlot.leaderId] stamped
     * on `LeaderRunResult.Elected.leaderId`, or [LeaderRunResult.Skipped] when not elected.
     */
    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> {
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
        val mapped: java.util.concurrent.CompletableFuture<LeaderRunResult<T>> =
            source.toCompletableFuture().handle { value, failure ->
                when {
                    failure != null && elected.get() -> failure.toActionFailedResult()
                    failure != null -> throw failure.asCompletionException()
                    elected.get() -> LeaderRunResult.Elected(value, leaderId = slot.leaderId) as LeaderRunResult<T>
                    else -> LeaderRunResult.Skipped as LeaderRunResult<T>
                }
            }
        return VirtualFuture(mapped)
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
