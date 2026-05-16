package io.bluetape4k.leader

/**
 * Factory SPI that creates [LeaderGroupElector] instances with per-call options.
 *
 * ## Background
 * The core interface [LeaderGroupElector.runIfLeader] only provides a `(lockName, action)` signature
 * and has no means to accept per-call options. This SPI is added so that AOP advice can create
 * and cache backend instances for each set of annotation options (`maxLeaders`, `waitTime`, `leaseTime`).
 *
 * ## Usage Example
 * ```kotlin
 * val factory: LeaderGroupElectionFactory = RedissonLeaderGroupElectionFactory(redisson)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @see LeaderElectorFactory factory for single-leader election
 */
fun interface LeaderGroupElectorFactory {

    /**
     * Creates a new [LeaderGroupElector] instance with the given [options].
     *
     * @param options the options to apply to the new instance (maxLeaders, waitTime, leaseTime)
     * @return a new [LeaderGroupElector] instance with the per-call options applied
     */
    fun create(options: LeaderGroupElectionOptions): LeaderGroupElector
}
