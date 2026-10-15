package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * 현재 JVM 내부에서만 동작하는 suspend 다중 slot elector를 생성합니다.
 */
class LocalSuspendLeaderGroupElectorFactory: SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        LocalSuspendLeaderGroupElector(options)
}
