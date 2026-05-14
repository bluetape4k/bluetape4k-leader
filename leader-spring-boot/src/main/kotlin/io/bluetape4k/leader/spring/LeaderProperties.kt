package io.bluetape4k.leader.spring

import io.bluetape4k.leader.spring.properties.LeaderElectionProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.time.Duration

/**
 * Spring Boot 자동 구성 진입 속성.
 *
 * `bluetape4k.leader.*` prefix 로 yaml 바인딩됩니다. 백엔드별 옵션 변환은 [adapter.PropertiesAdapter] 사용.
 *
 * ```yaml
 * bluetape4k:
 *   leader:
 *     wait-time: 5s
 *     lease-time: 60s
 *     watchdog-threads: 4          # optional; defaults to availableProcessors().coerceAtLeast(2)
 *     watchdog-async-extend: true  # optional; defaults to false
 *     group:
 *       max-leaders: 3
 *       wait-time: 5s
 *       lease-time: 60s
 *     mongo:
 *       single-collection: leader_election
 *       group-collection: leader_group_election
 * ```
 *
 * @property waitTime 단일 리더 획득 대기 최대 시간. 기본 5초
 * @property leaseTime 단일 리더 보유 최대 시간. 기본 60초
 * @property watchdogThreads watchdog 스케줄러 스레드 수. null 이면 [LeaderLeaseAutoExtender.DEFAULT_WATCHDOG_THREADS] 사용
 * @property watchdogAsyncExtend true 이면 watchdog tick 마다 extend 를 virtual thread 로 비동기 디스패치
 * @property group 멀티 리더 그룹 옵션
 * @property mongo MongoDB 백엔드 컬렉션 이름
 */
@ConfigurationProperties(prefix = "bluetape4k.leader")
data class LeaderProperties(
    val waitTime: Duration = LeaderElectionProperties.DefaultWaitTime,
    val leaseTime: Duration = LeaderElectionProperties.DefaultLeaseTime,
    val watchdogThreads: Int? = null,
    val watchdogAsyncExtend: Boolean = false,
    @field:NestedConfigurationProperty
    val group: LeaderGroupProperties = LeaderGroupProperties(),
    @field:NestedConfigurationProperty
    val mongo: MongoCollectionProperties = MongoCollectionProperties(),
)
