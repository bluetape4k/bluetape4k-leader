package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.lettuce.core.api.StatefulRedisConnection

/**
 * [LettuceSuspendLeaderElector] 팩토리 — Lettuce Redis 클라이언트 기반 suspend 단일 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = LettuceSuspendLeaderElectorFactory(connection)
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param connection 공유 Redis connection. 호출자가 수명 관리.
 */
class LettuceSuspendLeaderElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        LettuceSuspendLeaderElector(connection, options)
}
