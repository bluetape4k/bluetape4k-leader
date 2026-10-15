package io.bluetape4k.leader.spring

import io.bluetape4k.leader.spring.properties.LeaderDiagnosticsProperties
import io.bluetape4k.leader.spring.properties.LeaderElectionProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import io.bluetape4k.leader.spring.properties.LeaderObservabilityProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.support.requireNotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.io.Serializable
import java.time.Duration

/**
 * `bluetape4k.leader` 아래의 공통 leader election과 backend별 Spring Boot 설정을 묶습니다.
 *
 * @property waitTime 공통 lock 획득 대기 시간입니다. 기본값은 5초이며 실제 범위 검증은 core options가 담당합니다.
 * @property leaseTime 공통 lease TTL입니다. 기본값은 60초이며 양수 여부 검증은 core options가 담당합니다.
 * @property watchdogThreads lease auto-extension에 사용할 thread 수 override입니다. `null`이면 `LeaderLeaseAutoExtender`의 기본 thread 수를 사용합니다.
 * @property watchdogAsyncExtend watchdog이 lease를 asynchronous 방식으로 연장할지 정합니다. 기본값은 `false`입니다.
 * @property diagnostics startup/backend diagnostics 설정입니다. 기본값은 `LeaderDiagnosticsProperties()`입니다.
 * @property routeGuard HTTP route guard 설정입니다. 기본값은 `LeaderRouteGuardProperties()`입니다.
 * @property observability metric과 observation recording 설정입니다. 기본값은 `LeaderObservabilityProperties()`입니다.
 * @property group group election의 슬롯 수·대기 시간·lease TTL 설정입니다. 기본값은 `LeaderGroupProperties()`입니다.
 * @property mongo MongoDB backend의 단일/group collection 이름 설정입니다.
 * @property etcd etcd backend의 key prefix 설정입니다.
 * @property consul Consul backend의 key/session prefix와 lock delay 설정입니다.
 * @property dynamodb DynamoDB backend의 table·key prefix·TTL 보정 설정입니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.leader")
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
): Serializable {
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
    ): this(
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
    ): this(
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
            waitTime = if (mask and 0x001 != 0) self.waitTime else waitTime.requireNotNull("waitTime"),
            leaseTime = if (mask and 0x002 != 0) self.leaseTime else leaseTime.requireNotNull("leaseTime"),
            watchdogThreads = if (mask and 0x004 != 0) self.watchdogThreads else watchdogThreads,
            watchdogAsyncExtend = if (mask and 0x008 != 0) self.watchdogAsyncExtend else watchdogAsyncExtend,
            observability = if (mask and 0x010 != 0) {
                self.observability
            } else {
                observability.requireNotNull("observability")
            },
            group = if (mask and 0x020 != 0) self.group else group.requireNotNull("group"),
            mongo = if (mask and 0x040 != 0) self.mongo else mongo.requireNotNull("mongo"),
            etcd = if (mask and 0x080 != 0) self.etcd else etcd.requireNotNull("etcd"),
            consul = if (mask and 0x100 != 0) self.consul else consul.requireNotNull("consul"),
            dynamodb = if (mask and 0x200 != 0) self.dynamodb else dynamodb.requireNotNull("dynamodb"),
        )

        private const val serialVersionUID: Long = 1L
    }
}
