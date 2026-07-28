package io.bluetape4k.leader.spring

import io.bluetape4k.leader.spring.properties.LeaderElectionProperties
import io.bluetape4k.leader.spring.properties.LeaderDiagnosticsProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import io.bluetape4k.leader.spring.properties.LeaderObservabilityProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.time.Duration

/**
 * `LeaderProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property waitTime Spring Boot integration 계약에서 `waitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTime Spring Boot integration 계약에서 `leaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property watchdogThreads Spring Boot integration 계약에서 `watchdogThreads` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property watchdogAsyncExtend Spring Boot integration 계약에서 `watchdogAsyncExtend` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property diagnostics Spring Boot integration 계약에서 `diagnostics` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property routeGuard Spring Boot integration 계약에서 `routeGuard` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property observability Spring Boot integration 계약에서 `observability` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property group Spring Boot integration 계약에서 `group` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property mongo Spring Boot integration 계약에서 `mongo` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property etcd Spring Boot integration 계약에서 `etcd` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property consul Spring Boot integration 계약에서 `consul` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property dynamodb Spring Boot integration 계약에서 `dynamodb` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.leader")
/**
 * `LeaderProperties`는 Spring Boot integration에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property waitTime Spring Boot integration 계약에서 `waitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTime Spring Boot integration 계약에서 `leaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property watchdogThreads Spring Boot integration 계약에서 `watchdogThreads` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property watchdogAsyncExtend Spring Boot integration 계약에서 `watchdogAsyncExtend` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property diagnostics Spring Boot integration 계약에서 `diagnostics` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property routeGuard Spring Boot integration 계약에서 `routeGuard` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property observability Spring Boot integration 계약에서 `observability` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property group Spring Boot integration 계약에서 `group` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property mongo Spring Boot integration 계약에서 `mongo` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property etcd Spring Boot integration 계약에서 `etcd` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property consul Spring Boot integration 계약에서 `consul` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property dynamodb Spring Boot integration 계약에서 `dynamodb` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderProperties(
    val waitTime: Duration = LeaderElectionProperties.DefaultWaitTime,
    val leaseTime: Duration = LeaderElectionProperties.DefaultLeaseTime,
    val watchdogThreads: Int? = null,
    val watchdogAsyncExtend: Boolean = false,
    @field:NestedConfigurationProperty
    val diagnostics: LeaderDiagnosticsProperties = LeaderDiagnosticsProperties(),
    @field:NestedConfigurationProperty
    val routeGuard: LeaderRouteGuardProperties = LeaderRouteGuardProperties(),
    @field:NestedConfigurationProperty
    val observability: LeaderObservabilityProperties = LeaderObservabilityProperties(),
    @field:NestedConfigurationProperty
    val group: LeaderGroupProperties = LeaderGroupProperties(),
    @field:NestedConfigurationProperty
    val mongo: MongoCollectionProperties = MongoCollectionProperties(),
    @field:NestedConfigurationProperty
    val etcd: EtcdLeaderProperties = EtcdLeaderProperties(),
    @field:NestedConfigurationProperty
    val consul: ConsulLeaderProperties = ConsulLeaderProperties(),
    @field:NestedConfigurationProperty
    val dynamodb: DynamoDbLeaderProperties = DynamoDbLeaderProperties(),
)
