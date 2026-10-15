package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory

/**
 * 현재 JVM 내부에서만 동작하는 다중 slot elector를 생성합니다.
 */
class LocalLeaderGroupElectorFactory: LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        LocalLeaderGroupElector(options)
}
