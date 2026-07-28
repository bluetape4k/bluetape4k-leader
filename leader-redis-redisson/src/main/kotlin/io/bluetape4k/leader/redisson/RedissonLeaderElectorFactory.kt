package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import org.redisson.api.RedissonClient

/**
 * `RedissonLeaderElectorFactory`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property redissonClient Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class RedissonLeaderElectorFactory(
    private val redissonClient: RedissonClient,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        RedissonLeaderElector(redissonClient, options)
}
