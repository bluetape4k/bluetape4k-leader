package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions

/**
 * [HazelcastLeaderElector] 팩토리 — Hazelcast 기반 단일 리더 선출.
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
class HazelcastLeaderElectorFactory(
    private val hazelcast: HazelcastInstance,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        HazelcastLeaderElector(hazelcast, options)
}
