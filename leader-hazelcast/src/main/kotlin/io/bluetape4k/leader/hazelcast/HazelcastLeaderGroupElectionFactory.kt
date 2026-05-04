package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderGroupElection
import io.bluetape4k.leader.LeaderGroupElectionFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * [HazelcastLeaderGroupElection] 팩토리 — Hazelcast `ISemaphore` 기반 다중 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = HazelcastLeaderGroupElectionFactory(hazelcast)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param hazelcast 공유 [HazelcastInstance]
 */
class HazelcastLeaderGroupElectionFactory(
    private val hazelcast: HazelcastInstance,
) : LeaderGroupElectionFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElection =
        HazelcastLeaderGroupElection(hazelcast, options)
}
