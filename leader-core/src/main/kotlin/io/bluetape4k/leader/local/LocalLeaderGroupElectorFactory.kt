package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * Factory for [LocalLeaderGroupElector] — creates single-JVM multi-leader election instances based on Semaphore.
 *
 * ## Usage
 * ```kotlin
 * val factory = LocalLeaderGroupElectionFactory()
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 */
class LocalLeaderGroupElectorFactory : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        LocalLeaderGroupElector(options)
}
