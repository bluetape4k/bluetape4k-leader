package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.redisson.api.RedissonClient

/**
 * Factory for [RedissonLeaderGroupElector] — multi-leader election backed by Redisson `RSemaphore`.
 *
 * ## Usage
 * ```kotlin
 * val factory = RedissonLeaderGroupElectionFactory(redissonClient)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param redissonClient Shared Redisson client
 */
class RedissonLeaderGroupElectorFactory(
    private val redissonClient: RedissonClient,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        RedissonLeaderGroupElector(redissonClient, options)
}
