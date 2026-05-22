package io.bluetape4k.leader.dynamodb

import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
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
import java.net.URI

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractDynamoDbLeaderTest {

    companion object {
        private const val DYNAMODB_PORT = 8000

        private val container = DynamoDbLocalContainer()
        private lateinit var syncClient: DynamoDbClient
        private lateinit var asyncClient: DynamoDbAsyncClient
        private lateinit var sharedTableName: String

        @BeforeAll
        @JvmStatic
        fun startDynamoDb() {
            container.start()
            val endpoint = URI.create("http://${container.host}:${container.getMappedPort(DYNAMODB_PORT)}")
            val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            syncClient = DynamoDbClient.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .region(Region.US_EAST_1)
                .build()
            asyncClient = DynamoDbAsyncClient.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .region(Region.US_EAST_1)
                .build()
            sharedTableName = "leader_${Base58.randomString(12)}"
            createTable(syncClient, sharedTableName)
        }

        @AfterAll
        @JvmStatic
        fun stopDynamoDb() {
            if (::asyncClient.isInitialized) {
                asyncClient.close()
            }
            if (::syncClient.isInitialized) {
                syncClient.close()
            }
            container.stop()
        }

        private fun createTable(client: DynamoDbClient, tableName: String) {
            client.createTable(
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
            client.waiter().waitUntilTableExists { it.tableName(tableName) }
            runCatching {
                client.updateTimeToLive {
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
    }

    protected val dynamoDb: DynamoDbClient get() = syncClient
    protected val dynamoDbAsync: DynamoDbAsyncClient get() = asyncClient
    protected val tableName: String get() = sharedTableName

    protected fun keyPrefix(): String =
        "test-${Base58.randomString(8)}"

    protected fun randomName(): String =
        "leader-test-${Base58.randomString(8)}"

    private class DynamoDbLocalContainer :
        GenericContainer<DynamoDbLocalContainer>(DockerImageName.parse("amazon/dynamodb-local:2.6.1")) {
        init {
            withExposedPorts(DYNAMODB_PORT)
            withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
        }
    }
}
