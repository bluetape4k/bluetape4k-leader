package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * Factory SPI that creates [SuspendLeaderGroupElector] instances with per-call options.
 *
 * ## Usage
 * ```kotlin
 * val factory: SuspendLeaderGroupElectorFactory = LocalSuspendLeaderGroupElectorFactory()
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3, waitTime = 3.seconds))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @see SuspendLeaderElectorFactory single-leader suspend factory
 * @see io.bluetape4k.leader.LeaderGroupElectorFactory sync version factory
 */
fun interface SuspendLeaderGroupElectorFactory {

    /**
     * Creates a new [SuspendLeaderGroupElector] instance with the given [options].
     *
     * @param options options to apply to the new instance (maxLeaders, waitTime, leaseTime)
     * @return a [SuspendLeaderGroupElector] instance configured with the per-call options
     */
    suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector
}
