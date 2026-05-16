package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoCollection as CoroutineMongoCollection
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.bson.Document

/**
 * Factory for [MongoSuspendLeaderGroupElector] — suspend multi-leader election backed by MongoDB.
 *
 * ## Option handling
 * MongoDB-specific options are fixed at factory construction time via [baseOptions];
 * each call replaces only `maxLeaders`/`waitTime`/`leaseTime` via
 * `baseOptions.copy(leaderGroupOptions = options)`.
 *
 * Calls to `MongoSuspendLeaderGroupElector(...)` are routed through the companion
 * `suspend operator fun invoke`, which also runs
 * [io.bluetape4k.leader.mongodb.lock.MongoSuspendLock.ensureIndexes].
 *
 * ## Usage
 * ```kotlin
 * val factory = MongoSuspendLeaderGroupElectorFactory(syncCollection, coroutineCollection)
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param groupCollection lock collection (sync driver — used for activeCount)
 * @param coroutineGroupCollection lock collection (coroutine driver — used for tryLock/unlock)
 * @param baseOptions MongoDB-specific option defaults
 */
class MongoSuspendLeaderGroupElectorFactory(
    private val groupCollection: MongoCollection<Document>,
    private val coroutineGroupCollection: CoroutineMongoCollection<Document>,
    private val baseOptions: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        MongoSuspendLeaderGroupElector(
            groupCollection,
            coroutineGroupCollection,
            baseOptions.copy(leaderGroupOptions = options),
        )
}
