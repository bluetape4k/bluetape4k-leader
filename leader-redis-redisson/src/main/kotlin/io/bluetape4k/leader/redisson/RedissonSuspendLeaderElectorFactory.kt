package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.redisson.api.RedissonClient

/**
 * [RedissonSuspendLeaderElector] 팩토리 — Redisson 분산 락 기반 suspend 단일 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = RedissonSuspendLeaderElectorFactory(redissonClient)
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param redissonClient 공유 Redisson 클라이언트. 호출자가 수명 관리.
 */
class RedissonSuspendLeaderElectorFactory(
    private val redissonClient: RedissonClient,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        RedissonSuspendLeaderElector(redissonClient, options)
}
