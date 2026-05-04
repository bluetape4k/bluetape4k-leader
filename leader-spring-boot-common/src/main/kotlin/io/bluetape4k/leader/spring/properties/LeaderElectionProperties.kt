package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderElectionOptions
import java.time.Duration

/**
 * 리더 선출 자동 구성 속성입니다.
 *
 * Spring Boot 3/4 양쪽에서 공유하는 Boot 버전 독립 속성 클래스입니다.
 * 각 Boot 버전 모듈에서 `@ConfigurationProperties(prefix = "leader")`를 붙여 등록합니다.
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

    /**
     * 이 속성을 [LeaderElectionOptions]으로 변환합니다.
     */
    fun toOptions(): LeaderElectionOptions =
        LeaderElectionOptions(
            waitTime = waitTime,
            leaseTime = leaseTime,
        )
}
