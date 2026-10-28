package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug

/**
 * 현재 JVM 내부에서만 동작하는 suspend 다중 slot elector를 생성합니다.
 */
class LocalSuspendLeaderGroupElectorFactory: SuspendLeaderGroupElectorFactory {

    companion object: KLoggingChannel()

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector {
        log.debug { "Create LocalSuspendLeaderGroupElector. options=$options" }
        return LocalSuspendLeaderGroupElector(options)
    }
}
