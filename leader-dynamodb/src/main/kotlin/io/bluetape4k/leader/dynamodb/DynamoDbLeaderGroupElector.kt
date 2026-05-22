package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.dynamodb.internal.DynamoDbKeys
import io.bluetape4k.leader.dynamodb.internal.DynamoDbLockClient
import io.bluetape4k.leader.dynamodb.internal.DynamoDbLockExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Slot-based multi-leader election backed by DynamoDB conditional writes.
 */
class DynamoDbLeaderGroupElector(
    private val dynamoDb: DynamoDbClient,
    val options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
) : LeaderGroupElector {

    companion object : KLogging() {
        internal const val DYNAMODB_GROUP_FACTORY_BEAN_NAME = "dynamodb-leader-group-elector"
    }

    override val maxLeaders: Int get() = options.maxLeaders

    private val lockClient = DynamoDbLockClient(options.tableName, syncClient = dynamoDb)

    override fun activeCount(lockName: String): Int = state(lockName).activeCount

    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState {
        DynamoDbKeys.validateUserLockName(lockName)
        val leases = lockClient.activeGroupLeases(DynamoDbKeys.groupPrefix(options.keyPrefix, lockName))
        return LeaderGroupState(lockName, maxLeaders, leases.size.coerceAtMost(maxLeaders), leases)
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
        val start = Random.nextInt(maxLeaders)
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders

        for (offset in 0 until maxLeaders) {
            val slot = (start + offset) % maxLeaders
            val key = DynamoDbKeys.groupSlot(options.keyPrefix, lockName, slot)
            val ownerId = DynamoDbLockClient.newOwnerId()
            val lock = lockClient.acquire(
                key,
                ownerId,
                auditLeaderId ?: ownerId,
                options.leaderGroupOptions.nodeId,
                perSlotWait,
                options.leaderGroupOptions.leaseTime,
                options.retryDelay,
                options.ttlPadding,
                options.clockSkewTolerance,
            ) ?: continue

            val acquiredAtNanos = System.nanoTime()
            val delegate = DynamoDbLockExtendDelegate(lockClient, lock, options.ttlPadding)
            val handle = LeaderLockHandle.real(
                identity = LockIdentity(
                    lockName = lockName,
                    kind = LockIdentity.AnnotationKind.GROUP,
                    factoryBeanName = DYNAMODB_GROUP_FACTORY_BEAN_NAME,
                    groupParams = LockIdentity.GroupParams(maxLeaders),
                ),
                token = lock.ownerId,
                acquiredAtNanos = acquiredAtNanos,
                slotId = slot.toString(),
                extendDelegate = delegate,
                auditLeaderId = lock.auditLeaderId,
            )
            val watchdog = LeaderLeaseAutoExtender.start(
                false,
                options.leaderGroupOptions.leaseTime,
                delegate,
                DynamoDbLeaderElector.ERROR_CLASSIFIER,
            )

            try {
                log.debug { "DynamoDB leader group slot acquired. lockName=$lockName, slot=$slot" }
                return AopScopeAccess.withPushedSync(handle) {
                    AopScopeAccess.setCapture(handle)
                    try {
                        action()
                    } finally {
                        AopScopeAccess.clearCapture()
                    }
            }
        } finally {
                runCatching { watchdog.close() }
                    .onFailure { e ->
                        log.warn(e) { "DynamoDB group watchdog close failed. lockName=$lockName, slot=$slot" }
                    }
                runCatching { lockClient.release(lock, options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                    .onFailure { e -> log.warn(e) { "DynamoDB group slot release failed. lockName=$lockName, slot=$slot" } }
            }
        }

        return null
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

fun <T> DynamoDbClient.runIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = DynamoDbLeaderGroupElector(this, options).runIfLeader(lockName, action)

fun <T> DynamoDbClient.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = DynamoDbLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
