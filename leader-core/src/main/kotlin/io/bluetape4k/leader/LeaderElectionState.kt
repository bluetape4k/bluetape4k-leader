package io.bluetape4k.leader

/**
 * 단일 리더 선출의 상태 조회 메서드를 정의하는 공통 인터페이스입니다.
 *
 * ## 계약
 * [state]는 조회 시점의 best-effort 스냅샷입니다. 상태 조회 결과를 보고 작업 실행 여부를
 * 직접 결정하지 말고, 기존 `runIfLeader` 계열의 원자적 lock 획득 경로를 사용해야 합니다.
 *
 * ```kotlin
 * val state = election.state("daily-job")
 * println(state.status)
 * ```
 */
interface LeaderElectionState {

    /**
     * [lockName]에 대한 현재 단일 리더 상태 스냅샷을 반환합니다.
     *
     * 기본 구현은 외부 구현체의 소스 호환성을 위해 빈 스냅샷을 반환합니다. backend가 실제
     * owner metadata를 제공할 수 있다면 이 메서드를 override해야 합니다.
     *
     * @param lockName 조회할 락 이름
     * @return 현재 리더 상태 스냅샷
     */
    fun state(lockName: String): LeaderState =
        LeaderState.empty(lockName)
}
