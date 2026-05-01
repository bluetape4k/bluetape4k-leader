package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.LeaderGroupElectionOptions
import java.io.Serializable
import java.time.Duration

/**
 * MongoDB 기반 복수 리더 그룹 선출에 사용하는 옵션 데이터 클래스입니다.
 *
 * ```kotlin
 * val options = MongoLeaderGroupElectionOptions(
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     retryDelay = Duration.ofMillis(100),
 * )
 * val election = MongoLeaderGroupElection(groupCollection, options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * // result == processChunk() 반환값 (슬롯 획득 성공) 또는 null (슬롯 없음)
 * ```
 *
 * @property leaderGroupOptions 그룹 리더 선출 옵션 (maxLeaders, waitTime, leaseTime)
 * @property retryDelay 락 획득 재시도 대기 기본 시간 (jitter 적용 전). 기본값 50ms
 * @property releaseTimeout suspend 코드에서 락 해제 시 적용할 타임아웃. 기본값 5초
 */
data class MongoLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryDelay: Duration = Duration.ofMillis(50),
    val releaseTimeout: Duration = Duration.ofSeconds(5),
) : Serializable {

    /** 허용하는 최대 동시 리더 수 ([LeaderGroupElectionOptions.maxLeaders] 위임). */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        require(maxLeaders > 0) { "maxLeaders must be positive: $maxLeaders" }
        require(retryDelay > Duration.ZERO) { "retryDelay must be positive: $retryDelay" }
        require(releaseTimeout > Duration.ZERO) { "releaseTimeout must be positive: $releaseTimeout" }
    }

    companion object {
        /**
         * 기본 옵션 인스턴스 (`maxLeaders=2`, `waitTime=5s`, `leaseTime=60s`, `retryDelay=50ms`).
         */
        @JvmField
        val Default = MongoLeaderGroupElectionOptions()
    }
}
