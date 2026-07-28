package io.bluetape4k.leader

/**
 * `LeaderGroupElectionState`는 leader election의 현재 상태를 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface LeaderGroupElectionState {

    /**
     * `maxLeaders` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val maxLeaders: Int

    /**
     * `activeCount`는 현재 점유된 group leader slot 수를 조회합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun activeCount(lockName: String): Int

    /**
     * `availableSlots`는 아직 획득 가능한 group leader slot 수를 조회합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun availableSlots(lockName: String): Int

    /**
     * `state`는 현재 leader election 상태 snapshot을 조회합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun state(lockName: String): LeaderGroupState
}
