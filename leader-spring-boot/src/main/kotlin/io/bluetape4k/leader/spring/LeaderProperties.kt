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
    @field:NestedConfigurationProperty
    val diagnostics: LeaderDiagnosticsProperties = LeaderDiagnosticsProperties(),
    @field:NestedConfigurationProperty
    val routeGuard: LeaderRouteGuardProperties = LeaderRouteGuardProperties(),
) {
    /** Preserves the ten-argument constructor from the 0.4.0 public API. */
    constructor(
        waitTime: Duration,
        leaseTime: Duration,
        watchdogThreads: Int?,
        watchdogAsyncExtend: Boolean,
        observability: LeaderObservabilityProperties,
        group: LeaderGroupProperties,
        mongo: MongoCollectionProperties,
        etcd: EtcdLeaderProperties,
        consul: ConsulLeaderProperties,
        dynamodb: DynamoDbLeaderProperties,
    ) : this(
        waitTime = waitTime,
        leaseTime = leaseTime,
        watchdogThreads = watchdogThreads,
        watchdogAsyncExtend = watchdogAsyncExtend,
        observability = observability,
        group = group,
        mongo = mongo,
        etcd = etcd,
        consul = consul,
        dynamodb = dynamodb,
        diagnostics = LeaderDiagnosticsProperties(),
        routeGuard = LeaderRouteGuardProperties(),
    )

    /** Preserves Kotlin's published ten-argument default-constructor descriptor. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        waitTime: Duration,
        leaseTime: Duration,
        watchdogThreads: Int?,
        watchdogAsyncExtend: Boolean,
        observability: LeaderObservabilityProperties,
        group: LeaderGroupProperties,
        mongo: MongoCollectionProperties,
        etcd: EtcdLeaderProperties,
        consul: ConsulLeaderProperties,
        dynamodb: DynamoDbLeaderProperties,
        mask: Int,
        marker: kotlin.jvm.internal.DefaultConstructorMarker?,
    ) : this(
        waitTime = if (mask and 0x001 != 0) LeaderElectionProperties.DefaultWaitTime else waitTime,
        leaseTime = if (mask and 0x002 != 0) LeaderElectionProperties.DefaultLeaseTime else leaseTime,
        watchdogThreads = if (mask and 0x004 != 0) null else watchdogThreads,
        watchdogAsyncExtend = if (mask and 0x008 != 0) false else watchdogAsyncExtend,
        observability = if (mask and 0x010 != 0) LeaderObservabilityProperties() else observability,
        group = if (mask and 0x020 != 0) LeaderGroupProperties() else group,
        mongo = if (mask and 0x040 != 0) MongoCollectionProperties() else mongo,
        etcd = if (mask and 0x080 != 0) EtcdLeaderProperties() else etcd,
        consul = if (mask and 0x100 != 0) ConsulLeaderProperties() else consul,
        dynamodb = if (mask and 0x200 != 0) DynamoDbLeaderProperties() else dynamodb,
        diagnostics = LeaderDiagnosticsProperties(),
        routeGuard = LeaderRouteGuardProperties(),
    )

    /** Preserves the ten-argument data-class copy entry point from the 0.4.0 API. */
    @Suppress("LongParameterList")
    fun copy(
        waitTime: Duration,
        leaseTime: Duration,
        watchdogThreads: Int?,
        watchdogAsyncExtend: Boolean,
        observability: LeaderObservabilityProperties,
        group: LeaderGroupProperties,
        mongo: MongoCollectionProperties,
        etcd: EtcdLeaderProperties,
        consul: ConsulLeaderProperties,
        dynamodb: DynamoDbLeaderProperties,
    ): LeaderProperties = copy(
        waitTime = waitTime,
        leaseTime = leaseTime,
        watchdogThreads = watchdogThreads,
        watchdogAsyncExtend = watchdogAsyncExtend,
        observability = observability,
        group = group,
        mongo = mongo,
        etcd = etcd,
        consul = consul,
        dynamodb = dynamodb,
        diagnostics = diagnostics,
        routeGuard = routeGuard,
    )

    companion object {
        /** Preserves Kotlin's published ten-argument `copy$default` descriptor. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "FunctionNaming", "LongParameterList")
        fun `copy$default`(
            self: LeaderProperties,
            waitTime: Duration?,
            leaseTime: Duration?,
            watchdogThreads: Int?,
            watchdogAsyncExtend: Boolean,
            observability: LeaderObservabilityProperties?,
            group: LeaderGroupProperties?,
            mongo: MongoCollectionProperties?,
            etcd: EtcdLeaderProperties?,
            consul: ConsulLeaderProperties?,
            dynamodb: DynamoDbLeaderProperties?,
            mask: Int,
            marker: Any?,
        ): LeaderProperties = self.copy(
            waitTime = if (mask and 0x001 != 0) self.waitTime else requireNotNull(waitTime),
            leaseTime = if (mask and 0x002 != 0) self.leaseTime else requireNotNull(leaseTime),
            watchdogThreads = if (mask and 0x004 != 0) self.watchdogThreads else watchdogThreads,
            watchdogAsyncExtend = if (mask and 0x008 != 0) self.watchdogAsyncExtend else watchdogAsyncExtend,
            observability = if (mask and 0x010 != 0) self.observability else requireNotNull(observability),
            group = if (mask and 0x020 != 0) self.group else requireNotNull(group),
            mongo = if (mask and 0x040 != 0) self.mongo else requireNotNull(mongo),
            etcd = if (mask and 0x080 != 0) self.etcd else requireNotNull(etcd),
            consul = if (mask and 0x100 != 0) self.consul else requireNotNull(consul),
            dynamodb = if (mask and 0x200 != 0) self.dynamodb else requireNotNull(dynamodb),
        )
    }
}
