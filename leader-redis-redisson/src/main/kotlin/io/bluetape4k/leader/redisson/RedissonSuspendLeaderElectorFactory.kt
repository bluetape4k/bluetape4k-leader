package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.logging.KLogging
import org.redisson.api.RedissonClient

/**
 * `RedissonSuspendLeaderElectorFactory`는 공유 Redis Redisson client와 단일 선출 옵션으로 elector를 생성합니다.
 *
 * @property redissonClient 각 suspend elector가 async `RLock`을 만드는 데 공유할 Redisson client입니다.
 */
class RedissonSuspendLeaderElectorFactory(
    private val redissonClient: RedissonClient,
): SuspendLeaderElectorFactory {

    companion object: KLogging()

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        RedissonSuspendLeaderElector(redissonClient, options)
}
