package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import io.lettuce.core.api.StatefulRedisConnection

/**
 * [LettuceLeaderElector] 팩토리 — Lettuce Redis 클라이언트 기반 단일 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val connection: StatefulRedisConnection<String, String> = redisClient.connect()
 * val factory = LettuceLeaderElectionFactory(connection)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param connection 공유 Redis connection. 호출자가 수명 관리. factory 가 인스턴스를 새로 만들어도
 *                   동일 connection 을 공유하여 connection pool overhead 를 회피한다.
 */
class LettuceLeaderElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        LettuceLeaderElector(connection, options)
}
