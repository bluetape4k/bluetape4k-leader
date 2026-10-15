package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.lettuce.core.api.StatefulRedisConnection

/**
 * `LettuceLeaderElectorFactory`는 공유 Redis Lettuce connection과 단일 선출 옵션으로 elector를 생성합니다.
 *
 * @property connection 각 elector가 lock 연산에 공유할 `StatefulRedisConnection`입니다.
 */
class LettuceLeaderElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
): LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        LettuceLeaderElector(connection, options)
}
