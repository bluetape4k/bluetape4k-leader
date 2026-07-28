package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * `SuspendLeaderGroupElectorFactory`는 backend별 leader elector 인스턴스를 생성하는 factory 계약입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
fun interface SuspendLeaderGroupElectorFactory {

    /**
     * `create` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param options `options` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector
}
