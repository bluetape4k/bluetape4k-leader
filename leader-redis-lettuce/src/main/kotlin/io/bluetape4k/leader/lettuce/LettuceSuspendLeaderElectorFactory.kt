package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.lettuce.core.api.StatefulRedisConnection

/**
 * Factory for [LettuceSuspendLeaderElector] — suspend single-leader election backed by the Lettuce Redis client.
 *
 * ## Usage
 * ```kotlin
 * val factory = LettuceSuspendLeaderElectorFactory(connection)
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param connection Shared Redis connection whose lifecycle is managed by the caller.
 */
class LettuceSuspendLeaderElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        LettuceSuspendLeaderElector(connection, options)
}
