package io.bluetape4k.leader.examples.dynamodbexport

import io.bluetape4k.aws.dynamodb.model.CreateTableRequest
import io.bluetape4k.aws.dynamodb.model.ScanRequest
import io.bluetape4k.aws.dynamodb.model.toAttributeValue
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification
import java.time.Instant

/**
 * `DynamoDbExportTable`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property client example workflow 계약에서 사용하는 속성입니다.
 * @property tableName example workflow 계약에서 사용하는 속성입니다.
 */
class DynamoDbExportTable(
    private val client: DynamoDbClient,
    val tableName: String,
) {

    init {
        tableName.requireNotBlank("tableName")
    }

    suspend fun put(record: DynamoDbExportRecord) {
        withContext(Dispatchers.IO) {
            client.putItem {
                it.tableName(tableName)
                    .item(record.toItem())
            }
        }
    }

    suspend fun records(): List<DynamoDbExportRecord> = withContext(Dispatchers.IO) {
        val records = mutableListOf<DynamoDbExportRecord>()
        var lastKey: Map<String, AttributeValue>? = null

        do {
            val request = ScanRequest {
                tableName(tableName)
                exclusiveStartKey(lastKey)
            }
            val response = client.scan(request)
            records += response.items().map(::recordFrom)
            lastKey = response.lastEvaluatedKey()
        } while (lastKey.isNotEmpty())

        records.sortedWith(recordComparator)
    }

    suspend fun recordsForBatch(batchId: String): List<DynamoDbExportRecord> {
        batchId.requireNotBlank("batchId")

        return records().filter { it.batchId == batchId }
    }

    companion object: KLoggingChannel() {
        const val LOCK_KEY = "lockName"
        const val LOCK_TTL = "ttl"
        const val EXPORT_KEY = "exportId"
        const val FIELD_BATCH_ID = "batchId"
        const val FIELD_NODE_ID = "nodeId"
        const val FIELD_CREATED_AT = "createdAt"
        const val FIELD_SUMMARY = "summary"

        private val recordComparator = compareBy<DynamoDbExportRecord> { it.createdAt }.thenBy { it.exportId }

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
                val request = CreateTableRequest {
                    tableName(tableName)
                    billingMode(BillingMode.PAY_PER_REQUEST)
                    attributeDefinitions(
                        AttributeDefinition.builder()
                            .attributeName(hashKey)
                            .attributeType(ScalarAttributeType.S)
                            .build(),
                    )
                    keySchema(
                        KeySchemaElement.builder()
                            .attributeName(hashKey)
                            .keyType(KeyType.HASH)
                            .build(),
                    )
                }
                client.createTable(request)
            } catch (_: ResourceInUseException) {
                // Existing table is fine for demo reruns that reuse a local endpoint.
            }

            client.waiter().waitUntilTableExists { it.tableName(tableName) }
        }

        private fun DynamoDbExportRecord.toItem(): Map<String, AttributeValue> =
            mapOf(
                EXPORT_KEY to exportId.toAttributeValue(),
                FIELD_BATCH_ID to batchId.toAttributeValue(),
                FIELD_NODE_ID to nodeId.toAttributeValue(),
                FIELD_CREATED_AT to createdAt.toString().toAttributeValue(),
                FIELD_SUMMARY to summary.toAttributeValue(),
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
