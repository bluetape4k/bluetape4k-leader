package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.lettuce.core.api.StatefulRedisConnection

/**
 * `LettuceSuspendLeaderElectorFactory`는 공유 Redis Lettuce connection과 단일 선출 옵션으로 elector를 생성합니다.
 *
 * @property connection 각 suspend elector가 lock 연산에 공유할 `StatefulRedisConnection`입니다.
 */
class LettuceSuspendLeaderElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
): SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        LettuceSuspendLeaderElector(connection, options)
}
