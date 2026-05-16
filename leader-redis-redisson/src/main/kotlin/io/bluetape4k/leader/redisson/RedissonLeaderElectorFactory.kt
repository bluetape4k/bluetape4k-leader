package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import org.redisson.api.RedissonClient

/**
 * Factory for [RedissonLeaderElector] — single-leader election backed by the Redisson client.
 *
 * ## Usage
 * ```kotlin
 * val factory = RedissonLeaderElectionFactory(redissonClient)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param redissonClient Shared Redisson client. All factory-created instances share the same client
 *                       (i.e., the same connection pool).
 */
class RedissonLeaderElectorFactory(
    private val redissonClient: RedissonClient,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        RedissonLeaderElector(redissonClient, options)
}
