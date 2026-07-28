package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * `HazelcastLeaderGroupElectorFactory`는 Hazelcast backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property hazelcast Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class HazelcastLeaderGroupElectorFactory(
    private val hazelcast: HazelcastInstance,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        HazelcastLeaderGroupElector(hazelcast, options)
}
