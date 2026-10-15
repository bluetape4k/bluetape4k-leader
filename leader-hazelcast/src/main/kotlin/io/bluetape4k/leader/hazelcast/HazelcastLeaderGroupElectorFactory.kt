package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.logging.KLogging

/**
 * `HazelcastLeaderGroupElectorFactory`는 공유 HazelcastInstance와 그룹 옵션으로 그룹 elector를 생성하며, 호출마다 동일 client를 재사용합니다.
 *
 * @property hazelcast 각 그룹 elector가 공유할 슬롯 `IMap`과 transaction context를 제공하는 `HazelcastInstance`입니다.
 */
class HazelcastLeaderGroupElectorFactory(
    private val hazelcast: HazelcastInstance,
): LeaderGroupElectorFactory {

    companion object: KLogging()

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        HazelcastLeaderGroupElector(hazelcast, options)
}
