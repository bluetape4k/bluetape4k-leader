package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderElection
import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.leader.LeaderElectionOptions
import org.redisson.api.RedissonClient

/**
 * [RedissonLeaderElection] 팩토리 — Redisson 클라이언트 기반 단일 리더 선출.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = RedissonLeaderElectionFactory(redissonClient)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @param redissonClient 공유 Redisson 클라이언트. factory 가 인스턴스를 새로 만들어도 동일 클라이언트
 *                       (즉 동일 connection pool) 를 공유한다.
 */
class RedissonLeaderElectionFactory(
    private val redissonClient: RedissonClient,
) : LeaderElectionFactory {

    override fun create(options: LeaderElectionOptions): LeaderElection =
        RedissonLeaderElection(redissonClient, options)
}
