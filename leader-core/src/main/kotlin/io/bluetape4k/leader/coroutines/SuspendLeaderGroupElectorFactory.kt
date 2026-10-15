package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * 그룹 선거 설정을 받아 backend의 suspend group elector를 생성하는 factory 계약입니다.
 */
fun interface SuspendLeaderGroupElectorFactory {

    /**
     * @param options node ID, 허용 slot 수, 대기 시간 및 lease 설정
     * @return 전달한 backend 설정을 사용하는 suspend group elector
     */
    suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector
}
