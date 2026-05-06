package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderElectionOptions
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * 리더 선출 자동 구성 속성입니다.
 *
 * ```yaml
 * leader:
 *   wait-time: 5s
 *   lease-time: 60s
 *   group:
 *     max-leaders: 3
 *     wait-time: 5s
 *     lease-time: 60s
 * ```
 *
 * @property waitTime 리더 획득 대기 최대 시간. 기본값 5초
 * @property leaseTime 리더 보유(임대) 최대 시간. 기본값 60초
 * @property group 복수 리더 그룹 선출 속성
 */
data class LeaderElectionProperties(
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val group: LeaderGroupProperties = LeaderGroupProperties(),
) {
    companion object {
        val DefaultWaitTime: Duration = Duration.ofSeconds(5)
        val DefaultLeaseTime: Duration = Duration.ofSeconds(60)
    }

    fun toOptions(): LeaderElectionOptions =
        LeaderElectionOptions(
            waitTime = waitTime.toKotlinDuration(),
            leaseTime = leaseTime.toKotlinDuration(),
        )
}
