package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * Factory for [ExposedR2DbcSuspendLeaderElector] — suspend single leader election backed by Exposed R2DBC.
 *
 * ## Option handling
 * Exposed R2DBC-specific options (`retryStrategy`, `recordHistory`) are fixed at factory construction time
 * via [baseOptions]; each call replaces only `waitTime`/`leaseTime` via
 * `baseOptions.copy(leaderOptions = options)`.
 *
 * Calls to `ExposedR2DbcSuspendLeaderElector(...)` are routed through the companion
 * `suspend operator fun invoke`, which also runs [ExposedR2dbcSchemaInitializer.ensureSchema].
 *
 * ## Usage
 * ```kotlin
 * val factory = ExposedR2DbcSuspendLeaderElectorFactory(r2dbcDatabase)
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param db Exposed [R2dbcDatabase] instance
 * @param baseOptions Exposed R2DBC-specific option defaults
 */
class ExposedR2DbcSuspendLeaderElectorFactory(
    private val db: R2dbcDatabase,
    private val baseOptions: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        ExposedR2DbcSuspendLeaderElector(db, baseOptions.copy(leaderOptions = options))
}
