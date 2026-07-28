package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions

/**
 * `LocalLeaderElectorFactory`는 backend별 leader elector 인스턴스를 생성하는 factory 계약입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
class LocalLeaderElectorFactory : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        LocalLeaderElector(options)
}
