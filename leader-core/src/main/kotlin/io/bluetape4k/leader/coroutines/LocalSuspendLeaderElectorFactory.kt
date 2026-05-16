package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions

/**
 * Factory for [LocalSuspendLeaderElector] — creates single-JVM suspend leader election instances based on `kotlinx.coroutines.sync.Mutex`.
 *
 * ## Usage
 * ```kotlin
 * val factory = LocalSuspendLeaderElectorFactory()
 * val elector = factory.create(LeaderElectionOptions.Default)
 * val result = elector.runIfLeader("job-lock") { "done" }
 * ```
 */
class LocalSuspendLeaderElectorFactory : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        LocalSuspendLeaderElector(options)
}
