package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import org.bson.Document

/**
 * Factory for [MongoLeaderElector] — single leader election backed by the MongoDB sync client.
 *
 * ## Usage
 * ```kotlin
 * val collection: MongoCollection<Document> = database.getCollection("leader_lock")
 * val factory = MongoLeaderElectionFactory(collection)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * ## Option handling
 * The [LeaderElectionOptions] passed by an AOP advice only carries `waitTime`/`leaseTime`.
 * MongoDB-specific options (e.g. `retryDelay`) are fixed at factory construction time via [baseOptions];
 * each call replaces only [LeaderElectionOptions] via `baseOptions.copy(leaderOptions = options)`.
 *
 * Calls to `MongoLeaderElector(...)` are routed through the companion `operator fun invoke`,
 * which also runs [MongoLock.ensureIndexes].
 *
 * @param collection lock collection
 * @param baseOptions MongoDB-specific option defaults. Only `waitTime`/`leaseTime` are replaced on each AOP call.
 */
class MongoLeaderElectorFactory(
    private val collection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        MongoLeaderElector(collection, baseOptions.copy(leaderOptions = options))
}
