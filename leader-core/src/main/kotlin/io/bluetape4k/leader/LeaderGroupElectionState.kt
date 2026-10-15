package io.bluetape4k.leader

/**
 * 그룹 선거에서 동시 점유 slot 수와 lock별 상태 snapshot을 조회하는 계약입니다.
 *
 */
interface LeaderGroupElectionState {

    /**
     * 한 `lockName`에서 동시에 점유할 수 있는 leader slot의 상한입니다. 유효한 설정은 양수여야 합니다.
     */
    val maxLeaders: Int

    /**
     * 지정한 lock에서 현재 점유된 group leader slot 수를 반환합니다.
     * @param lockName slot 점유 수를 조회할 lock 이름
     * @return 현재 점유된 slot 수
     */
    fun activeCount(lockName: String): Int

    /**
     * 지정한 lock에서 아직 획득 가능한 group leader slot 수를 반환합니다.
     * @param lockName 사용 가능 slot 수를 조회할 lock 이름
     * @return 아직 점유되지 않은 slot 수
     */
    fun availableSlots(lockName: String): Int

    /**
     * 지정한 lock의 최대 slot 수, 현재 점유 수, backend가 제공하는 leader ID를 담은 snapshot을 반환합니다.
     * @param lockName 그룹 상태를 조회할 lock 이름
     * @return `lockName`의 그룹 상태 snapshot
     */
    fun state(lockName: String): LeaderGroupState
}
