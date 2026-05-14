package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock

/**
 * [ReentrantLock]을 이용한 로컬(단일 JVM) 리더 선출 구현체입니다.
 *
 * ## 동작
 * - 동일 `lockName`에 대해 스레드 간 상호 배제(mutual exclusion)로 직렬 실행을 보장합니다.
 * - 락을 획득한 스레드가 리더로서 `action`을 실행하며, 다른 스레드는 락이 해제될 때까지 블로킹됩니다.
 * - [ReentrantLock] 특성상 동일 스레드에서 동일 `lockName`으로 중첩 호출(재진입)이 가능합니다.
 * - 분산 환경이 아닌 단일 JVM 프로세스 내 동시 실행 직렬화에 적합합니다.
 *
 * ```kotlin
 * val election = LocalLeaderElector()
 * val result = election.runIfLeader("job-lock") { "done" }
 * // result == "done"
 * ```
 */
class LocalLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): AbstractLocalLeaderElector(options), LeaderElector {

    /**
     * [lockName]에 대한 [ReentrantLock]을 획득하고 [action]을 직렬로 실행합니다.
     *
     * 다른 스레드가 동일 [lockName]의 락을 보유 중이면 해제될 때까지 블로킹됩니다.
     * 동일 스레드에서 재진입 시 즉시 락을 획득합니다.
     *
     * ```kotlin
     * val election = LocalLeaderElector()
     * val result = election.runIfLeader("job-lock") { 42 }
     * // result == 42
     * ```
     *
     * @param lockName 리더 선출에 사용할 락 이름
     * @param action 리더 획득 성공 시 실행할 동기 작업
     * @return [action] 실행 결과, 리더 획득 실패 시 `null`
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        tryWithLeaderLock(lockName, options.waitTime, action)

    /**
     * [lockName]에 대한 [ReentrantLock]을 획득하고 [action]을 [executor]에서 비동기로 실행합니다.
     *
     * [action]이 반환하는 [CompletableFuture]가 완료될 때까지 락을 보유합니다.
     * 다른 스레드가 동일 [lockName]의 락을 보유 중이면 [executor] 스레드가 블로킹됩니다.
     *
     * ```kotlin
     * val election = LocalLeaderElector()
     * val result = election.runAsyncIfLeader("job-lock") {
     *     CompletableFuture.completedFuture("async-ok")
     * }.join()
     * // result == "async-ok"
     * ```
     *
     * @param lockName 리더 선출에 사용할 락 이름
     * @param executor 비동기 실행에 사용할 [Executor]
     * @param action 리더 획득 성공 시 실행할 비동기 작업
     * @return [action] 실행 결과를 담은 [CompletableFuture]. 리더 획득 실패 시 `null`로 완료됨
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync(
            { tryWithLeaderLock(lockName, options.waitTime) { action().join() } },
            executor
        )

    /**
     * Slot-aware override — stamps [LeaderSlot.leaderId] as `LeaderLease.auditLeaderId`
     * and `LeaderLockHandle.Real.auditLeaderId` for audit traceability.
     */
    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        tryWithLeaderLock(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
            waitTime = options.waitTime,
            action = action,
        )

    /**
     * Slot-aware override — returns [LeaderRunResult.Elected] with [LeaderSlot.leaderId] stamped
     * on `LeaderRunResult.Elected.leaderId`, or [LeaderRunResult.Skipped] when not elected.
     */
    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            tryWithLeaderLock(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
                waitTime = options.waitTime,
            ) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }
}
