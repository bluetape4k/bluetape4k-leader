package io.bluetape4k.leader.examples.dynamodbexport

import io.bluetape4k.support.requireNotBlank
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.ScanRequest
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification

/**
 * Small DynamoDB export table helper for the scheduled export example.
 *
 * ## Contract
 *
 * The export table is ordinary application data and is intentionally separate
 * from the `leader-dynamodb` lock table. Leadership correctness depends on the
 * lock table's conditional writes and logical `leaseExpiry`; export rows only
 * prove which elected node performed the example work.
 */
class DynamoDbExportTable(
    private val dynamoDb: DynamoDbClient,
    val tableName: String,
) {

    init {
        tableName.requireNotBlank("tableName")
    }

    suspend fun put(record: DynamoDbExportRecord) {
        withContext(Dispatchers.IO) {
            dynamoDb.putItem {
                it.tableName(tableName)
                    .item(record.toItem())
            }
        }
    }

    suspend fun records(): List<DynamoDbExportRecord> =
        withContext(Dispatchers.IO) {
            val records = mutableListOf<DynamoDbExportRecord>()
            var lastKey: Map<String, AttributeValue>? = null

            do {
                val request = ScanRequest.builder()
                    .tableName(tableName)
                    .exclusiveStartKey(lastKey)
                    .build()
                val response = dynamoDb.scan(request)
                records += response.items().map(::recordFrom)
                lastKey = response.lastEvaluatedKey()
            } while (!lastKey.isNullOrEmpty())

            records.sortedWith(compareBy<DynamoDbExportRecord> { it.createdAt }.thenBy { it.exportId })
        }

    suspend fun recordsForBatch(batchId: String): List<DynamoDbExportRecord> {
        batchId.requireNotBlank("batchId")
        return records().filter { it.batchId == batchId }
    }

    companion object {
        const val LOCK_KEY = "lockName"
        const val LOCK_TTL = "ttl"
        const val EXPORT_KEY = "exportId"
        const val FIELD_BATCH_ID = "batchId"
        const val FIELD_NODE_ID = "nodeId"
        const val FIELD_CREATED_AT = "createdAt"
        const val FIELD_SUMMARY = "summary"

        fun createLockTable(client: DynamoDbClient, tableName: String) {
            tableName.requireNotBlank("tableName")
            createTable(client, tableName, LOCK_KEY)
            runCatching {
                client.updateTimeToLive {
                    it.tableName(tableName)
                        .timeToLiveSpecification(
                            TimeToLiveSpecification.builder()
                                .attributeName(LOCK_TTL)
                                .enabled(true)
                                .build(),
                        )
                }
            }
        }

        fun createExportTable(client: DynamoDbClient, tableName: String) {
            tableName.requireNotBlank("tableName")
            createTable(client, tableName, EXPORT_KEY)
        }

        private fun createTable(client: DynamoDbClient, tableName: String, hashKey: String) {
            try {
                client.createTable(
                    CreateTableRequest.builder()
                        .tableName(tableName)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                            AttributeDefinition.builder()
                                .attributeName(hashKey)
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        )
                        .keySchema(
                            KeySchemaElement.builder()
                                .attributeName(hashKey)
                                .keyType(KeyType.HASH)
                                .build(),
                        )
                        .build(),
                )
            } catch (_: ResourceInUseException) {
                // Existing table is fine for demo reruns that reuse a local endpoint.
            }
            client.waiter().waitUntilTableExists { it.tableName(tableName) }
        }

        private fun DynamoDbExportRecord.toItem(): Map<String, AttributeValue> =
            mapOf(
                EXPORT_KEY to AttributeValue.fromS(exportId),
                FIELD_BATCH_ID to AttributeValue.fromS(batchId),
                FIELD_NODE_ID to AttributeValue.fromS(nodeId),
                FIELD_CREATED_AT to AttributeValue.fromS(createdAt.toString()),
                FIELD_SUMMARY to AttributeValue.fromS(summary),
            )

        private fun recordFrom(item: Map<String, AttributeValue>): DynamoDbExportRecord =
            DynamoDbExportRecord(
                exportId = item.getValue(EXPORT_KEY).s(),
                batchId = item.getValue(FIELD_BATCH_ID).s(),
                nodeId = item.getValue(FIELD_NODE_ID).s(),
                createdAt = Instant.parse(item.getValue(FIELD_CREATED_AT).s()),
                summary = item.getValue(FIELD_SUMMARY).s(),
            )
    }
}
