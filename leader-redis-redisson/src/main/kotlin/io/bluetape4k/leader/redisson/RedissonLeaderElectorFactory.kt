package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.logging.KLogging
import org.redisson.api.RedissonClient

/**
 * `RedissonLeaderElectorFactory`는 공유 Redis Redisson client와 단일 선출 옵션으로 elector를 생성합니다.
 *
 * @property redissonClient 각 elector가 `RLock`을 만들고 lease를 관리하는 데 공유할 Redisson client입니다.
 */
class RedissonLeaderElectorFactory(
    private val redissonClient: RedissonClient,
): LeaderElectorFactory {

    companion object: KLogging()

    override fun create(options: LeaderElectionOptions): LeaderElector =
        RedissonLeaderElector(redissonClient, options)
}
