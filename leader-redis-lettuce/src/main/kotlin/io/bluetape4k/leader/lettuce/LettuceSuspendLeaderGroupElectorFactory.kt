package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.lettuce.core.api.StatefulRedisConnection

/**
 * [LettuceSuspendLeaderGroupElector] 팩토리 — Lettuce Redis 클라이언트 기반 suspend 복수 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = LettuceSuspendLeaderGroupElectorFactory(connection)
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @param connection 공유 Redis connection. 호출자가 수명 관리.
 */
class LettuceSuspendLeaderGroupElectorFactory(
    private val connection: StatefulRedisConnection<String, String>,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        LettuceSuspendLeaderGroupElector(connection, options)
}
