package io.bluetape4k.leader.spring

import io.bluetape4k.leader.spring.properties.LeaderElectionProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import io.bluetape4k.leader.spring.properties.LeaderObservabilityProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.time.Duration

/**
 * Spring Boot auto-configuration entry properties.
 *
 * Bound from yaml under the `bluetape4k.leader.*` prefix. Use [adapter.PropertiesAdapter] for
 * per-backend option conversion.
 *
 * ```yaml
 * bluetape4k:
 *   leader:
 *     wait-time: 5s
 *     lease-time: 60s
 *     watchdog-threads: 4          # optional; defaults to availableProcessors().coerceAtLeast(2)
 *     watchdog-async-extend: true  # optional; defaults to false
 *     observability:
 *       lock-names:
 *         - batch-job
 *     group:
 *       max-leaders: 3
 *       wait-time: 5s
 *       lease-time: 60s
 *     mongo:
 *       single-collection: leader_election
 *       group-collection: leader_group_election
 *     etcd:
 *       key-prefix: /apps/orders/leader
 *     consul:
 *       key-prefix: apps/orders/leader
 *       session-name-prefix: orders-leader
 *     dynamodb:
 *       table-name: bluetape4k_leader_locks
 *       key-prefix: leader
 * ```
 *
 * @property waitTime maximum wait time to acquire a single leader lease. Default 5 seconds.
 * @property leaseTime maximum hold time for a single leader lease. Default 60 seconds.
 * @property watchdogThreads watchdog scheduler thread count. When null, uses [LeaderLeaseAutoExtender.DEFAULT_WATCHDOG_THREADS].
 * @property watchdogAsyncExtend when true, each watchdog tick dispatches the extend call asynchronously on a virtual thread.
 * @property observability leader status observability and endpoint seed options.
 * @property group multi-leader group options.
 * @property mongo MongoDB backend collection names.
 * @property etcd etcd backend key-prefix options.
 * @property consul Consul backend KV/session options.
 * @property dynamodb DynamoDB backend table, key-prefix, TTL, retry, and clock-skew options.
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
)
