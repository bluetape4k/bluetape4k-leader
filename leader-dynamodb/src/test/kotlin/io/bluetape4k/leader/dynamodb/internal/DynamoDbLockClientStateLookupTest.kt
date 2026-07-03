package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes
import software.amazon.awssdk.services.dynamodb.model.ScanRequest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbLockClientStateLookupTest {

    @Test
    fun `activeGroupLeases reads deterministic slot keys without scan`() {
        val client = mockk<DynamoDbClient>()
        val request = slot<BatchGetItemRequest>()
        val now = 1_000L
        val tableName = "leader-locks"
        val prefix = "leader/group/orders#slot-"
        val lockClient = DynamoDbLockClient(tableName, syncClient = client, nowMillis = { now })

        every { client.batchGetItem(capture(request)) } returns BatchGetItemResponse.builder()
            .responses(
                mapOf(
                    tableName to listOf(
                        item("${prefix}0", ownerId = "owner-a", auditLeaderId = "audit-a", nodeId = "node-a", expiresAt = 5_000L),
                        item("${prefix}1", ownerId = "owner-b", auditLeaderId = "audit-b", nodeId = "node-b", expiresAt = 999L),
                    ),
                ),
            )
            .unprocessedKeys(emptyMap())
            .build()

        val leases = lockClient.activeGroupLeases(prefix, maxLeaders = 3)

        leases.size shouldBeEqualTo 1
        leases.single().auditLeaderId shouldBeEqualTo "audit-a"
        leases.single().nodeId shouldBeEqualTo "node-a"
        leases.single().slot shouldBeEqualTo 0
        request.captured.requestedKeys(tableName) shouldBeEqualTo listOf("${prefix}0", "${prefix}1", "${prefix}2")
        verify(exactly = 1) { client.batchGetItem(any<BatchGetItemRequest>()) }
        verify(exactly = 0) { client.scan(any<ScanRequest>()) }
    }

    @Test
    fun `activeGroupLeases chunks slot lookup at DynamoDB batch get limit`() {
        val client = mockk<DynamoDbClient>()
        val requests = mutableListOf<BatchGetItemRequest>()
        val tableName = "leader-locks"
        val prefix = "leader/group/orders#slot-"
        val lockClient = DynamoDbLockClient(tableName, syncClient = client, nowMillis = { 1_000L })

        every { client.batchGetItem(any<BatchGetItemRequest>()) } answers {
            requests += invocation.args[0] as BatchGetItemRequest
            BatchGetItemResponse.builder()
                .responses(emptyMap())
                .unprocessedKeys(emptyMap())
                .build()
        }

        lockClient.activeGroupLeases(prefix, maxLeaders = 250) shouldBeEqualTo emptyList()

        requests.map { it.requestedKeys(tableName).size } shouldBeEqualTo listOf(100, 100, 50)
        requests.flatMap { it.requestedKeys(tableName) }.toSet().size shouldBeEqualTo 250
        verify(exactly = 3) { client.batchGetItem(any<BatchGetItemRequest>()) }
        verify(exactly = 0) { client.scan(any<ScanRequest>()) }
    }

    @Test
    fun `activeGroupLeases retries unprocessed slot keys`() {
        val client = mockk<DynamoDbClient>()
        val requests = mutableListOf<BatchGetItemRequest>()
        val tableName = "leader-locks"
        val prefix = "leader/group/orders#slot-"
        val lockClient = DynamoDbLockClient(tableName, syncClient = client, nowMillis = { 1_000L })

        var attempt = 0
        every { client.batchGetItem(any<BatchGetItemRequest>()) } answers {
            requests += invocation.args[0] as BatchGetItemRequest
            attempt += 1
            if (attempt == 1) {
                BatchGetItemResponse.builder()
                    .responses(emptyMap())
                    .unprocessedKeys(
                        mapOf(
                            tableName to KeysAndAttributes.builder()
                                .consistentRead(true)
                                .keys(listOf(mapOf(DynamoDbLockClient.LockName to s("${prefix}0"))))
                                .build(),
                        ),
                    )
                    .build()
            } else {
                BatchGetItemResponse.builder()
                    .responses(
                        mapOf(
                            tableName to listOf(
                                item(
                                    "${prefix}0",
                                    ownerId = "owner-a",
                                    auditLeaderId = "audit-a",
                                    nodeId = "node-a",
                                    expiresAt = 5_000L,
                                ),
                            ),
                        ),
                    )
                    .unprocessedKeys(emptyMap())
                    .build()
            }
        }

        val leases = lockClient.activeGroupLeases(prefix, maxLeaders = 1)

        leases.single().auditLeaderId shouldBeEqualTo "audit-a"
        requests.map { it.requestedKeys(tableName) } shouldBeEqualTo listOf(listOf("${prefix}0"), listOf("${prefix}0"))
        verify(exactly = 2) { client.batchGetItem(any<BatchGetItemRequest>()) }
        verify(exactly = 0) { client.scan(any<ScanRequest>()) }
    }

    private fun BatchGetItemRequest.requestedKeys(tableName: String): List<String> =
        requestItems()[tableName]
            ?.keys()
            .orEmpty()
            .mapNotNull { key -> key[DynamoDbLockClient.LockName]?.s() }

    private fun item(
        lockName: String,
        ownerId: String,
        auditLeaderId: String,
        nodeId: String,
        expiresAt: Long,
    ): Map<String, AttributeValue> =
        mapOf(
            DynamoDbLockClient.LockName to s(lockName),
            DynamoDbLockClient.OwnerId to s(ownerId),
            DynamoDbLockClient.AuditLeaderId to s(auditLeaderId),
            DynamoDbLockClient.NodeId to s(nodeId),
            DynamoDbLockClient.LeaseExpiry to n(expiresAt),
        )

    private fun s(value: String): AttributeValue =
        AttributeValue.builder().s(value).build()

    private fun n(value: Long): AttributeValue =
        AttributeValue.builder().n(value.toString()).build()
}
