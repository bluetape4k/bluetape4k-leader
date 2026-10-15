package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory

/**
 * 현재 JVM 내부에서만 동작하는 단일 lock elector를 생성합니다.
 */
class LocalLeaderElectorFactory: LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        LocalLeaderElector(options)
}
