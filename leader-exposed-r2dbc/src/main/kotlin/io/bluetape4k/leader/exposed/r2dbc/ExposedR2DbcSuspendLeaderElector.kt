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
 * Coroutine-based single-leader election implementation backed by Exposed R2DBC.
 *
 * Implements row-level database locking using the `UPDATE + insertIgnore + SELECT` pattern.
 *
 * ## Basic usage
 * ```kotlin
 * val election = ExposedR2dbcSuspendLeaderElector(db)
 * val result = election.runIfLeader("daily-job") {
 *     delay(100)
 *     processData()
 * }
 * // result == processData() return value (leader acquired) or null (acquisition failed / action threw)
 * ```
 *
 * ## History recording
 * ```kotlin
 * val sink = ExposedSuspendLeaderHistorySink(db)
 * val recorder = SuspendSafeLeaderHistoryRecorder(sink)
 * val election = ExposedR2DbcSuspendLeaderElector(db, historyRecorder = recorder)
 * ```
 *
 * ## Cancellation safety
 * The `finally` block always runs inside `withContext(NonCancellable)` to guarantee lock release.
 *
 * ## Behavior / Contract
 * - Returns `null` when the lock cannot be acquired (contention) — no exception is thrown.
 * - Rethrows [CancellationException] if the action throws it.
 * - If the action throws any other [Exception], records a FAILED history entry and rethrows the exception.
 * - If [historyRecorder] is null, operates without recording history.
 *
 * **private constructor** — create instances only through the [invoke] factory.
 *
 * @param db Exposed [R2dbcDatabase] instance
 * @param options Single-leader election options
 * @param historyRecorder Optional history recorder; no history is recorded when null
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
         * Creates an [ExposedR2DbcSuspendLeaderElector] instance.
         *
         * Automatically creates the leader election table schema on the first call (once only).
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
     * Executes the suspend [action] and returns its result when promoted to leader for [lockName].
     *
     * - Returns the [action] result on successful leader acquisition.
     * - Returns `null` when leader acquisition fails (no exception thrown).
     * - Rethrows [CancellationException] if [action] throws it.
     * - If [action] throws any other exception, records a FAILED history entry and rethrows the exception.
     * - The lock is always released regardless of whether execution completes normally or with an exception.
     *
     * @param lockName Lock identifier (alphanumeric/hyphen/underscore/colon, 1–255 characters)
     * @param action Suspend action to execute upon successful leader acquisition
     * @return [action] result or `null`
     * @throws IllegalArgumentException if [lockName] is invalid
     * @throws CancellationException if the action throws CancellationException
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
