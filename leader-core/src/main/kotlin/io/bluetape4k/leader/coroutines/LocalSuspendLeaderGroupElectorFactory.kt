package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * `LocalSuspendLeaderGroupElectorFactory`는 backend별 leader elector 인스턴스를 생성하는 factory 계약입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
class LocalSuspendLeaderGroupElectorFactory : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        LocalSuspendLeaderGroupElector(options)
}
