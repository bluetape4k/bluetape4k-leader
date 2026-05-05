package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * [HazelcastLeaderGroupElector] 팩토리 — Hazelcast `ISemaphore` 기반 다중 리더 선출.
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
class HazelcastLeaderGroupElectorFactory(
    private val hazelcast: HazelcastInstance,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        HazelcastLeaderGroupElector(hazelcast, options)
}
