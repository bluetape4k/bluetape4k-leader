package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderElection
import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.leader.LeaderElectionOptions

/**
 * [HazelcastLeaderElection] 팩토리 — Hazelcast 기반 단일 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val hazelcast: HazelcastInstance = ...
 * val factory = HazelcastLeaderElectionFactory(hazelcast)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param hazelcast 공유 [HazelcastInstance]
 */
class HazelcastLeaderElectionFactory(
    private val hazelcast: HazelcastInstance,
) : LeaderElectionFactory {

    override fun create(options: LeaderElectionOptions): LeaderElection =
        HazelcastLeaderElection(hazelcast, options)
}
