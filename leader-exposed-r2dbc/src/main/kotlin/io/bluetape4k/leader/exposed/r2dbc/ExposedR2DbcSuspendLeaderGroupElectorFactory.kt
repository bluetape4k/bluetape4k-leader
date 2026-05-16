package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * Factory for [ExposedR2DbcSuspendLeaderGroupElector] — suspend multi-leader election backed by Exposed R2DBC.
 *
 * ## Option handling
 * Exposed R2DBC-specific options (`retryStrategy`, `recordHistory`) are fixed at factory construction time
 * via [baseOptions]; each call replaces only `maxLeaders`/`waitTime`/`leaseTime` via
 * `baseOptions.copy(leaderGroupOptions = options)`.
 *
 * Calls to `ExposedR2DbcSuspendLeaderGroupElector(...)` are routed through the companion
 * `suspend operator fun invoke`, which also runs [ExposedR2dbcSchemaInitializer.ensureSchema].
 *
 * ## Usage
 * ```kotlin
 * val factory = ExposedR2DbcSuspendLeaderGroupElectorFactory(r2dbcDatabase)
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param db Exposed [R2dbcDatabase] instance
 * @param baseOptions Exposed R2DBC-specific option defaults
 */
class ExposedR2DbcSuspendLeaderGroupElectorFactory(
    private val db: R2dbcDatabase,
    private val baseOptions: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        ExposedR2DbcSuspendLeaderGroupElector(db, baseOptions.copy(leaderGroupOptions = options))
}
