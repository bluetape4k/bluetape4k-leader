package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes
import java.util.concurrent.CompletableFuture

class DynamoDbBatchReadRetryTest {

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `계속 미처리된 키는 sync와 async 모두 8회 재시도 뒤 실패한다`(async: Boolean) {
        val syncClient = mockk<DynamoDbClient>()
        val asyncClient = mockk<DynamoDbAsyncClient>()
        var calls = 0
        val delays = mutableListOf<Long>()
        fun respond(request: BatchGetItemRequest): BatchGetItemResponse {
            calls++
            if (calls > 9) throw AssertionError("retry budget was not enforced")
            return BatchGetItemResponse.builder().unprocessedKeys(request.requestItems()).build()
        }
        every { syncClient.batchGetItem(any<BatchGetItemRequest>()) } answers { respond(firstArg()) }
        every { asyncClient.batchGetItem(any<BatchGetItemRequest>()) } answers {
            CompletableFuture.completedFuture(respond(firstArg()))
        }
        val client = DynamoDbLockClient(
            "leader-locks",
            syncClient = syncClient.takeUnless { async },
            asyncClient = asyncClient.takeIf { async },
            batchRetrySleep = { delays += it },
        )

        assertFailsWith<IllegalStateException> { client.activeGroupLeases("group#slot-", 1) }
        calls shouldBeEqualTo 9
        delays shouldBeEqualTo listOf(25L, 50L, 100L, 200L, 200L, 200L, 200L, 200L)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `여러 chunk가 하나의 재시도 상한을 공유한다`(async: Boolean) {
        var calls = 0
        var waits = 0
        val client = client(async, { waits++ }) { request ->
            calls++
            if (calls == 9) BatchGetItemResponse.builder().build()
            else BatchGetItemResponse.builder().unprocessedKeys(request.requestItems()).build()
        }

        assertFailsWith<IllegalStateException> { client.activeGroupLeases("group#slot-", 250) }
        calls shouldBeEqualTo 10
        waits shouldBeEqualTo 8
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `일부 성공과 missing 및 expired slot을 chunk 재시도 뒤에도 구분한다`(async: Boolean) {
        val requests = mutableListOf<BatchGetItemRequest>()
        val client = client(async) { request ->
            requests += request
            val keys = request.requestItems().getValue("leader-locks").keys()
            when (requests.size) {
                1 -> BatchGetItemResponse.builder()
                    .responses(mapOf("leader-locks" to listOf(item("group#slot-0", 5_000L))))
                    .unprocessedKeys(mapOf("leader-locks" to KeysAndAttributes.builder()
                        .consistentRead(true).keys(listOf(keys[1])).build()))
                    .build()
                2 -> BatchGetItemResponse.builder()
                    .responses(mapOf("leader-locks" to listOf(item("group#slot-1", 999L))))
                    .build()
                else -> BatchGetItemResponse.builder().build()
            }
        }

        val leases = client.activeGroupLeases("group#slot-", 101)
        leases.map { it.slot } shouldBeEqualTo listOf(0)
        requests.map { it.requestItems().getValue("leader-locks").keys().size } shouldBeEqualTo listOf(100, 1, 1)
        requests.all { it.requestItems().getValue("leader-locks").consistentRead() } shouldBeEqualTo true
    }

    @Test
    fun `재시도 대기 interrupt를 정상 빈 상태로 숨기지 않는다`() {
        val interruption = InterruptedException("stop")
        var calls = 0
        val client = client(false, { throw interruption }) { request ->
            calls++
            BatchGetItemResponse.builder().unprocessedKeys(request.requestItems()).build()
        }

        val failure = assertFailsWith<InterruptedException> { client.activeGroupLeases("group#slot-", 1) }
        (failure === interruption) shouldBeEqualTo true
        calls shouldBeEqualTo 1
    }

    private fun client(
        async: Boolean,
        sleep: (Long) -> Unit = {},
        respond: (BatchGetItemRequest) -> BatchGetItemResponse,
    ): DynamoDbLockClient {
        val syncClient = mockk<DynamoDbClient>()
        val asyncClient = mockk<DynamoDbAsyncClient>()
        every { syncClient.batchGetItem(any<BatchGetItemRequest>()) } answers { respond(firstArg()) }
        every { asyncClient.batchGetItem(any<BatchGetItemRequest>()) } answers {
            CompletableFuture.completedFuture(respond(firstArg()))
        }
        return DynamoDbLockClient(
            "leader-locks",
            syncClient = syncClient.takeUnless { async },
            asyncClient = asyncClient.takeIf { async },
            nowMillis = { 1_000L },
            batchRetrySleep = sleep,
        )
    }

    private fun item(key: String, expiry: Long): Map<String, AttributeValue> = mapOf(
        DynamoDbLockClient.LockName to AttributeValue.builder().s(key).build(),
        DynamoDbLockClient.OwnerId to AttributeValue.builder().s("owner").build(),
        DynamoDbLockClient.LeaseExpiry to AttributeValue.builder().n(expiry.toString()).build(),
    )
}
