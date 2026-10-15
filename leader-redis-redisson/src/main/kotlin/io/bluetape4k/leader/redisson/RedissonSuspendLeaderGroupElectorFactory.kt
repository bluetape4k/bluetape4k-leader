package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.logging.KLogging
import org.redisson.api.RedissonClient

/**
 * `RedissonSuspendLeaderGroupElectorFactory`는 공유 Redis Redisson client와 그룹 옵션으로 그룹 elector를 생성하며, 호출마다 동일 client를 재사용합니다.
 *
 * @property redissonClient 각 suspend 그룹 elector가 async permit semaphore와 audit map을 만드는 데 공유할 Redisson client입니다.
 */
class RedissonSuspendLeaderGroupElectorFactory(
    private val redissonClient: RedissonClient,
): SuspendLeaderGroupElectorFactory {

    companion object: KLogging()

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        RedissonSuspendLeaderGroupElector(redissonClient, options)
}
