package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import io.lettuce.core.api.StatefulRedisConnection

/**
 * Factory for [LettuceLeaderElector] — single-leader election backed by the Lettuce Redis client.
 *
 * ## Usage
 * ```kotlin
 * val connection: StatefulRedisConnection<String, String> = redisClient.connect()
 * val factory = LettuceLeaderElectionFactory(connection)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param connection Shared Redis connection whose lifecycle is managed by the caller.
 *                   All factory-created instances share this connection, avoiding connection pool overhead.
 */
class LettuceLeaderElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        LettuceLeaderElector(connection, options)
}
