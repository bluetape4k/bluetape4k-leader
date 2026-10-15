package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.logging.KLogging

/**
 * `HazelcastLeaderElectorFactory`는 공유 HazelcastInstance와 단일 선출 옵션으로 elector를 생성합니다.
 *
 * @property hazelcast 각 `create` 호출이 공유할 `IMap`과 transaction context를 제공하는 `HazelcastInstance`입니다.
 */
class HazelcastLeaderElectorFactory(
    private val hazelcast: HazelcastInstance,
): LeaderElectorFactory {

    companion object: KLogging()

    override fun create(options: LeaderElectionOptions): LeaderElector =
        HazelcastLeaderElector(hazelcast, options)
}
