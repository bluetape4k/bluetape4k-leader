package io.bluetape4k.leader.mongodb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.bson.Document

/**
 * Factory for [MongoSuspendLeaderElector] — suspend single leader election backed by MongoDB distributed locks.
 *
 * ## Option handling
 * MongoDB-specific options (e.g. `retryDelay`) are fixed at factory construction time via [baseOptions];
 * each call replaces only `waitTime`/`leaseTime` via `baseOptions.copy(leaderOptions = options)`.
 *
 * Calls to `MongoSuspendLeaderElector(...)` are routed through the companion `suspend operator fun invoke`,
 * which also runs [io.bluetape4k.leader.mongodb.lock.MongoSuspendLock.ensureIndexes].
 *
 * ## Usage
 * ```kotlin
 * val factory = MongoSuspendLeaderElectorFactory(coroutineCollection)
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param collection lock collection (coroutine driver)
 * @param baseOptions MongoDB-specific option defaults
 */
class MongoSuspendLeaderElectorFactory(
    private val collection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        MongoSuspendLeaderElector(collection, baseOptions.copy(leaderOptions = options))
}
