package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * Factory for [HazelcastLeaderGroupElector] — Hazelcast `ISemaphore`-based multi-leader election.
 *
 * ## Usage
 * ```kotlin
 * val factory = HazelcastLeaderGroupElectionFactory(hazelcast)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param hazelcast Shared [HazelcastInstance]
 */
class HazelcastLeaderGroupElectorFactory(
    private val hazelcast: HazelcastInstance,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        HazelcastLeaderGroupElector(hazelcast, options)
}
