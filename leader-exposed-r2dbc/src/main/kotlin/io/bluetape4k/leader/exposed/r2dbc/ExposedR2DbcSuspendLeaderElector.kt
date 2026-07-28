package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.internal.ExposedR2dbcBackendErrorClassifier
import io.bluetape4k.leader.exposed.r2dbc.internal.ExposedR2dbcSuspendLockExtendDelegate
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcLock
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcSchemaInitializer
import io.bluetape4k.leader.exposed.r2dbc.lock.validateExposedR2dbcLockName
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * `ExposedR2DbcSuspendLeaderElector`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property db Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property historyRecorder Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ExposedR2DbcSuspendLeaderElector private constructor(
    private val db: R2dbcDatabase,
    val options: ExposedR2dbcLeaderElectionOptions,
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
) : SuspendLeaderElector {

    companion object : KLoggingChannel() {

        internal const val EXPOSED_R2DBC_SUSPEND_FACTORY_BEAN_NAME = "exposed-r2dbc-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedR2dbcBackendErrorClassifier)

        /**
         * `invoke` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
         */
        suspend operator fun invoke(
            db: R2dbcDatabase,
            options: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
            historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
        ): ExposedR2DbcSuspendLeaderElector {
            ExposedR2dbcSchemaInitializer.ensureSchema(db)
            return ExposedR2DbcSuspendLeaderElector(db, options, historyRecorder)
        }
    }

    /**
     * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        validateExposedR2dbcLockName(lockName)

        val lock = ExposedR2dbcLock(
            db = db,
            lockName = lockName,
            retryStrategy = options.retryStrategy,
            lockOwner = options.lockOwner,
            useDbTime = options.leaderOptions.useDbTime,
        )
        log.debug { "리더 승격을 요청합니다. lockName=$lockName" }

        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            log.debug { "리더 승격 실패 (락 획득 불가). lockName=$lockName" }
            return null
        }

        log.debug { "리더로 승격하여 작업을 수행합니다. lockName=$lockName" }

        val acquiredAtNanos = System.nanoTime()
        var watchdog: AutoCloseable? = null

        try {
            val startedAt = Instant.now()
            val delegate: SuspendExtendDelegate = ExposedR2dbcSuspendLockExtendDelegate(lock)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = EXPOSED_R2DBC_SUSPEND_FACTORY_BEAN_NAME,
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
                    nodeId = options.lockOwner,
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
                    log.debug { "리더 권한을 반납했습니다. lockName=$lockName" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "락 해제 실패. lockName=$lockName" }
                }
            }
        }
    }
}
