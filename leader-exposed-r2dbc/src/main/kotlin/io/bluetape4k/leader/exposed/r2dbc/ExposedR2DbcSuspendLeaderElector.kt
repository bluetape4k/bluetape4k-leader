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
import io.bluetape4k.leader.exposed.tables.HistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Instant

/**
 * Exposed R2DBC 기반 코루틴 단일 리더 선출 구현체.
 *
 * `UPDATE + insertIgnore + SELECT` 패턴으로 DB 행 수준 락을 구현합니다.
 *
 * ## 기본 사용
 * ```kotlin
 * val election = ExposedR2dbcSuspendLeaderElector(db)
 * val result = election.runIfLeader("daily-job") {
 *     delay(100)
 *     processData()
 * }
 * // result == processData() 반환값 (리더 획득 성공) 또는 null (획득 실패)
 * ```
 *
 * ## 취소 안전성
 * `finally` 블록은 항상 `withContext(NonCancellable)`에서 실행되어 락이 보장 해제됩니다.
 *
 * **private constructor** — [invoke] 팩터리를 통해서만 생성하세요.
 * 첫 호출 시 [ExposedR2dbcSchemaInitializer.ensureSchema]가 실행되어 스키마가 자동으로 생성됩니다.
 *
 * @param db Exposed [R2dbcDatabase] 인스턴스
 * @param options 단일 리더 선출 옵션
 */
class ExposedR2DbcSuspendLeaderElector private constructor(
    private val db: R2dbcDatabase,
    val options: ExposedR2dbcLeaderElectionOptions,
) : SuspendLeaderElector {

    companion object : KLoggingChannel() {

        internal const val EXPOSED_R2DBC_SUSPEND_FACTORY_BEAN_NAME = "exposed-r2dbc-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedR2dbcBackendErrorClassifier)

        /**
         * [ExposedR2DbcSuspendLeaderElector] 인스턴스를 생성합니다.
         *
         * 첫 호출 시 리더 선출 테이블 스키마를 자동으로 생성합니다 (최초 1회).
         */
        suspend operator fun invoke(
            db: R2dbcDatabase,
            options: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
        ): ExposedR2DbcSuspendLeaderElector {
            ExposedR2dbcSchemaInitializer.ensureSchema(db)
            return ExposedR2DbcSuspendLeaderElector(db, options)
        }
    }

    /**
     * [lockName]에 대해 리더로 승격되면 suspend [action]을 실행하고 결과를 반환합니다.
     *
     * - 리더 획득에 성공하면 [action] 결과를 반환합니다.
     * - 리더 획득에 실패하면 `null`을 반환합니다 (**예외 없음** — ShedLock 호환 skip-on-contention 계약).
     * - [action]이 던지는 예외는 그대로 전파되며, 락은 항상 해제됩니다.
     * - [CancellationException]은 재전파되며 FAILED 이력에 기록되지 않습니다.
     *
     * @param lockName 락 식별자 (영숫자/하이픈/언더스코어/콜론, 1-255자)
     * @param action 리더 획득 성공 시 실행할 suspend 작업
     * @return [action] 결과 또는 `null`
     * @throws IllegalArgumentException [lockName]이 유효하지 않은 경우
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        validateExposedR2dbcLockName(lockName)

        val lock = ExposedR2dbcLock(db, lockName, options.retryStrategy, options.lockOwner)
        log.debug { "리더 승격을 요청합니다. lockName=$lockName" }

        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            log.debug { "리더 승격 실패 (락 획득 불가). lockName=$lockName" }
            return null
        }

        log.debug { "리더로 승격하여 작업을 수행합니다. lockName=$lockName" }

        val historyId = recordAcquired(lockName, lock.token)
        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()

        // T11 PR 6 (Issue #79) — ExtendDelegate / handle / watchdog 단일 reference 공유 (AC-15).
        val delegate = ExposedR2dbcSuspendLockExtendDelegate(lock)
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
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )

        var actionSucceeded = false
        var actionFailed = false

        try {
            val result = withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
            actionSucceeded = true
            return result
        } catch (e: CancellationException) {
            // CancellationException은 actionFailed 미설정 + 즉시 재전파 (코루틴 취소 계약)
            throw e
        } catch (e: Throwable) {
            actionFailed = true
            throw e
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
                when {
                    actionSucceeded -> recordCompleted(historyId, lock.token, startedAt)
                    actionFailed -> recordFailed(historyId, lock.token, startedAt)
                    // CancellationException: 이력 미기록 (취소이므로 FAILED 아님)
                }
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

    private suspend fun recordAcquired(lockName: String, token: String): Long? {
        if (!options.recordHistory) return null
        return runCatching {
            suspendTransaction(db) {
                val now = Instant.now()
                LeaderLockHistoryTable.insert {
                    it[LeaderLockHistoryTable.lockName] = lockName
                    it[LeaderLockHistoryTable.lockOwner] = options.lockOwner
                    it[LeaderLockHistoryTable.token] = token
                    it[LeaderLockHistoryTable.lockedUntil] = now.plusMillis(options.leaderOptions.leaseTime.inWholeMilliseconds)
                    it[LeaderLockHistoryTable.status] = HistoryStatus.ACQUIRED.name
                    it[LeaderLockHistoryTable.startedAt] = now
                }[LeaderLockHistoryTable.id]
            }
        }.getOrElse { e ->
            log.warn(e) { "이력 ACQUIRED 기록 실패 (best-effort, 무시): lockName=$lockName" }
            null
        }
    }

    private suspend fun recordCompleted(historyId: Long?, token: String, startedAt: Instant) =
        recordFinished(historyId, token, startedAt, HistoryStatus.COMPLETED)

    private suspend fun recordFailed(historyId: Long?, token: String, startedAt: Instant) =
        recordFinished(historyId, token, startedAt, HistoryStatus.FAILED)

    private suspend fun recordFinished(historyId: Long?, token: String, startedAt: Instant, status: HistoryStatus) {
        if (!options.recordHistory || historyId == null) return
        val finishedAt = Instant.now()
        runCatching {
            suspendTransaction(db) {
                LeaderLockHistoryTable.update(
                    where = { (LeaderLockHistoryTable.id eq historyId) and (LeaderLockHistoryTable.token eq token) }
                ) {
                    it[LeaderLockHistoryTable.status] = status.name
                    it[LeaderLockHistoryTable.finishedAt] = finishedAt
                    it[LeaderLockHistoryTable.durationMs] = finishedAt.toEpochMilli() - startedAt.toEpochMilli()
                }
            }
        }.getOrElse { e ->
            log.warn(e) { "이력 ${status.name} 기록 실패 (best-effort, 무시): historyId=$historyId" }
        }
    }
}
