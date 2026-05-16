package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions

/**
 * [LocalLeaderElector] factory — creates single-JVM leader election instances backed by `ReentrantLock`.
 *
 * ## Usage
 * ```kotlin
 * val factory = LocalLeaderElectionFactory()
 * val election = factory.create(LeaderElectionOptions.Default)
 * val result = election.runIfLeader("job-lock") { "done" }
 * ```
 *
 * Every call returns a new [LocalLeaderElector] instance. Serialization for the same lock name is
 * guaranteed by the lock map shared statically inside [AbstractLocalLeaderElector], so mutual
 * exclusion across the same lockName is preserved even across different instances.
 */
class LocalLeaderElectorFactory : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        LocalLeaderElector(options)
}
