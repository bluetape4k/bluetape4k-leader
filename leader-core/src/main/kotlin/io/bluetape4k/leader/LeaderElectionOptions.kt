package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 리더 선출에 사용하는 옵션 데이터 클래스입니다.
 *
 * ```kotlin
 * val options = LeaderElectionOptions(
 *     waitTime = 3.seconds,
 *     leaseTime = 30.seconds,
 * )
 * val election = LocalLeaderElector(options)
 * val result = election.runIfLeader("job-lock") { "done" }
 * // result == "done"
 * ```
 *
 * @property waitTime 리더 획득 대기 최대 시간. 기본값 5초
 * @property leaseTime 리더 보유(임대) 최대 시간. 기본값 60초
 * @property minLeaseTime 작업이 빨리 끝나도 리더 lease를 최소로 보유할 시간. 기본값 0초
 */
data class LeaderElectionOptions(
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val minLeaseTime: Duration = Duration.ZERO,
): Serializable {
    init {
        waitTime.requireGe(Duration.ZERO, "waitTime")
        leaseTime.requireGt(Duration.ZERO, "leaseTime")
        minLeaseTime.requireGe(Duration.ZERO, "minLeaseTime")
        require(minLeaseTime <= leaseTime) {
            "minLeaseTime must not exceed leaseTime: minLeaseTime=$minLeaseTime, leaseTime=$leaseTime"
        }
    }

    companion object {
        val DefaultWaitTime: Duration = 5.seconds
        val DefaultLeaseTime: Duration = 60.seconds

        /**
         * 기본 옵션 인스턴스 (`waitTime=5s`, `leaseTime=60s`).
         *
         * ```kotlin
         * val election = LocalLeaderElector(LeaderElectionOptions.Default)
         * ```
         */
        @JvmField
        val Default = LeaderElectionOptions()

        private const val serialVersionUID = 1L
    }
}
