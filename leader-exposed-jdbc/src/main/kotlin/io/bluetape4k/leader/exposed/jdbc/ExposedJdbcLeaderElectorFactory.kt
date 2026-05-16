package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Factory for [ExposedJdbcLeaderElector] — single-leader election backed by Exposed JDBC.
 *
 * ## Usage
 * ```kotlin
 * val db: Database = Database.connect(dataSource)
 * val factory = ExposedJdbcLeaderElectionFactory(db)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * ## Option handling
 * The [LeaderElectionOptions] passed by an AOP advice only contains `waitTime`/`leaseTime`.
 * Exposed backend-specific options (`retryStrategy`, `lockOwner`) are fixed at factory construction time
 * via [baseOptions], and are merged on each call as `baseOptions.copy(leaderOptions = options)`.
 *
 * Calls to `ExposedJdbcLeaderElector(...)` are routed through the companion `operator fun invoke`,
 * which also runs `ExposedJdbcSchemaInitializer.ensureSchema(db)`.
 *
 * @param db Exposed [Database]
 * @param baseOptions Default values for Exposed-specific options
 */
class ExposedJdbcLeaderElectorFactory(
    private val db: Database,
    private val baseOptions: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ExposedJdbcLeaderElector(db, baseOptions.copy(leaderOptions = options))
}
