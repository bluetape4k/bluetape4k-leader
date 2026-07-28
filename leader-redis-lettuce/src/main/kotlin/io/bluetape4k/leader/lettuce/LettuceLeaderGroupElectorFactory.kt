package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.lettuce.core.api.StatefulRedisConnection

/**
 * `LettuceLeaderGroupElectorFactory`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property connection Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class LettuceLeaderGroupElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        LettuceLeaderGroupElector(connection, options)
}
