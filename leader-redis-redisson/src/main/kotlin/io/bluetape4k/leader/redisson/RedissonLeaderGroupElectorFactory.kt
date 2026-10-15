package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import org.redisson.api.RedissonClient

/**
 * `RedissonLeaderGroupElectorFactory`는 공유 Redis Redisson client와 그룹 옵션으로 그룹 elector를 생성하며, 호출마다 동일 client를 재사용합니다.
 *
 * @property redissonClient 각 그룹 elector가 permit semaphore와 audit map을 만드는 데 공유할 Redisson client입니다.
 */
class RedissonLeaderGroupElectorFactory(
    private val redissonClient: RedissonClient,
): LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        RedissonLeaderGroupElector(redissonClient, options)
}
