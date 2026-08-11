package io.bluetape4k.leader.dynamodb.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.aws.DynamoDbLocalServer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
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
    private val container = DynamoDbLocalServer.Launcher.dynamoDb
    val tableName: String = "contract_" + Base58.randomString(12)

    val dynamoDb: DynamoDbClient
    val dynamoDbAsync: DynamoDbAsyncClient

    init {
        val credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(container.awsAccessKey, container.awsSecretKey),
        )
        val region = Region.of(container.regionName)
        dynamoDb = DynamoDbClient.builder()
            .endpointOverride(container.awsEndpoint)
            .credentialsProvider(credentials)
            .region(region)
            .build()
        dynamoDbAsync = DynamoDbAsyncClient.builder()
            .endpointOverride(container.awsEndpoint)
            .credentialsProvider(credentials)
            .region(region)
            .build()
        dynamoDb.createTable(
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
        dynamoDb.waiter().waitUntilTableExists { it.tableName(tableName) }
        runCatching {
            dynamoDb.updateTimeToLive {
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
