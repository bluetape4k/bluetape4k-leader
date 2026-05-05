package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderGroupElection
import io.bluetape4k.leader.LeaderGroupElectionFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.redisson.api.RedissonClient

/**
 * [RedissonLeaderGroupElection] 팩토리 — Redisson `RSemaphore` 기반 다중 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = RedissonLeaderGroupElectionFactory(redissonClient)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param redissonClient 공유 Redisson 클라이언트
 */
class RedissonLeaderGroupElectionFactory(
    private val redissonClient: RedissonClient,
) : LeaderGroupElectionFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElection =
        RedissonLeaderGroupElection(redissonClient, options)
}
