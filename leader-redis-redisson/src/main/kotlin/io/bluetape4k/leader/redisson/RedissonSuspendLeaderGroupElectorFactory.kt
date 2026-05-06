package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.redisson.api.RedissonClient

/**
 * [RedissonSuspendLeaderGroupElector] 팩토리 — Redisson 분산 Semaphore 기반 suspend 복수 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = RedissonSuspendLeaderGroupElectorFactory(redissonClient)
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param redissonClient 공유 Redisson 클라이언트. 호출자가 수명 관리.
 */
class RedissonSuspendLeaderGroupElectorFactory(
    private val redissonClient: RedissonClient,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        RedissonSuspendLeaderGroupElector(redissonClient, options)
}
