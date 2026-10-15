package io.bluetape4k.leader

/**
 * 개별 lock의 관측 가능한 leader 상태를 조회하는 계약입니다.
 *
 * backend가 owner 정보를 제공하지 않으면 `supportsAuditLeaderState`를 `false`로 두고 빈 상태 snapshot을 반환할 수 있습니다.
 */
interface LeaderElectionState {

    /**
     * backend가 상태 snapshot에 현재 owner의 audit leader ID를 포함할 수 있는지 나타냅니다.
     */
    val supportsAuditLeaderState: Boolean
        get() = false

    /**
     * 지정한 lock에 대해 backend가 관측한 상태 snapshot을 반환합니다. 기본 구현은 owner가 없는 빈 snapshot입니다.
     * @param lockName 상태를 조회할 lock 이름
     * @return `lockName`을 포함하는 상태 snapshot이며, 반환값은 `null`이 아닙니다.
     */
    fun state(lockName: String): LeaderState =
        LeaderState.empty(lockName)
}
