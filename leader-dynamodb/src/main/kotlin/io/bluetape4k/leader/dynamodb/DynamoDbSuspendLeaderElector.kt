package io.bluetape4k.leader.dynamodb

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
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
import kotlin.time.Duration

/**
 * `DynamoDbSuspendLeaderElector`는 DynamoDB backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property dynamoDb DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class DynamoDbSuspendLeaderElector(
    private val dynamoDb: DynamoDbAsyncClient,
    val options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
) : SuspendLeaderElector,
    LeaderBackendDiagnosticsProvider by DynamoDbLeaderBackendDiagnostics,
    io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport {

    override val suspendLeaseAcquirerDelegate: io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.SuspendLeaderElectorLeaseAdapter({ this }, options.leaderOptions)
    }

    companion object : KLoggingChannel()

    private val lockClient = DynamoDbLockClient(options.tableName, asyncClient = dynamoDb)

    override val supportsAuditLeaderState: Boolean = true

    override fun state(lockName: String): LeaderState {
        DynamoDbKeys.validateUserLockName(lockName)
        return lockClient.state(lockName, DynamoDbKeys.single(options.keyPrefix, lockName))
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
        val key = DynamoDbKeys.single(options.keyPrefix, lockName)
        val ownerId = DynamoDbLockClient.newOwnerId()
        val acquireFuture = lockClient.acquireAsync(
            key,
            ownerId,
            auditLeaderId ?: ownerId,
            options.leaderOptions.nodeId,
            options.leaderOptions.waitTime,
            options.leaderOptions.leaseTime,
            options.retryDelay,
            options.ttlPadding,
            options.clockSkewTolerance,
        )
        val lock = acquireFuture.awaitWithoutCancellingFuture { future ->
            future.whenComplete { acquired, _ ->
                if (acquired != null) {
                    lockClient.releaseAsync(acquired, Duration.ZERO, System.nanoTime())
                }
            }
        } ?: return null

        val acquiredAtNanos = System.nanoTime()
        val delegate = DynamoDbSuspendLockExtendDelegate(lockClient, lock, options.ttlPadding)
        val handle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = DynamoDbLeaderElector.DYNAMODB_FACTORY_BEAN_NAME,
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
            DynamoDbLeaderElector.ERROR_CLASSIFIER,
        )

        try {
            log.debug { "DynamoDB suspend leader acquired. lockName=$lockName" }
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            runCatching { watchdog.close() }
                .onFailure { e -> log.warn(e) { "DynamoDB suspend leader watchdog close failed. lockName=$lockName" } }
            withContext(NonCancellable) {
                try {
                    lockClient.releaseAsync(lock, options.leaderOptions.minLeaseTime, acquiredAtNanos)
                        .awaitWithoutCancellingFuture { }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "DynamoDB suspend leader release failed. lockName=$lockName" }
                }
            }
        }
    }
}
