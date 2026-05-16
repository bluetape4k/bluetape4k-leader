package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.bson.Document

/**
 * Factory for [MongoLeaderGroupElector] — multi-leader election backed by the MongoDB sync client.
 *
 * ## Usage
 * ```kotlin
 * val factory = MongoLeaderGroupElectionFactory(groupCollection)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * Replaces `maxLeaders`/`waitTime`/`leaseTime` on each call via
 * `baseOptions.copy(leaderGroupOptions = options)` while preserving `retryDelay`.
 *
 * @param groupCollection group lock collection
 * @param baseOptions MongoDB-specific option defaults
 */
class MongoLeaderGroupElectorFactory(
    private val groupCollection: MongoCollection<Document>,
    private val baseOptions: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        MongoLeaderGroupElector(groupCollection, baseOptions.copy(leaderGroupOptions = options))
}
