package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.lettuce.core.api.StatefulRedisConnection

/**
 * Factory for [LettuceLeaderGroupElector] — multi-leader election backed by the Lettuce Redis client.
 *
 * ## Usage
 * ```kotlin
 * val factory = LettuceLeaderGroupElectionFactory(connection)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param connection Shared Redis connection
 */
class LettuceLeaderGroupElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        LettuceLeaderGroupElector(connection, options)
}
