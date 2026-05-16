package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.VirtualThreadLeaderElector

/**
 * Virtual Thread-based Exposed JDBC leader election implementation.
 *
 * Uses [ExposedJdbcLeaderElector] as the delegate and wraps results in [virtualFuture].
 * Schema initialization is guaranteed by the delegate.
 *
 * ```kotlin
 * val election = ExposedJdbcLeaderElector(db)
 * val vtElection = ExposedJdbcVirtualThreadLeaderElector(election)
 * val result: String? = vtElection.runAsyncIfLeader("job-lock") { processData() }
 *     .get(5, TimeUnit.SECONDS)
 * ```
 *
 * The convenience extension [Database.runVirtualIfLeader] is also available.
 *
 * @property delegate underlying [ExposedJdbcLeaderElector] instance
 */
class ExposedJdbcVirtualThreadLeaderElector(
    private val delegate: ExposedJdbcLeaderElector,
) : VirtualThreadLeaderElector {

    /**
     * Runs leader election for [lockName] asynchronously on a Virtual Thread.
     *
     * @param lockName lock identifier
     * @param action the work to execute when the leader lock is acquired
     * @return [VirtualFuture] holding the [action] result, completing with `null` when the lock cannot be acquired
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(lockName, action)
        }
}
