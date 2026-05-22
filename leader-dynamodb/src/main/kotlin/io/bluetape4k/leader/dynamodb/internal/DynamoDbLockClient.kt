package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.ScanRequest
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.time.Duration

internal class DynamoDbLockClient(
    private val tableName: String,
    private val syncClient: DynamoDbClient? = null,
    private val asyncClient: DynamoDbAsyncClient? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object : KLogging() {
        const val LockName = "lockName"
        const val OwnerId = "ownerId"
        const val AuditLeaderId = "auditLeaderId"
        const val NodeId = "nodeId"
        const val LeaseExpiry = "leaseExpiry"
        const val Ttl = "ttl"

        private const val LockNameAttr = "#lockName"
        private const val OwnerAttr = "#ownerId"
        private const val LeaseAttr = "#leaseExpiry"
        private const val TtlAttr = "#ttl"

        fun newOwnerId(): String = UUID.randomUUID().toString()
    }

    data class AcquiredLock(
        val key: String,
        val ownerId: String,
        val auditLeaderId: String,
        val nodeId: String,
        val leaseExpiryMillis: Long,
    ) {
        val leaseUntil: Instant get() = Instant.ofEpochMilli(leaseExpiryMillis)
    }

    fun acquire(
        key: String,
        ownerId: String,
        auditLeaderId: String,
        nodeId: String,
        waitTime: Duration,
        leaseTime: Duration,
        retryDelay: Duration,
        ttlPadding: Duration,
        clockSkewTolerance: Duration,
    ): AcquiredLock? {
        val deadline = MonotonicDeadline.fromNow(waitTime)
        do {
            val acquired = acquireOnce(key, ownerId, auditLeaderId, nodeId, leaseTime, ttlPadding, clockSkewTolerance)
            if (acquired != null) {
                return acquired
            }
            sleepBeforeRetry(deadline, retryDelay)
        } while (deadline.hasTimeRemaining())
        return null
    }

    fun acquireAsync(
        key: String,
        ownerId: String,
        auditLeaderId: String,
        nodeId: String,
        waitTime: Duration,
        leaseTime: Duration,
        retryDelay: Duration,
        ttlPadding: Duration,
        clockSkewTolerance: Duration,
    ): CompletableFuture<AcquiredLock?> {
        val deadline = MonotonicDeadline.fromNow(waitTime)
        val result = CompletableFuture<AcquiredLock?>()

        fun releaseLate(acquired: AcquiredLock) {
            releaseAsync(acquired, Duration.ZERO, System.nanoTime())
                .whenComplete { _, failure ->
                    if (failure != null) {
                        log.warn(failure.unwrapCompletion()) { "DynamoDB late async acquire release failed. key=$key" }
                    }
                }
        }

        fun attempt() {
            if (result.isDone) {
                return
            }
            acquireOnceAsync(key, ownerId, auditLeaderId, nodeId, leaseTime, ttlPadding, clockSkewTolerance)
                .whenComplete { acquired, failure ->
                    val cause = failure?.unwrapCompletion()
                    when {
                        result.isDone && acquired != null -> releaseLate(acquired)
                        result.isDone -> Unit
                        cause != null -> result.completeExceptionally(cause)
                        acquired != null -> result.complete(acquired)
                        !deadline.hasTimeRemaining() -> result.complete(null)
                        else -> {
                            val delayMillis = jitterDelayMillis(deadline, retryDelay)
                            CompletableFuture.runAsync(
                                {},
                                CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS),
                            ).whenComplete { _, delayFailure ->
                                val delayCause = delayFailure?.unwrapCompletion()
                                when {
                                    result.isDone -> Unit
                                    delayCause != null -> result.completeExceptionally(delayCause)
                                    else -> attempt()
                                }
                            }
                        }
                    }
                }
        }

        attempt()
        return result
    }

    private fun acquireOnce(
        key: String,
        ownerId: String,
        auditLeaderId: String,
        nodeId: String,
        leaseTime: Duration,
        ttlPadding: Duration,
        clockSkewTolerance: Duration,
    ): AcquiredLock? {
        val client = requireNotNull(syncClient) { "sync DynamoDbClient is required" }
        val leaseExpiryMillis = nowMillis() + leaseTime.inWholeMilliseconds
        return try {
            client.putItem(acquireRequest(key, ownerId, auditLeaderId, nodeId, leaseExpiryMillis, ttlPadding, clockSkewTolerance))
            AcquiredLock(key, ownerId, auditLeaderId, nodeId, leaseExpiryMillis)
        } catch (e: ConditionalCheckFailedException) {
            reconcileOwned(key, ownerId)
        }
    }

    private fun acquireOnceAsync(
        key: String,
        ownerId: String,
        auditLeaderId: String,
        nodeId: String,
        leaseTime: Duration,
        ttlPadding: Duration,
        clockSkewTolerance: Duration,
    ): CompletableFuture<AcquiredLock?> {
        val client = requireNotNull(asyncClient) { "async DynamoDbAsyncClient is required" }
        val leaseExpiryMillis = nowMillis() + leaseTime.inWholeMilliseconds
        return client.putItem(acquireRequest(key, ownerId, auditLeaderId, nodeId, leaseExpiryMillis, ttlPadding, clockSkewTolerance))
            .thenApply<AcquiredLock?> { AcquiredLock(key, ownerId, auditLeaderId, nodeId, leaseExpiryMillis) }
            .exceptionallyCompose { failure ->
                val cause = failure.unwrapCompletion()
                if (cause is ConditionalCheckFailedException) {
                    reconcileOwnedAsync(key, ownerId)
                } else {
                    CompletableFuture.failedFuture(cause)
                }
            }
    }

    private fun acquireRequest(
        key: String,
        ownerId: String,
        auditLeaderId: String,
        nodeId: String,
        leaseExpiryMillis: Long,
        ttlPadding: Duration,
        clockSkewTolerance: Duration,
    ): PutItemRequest {
        val safeNow = nowMillis() - clockSkewTolerance.inWholeMilliseconds
        return PutItemRequest.builder()
            .tableName(tableName)
            .item(
                mapOf(
                    LockName to s(key),
                    OwnerId to s(ownerId),
                    AuditLeaderId to s(auditLeaderId),
                    NodeId to s(nodeId),
                    LeaseExpiry to n(leaseExpiryMillis),
                    Ttl to n(ttlEpochSeconds(leaseExpiryMillis, ttlPadding)),
                )
            )
            .conditionExpression(
                "attribute_not_exists($LockNameAttr) OR attribute_not_exists($LeaseAttr) OR $LeaseAttr < :safeNow"
            )
            .expressionAttributeNames(mapOf(LockNameAttr to LockName, LeaseAttr to LeaseExpiry))
            .expressionAttributeValues(mapOf(":safeNow" to n(safeNow)))
            .build()
    }

    fun release(lock: AcquiredLock, minLeaseTime: Duration, acquiredAtNanos: Long) {
        val client = requireNotNull(syncClient) { "sync DynamoDbClient is required" }
        try {
            val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
            if (remaining > Duration.ZERO) {
                client.updateItem(retainUntilRequest(lock, remaining))
            } else {
                client.deleteItem(deleteRequest(lock.key, lock.ownerId))
            }
        } catch (e: ConditionalCheckFailedException) {
            log.warn(e) { "DynamoDB release ignored because owner no longer matches. key=${lock.key}" }
        }
    }

    fun releaseAsync(lock: AcquiredLock, minLeaseTime: Duration, acquiredAtNanos: Long): CompletableFuture<Unit> {
        val client = requireNotNull(asyncClient) { "async DynamoDbAsyncClient is required" }
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
        val future =
            if (remaining > Duration.ZERO) {
                client.updateItem(retainUntilRequest(lock, remaining)).thenApply { Unit }
            } else {
                client.deleteItem(deleteRequest(lock.key, lock.ownerId)).thenApply { Unit }
            }
        return future.exceptionallyCompose { failure ->
            val cause = failure.unwrapCompletion()
            if (cause is ConditionalCheckFailedException) {
                log.warn(cause) { "DynamoDB async release ignored because owner no longer matches. key=${lock.key}" }
                CompletableFuture.completedFuture(Unit)
            } else {
                CompletableFuture.failedFuture(cause)
            }
        }
    }

    fun extend(lock: AcquiredLock, leaseTime: Duration, ttlPadding: Duration): ExtendOutcome {
        val client = requireNotNull(syncClient) { "sync DynamoDbClient is required" }
        val now = nowMillis()
        val leaseExpiryMillis = now + leaseTime.inWholeMilliseconds
        return try {
            client.updateItem(extendRequest(lock, now, leaseExpiryMillis, ttlPadding))
            ExtendOutcome.Extended(Instant.ofEpochMilli(leaseExpiryMillis))
        } catch (e: ConditionalCheckFailedException) {
            log.debug { "DynamoDB extend returned NotHeld. key=${lock.key}" }
            ExtendOutcome.NotHeld
        }
    }

    fun extendAsync(lock: AcquiredLock, leaseTime: Duration, ttlPadding: Duration): CompletableFuture<ExtendOutcome> {
        val client = requireNotNull(asyncClient) { "async DynamoDbAsyncClient is required" }
        val now = nowMillis()
        val leaseExpiryMillis = now + leaseTime.inWholeMilliseconds
        return client.updateItem(extendRequest(lock, now, leaseExpiryMillis, ttlPadding))
            .thenApply<ExtendOutcome> { ExtendOutcome.Extended(Instant.ofEpochMilli(leaseExpiryMillis)) }
            .exceptionallyCompose { failure ->
                val cause = failure.unwrapCompletion()
                if (cause is ConditionalCheckFailedException) {
                    CompletableFuture.completedFuture(ExtendOutcome.NotHeld)
                } else {
                    CompletableFuture.failedFuture(cause)
                }
            }
    }

    fun isHeld(lock: AcquiredLock): Boolean =
        read(lock.key)?.ownerId == lock.ownerId

    fun isHeldAsync(lock: AcquiredLock): CompletableFuture<Boolean> =
        readAsync(lock.key).thenApply { it?.ownerId == lock.ownerId }

    fun state(lockName: String, key: String): LeaderState {
        val record = read(key)
        val now = nowMillis()
        return if (record == null || record.leaseExpiryMillis <= now) {
            LeaderState.empty(lockName)
        } else {
            LeaderState.occupied(
                lockName,
                LeaderLease(
                    auditLeaderId = record.auditLeaderId,
                    nodeId = record.nodeId,
                    electedAt = null,
                    leaseUntil = Instant.ofEpochMilli(record.leaseExpiryMillis),
                )
            )
        }
    }

    fun activeGroupLeases(prefix: String): List<LeaderLease> {
        val now = nowMillis()
        val request = ScanRequest.builder()
            .tableName(tableName)
            .filterExpression("begins_with($LockNameAttr, :prefix) AND $LeaseAttr > :now")
            .expressionAttributeNames(mapOf(LockNameAttr to LockName, LeaseAttr to LeaseExpiry))
            .expressionAttributeValues(mapOf(":prefix" to s(prefix), ":now" to n(now)))
            .build()
        val items = scanAll(request)
        return items.mapNotNull { item ->
            val ownerId = item[OwnerId]?.s() ?: return@mapNotNull null
            val auditLeaderId = item[AuditLeaderId]?.s() ?: ownerId
            val nodeId = item[NodeId]?.s()
            val leaseExpiry = item[LeaseExpiry]?.n()?.toLongOrNull() ?: return@mapNotNull null
            val slot = item[LockName]?.s()?.substringAfterLast("#slot-", "")?.toIntOrNull()
            LeaderLease(auditLeaderId, leaseUntil = Instant.ofEpochMilli(leaseExpiry), slot = slot, nodeId = nodeId)
        }
    }

    private fun scanAll(request: ScanRequest): List<Map<String, AttributeValue>> {
        val items = mutableListOf<Map<String, AttributeValue>>()
        var nextKey: Map<String, AttributeValue>? = null
        do {
            val pageRequest = request.toBuilder().apply {
                if (!nextKey.isNullOrEmpty()) {
                    exclusiveStartKey(nextKey)
                }
            }.build()
            val response = syncClient?.scan(pageRequest)
                ?: requireNotNull(asyncClient) { "DynamoDB client is required" }.scan(pageRequest).join()
            items += response.items()
            nextKey = response.lastEvaluatedKey().takeUnless { it.isNullOrEmpty() }
        } while (nextKey != null)
        return items
    }

    private data class LockRecord(
        val ownerId: String,
        val auditLeaderId: String,
        val nodeId: String?,
        val leaseExpiryMillis: Long,
    )

    private fun reconcileOwned(key: String, ownerId: String): AcquiredLock? =
        read(key)
            ?.takeIf { it.ownerId == ownerId }
            ?.let { AcquiredLock(key, ownerId, it.auditLeaderId, it.nodeId.orEmpty(), it.leaseExpiryMillis) }

    private fun reconcileOwnedAsync(key: String, ownerId: String): CompletableFuture<AcquiredLock?> =
        readAsync(key).thenApply { record ->
            record?.takeIf { it.ownerId == ownerId }
                ?.let { AcquiredLock(key, ownerId, it.auditLeaderId, it.nodeId.orEmpty(), it.leaseExpiryMillis) }
        }

    private fun read(key: String): LockRecord? {
        val request = readRequest(key)
        val item = syncClient?.getItem(request)?.item()
            ?: requireNotNull(asyncClient) { "DynamoDB client is required" }.getItem(request).join().item()
        return item.toLockRecord()
    }

    private fun readAsync(key: String): CompletableFuture<LockRecord?> {
        val client = requireNotNull(asyncClient) { "async DynamoDbAsyncClient is required" }
        return client.getItem(readRequest(key)).thenApply { it.item().toLockRecord() }
    }

    private fun Map<String, AttributeValue>?.toLockRecord(): LockRecord? {
        if (this.isNullOrEmpty()) {
            return null
        }
        val ownerId = get(OwnerId)?.s() ?: return null
        val auditLeaderId = get(AuditLeaderId)?.s() ?: ownerId
        val nodeId = get(NodeId)?.s()
        val leaseExpiry = get(LeaseExpiry)?.n()?.toLongOrNull() ?: return null
        return LockRecord(ownerId, auditLeaderId, nodeId, leaseExpiry)
    }

    private fun readRequest(key: String): GetItemRequest =
        GetItemRequest.builder()
            .tableName(tableName)
            .consistentRead(true)
            .key(mapOf(LockName to s(key)))
            .build()

    private fun retainUntilRequest(lock: AcquiredLock, remaining: Duration): UpdateItemRequest {
        val leaseExpiryMillis = nowMillis() + remaining.inWholeMilliseconds
        return updateLeaseRequest(lock, leaseExpiryMillis, Duration.ZERO)
    }

    private fun extendRequest(
        lock: AcquiredLock,
        nowMillis: Long,
        leaseExpiryMillis: Long,
        ttlPadding: Duration,
    ): UpdateItemRequest =
        updateLeaseRequest(lock, leaseExpiryMillis, ttlPadding)
            .toBuilder()
            .conditionExpression("$OwnerAttr = :owner AND $LeaseAttr > :now")
            .expressionAttributeValues(
                mapOf(
                    ":owner" to s(lock.ownerId),
                    ":now" to n(nowMillis),
                    ":leaseExpiry" to n(leaseExpiryMillis),
                    ":ttl" to n(ttlEpochSeconds(leaseExpiryMillis, ttlPadding)),
                )
            )
            .build()

    private fun updateLeaseRequest(
        lock: AcquiredLock,
        leaseExpiryMillis: Long,
        ttlPadding: Duration,
    ): UpdateItemRequest =
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(mapOf(LockName to s(lock.key)))
            .updateExpression("SET $LeaseAttr = :leaseExpiry, $TtlAttr = :ttl")
            .conditionExpression("$OwnerAttr = :owner")
            .expressionAttributeNames(mapOf(OwnerAttr to OwnerId, LeaseAttr to LeaseExpiry, TtlAttr to Ttl))
            .expressionAttributeValues(
                mapOf(
                    ":owner" to s(lock.ownerId),
                    ":leaseExpiry" to n(leaseExpiryMillis),
                    ":ttl" to n(ttlEpochSeconds(leaseExpiryMillis, ttlPadding)),
                )
            )
            .build()

    private fun deleteRequest(key: String, ownerId: String): DeleteItemRequest =
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(mapOf(LockName to s(key)))
            .conditionExpression("$OwnerAttr = :owner")
            .expressionAttributeNames(mapOf(OwnerAttr to OwnerId))
            .expressionAttributeValues(mapOf(":owner" to s(ownerId)))
            .build()

    private fun sleepBeforeRetry(deadline: MonotonicDeadline, retryDelay: Duration) {
        val delayMillis = jitterDelayMillis(deadline, retryDelay)
        if (delayMillis > 0L) {
            Thread.sleep(delayMillis)
        }
    }

    private fun jitterDelayMillis(deadline: MonotonicDeadline, retryDelay: Duration): Long {
        val bound = retryDelay.inWholeMilliseconds.coerceAtLeast(2L)
        val jitter = Random.nextLong(1L, bound)
        return deadline.remainingMillisForDelay(jitter)
    }

    private fun ttlEpochSeconds(leaseExpiryMillis: Long, ttlPadding: Duration): Long =
        ceil((leaseExpiryMillis + ttlPadding.inWholeMilliseconds) / 1000.0).toLong()

    private fun s(value: String): AttributeValue = AttributeValue.builder().s(value).build()
    private fun n(value: Long): AttributeValue = AttributeValue.builder().n(value.toString()).build()

    private fun Throwable.unwrapCompletion(): Throwable =
        (this as? java.util.concurrent.CompletionException)?.cause ?: this
}
