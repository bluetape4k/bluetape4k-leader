package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.lettuce.core.api.StatefulRedisConnection

/**
 * `LettuceSuspendLeaderElectorFactory`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property connection Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class LettuceSuspendLeaderElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        LettuceSuspendLeaderElector(connection, options)
}
