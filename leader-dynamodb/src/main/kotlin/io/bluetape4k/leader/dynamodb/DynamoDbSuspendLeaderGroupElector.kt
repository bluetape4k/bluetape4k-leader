package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.dynamodb.internal.DynamoDbKeys
import io.bluetape4k.leader.dynamodb.internal.DynamoDbLockClient
import io.bluetape4k.leader.dynamodb.internal.DynamoDbSuspendLockExtendDelegate
import io.bluetape4k.leader.dynamodb.internal.awaitWithoutCancellingFuture
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Coroutine slot-based multi-leader election backed by DynamoDB conditional writes.
 */
class DynamoDbSuspendLeaderGroupElector(
    private val dynamoDb: DynamoDbAsyncClient,
    val options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElector {

    companion object : KLoggingChannel()

    override val maxLeaders: Int get() = options.maxLeaders

    private val lockClient = DynamoDbLockClient(options.tableName, asyncClient = dynamoDb)

    override fun activeCount(lockName: String): Int = state(lockName).activeCount

    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState {
        DynamoDbKeys.validateUserLockName(lockName)
        val leases = lockClient.activeGroupLeases(DynamoDbKeys.groupPrefix(options.keyPrefix, lockName))
        return LeaderGroupState(lockName, maxLeaders, leases.size.coerceAtMost(maxLeaders), leases)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runWithLock(lockName, auditLeaderId = null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runWithLock(slot.lockName, slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(slot) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private suspend fun <T> runWithLock(lockName: String, auditLeaderId: String?, action: suspend () -> T): T? {
        DynamoDbKeys.validateUserLockName(lockName)
        val start = Random.nextInt(maxLeaders)
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders

        for (offset in 0 until maxLeaders) {
            val slot = (start + offset) % maxLeaders
            val key = DynamoDbKeys.groupSlot(options.keyPrefix, lockName, slot)
            val ownerId = DynamoDbLockClient.newOwnerId()
            val future = lockClient.acquireAsync(
                key,
                ownerId,
                auditLeaderId ?: ownerId,
                options.leaderGroupOptions.nodeId,
                perSlotWait,
                options.leaderGroupOptions.leaseTime,
                options.retryDelay,
                options.ttlPadding,
                options.clockSkewTolerance,
            )
            val lock = future.awaitWithoutCancellingFuture { pending ->
                pending.whenComplete { acquired, _ ->
                    if (acquired != null) {
                        lockClient.releaseAsync(acquired, Duration.ZERO, System.nanoTime())
                    }
                }
            } ?: continue

            val acquiredAtNanos = System.nanoTime()
            val delegate = DynamoDbSuspendLockExtendDelegate(lockClient, lock, options.ttlPadding)
            val handle = LeaderLockHandle.real(
                identity = LockIdentity(
                    lockName = lockName,
                    kind = LockIdentity.AnnotationKind.GROUP,
                    factoryBeanName = DynamoDbLeaderGroupElector.DYNAMODB_GROUP_FACTORY_BEAN_NAME,
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
                log.debug { "DynamoDB suspend group slot acquired. lockName=$lockName, slot=$slot" }
                return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                runCatching { watchdog.close() }
                    .onFailure { e ->
                        log.warn(e) { "DynamoDB suspend group watchdog close failed. lockName=$lockName, slot=$slot" }
                    }
                withContext(NonCancellable) {
                    runCatching {
                        lockClient.releaseAsync(lock, options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)
                            .awaitWithoutCancellingFuture { }
                    }.onFailure { e ->
                        log.warn(e) { "DynamoDB suspend group slot release failed. lockName=$lockName, slot=$slot" }
                    }
                }
            }
        }

        return null
    }
}
