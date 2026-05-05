package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderGroupElection
import io.bluetape4k.leader.LeaderGroupElectionFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.lettuce.core.api.StatefulRedisConnection

/**
 * [LettuceLeaderGroupElection] 팩토리 — Lettuce Redis 클라이언트 기반 다중 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = LettuceLeaderGroupElectionFactory(connection)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param connection 공유 Redis connection
 */
class LettuceLeaderGroupElectionFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : LeaderGroupElectionFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElection =
        LettuceLeaderGroupElection(connection, options)
}
