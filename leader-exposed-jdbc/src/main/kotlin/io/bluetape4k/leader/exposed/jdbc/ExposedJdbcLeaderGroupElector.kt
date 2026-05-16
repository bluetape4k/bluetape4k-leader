package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcBackendErrorClassifier
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcSlotExtendDelegate
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcGroupLock
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.leader.exposed.jdbc.lock.validateExposedLockName
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Multi-leader group election implementation backed by Exposed JDBC.
 *
 * Iterates over slots using the `(lockName, slot)` composite primary key to allow up to
 * [ExposedJdbcLeaderGroupElectionOptions.maxLeaders] simultaneous leaders.
 * The starting slot position is randomized to prevent hotspots.
 *
 * ### Basic usage
 * ```kotlin
 * val election = ExposedJdbcLeaderGroupElector(
 *     db,
 *     ExposedJdbcLeaderGroupElectionOptions(
 *         leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     ),
 * )
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * // up to 3 nodes run concurrently; remaining nodes return null
 * ```
 *
 * ### Group state query
 * ```kotlin
 * val state = election.state("batch-job")
 * println("active=${state.activeCount} / max=${state.maxLeaders}")
 * println("available=${election.availableSlots("batch-job")}")
 * ```
 *
 * **private constructor** — use the [invoke] factory. Schema is automatically created on the first call.
 *
 * @param db Exposed [Database] instance
 * @param options group leader election options
 */
