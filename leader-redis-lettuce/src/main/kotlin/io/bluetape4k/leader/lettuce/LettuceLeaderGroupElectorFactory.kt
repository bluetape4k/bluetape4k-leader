package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.lettuce.core.api.StatefulRedisConnection

/**
 * `LettuceLeaderGroupElectorFactory`는 공유 Redis Lettuce connection과 그룹 옵션으로 그룹 elector를 생성하며, 호출마다 동일 client를 재사용합니다.
 *
 * @property connection 각 그룹 elector가 slot sorted-set과 metadata hash에 사용할 `StatefulRedisConnection`입니다.
 */
class LettuceLeaderGroupElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
): LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        LettuceLeaderGroupElector(connection, options)
}
