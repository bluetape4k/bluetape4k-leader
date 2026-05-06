package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * MongoDB 기반 복수 리더 그룹 선출에 사용하는 옵션 데이터 클래스입니다.
 *
 * ```kotlin
 * val options = MongoLeaderGroupElectionOptions(
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     retryDelay = 100.milliseconds,
 * )
 * val election = MongoLeaderGroupElector(groupCollection, options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * // result == processChunk() 반환값 (슬롯 획득 성공) 또는 null (슬롯 없음)
 * ```
 *
 * @property leaderGroupOptions 그룹 리더 선출 옵션 (maxLeaders, waitTime, leaseTime)
 * @property retryDelay 락 획득 재시도 시 적용할 full jitter 상한 (`[1ms, retryDelay)` 균등 분포). 기본값 50ms
 */
data class MongoLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {

    /** 허용하는 최대 동시 리더 수 ([LeaderGroupElectionOptions.maxLeaders] 위임). */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        maxLeaders.requirePositiveNumber("maxLeaders")
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * 기본 옵션 인스턴스 (`maxLeaders=2`, `waitTime=5s`, `leaseTime=60s`, `retryDelay=50ms`).
         */
        @JvmField
        val Default = MongoLeaderGroupElectionOptions()
    }
}
