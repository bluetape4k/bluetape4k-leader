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
 * // result == processData() 반환값 (리더 획득 성공) 또는 null (획득 실패 / action 예외)
 * ```
 *
 * ## 히스토리 기록
 * ```kotlin
 * val sink = ExposedSuspendLeaderHistorySink(db)
 * val recorder = SuspendSafeLeaderHistoryRecorder(sink)
 * val election = ExposedR2DbcSuspendLeaderElector(db, historyRecorder = recorder)
 * ```
 *
 * ## 취소 안전성
 * `finally` 블록은 항상 `withContext(NonCancellable)`에서 실행되어 락이 보장 해제됩니다.
 *
 * ## Behavior / Contract
 * - Lock 미획득(contention) 시 `null` 반환 — 예외 없음.
 * - Action이 [CancellationException]을 던지면 rethrow.
 * - Action이 다른 [Exception]을 던지면 FAILED 이력 기록 후 예외를 재전파합니다.
 * - [historyRecorder]가 null이면 이력 기록 없이 동작.
 *
 * **private constructor** — [invoke] 팩터리를 통해서만 생성하세요.
 *
 * @param db Exposed [R2dbcDatabase] 인스턴스
 * @param options 단일 리더 선출 옵션
 * @param historyRecorder 선택적 이력 기록기; null이면 이력 기록 안 함
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
         * [ExposedR2DbcSuspendLeaderElector] 인스턴스를 생성합니다.
         *
         * 첫 호출 시 리더 선출 테이블 스키마를 자동으로 생성합니다 (최초 1회).
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
     * [lockName]에 대해 리더로 승격되면 suspend [action]을 실행하고 결과를 반환합니다.
     *
     * - 리더 획득에 성공하면 [action] 결과를 반환합니다.
     * - 리더 획득에 실패하면 `null`을 반환합니다 (예외 없음).
     * - [action]이 [CancellationException]을 던지면 rethrow합니다.
     * - [action]이 다른 예외를 던지면 FAILED 이력 기록 후 예외를 재전파합니다.
     * - 락은 정상/예외 어느 경로에서도 항상 해제됩니다.
     *
     * @param lockName 락 식별자 (영숫자/하이픈/언더스코어/콜론, 1-255자)
     * @param action 리더 획득 성공 시 실행할 suspend 작업
     * @return [action] 결과 또는 `null`
     * @throws IllegalArgumentException [lockName]이 유효하지 않은 경우
     * @throws CancellationException action이 CancellationException을 던진 경우
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

        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()

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

        try {
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
                watchdog.close()
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
