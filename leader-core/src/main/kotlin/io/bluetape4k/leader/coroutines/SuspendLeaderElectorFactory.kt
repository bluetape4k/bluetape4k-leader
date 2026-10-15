package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions

/**
 * 단일 선거 설정을 받아 backend의 suspend elector를 생성하는 factory 계약입니다.
 */
fun interface SuspendLeaderElectorFactory {

    /**
     * @param options node ID, lock 대기 시간, lease 및 extension 설정
     * @return 전달한 backend 설정을 사용하는 suspend elector
     */
    suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector
}
