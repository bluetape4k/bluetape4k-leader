package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * [AbstractLocalLeaderGroupElector]을 상속한 로컬(단일 JVM) 복수 리더 선출 구현체입니다.
 *
 * ## 동작
 * - 동기 [runIfLeader]와 비동기 [runAsyncIfLeader] 모두 지원합니다.
 * - 슬롯 관리(Semaphore 풀, 상태 조회)는 [AbstractLocalLeaderGroupElector]에서 처리합니다.
 * - 분산 환경이 아닌 단일 JVM 프로세스 내 동시 실행 제한에 적합합니다.
 *
 * ```kotlin
 * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 *
 * // 동기 실행 (최대 3개 스레드 동시)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 *
 * // 비동기 실행
 * val future = election.runAsyncIfLeader("batch-job") { CompletableFuture.completedFuture(42) }
 * ```
 *
 * @param options 리더 그룹 선출 옵션. 기본값은 [LeaderGroupElectionOptions.Default]
 */
class LocalLeaderGroupElector private constructor(options: LeaderGroupElectionOptions):
    AbstractLocalLeaderGroupElector(options), LeaderGroupElector {

    companion object: KLogging() {

        /**
         * [LeaderGroupElectionOptions]을 이용해 [LocalLeaderGroupElector] 인스턴스를 생성합니다.
         *
         * ```kotlin
         * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
         * val result = election.runIfLeader("batch-job") { "done" }
         * // result == "done"
         * ```
         *
         * @param options 리더 그룹 선출 옵션. 기본값은 [LeaderGroupElectionOptions.Default]
         * @return [LeaderGroupElector] 구현체 인스턴스
         */
        operator fun invoke(options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default): LeaderGroupElector =
            options
                .also { it.maxLeaders.requirePositiveNumber("maxLeaders") }
                .let(::LocalLeaderGroupElector)
    }

    /**
     * [lockName]의 슬롯을 획득하고 [action]을 동기로 실행합니다.
     *
     * ```kotlin
     * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val result = election.runIfLeader("batch-job") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param action 슬롯 획득 성공 시 실행할 동기 작업
     * @return [action] 실행 결과, 슬롯 획득 실패 시 `null`
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        return tryWithPermit(lockName, action)
    }

    /**
     * [lockName]의 슬롯을 [executor]에서 획득하고 비동기 [action]을 실행합니다.
     *
     * ```kotlin
     * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val result = election.runAsyncIfLeader("batch-job") {
     *     CompletableFuture.completedFuture(42)
     * }.join()
     * // result == 42
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param executor 비동기 실행에 사용할 [Executor]. 기본값은 [VirtualThreadExecutor]
     * @param action 슬롯 획득 성공 시 실행할 비동기 작업
     * @return [action] 실행 결과를 담은 [CompletableFuture]. 슬롯 획득 실패 시 `null`로 완료됨
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync(
            { tryWithPermit(lockName) { action().join() } },
            executor
        )

    /**
     * Slot-aware override — stamps [LeaderSlot.leaderId] as `LeaderLease.auditLeaderId`
     * and `LeaderLockHandle.Real.auditLeaderId` for audit traceability.
     */
    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        tryWithPermit(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * Slot-aware override — returns [LeaderRunResult.Elected] with [LeaderSlot.leaderId] stamped
     * on `LeaderRunResult.Elected.leaderId`, or [LeaderRunResult.Skipped] when not elected.
     */
    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = tryWithPermit(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
        ) {
            elected = true
            action()
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }
}
