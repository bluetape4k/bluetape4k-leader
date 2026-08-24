package io.bluetape4k.leader.mongodb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.mongodb.internal.MongoBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoSuspendLockExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.bson.Document
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * `MongoSuspendLeaderElector`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property collection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property historyRecorder MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class MongoSuspendLeaderElector private constructor(
    private val collection: MongoCollection<Document>,
    val options: MongoLeaderElectionOptions,
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
): SuspendLeaderElector,
    LeaderBackendDiagnosticsProvider by MongoLeaderBackendDiagnostics,
    io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport {

    override val suspendLeaseAcquirerDelegate: io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.SuspendLeaderElectorLeaseAdapter({ this }, options.leaderOptions)
    }

    companion object : KLoggingChannel() {
        internal const val MONGO_SUSPEND_FACTORY_BEAN_NAME = "mongo-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(MongoBackendErrorClassifier)

        suspend operator fun invoke(
            collection: MongoCollection<Document>,
            options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
            historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
        ): MongoSuspendLeaderElector {
            MongoSuspendLock.ensureIndexes(collection)
            return MongoSuspendLeaderElector(collection, options, historyRecorder)
        }
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        validateMongoLockName(lockName)
        val lock = MongoSuspendLock(collection, lockName, options.retryDelay)
        log.debug { "리더 승격을 요청합니다 (suspend). lockName=$lockName" }

        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            log.debug { "리더 승격 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        var watchdog: AutoCloseable? = null

        log.debug { "리더로 승격하여 suspend 작업을 수행합니다. lockName=$lockName" }
        try {
            val startedAt = Instant.now()
            val delegate: SuspendExtendDelegate = MongoSuspendLockExtendDelegate(lock)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = MONGO_SUSPEND_FACTORY_BEAN_NAME,
            )
            val handle = LeaderLockHandle.real(
                identity = identity,
                token = lock.token,
                acquiredAtNanos = acquiredAtNanos,
                extendDelegate = delegate,
            )
            watchdog = LeaderLeaseAutoExtender.start(
                options.leaderOptions.autoExtend,
                options.leaderOptions.leaseTime,
                delegate,
                ERROR_CLASSIFIER,
            )

            val record = historyRecorder?.let {
                LeaderLockHistoryRecord(
                    lockName = lockName,
                    token = lock.token,
                    kind = LockIdentity.AnnotationKind.SINGLE,
                    acquiredAt = startedAt,
                    lockedUntil = startedAt.plusMillis(options.leaderOptions.leaseTime.inWholeMilliseconds),
                )
            }
            val key = record?.let { historyRecorder.recordAcquired(it) }
            val effectiveKey: LeaderHistoryKey? =
                key ?: record?.let { LeaderHistoryKey(lockName = lockName, token = lock.token) }

            return try {
                val result = withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                throw e
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog?.close()
                try {
                    lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos)
                    log.debug { "리더 권한을 반납했습니다 (suspend). lockName=$lockName" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "락 해제 실패 (suspend). lockName=$lockName" }
                }
            }
        }
    }
}

/**
 * `선언` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend fun <T> MongoCollection<Document>.suspendRunIfLeader(
    lockName: String,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = MongoSuspendLeaderElector(this, options).runIfLeader(lockName, action)
