package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 복수 리더 그룹 선출에 사용하는 옵션 데이터 클래스입니다.
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(
 *     maxLeaders = 3,
 *     waitTime = 3.seconds,
 *     leaseTime = 30.seconds,
 * )
 * val election = LocalLeaderGroupElector(options)
 * val result = election.runIfLeader("batch-job") { "done" }
 * // result == "done"
 * ```
 *
 * @property maxLeaders 허용하는 최대 동시 리더 수. 기본값 2
 * @property waitTime 리더 획득 대기 최대 시간. 기본값 5초
 * @property leaseTime 리더 보유(임대) 최대 시간. 기본값 60초
 */
data class LeaderGroupElectionOptions(
    val maxLeaders: Int = DefaultMaxLeaders,
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
): Serializable {

    init {
        maxLeaders.requireGe(1, "maxLeaders")
        waitTime.requireGe(Duration.ZERO, "waitTime")
        leaseTime.requireGt(Duration.ZERO, "leaseTime")
    }

    companion object {
        const val DefaultMaxLeaders: Int = 2
        val DefaultWaitTime: Duration = 5.seconds
        val DefaultLeaseTime: Duration = 60.seconds

        /**
         * 기본 옵션 인스턴스 (`maxLeaders=2`, `waitTime=5s`, `leaseTime=60s`).
         *
         * ```kotlin
         * val election = LocalLeaderGroupElector(LeaderGroupElectionOptions.Default)
         * ```
         */
        @JvmField
        val Default = LeaderGroupElectionOptions()

        private const val serialVersionUID = 1L
    }
}
