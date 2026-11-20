package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.aws.dynamodb.dynamoDbAsyncClient
import io.bluetape4k.aws.dynamodb.dynamoDbClient
import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.aws.DynamoDbLocalServer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification

internal object DynamoDbContractSupport {

    private val dynamodb by lazy { DynamoDbLocalServer.Launcher.dynamoDb }
    val tableName: String = "contract_" + Base58.randomString(12)

    val credentials by lazy {
        staticCredentialsProviderOf(dynamodb.awsAccessKey, dynamodb.awsSecretKey)
    }
    val region by lazy { Region.of(dynamodb.regionName) }

    val syncClient: DynamoDbClient by lazy {
        dynamoDbClient {
            endpointOverride(dynamodb.awsEndpoint)
            credentialsProvider(credentials)
            region(region)
        }
    }
    val asyncClient: DynamoDbAsyncClient by lazy {
        dynamoDbAsyncClient {
            endpointOverride(dynamodb.awsEndpoint)
            credentialsProvider(credentials)
            region(region)
            httpClient(SdkAsyncHttpClientProvider.AwsCrt.httpClient)
        }

    }

    init {

        syncClient.createTable(
            CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("lockName")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                )
                .keySchema(
                    KeySchemaElement.builder()
                        .attributeName("lockName")
                        .keyType(KeyType.HASH)
                        .build(),
                )
                .build(),
        )

        syncClient.waiter().waitUntilTableExists {
            it.tableName(tableName)
        }

        runCatching {
            syncClient.updateTimeToLive {
                it.tableName(tableName)
                    .timeToLiveSpecification(
                        TimeToLiveSpecification.builder()
                            .attributeName("ttl")
                            .enabled(true)
                            .build(),
                    )
            }
        }
    }

    fun keyPrefix(): String = "contract-" + Base58.randomString(8)
}
