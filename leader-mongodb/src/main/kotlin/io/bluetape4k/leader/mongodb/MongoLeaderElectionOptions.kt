package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * MongoDB 기반 리더 선출에 사용하는 옵션 데이터 클래스입니다.
 *
 * ```kotlin
 * val options = MongoLeaderElectionOptions(
 *     leaderOptions = LeaderElectionOptions(
 *         waitTime = 3.seconds,
 *         leaseTime = 30.seconds,
 *     ),
 *     retryDelay = 100.milliseconds,
 * )
 * val election = MongoLeaderElector(collection, options)
 * val result = election.runIfLeader("job-lock") { "done" }
 * // result == "done"
 * ```
 *
 * @property leaderOptions 단일 리더 선출 옵션 (waitTime, leaseTime)
 * @property retryDelay 락 획득 재시도 시 적용할 full jitter 상한 (`[1ms, retryDelay)` 균등 분포). 기본값 50ms
 */
data class MongoLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {
    init {
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * 기본 옵션 인스턴스 (`waitTime=5s`, `leaseTime=60s`, `retryDelay=50ms`).
         */
        @JvmField
        val Default = MongoLeaderElectionOptions()
    }
}
