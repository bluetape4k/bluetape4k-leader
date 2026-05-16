package io.bluetape4k.leader

/**
 * Factory SPI that creates [LeaderElector] instances with per-call options.
 *
 * ## Background
 * The core interface [LeaderElector.runIfLeader] only provides a `(lockName, action)` signature
 * and has no means to accept per-call options. This SPI is added so that AOP advice can create
 * and cache backend instances for each set of annotation options.
 *
 * ## Usage Example
 * ```kotlin
 * val factory: LeaderElectionFactory = RedissonLeaderElectionFactory(redisson)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * ## Responsibility Separation
 * - This SPI: creates a new [LeaderElector] instance with the options requested by the caller.
 * - Caching: responsibility of the AOP layer or the caller (e.g. `ConcurrentHashMap<FactoryCacheKey, LeaderElector>`).
 * - Backend client lifetime (`RedissonClient`, `MongoClient`, etc.): responsibility of the caller or DI container.
 *
 * @see LeaderGroupElectorFactory factory for group leader election
 */
fun interface LeaderElectorFactory {

    /**
     * Creates a new [LeaderElector] instance with the given [options].
     *
     * A new instance may be returned for the same [options] on every call; identity guarantees are the caller's responsibility.
     *
     * @param options the options to apply to the new instance (waitTime, leaseTime)
     * @return a new [LeaderElector] instance with the per-call options applied
     */
    fun create(options: LeaderElectionOptions): LeaderElector
}
