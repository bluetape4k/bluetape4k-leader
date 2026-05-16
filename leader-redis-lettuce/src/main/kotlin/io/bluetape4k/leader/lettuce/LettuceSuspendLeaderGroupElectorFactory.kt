package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.lettuce.core.api.StatefulRedisConnection

/**
 * Factory for [LettuceSuspendLeaderGroupElector] — suspend multi-leader election backed by the Lettuce Redis client.
 *
 * ## Usage
 * ```kotlin
 * val factory = LettuceSuspendLeaderGroupElectorFactory(connection)
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param connection Shared Redis connection whose lifecycle is managed by the caller.
 */
class LettuceSuspendLeaderGroupElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        LettuceSuspendLeaderGroupElector(connection, options)
}
