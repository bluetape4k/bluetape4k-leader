package io.bluetape4k.leader.spring.boot4

import io.bluetape4k.leader.spring.properties.LeaderElectionProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.time.Duration

/**
 * Spring Boot 4 자동 구성 진입 속성.
 *
 * `bluetape4k.leader.*` prefix 로 yaml 바인딩됩니다. 백엔드별 옵션 변환은 [adapter.PropertiesAdapter] 사용.
 *
 * ```yaml
 * bluetape4k:
 *   leader:
 *     wait-time: 5s
 *     lease-time: 60s
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
 * @property group 멀티 리더 그룹 옵션
 * @property mongo MongoDB 백엔드 컬렉션 이름
 */
@ConfigurationProperties(prefix = "bluetape4k.leader")
data class Boot4LeaderProperties(
    val waitTime: Duration = LeaderElectionProperties.DefaultWaitTime,
    val leaseTime: Duration = LeaderElectionProperties.DefaultLeaseTime,
    @field:NestedConfigurationProperty
    val group: LeaderGroupProperties = LeaderGroupProperties(),
    @field:NestedConfigurationProperty
    val mongo: MongoCollectionProperties = MongoCollectionProperties(),
)
