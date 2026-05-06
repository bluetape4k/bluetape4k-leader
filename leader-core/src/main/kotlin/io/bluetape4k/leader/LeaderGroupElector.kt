package io.bluetape4k.leader

/**
 * Semaphore 기반 복수 리더 선출 계약을 정의합니다.
 *
 * ## [LeaderElector] 과의 차이
 * - [LeaderElector]은 `lockName`당 리더를 1개로 제한합니다.
 * - [LeaderGroupElector]은 [maxLeaders]개까지 동시에 리더를 허용합니다.
 * - 내부적으로 `Semaphore(maxLeaders)`를 이용하여 동시 실행 수를 제한합니다.
 *
 * ## [AsyncLeaderGroupElector] 과의 관계
 * - [AsyncLeaderGroupElector]을 상속하여 동기 [runIfLeader]를 추가합니다.
 * - 비동기 실행([AsyncLeaderGroupElector.runAsyncIfLeader])과 상태 조회 메서드는 부모 인터페이스에서 상속합니다.
 *
 * ## 동작/계약
 * - 구현체는 `lockName` 기준으로 최대 [maxLeaders]개의 `action`을 동시에 실행합니다.
 * - 슬롯이 가득 찬 경우, 빈 슬롯이 생길 때까지 호출 스레드가 블로킹됩니다.
 * - `action` 예외 발생 시에도 슬롯이 반드시 반환됩니다.
 * - 상태 조회 메서드([state], [activeCount], [availableSlots])는 근사값을 반환할 수 있습니다.
 *
 * ```kotlin
 * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-job") { processChunk() }
 *
 * println(election.state("batch-job"))  // LeaderGroupState(activeCount=2, ...)
 * ```
 */
interface LeaderGroupElector: AsyncLeaderGroupElector {

    /**
     * 슬롯을 획득하여 리더로 선출되면 [action]을 실행합니다.
     *
     * ## 동작/계약
     * - 슬롯이 가득 찬 경우 빈 슬롯이 생길 때까지 블로킹됩니다.
     * - [action] 예외 발생 시에도 슬롯은 반드시 반환됩니다.
     * - [action] 실행 중 [activeCount]가 증가하고, 완료 시 감소합니다.
     *
     * ```kotlin
     * val result = election.runIfLeader("job-lock") { compute() }
     * // result == compute() 반환값 (슬롯 획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param action 리더 선출 성공 시 실행할 동기 작업
     * @return [action] 실행 결과, 슬롯 획득 실패 시 `null`
     */
    fun <T> runIfLeader(lockName: String, action: () -> T): T?

    /**
     * 리더 그룹 선출 결과를 [LeaderRunResult]로 반환합니다.
     *
     * [LeaderElector.runIfLeaderResult]와 동일 메서드명 — "Group" 컨텍스트는 인터페이스 타입으로 전달.
     * `runIfLeader`의 `null` 반환 모호성을 해결합니다:
     * action()이 null을 반환해도 [LeaderRunResult.Elected]로 구분되고,
     * 슬롯 미획득은 [LeaderRunResult.Skipped]로 구분됩니다.
     *
     * NOTE: 동기 [LeaderGroupElector] 전용입니다.
     * `SuspendLeaderGroupElector` / `AsyncLeaderGroupElector` 의 동등 메서드는
     * v1.x 후속 이슈에서 추가 예정입니다.
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param action 슬롯 획득 성공 시 실행할 동기 작업
     * @return [LeaderRunResult.Elected] (action 실행됨) 또는 [LeaderRunResult.Skipped] (슬롯 미획득)
     */
    fun <T> runIfLeaderResult(lockName: String, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = runIfLeader(lockName) { elected = true; action() }
        return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
    }
}
