package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderElectionOptions
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * `LeaderElectionProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property waitTime Spring Boot integration 계약에서 `waitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTime Spring Boot integration 계약에서 `leaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property group Spring Boot integration 계약에서 `group` 값을 계산하거나 전달할 때 사용하는 속성입니다.
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
