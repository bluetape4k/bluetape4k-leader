package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

/** 이전 공개 source 호출을 기준 revision에서 컴파일하는 소비자 fixture입니다. */
class DynamoDbLegacySourceConsumerTest : AbstractDynamoDbLeaderTest() {

    @BeforeAll
    fun createDefaultOptionsTable() {
        val defaultTable = DynamoDbLeaderElectionOptions.DefaultTableName
        if (dynamoDb.listTables().tableNames().contains(defaultTable)) {
            return
        }

        dynamoDb.createTable(
            CreateTableRequest.builder()
                .tableName(defaultTable)
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
        dynamoDb.waiter().waitUntilTableExists { it.tableName(defaultTable) }
    }

    private suspend fun callSuspendRunIfLeaderExplicit(lockName: String, action: suspend () -> String): String? =
        dynamoDbAsync.suspendRunIfLeader(lockName, DynamoDbLeaderElectionOptions(tableName = tableName), action)

    private suspend fun callSuspendRunIfLeaderDefault(lockName: String, action: suspend () -> String): String? =
        dynamoDbAsync.suspendRunIfLeader(lockName, action = action)

    private suspend fun callSuspendRunIfLeaderGroupExplicit(lockName: String, action: suspend () -> String): String? =
        dynamoDbAsync.suspendRunIfLeaderGroup(lockName, DynamoDbLeaderGroupElectionOptions(tableName = tableName), action)

    private suspend fun callSuspendRunIfLeaderGroupDefault(lockName: String, action: suspend () -> String): String? =
        dynamoDbAsync.suspendRunIfLeaderGroup(lockName, action = action)

    @Test
    fun `historical single consumer links to candidate facade shim`() = runSuspendIO {
        callSuspendRunIfLeaderExplicit(randomName()) { "single" } shouldBeEqualTo "single"
    }

    @Test
    fun `historical group consumer links to candidate facade shim`() = runSuspendIO {
        callSuspendRunIfLeaderGroupExplicit(randomName()) { "group" } shouldBeEqualTo "group"
    }

    @Test
    fun `historical single default consumer links to candidate facade shim`() = runSuspendIO {
        callSuspendRunIfLeaderDefault(randomName()) { "single-default" } shouldBeEqualTo "single-default"
    }

    @Test
    fun `historical group default consumer links to candidate facade shim`() = runSuspendIO {
        callSuspendRunIfLeaderGroupDefault(randomName()) { "group-default" } shouldBeEqualTo "group-default"
    }
}
