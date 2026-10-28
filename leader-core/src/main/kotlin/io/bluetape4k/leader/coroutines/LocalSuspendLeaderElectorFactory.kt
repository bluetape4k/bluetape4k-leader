package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info

/**
 * 현재 JVM 내부에서만 동작하는 suspend 단일 lock elector를 생성합니다.
 */
class LocalSuspendLeaderElectorFactory: SuspendLeaderElectorFactory {

    companion object: KLoggingChannel()

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector {
        log.info { "Create LocalSuspendLeaderElector. options=$options" }
        return LocalSuspendLeaderElector(options)
    }
}
