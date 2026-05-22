package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcBackendErrorClassifier
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcLockExtendDelegate
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.leader.exposed.jdbc.lock.validateExposedLockName
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Single leader election implementation backed by Exposed JDBC.
 *
 * Implements row-level locking via the `UPDATE + INSERT + SELECT` pattern.
 * See the module README compatibility matrix for supported databases.
 *
 * ### Basic usage
 * ```kotlin
 * val election = ExposedJdbcLeaderElector(db)
 * val result = election.runIfLeader("daily-job") { processData() }
 * // result == processData() return value (lock acquired) or null (lock not acquired / action threw)
 * ```
 *
 * ### History recording
 * ```kotlin
 * val sink = ExposedLeaderHistorySink(db)
 * val recorder = SafeLeaderHistoryRecorder(sink)
 * val election = ExposedJdbcLeaderElector(db, historyRecorder = recorder)
 * ```
 *
 * ### Skip on contention
 * ```kotlin
 * val report = election.runIfLeader("nightly-report") { generateReport() }
 *     ?: run { logger.info("Not the leader — skipping"); return }
 * ```
 *
 * ## Behavior / Contract
 * - Returns `null` when the lock cannot be acquired (contention) — never throws.
 * - Rethrows [CancellationException] or [InterruptedException] thrown by action.
 * - Records FAILED history and rethrows any other [Exception] thrown by action.
 * - When [historyRecorder] is null, operates without recording history.
 *
 * **private constructor** — create instances only through the [invoke] factory.
 *
 * @param db Exposed [Database] instance
 * @param options single leader election options
 * @param historyRecorder optional history recorder; no history is recorded when null
 */
class ExposedJdbcLeaderElector private constructor(
    private val db: Database,
    val options: ExposedJdbcLeaderElectionOptions,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
) : LeaderElector {

    companion object : KLogging() {

        internal const val EXPOSED_JDBC_FACTORY_BEAN_NAME = "exposed-jdbc-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedJdbcBackendErrorClassifier)

        /**
         * Creates an [ExposedJdbcLeaderElector] instance.
         *
         * On the first call, automatically creates the leader election table schema (once per database URL).
         */
        @JvmStatic
        @JvmOverloads
        operator fun invoke(
            db: Database,
            options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
            historyRecorder: SafeLeaderHistoryRecorder? = null,
        ): ExposedJdbcLeaderElector {
            ExposedJdbcSchemaInitializer.ensureSchema(db)
            return ExposedJdbcLeaderElector(db, options, historyRecorder)
        }
    }

    /**
     * Executes [action] and returns its result when this node is elected leader for [lockName].
     *
     * - Returns the [action] result when the lock is acquired successfully.
     * - Returns `null` when the lock cannot be acquired (no exception — ShedLock-compatible skip-on-contention contract).
     * - Records FAILED history and rethrows any exception thrown by [action].
     * - The lock is always released regardless of whether the action succeeds or throws.
     *
     * @param lockName lock identifier (alphanumeric/hyphen/underscore/colon, 1–255 characters)
     * @param action the work to execute when the leader lock is acquired
     * @return [action] result, or `null` when the lock cannot be acquired
     * @throws IllegalArgumentException when [lockName] is invalid
     * @throws Exception exception thrown by [action] (recorded as FAILED and rethrown)
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateExposedLockName(lockName)

        val lock = ExposedJdbcLock(
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

        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()

        val delegate = ExposedJdbcLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = EXPOSED_JDBC_FACTORY_BEAN_NAME,
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
                val result = AopScopeAccess.withPushedSync(handle) { action() }
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                throw e
            }
        } finally {
            watchdog.close()
            try {
                lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos)
                log.debug { "리더 권한을 반납했습니다. lockName=$lockName" }
            } catch (e: Exception) {
                log.warn(e) { "락 해제 실패. lockName=$lockName" }
            }
        }
    }

    /**
     * Executes [action] asynchronously when this node is elected leader for [lockName].
     *
     * @param lockName lock identifier
     * @param executor async executor
     * @param action the async work to execute when the leader lock is acquired
     * @return [CompletableFuture] holding the action result, or completing with `null` on lock failure or action exception
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateExposedLockName(lockName)

        val lock = ExposedJdbcLock(
            db = db,
            lockName = lockName,
            retryStrategy = options.retryStrategy,
            lockOwner = options.lockOwner,
            useDbTime = options.leaderOptions.useDbTime,
        )

        return CompletableFuture
            .supplyAsync({ lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime) }, executor)
            .thenComposeAsync({ acquired ->
                if (!acquired) {
                    log.debug { "리더 승격 실패 (비동기). lockName=$lockName" }
                    CompletableFuture.completedFuture(null)
                } else {
                    log.debug { "리더로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
                    val startedAt = Instant.now()
                    val acquiredAtNanos = System.nanoTime()
                    val delegate = ExposedJdbcLockExtendDelegate(lock)
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

                    val actionFuture = runCatching { action() }
                        .getOrElse { e ->
                            watchdog.close()
                            val finishedAt = Instant.now()
                            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                            effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                            runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                                .onFailure { ex -> log.warn(ex) { "락 해제 실패 (action 오류 경로). lockName=$lockName" } }
                            return@thenComposeAsync CompletableFuture.completedFuture(null)
                        }

                    actionFuture.whenCompleteAsync({ _, throwable ->
                        watchdog.close()
                        val finishedAt = Instant.now()
                        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                        when {
                            throwable == null -> effectiveKey?.let {
                                historyRecorder?.recordCompleted(it, finishedAt, durationMs)
                            }
                            throwable is java.util.concurrent.CancellationException -> { /* cancelled — no audit */ }
                            throwable is CancellationException -> { /* cancelled — no audit */ }
                            else -> effectiveKey?.let {
                                historyRecorder?.recordFailed(it, finishedAt, durationMs, throwable)
                            }
                        }
                        runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                            .onSuccess { log.debug { "비동기 리더 권한을 반납했습니다. lockName=$lockName" } }
                            .onFailure { e -> log.warn(e) { "비동기 락 해제 실패. lockName=$lockName" } }
                    }, executor)
                }
            }, executor)
    }
}
