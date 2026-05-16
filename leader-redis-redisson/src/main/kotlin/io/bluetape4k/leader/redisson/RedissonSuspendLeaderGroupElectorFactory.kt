package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.redisson.api.RedissonClient

/**
 * Factory for [RedissonSuspendLeaderGroupElector] — suspend multi-leader election backed by a Redisson distributed semaphore.
 *
 * ## Usage
 * ```kotlin
 * val factory = RedissonSuspendLeaderGroupElectorFactory(redissonClient)
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param redissonClient Shared Redisson client whose lifecycle is managed by the caller.
 */
class RedissonSuspendLeaderGroupElectorFactory(
    private val redissonClient: RedissonClient,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        RedissonSuspendLeaderGroupElector(redissonClient, options)
}
