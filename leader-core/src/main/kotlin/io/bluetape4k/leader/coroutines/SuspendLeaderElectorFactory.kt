package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions

/**
 * Factory SPI that creates [SuspendLeaderElector] instances with per-call options.
 *
 * ## Background
 * [SuspendLeaderElector.runIfLeader] only provides a `(lockName, action)` signature and has no means
 * to accept per-call options. This SPI is added so that AOP advice can create and cache suspend
 * backend instances for each set of annotation options.
 *
 * ## Usage Example
 * ```kotlin
 * val factory: SuspendLeaderElectorFactory = LocalSuspendLeaderElectorFactory()
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @see SuspendLeaderGroupElectorFactory suspend factory for multi-leader election
 * @see io.bluetape4k.leader.LeaderElectorFactory sync version factory
 */
fun interface SuspendLeaderElectorFactory {

    /**
     * Creates a new [SuspendLeaderElector] instance with the given [options].
     *
     * @param options the options to apply to the new instance (waitTime, leaseTime)
     * @return a [SuspendLeaderElector] instance with the per-call options applied
     */
    suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector
}
