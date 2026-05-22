package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElector
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderGroupElector
import io.bluetape4k.leader.dynamodb.DynamoDbVirtualThreadLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbVirtualThreadLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import kotlin.time.toKotlinDuration

/**
 * DynamoDB backend auto-configuration.
 *
 * The DynamoDB table is caller-provisioned. It must use `lockName` as the string partition key
 * and should enable TTL on the numeric `ttl` attribute.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(DynamoDbClient::class)
class DynamoDbLeaderConfiguration {

    @Bean
    @ConditionalOnBean(DynamoDbClient::class)
    @ConditionalOnMissingBean(name = ["dynamoDbLeaderElector"])
    fun dynamoDbLeaderElector(
        client: DynamoDbClient,
        props: LeaderProperties,
    ): DynamoDbLeaderElector =
        DynamoDbLeaderElector(client, electionOptions(props))

    @Bean
    @ConditionalOnBean(DynamoDbClient::class)
    @ConditionalOnMissingBean(name = ["dynamoDbLeaderGroupElector"])
    fun dynamoDbLeaderGroupElector(
        client: DynamoDbClient,
        props: LeaderProperties,
    ): DynamoDbLeaderGroupElector =
        DynamoDbLeaderGroupElector(client, groupOptions(props))

    @Bean
    @ConditionalOnBean(DynamoDbLeaderElector::class)
    @ConditionalOnMissingBean(name = ["dynamoDbVirtualThreadLeaderElector"])
    fun dynamoDbVirtualThreadLeaderElector(
        delegate: DynamoDbLeaderElector,
    ): DynamoDbVirtualThreadLeaderElector =
        DynamoDbVirtualThreadLeaderElector(delegate)

    @Bean
    @ConditionalOnBean(DynamoDbLeaderGroupElector::class)
    @ConditionalOnMissingBean(name = ["dynamoDbVirtualThreadLeaderGroupElector"])
    fun dynamoDbVirtualThreadLeaderGroupElector(
        delegate: DynamoDbLeaderGroupElector,
    ): DynamoDbVirtualThreadLeaderGroupElector =
        DynamoDbVirtualThreadLeaderGroupElector(delegate)

    @Bean
    @ConditionalOnBean(DynamoDbAsyncClient::class)
    @ConditionalOnClass(DynamoDbAsyncClient::class)
    @ConditionalOnMissingBean(name = ["dynamoDbSuspendLeaderElector"])
    fun dynamoDbSuspendLeaderElector(
        client: DynamoDbAsyncClient,
        props: LeaderProperties,
    ): DynamoDbSuspendLeaderElector =
        DynamoDbSuspendLeaderElector(client, electionOptions(props))

    @Bean
    @ConditionalOnBean(DynamoDbAsyncClient::class)
    @ConditionalOnClass(DynamoDbAsyncClient::class)
    @ConditionalOnMissingBean(name = ["dynamoDbSuspendLeaderGroupElector"])
    fun dynamoDbSuspendLeaderGroupElector(
        client: DynamoDbAsyncClient,
        props: LeaderProperties,
    ): DynamoDbSuspendLeaderGroupElector =
        DynamoDbSuspendLeaderGroupElector(client, groupOptions(props))

    companion object {
        fun electionOptions(props: LeaderProperties): DynamoDbLeaderElectionOptions =
            DynamoDbLeaderElectionOptions(
                leaderOptions = PropertiesAdapter.toCommonElection(props),
                tableName = props.dynamodb.tableName,
                keyPrefix = props.dynamodb.keyPrefix,
                retryDelay = props.dynamodb.retryDelay.toKotlinDuration(),
                ttlPadding = props.dynamodb.ttlPadding.toKotlinDuration(),
                clockSkewTolerance = props.dynamodb.clockSkewTolerance.toKotlinDuration(),
            )

        fun groupOptions(props: LeaderProperties): DynamoDbLeaderGroupElectionOptions =
            DynamoDbLeaderGroupElectionOptions(
                leaderGroupOptions = PropertiesAdapter.toCommonGroup(props),
                tableName = props.dynamodb.tableName,
                keyPrefix = props.dynamodb.keyPrefix,
                retryDelay = props.dynamodb.retryDelay.toKotlinDuration(),
                ttlPadding = props.dynamodb.ttlPadding.toKotlinDuration(),
                clockSkewTolerance = props.dynamodb.clockSkewTolerance.toKotlinDuration(),
            )
    }
}
