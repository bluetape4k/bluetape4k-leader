package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * Factory for [LocalSuspendLeaderGroupElector] — creates single-JVM suspend multi-leader election instances based on `kotlinx.coroutines.sync.Semaphore`.
 *
 * ## Usage
 * ```kotlin
 * val factory = LocalSuspendLeaderGroupElectorFactory()
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 */
class LocalSuspendLeaderGroupElectorFactory : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        LocalSuspendLeaderGroupElector(options)
}
