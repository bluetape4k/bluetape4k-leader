package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.redisson.api.RedissonClient

/**
 * Factory for [RedissonSuspendLeaderElector] — suspend single-leader election backed by a Redisson distributed lock.
 *
 * ## Usage
 * ```kotlin
 * val factory = RedissonSuspendLeaderElectorFactory(redissonClient)
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param redissonClient Shared Redisson client whose lifecycle is managed by the caller.
 */
class RedissonSuspendLeaderElectorFactory(
    private val redissonClient: RedissonClient,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        RedissonSuspendLeaderElector(redissonClient, options)
}
