package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderGroupElectionOptions
import java.time.Duration

/**
 * 복수 리더 그룹 선출 자동 구성 속성입니다.
 *
 * [LeaderElectionProperties.group]에 중첩되어 사용됩니다.
 *
 * ```yaml
 * leader:
 *   group:
 *     max-leaders: 3
 *     wait-time: 5s
 *     lease-time: 60s
 * ```
 *
 * @property maxLeaders 허용하는 최대 동시 리더 수. 기본값 2
 * @property waitTime 슬롯 획득 대기 최대 시간. 기본값 5초
 * @property leaseTime 슬롯 보유(임대) 최대 시간. 기본값 60초
 */
data class LeaderGroupProperties(
    val maxLeaders: Int = DefaultMaxLeaders,
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
) {
    companion object {
        const val DefaultMaxLeaders: Int = 2
        val DefaultWaitTime: Duration = Duration.ofSeconds(5)
        val DefaultLeaseTime: Duration = Duration.ofSeconds(60)
    }

    fun toOptions(): LeaderGroupElectionOptions =
        LeaderGroupElectionOptions(
            maxLeaders = maxLeaders,
            waitTime = waitTime,
            leaseTime = leaseTime,
        )
}
