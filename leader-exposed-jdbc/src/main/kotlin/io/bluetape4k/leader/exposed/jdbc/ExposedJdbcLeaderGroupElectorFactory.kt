package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Factory for [ExposedJdbcLeaderGroupElector] — Exposed JDBC-backed multi-leader election.
 *
 * ## Example
 * ```kotlin
 * val factory = ExposedJdbcLeaderGroupElectionFactory(db)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * Each call replaces only `maxLeaders`/`waitTime`/`leaseTime` via
 * `baseOptions.copy(leaderGroupOptions = options)`, preserving `retryStrategy`/`lockOwner`.
 *
 * @param db Exposed [Database]
 * @param baseOptions base Exposed-specific options
 */
class ExposedJdbcLeaderGroupElectorFactory(
    private val db: Database,
    private val baseOptions: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ExposedJdbcLeaderGroupElector(db, baseOptions.copy(leaderGroupOptions = options))
}
