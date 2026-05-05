package io.bluetape4k.leader

/**
 * 동기 방식 리더 선출 실행 계약을 정의합니다.
 *
 * ## 동작/계약
 * - 동일 [lockName]에 대해 구현체가 리더 획득에 성공한 호출만 [action]을 실행합니다.
 * - 리더 획득/해제 전략과 실패 처리(예외/재시도)는 구현체 정책을 따릅니다.
 * - [action] 실행 시점의 스레드와 컨텍스트는 구현체에 따라 달라질 수 있습니다.
 *
 * ```kotlin
 * val leaderElection = DefaultLeaderElection()
 * val result = leaderElection.runIfLeader("daily-job") {
 *     "done"
 * }
 * // result == "done" (리더 획득 성공 경로)
 */
interface LeaderElector: AsyncLeaderElector {

    /**
     * 리더로 선출된 경우에만 동기 [action]을 실행합니다.
     *
     * ## 동작/계약
     * - [lockName]에 대한 리더 획득 성공 시 [action]을 1회 실행합니다.
     * - [action]에서 발생한 예외는 호출자에게 전파됩니다.
     * - [lockName] 유효성(blank 허용 여부)은 구현체의 입력 검증 규칙을 따릅니다.
     * - [waitTime] 내 리더 획득 실패 시 `null`을 반환합니다 (ShedLock 방식).
     *
     * ```kotlin
     * val value = leaderElection.runIfLeader("job-lock") { 42 }
     * // value == 42 (리더 획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 리더 선출에 사용할 락 이름
     * @param action 리더 획득 성공 시 실행할 동기 작업
     * @return [action] 실행 결과, 리더 획득 실패 시 `null`
     */
    fun <T> runIfLeader(lockName: String, action: () -> T): T?

    /**
     * 리더 선출 결과를 [LeaderRunResult]로 반환합니다.
     *
     * `runIfLeader`의 `null` 반환 모호성을 해결합니다:
     * action()이 null을 반환해도 [LeaderRunResult.Elected]로 구분되고,
     * lock 미획득은 [LeaderRunResult.Skipped]로 구분됩니다.
     *
     * NOTE: 동기 [LeaderElector] 전용입니다.
     * `SuspendLeaderElector` / `AsyncLeaderElector` / `VirtualThreadLeaderElector` 의 동등
     * 메서드는 v1.x 후속 이슈에서 추가 예정입니다.
     *
     * ```kotlin
     * when (val r = election.runIfLeaderResult("job-lock") { compute() }) {
     *     is LeaderRunResult.Elected -> println("elected, value=${r.value}")
     *     is LeaderRunResult.Skipped -> println("skipped — lock not acquired")
     * }
     * ```
     *
     * @param lockName 리더 선출에 사용할 락 이름
     * @param action 리더 획득 성공 시 실행할 동기 작업
     * @return [LeaderRunResult.Elected] (action 실행됨) 또는 [LeaderRunResult.Skipped] (lock 미획득)
     */
    fun <T> runIfLeaderResult(lockName: String, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = runIfLeader(lockName) { elected = true; action() }
        return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
    }

}
