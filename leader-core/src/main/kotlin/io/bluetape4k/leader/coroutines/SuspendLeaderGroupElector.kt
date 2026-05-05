package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionState

/**
 * 코루틴 기반 복수 리더 선출 계약을 정의합니다.
 *
 * ## [SuspendLeaderElector] 과의 차이
 * - [SuspendLeaderElector]은 `lockName`당 리더를 1개로 제한합니다.
 * - [SuspendLeaderGroupElector]은 [maxLeaders]개까지 동시에 리더를 허용합니다.
 * - 내부적으로 `kotlinx.coroutines.sync.Semaphore(maxLeaders)`를 사용합니다.
 *
 * ## [LeaderGroupElectionState] 상속
 * - [maxLeaders], [activeCount], [availableSlots], [state] 상태 조회 메서드를 공유합니다.
 *
 * ## 동작/계약
 * - 구현체는 `lockName` 기준으로 최대 [maxLeaders]개의 `action`을 동시에 실행합니다.
 * - 슬롯이 가득 찬 경우 [waitTime] 내 슬롯을 획득하지 못하면 `null`을 반환합니다 (ShedLock skip 방식).
 * - `action` 예외 발생 시에도 슬롯이 반드시 반환됩니다.
 * - 코루틴 취소 시 슬롯은 반드시 반환되어야 하며, `CancellationException`은 반환 작업 후 재전파해야 합니다.
 * - 상태 조회 메서드([state], [activeCount], [availableSlots])는 근사값을 반환할 수 있습니다.
 *
 * ```kotlin
 * val election = LocalSuspendLeaderGroupElection(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-job") { processChunkSuspend() }
 *
 * println(election.state("batch-job"))  // LeaderGroupState(activeCount=2, ...)
 * ```
 */
interface SuspendLeaderGroupElector: LeaderGroupElectionState {

    /**
     * 슬롯을 획득하여 리더로 선출되면 suspend [action]을 실행합니다.
     *
     * ## 동작/계약
     * - 슬롯이 가득 찬 경우 빈 슬롯이 생길 때까지 코루틴이 suspend됩니다.
     * - [action] 예외 발생 시에도 슬롯은 반드시 반환됩니다.
     * - [action] 실행 중 [activeCount]가 증가하고, 완료 시 감소합니다.
     *
     * ```kotlin
     * val result = election.runIfLeader("job-lock") { computeSuspend() }
     * // result == computeSuspend() 반환값 (슬롯 획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param action 리더 선출 성공 시 실행할 suspend 작업
     * @return [action] 실행 결과, 슬롯 획득 실패 시 `null`
     */
    suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T?
}
