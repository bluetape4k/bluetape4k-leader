package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.dynamodb.internal.DynamoDbBackendErrorClassifier
import io.bluetape4k.leader.dynamodb.internal.DynamoDbKeys
import io.bluetape4k.leader.dynamodb.internal.DynamoDbLockClient
import io.bluetape4k.leader.dynamodb.internal.DynamoDbLockExtendDelegate
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-leader election backed by DynamoDB conditional writes.
 *
 * The table is caller-provisioned with `lockName` as string partition key and
 * TTL enabled on the numeric `ttl` attribute.
 */
class DynamoDbLeaderElector(
    private val dynamoDb: DynamoDbClient,
    val options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
) : LeaderElector {

    companion object : KLogging() {
        internal const val DYNAMODB_FACTORY_BEAN_NAME = "dynamodb-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(DynamoDbBackendErrorClassifier)
    }

    private val lockClient = DynamoDbLockClient(options.tableName, syncClient = dynamoDb)

    override fun state(lockName: String): LeaderState {
        DynamoDbKeys.validateUserLockName(lockName)
        return lockClient.state(lockName, DynamoDbKeys.single(options.keyPrefix, lockName))
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runWithLock(lockName, auditLeaderId = null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runWithLock(slot.lockName, slot.leaderId, action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(slot) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private fun <T> runWithLock(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        DynamoDbKeys.validateUserLockName(lockName)
        val key = DynamoDbKeys.single(options.keyPrefix, lockName)
        val ownerId = DynamoDbLockClient.newOwnerId()
        val lock = lockClient.acquire(
            key,
            ownerId,
            auditLeaderId ?: ownerId,
            options.leaderOptions.nodeId,
            options.leaderOptions.waitTime,
            options.leaderOptions.leaseTime,
            options.retryDelay,
            options.ttlPadding,
            options.clockSkewTolerance,
        ) ?: return null

        val acquiredAtNanos = System.nanoTime()
        val delegate = DynamoDbLockExtendDelegate(lockClient, lock, options.ttlPadding)
        val handle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = DYNAMODB_FACTORY_BEAN_NAME,
            ),
            token = lock.ownerId,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
            auditLeaderId = lock.auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )

        try {
            log.debug { "DynamoDB leader acquired. lockName=$lockName" }
            return AopScopeAccess.withPushedSync(handle) { action() }
        } finally {
            runCatching { watchdog.close() }
                .onFailure { e -> log.warn(e) { "DynamoDB leader watchdog close failed. lockName=$lockName" } }
            runCatching { lockClient.release(lock, options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                .onFailure { e -> log.warn(e) { "DynamoDB leader release failed. lockName=$lockName" } }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync({ runIfLeader(lockName) { action().join() } }, executor)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync({ runIfLeader(slot) { action().join() } }, executor)

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        val elected = AtomicBoolean(false)
        return runAsyncIfLeader(slot, executor) {
            elected.set(true)
            action()
        }.handle { value, failure ->
            val cause = (failure as? CompletionException)?.cause ?: failure
            when {
                cause is CancellationException -> throw cause
                cause != null && elected.get() -> LeaderRunResult.ActionFailed(cause)
                cause != null -> throw CompletionException(cause)
                elected.get() -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else -> LeaderRunResult.Skipped
            }
        }
    }
}

/**
 * Runs a blocking action only while this DynamoDB client holds leadership.
 *
 * ## Behavior / Contract
 * Returns the action result when leadership is acquired. Returns `null` when another node holds the lock
 * or acquisition times out according to [options].
 *
 * ```kotlin
 * val result = dynamoDb.runIfLeader("nightly-job") {
 *     rebuildIndex()
 * }
 * ```
 */
fun <T> DynamoDbClient.runIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: () -> T,
): T? = DynamoDbLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * Runs an asynchronous action only while this DynamoDB client holds leadership.
 *
 * ## Behavior / Contract
 * Returns a [CompletableFuture] that completes with the action result when leadership is acquired.
 * The future completes with `null` when another node holds the lock or acquisition times out according
 * to [options].
 *
 * ```kotlin
 * val future = dynamoDb.runAsyncIfLeader("nightly-job") {
 *     CompletableFuture.supplyAsync { rebuildIndex() }
 * }
 * ```
 */
fun <T> DynamoDbClient.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = DynamoDbLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