class ExposedJdbcLeaderGroupElector private constructor(
    private val db: Database,
    val options: ExposedJdbcLeaderGroupElectionOptions,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
) : LeaderGroupElector {

    companion object : KLogging() {

        internal const val EXPOSED_JDBC_GROUP_FACTORY_BEAN_NAME = "exposed-jdbc-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedJdbcBackendErrorClassifier)

        /**
         * Creates an [ExposedJdbcLeaderGroupElector] instance.
         *
         * On the first call, automatically creates the leader election table schema (once per database URL).
         */
        @JvmStatic
        @JvmOverloads
        operator fun invoke(
            db: Database,
            options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
            historyRecorder: SafeLeaderHistoryRecorder? = null,
        ): ExposedJdbcLeaderGroupElector {
            ExposedJdbcSchemaInitializer.ensureSchema(db)
            return ExposedJdbcLeaderGroupElector(db, options, historyRecorder)
        }
    }

    /** Maximum number of simultaneous leaders (delegates to [ExposedJdbcLeaderGroupElectionOptions.maxLeaders]). */
    override val maxLeaders: Int get() = options.maxLeaders

    /**
     * Returns the current number of active slots (rows with a non-expired lease) for [lockName] from the DB.
     *
     * Expired slots (`lockedUntil <= NOW()`) are excluded from the count.
     * Call cost: `SELECT COUNT(*)` — consider adding a cache layer for frequent calls.
     *
     * ⚠️ The returned value is a point-in-time snapshot. Another instance may acquire or release
     * a slot immediately after the query, so do not use this for concurrency decisions
     * (monitoring/logging only).
     *
     * Returns `0` on DB error (best-effort; no exception propagation).
     */
    override fun activeCount(lockName: String): Int = runCatching {
        transaction(db) {
            val now = Instant.now()
            LeaderGroupLockTable
                .selectAll()
                .where {
                    (LeaderGroupLockTable.lockName eq lockName) and
                        (LeaderGroupLockTable.lockedUntil greater now)
                }
                .count()
                .toInt()
        }
    }.getOrElse { e ->
        log.warn(e) { "activeCount DB 오류 (0 반환): lockName=$lockName" }
        0
    }

    /** Number of slots immediately available for [lockName]. */
    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    /** Returns a [LeaderGroupState] snapshot for [lockName]. */
    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * Executes [action] when an empty slot in the [lockName] group is acquired.
     *
     * - Returns `null` when all slots are occupied (no exception).
     * - Exceptions from [action] propagate as-is; the slot is always released.
     * - [CancellationException] is rethrown and not recorded as FAILED.
     *
     * ## Skip on slot contention
     * ```kotlin
     * val result = election.runIfLeader("batch-job") { processChunk() }
     * when (result) {
     *     null -> log.debug { "No slot — another instance handles this run" }
     *     else -> log.info { "Processing complete: $result" }
     * }
     * ```
     *
     * ## Slot tryLock tri-state
     * For each slot iterated:
     * - `true` — slot acquired; [action] is executed
     * - `false` — slot contested; iteration continues to the next slot
     * - `null` — DB error; iteration aborted and `null` is returned (distinguishable by warn log)
     *
     * @throws IllegalArgumentException when [lockName] is invalid
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateExposedLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = (options.leaderGroupOptions.waitTime / maxLeaders).coerceAtLeast(1.milliseconds)
        val start = Random.nextInt(maxLeaders)

        log.debug { "그룹 슬롯 획득을 요청합니다. lockName=$lockName, maxLeaders=$maxLeaders" }

        for (i in 0 until maxLeaders) {
            val slot = (start + i) % maxLeaders
            val lock = ExposedJdbcGroupLock(db, lockName, slot, options.retryStrategy, options.lockOwner)

            when (lock.tryLock(perSlotWait, leaseTime)) {
                true -> { /* 획득 성공 — 아래 로직 계속 */ }
                false -> continue
                null -> {
                    log.warn { "DB 오류로 슬롯 순회 중단: lockName=$lockName" }
                    return null
                }
            }

            log.debug { "그룹 슬롯을 획득하여 작업을 수행합니다. lockName=$lockName, slot=$slot" }

            val startedAt = Instant.now()
            val acquiredAtNanos = System.nanoTime()
            val record = historyRecorder?.let {
                LeaderLockHistoryRecord(
                    lockName = lockName,
                    token = lock.token,
                    kind = LockIdentity.AnnotationKind.GROUP,
                    acquiredAt = startedAt,
                    lockedUntil = startedAt.plusMillis(leaseTime.inWholeMilliseconds),
                    nodeId = options.lockOwner,
                    slotId = slot.toString(),
                )
            }
            val hKey = record?.let { historyRecorder.recordAcquired(it) }
            val effectiveKey: LeaderHistoryKey? =
                hKey ?: record?.let { LeaderHistoryKey(lockName = lockName, token = lock.token, slotId = slot.toString()) }

            // T10 PR 5 (Issue #79) — per-slot ExtendDelegate / handle / watchdog 단일 reference 공유 (AC-15).
            val delegate = ExposedJdbcSlotExtendDelegate(lock)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = EXPOSED_JDBC_GROUP_FACTORY_BEAN_NAME,
                groupParams = LockIdentity.GroupParams(maxLeaders),
            )
            val handle = LeaderLockHandle.real(
                identity = identity,
                token = lock.token,
                acquiredAtNanos = acquiredAtNanos,
                slotId = slot.toString(),
                extendDelegate = delegate,
            )
            // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
            val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)

            var actionSucceeded = false
            var capturedError: Throwable? = null

            try {
                val result = AopScopeAccess.withPushedSync(handle) {
                    AopScopeAccess.setCapture(handle)
                    try {
                        action()
                    } finally {
                        AopScopeAccess.clearCapture()
                    }
                }
                actionSucceeded = true
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                capturedError = e
                throw e
            } finally {
                watchdog.close()
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                when {
                    actionSucceeded -> effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                    capturedError != null -> effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, capturedError) }
                }
                runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                    .onSuccess { log.debug { "그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                    .onFailure { e -> log.warn(e) { "그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
            }
        }

        log.debug { "그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
        return null
    }

    /**
     * Acquires a slot in the [lockName] group asynchronously and executes [action].
     *
     * Returns a [CompletableFuture] completing with `null` when no slot can be acquired.
     * Synchronous exceptions from action are wrapped as [CompletableFuture.failedFuture].
     *
     * ```kotlin
     * val future = election.runAsyncIfLeader("batch", VirtualThreadExecutor) {
     *     processChunkAsync()  // CompletableFuture<Result>
     * }
     * ```
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateExposedLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = (options.leaderGroupOptions.waitTime / maxLeaders).coerceAtLeast(1.milliseconds)
        val start = Random.nextInt(maxLeaders)

        return CompletableFuture.supplyAsync({
            var acquired: Pair<ExposedJdbcGroupLock, Int>? = null
            for (i in 0 until maxLeaders) {
                val slot = (start + i) % maxLeaders
                val lock = ExposedJdbcGroupLock(db, lockName, slot, options.retryStrategy, options.lockOwner)
                when (lock.tryLock(perSlotWait, leaseTime)) {
                    true -> { acquired = lock to slot; break }
                    false -> continue
                    null -> {
                        log.warn { "DB 오류로 비동기 슬롯 순회 중단: lockName=$lockName, slot=$slot" }
                        return@supplyAsync null
                    }
                }
            }
            acquired
        }, executor).thenComposeAsync({ acquired ->
            if (acquired == null) {
                log.debug { "그룹 슬롯 획득 실패 (비동기). lockName=$lockName" }
                CompletableFuture.completedFuture(null)
            } else {
                val (lock, slot) = acquired
                log.debug { "그룹 슬롯 비동기 작업 수행. lockName=$lockName, slot=$slot" }

                val startedAt = Instant.now()
                val acquiredAtNanos = System.nanoTime()
                val record = historyRecorder?.let {
                    LeaderLockHistoryRecord(
                        lockName = lockName,
                        token = lock.token,
                        kind = LockIdentity.AnnotationKind.GROUP,
                        acquiredAt = startedAt,
                        lockedUntil = startedAt.plusMillis(leaseTime.inWholeMilliseconds),
                        nodeId = options.lockOwner,
                        slotId = slot.toString(),
                    )
                }
                val hKey = record?.let { historyRecorder.recordAcquired(it) }
                val effectiveKey: LeaderHistoryKey? =
                    hKey ?: record?.let { LeaderHistoryKey(lockName = lockName, token = lock.token, slotId = slot.toString()) }

                // T10 PR 5: async path 도 sync path 와 동일하게 watchdog/delegate 등록 (split-brain 방지)
                // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
                val delegate = ExposedJdbcSlotExtendDelegate(lock)
                val watchdog = LeaderLeaseAutoExtender.start(
                    false,
                    options.leaderGroupOptions.leaseTime,
                    delegate,
                    ERROR_CLASSIFIER,
                )

                val actionFuture = runCatching { action() }
                    .getOrElse { e ->
                        watchdog.close()
                        val finishedAt = Instant.now()
                        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                        effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                        runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                            .onFailure { ex -> log.warn(ex) { "슬롯 해제 실패 (action 오류 경로). slot=$slot" } }
                        return@thenComposeAsync CompletableFuture.failedFuture(e)
                    }

                actionFuture.whenCompleteAsync({ _, throwable ->
                    watchdog.close()
                    val finishedAt = Instant.now()
                    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                    when {
                        throwable == null -> effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                        // 취소(코루틴/CompletableFuture)는 FAILED로 기록하지 않음
                        throwable is java.util.concurrent.CancellationException -> { /* skip */ }
                        throwable is CancellationException -> { /* skip */ }
                        else -> effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, throwable) }
                    }
                    runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                        .onSuccess { log.debug { "비동기 그룹 슬롯 반납. lockName=$lockName, slot=$slot" } }
                        .onFailure { e -> log.warn(e) { "비동기 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
                }, executor)
            }
        }, executor)
    }

}
