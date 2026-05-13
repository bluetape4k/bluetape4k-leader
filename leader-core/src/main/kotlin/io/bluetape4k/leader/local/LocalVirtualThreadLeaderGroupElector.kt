package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderGroupElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [AbstractLocalLeaderGroupElector]을 상속한 로컬(단일 JVM) 복수 리더 Virtual Thread 비동기 선출 구현체입니다.
 *
 * ## 동작
 * - [VirtualFuture] 기반의 [runAsyncIfLeader]를 지원합니다.
 * - 슬롯 관리(Semaphore 풀, 상태 조회)는 [AbstractLocalLeaderGroupElector]에서 처리합니다.
 * - Virtual Thread는 [java.util.concurrent.Semaphore.acquire] 블로킹 시 carrier thread를 반납합니다.
 * - 분산 환경이 아닌 단일 JVM 프로세스 내 동시 실행 제한에 적합합니다.
 *
 * ## [LocalAsyncLeaderGroupElector] 과의 차이
 * - `action`이 `() -> T`로 단순하며, [java.util.concurrent.CompletableFuture] 래핑이 불필요합니다.
 * - 반환이 [VirtualFuture]로 `await()` API가 명시적입니다.
 *
 * ```kotlin
 * val election = LocalVirtualThreadLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 *
 * // 최대 3개 Virtual Thread 가 동시에 실행
 * val result = election.runAsyncIfLeader("batch-job") { processChunk() }.await()
 * ```
 *
 * @param options 리더 그룹 선출 옵션. 기본값은 [LeaderGroupElectionOptions.Default]
 */
class LocalVirtualThreadLeaderGroupElector private constructor(
    options: LeaderGroupElectionOptions,
): AbstractLocalLeaderGroupElector(options), VirtualThreadLeaderGroupElector {

    companion object: KLogging() {

        /**
         * [LeaderGroupElectionOptions]을 이용해 [LocalVirtualThreadLeaderGroupElector] 인스턴스를 생성합니다.
         *
         * ```kotlin
         * val election = LocalVirtualThreadLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
         * val result = election.runAsyncIfLeader("batch-job") { "done" }.await()
         * // result == "done"
         * ```
         *
         * @param options 리더 그룹 선출 옵션. 기본값은 [LeaderGroupElectionOptions.Default]
         * @return [VirtualThreadLeaderGroupElector] 구현체 인스턴스
         */
        operator fun invoke(
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): VirtualThreadLeaderGroupElector =
            options
                .also { it.maxLeaders.requirePositiveNumber("maxLeaders") }
                .let(::LocalVirtualThreadLeaderGroupElector)
    }

    /**
     * [lockName]의 슬롯을 Virtual Thread에서 획득하고 [action]을 실행합니다.
     *
     * ```kotlin
     * val election = LocalVirtualThreadLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val result = election.runAsyncIfLeader("batch-job") { 42 }.await()
     * // result == 42
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param action 슬롯 획득 성공 시 실행할 작업
     * @return [action] 실행 결과를 담은 [VirtualFuture]. 슬롯 획득 실패 시 `null`로 완료됨
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            tryWithPermit(lockName, action)
        }

    /**
     * Slot-aware override — stamps [LeaderSlot.leaderId] as `LeaderLease.auditLeaderId`
     * and `LeaderLockHandle.Real.auditLeaderId` for audit traceability.
     */
    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            tryWithPermit(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
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
            tryWithPermit(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
            ) {
                elected.set(true)
                action()
            }
        }
        val mapped: java.util.concurrent.CompletableFuture<LeaderRunResult<T>> =
            source.toCompletableFuture().thenApply { value ->
                if (elected.get()) {
                    LeaderRunResult.Elected(value, leaderId = slot.leaderId) as LeaderRunResult<T>
                } else {
                    LeaderRunResult.Skipped as LeaderRunResult<T>
                }
            }
        return VirtualFuture(mapped)
    }
}
