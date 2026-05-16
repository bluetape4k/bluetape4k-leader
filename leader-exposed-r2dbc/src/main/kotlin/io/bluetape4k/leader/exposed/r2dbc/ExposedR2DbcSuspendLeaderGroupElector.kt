package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.exposed.r2dbc.internal.ExposedR2dbcBackendErrorClassifier
import io.bluetape4k.leader.exposed.r2dbc.internal.ExposedR2dbcSuspendSlotExtendDelegate
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcGroupLock
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcSchemaInitializer
import io.bluetape4k.leader.exposed.r2dbc.lock.validateExposedR2dbcLockName
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Duration.Companion.milliseconds
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Coroutine-based multi-leader group election implementation backed by Exposed R2DBC.
 *
 * Allows up to [ExposedR2dbcLeaderGroupElectionOptions.maxLeaders] simultaneous leaders
 * by traversing slots using a `(lockName, slot)` composite PK.
 * The starting slot is randomized to avoid hotspots.
 *
 * ## Basic usage
 * ```kotlin
 * val election = ExposedR2dbcSuspendLeaderGroupElector(
 *     db,
 *     ExposedR2dbcLeaderGroupElectionOptions(
 *         leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     ),
 * )
 * val result = election.runIfLeader("batch-job") {
 *     delay(100)
 *     processChunk()
 * }
 * // Up to 3 nodes run concurrently; others return null
 * ```
 *
 * ## State queries
 * - [activeCount] / [availableSlots] / [state] — cache-based approximate values, auto-refreshed after `runIfLeader`.
 *   Changes from other JVM instances are not reflected — use for monitoring/logging only.
 * - [activeCountSuspend] — real-time DB query via SELECT. Use when an accurate count is needed
 *   in a multi-JVM environment. Has query cost (SELECT COUNT(*)); must not be used as a concurrency decision gate.
 *
 * **private constructor** — use the [invoke] factory. The schema is automatically created on the first call.
 *
 * @param db Exposed [R2dbcDatabase] instance
 * @param options group leader election options
 */
class ExposedR2DbcSuspendLeaderGroupElector private constructor(
    private val db: R2dbcDatabase,
    val options: ExposedR2dbcLeaderGroupElectionOptions,
    /**
     * Optional history recorder for group elections.
     * Group elector full wiring is deferred to v2 (#50 follow-up).
     */
    @Suppress("unused")
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
) : SuspendLeaderGroupElector {

    companion object : KLoggingChannel() {

        internal const val EXPOSED_R2DBC_SUSPEND_GROUP_FACTORY_BEAN_NAME = "exposed-r2dbc-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedR2dbcBackendErrorClassifier)

        /**
         * Creates an [ExposedR2DbcSuspendLeaderGroupElector] instance.
         *
         * Automatically creates the leader election table schema on the first call (once only).
         */
        suspend operator fun invoke(
            db: R2dbcDatabase,
            options: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
            historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
        ): ExposedR2DbcSuspendLeaderGroupElector {
            ExposedR2dbcSchemaInitializer.ensureSchema(db)
            return ExposedR2DbcSuspendLeaderGroupElector(db, options, historyRecorder)
        }
    }

    /** Number of simultaneous leaders allowed (delegates to [ExposedR2dbcLeaderGroupElectionOptions.maxLeaders]). */
    override val maxLeaders: Int get() = options.maxLeaders

    /**
     * Last observed active slot count. Starts at 0 and is updated after `runIfLeader`.
     *
     * [activeCount] / [availableSlots] / [state] return values from this cache.
     * Use the suspend version [activeCountSuspend] when a direct DB query is needed.
     */
    private val cachedActiveCount = AtomicInteger(0)

    /**
     * Current active slot count for [lockName] (cache-based, approximate).
     *
     * External changes after the last [runIfLeader] call may not be reflected.
     */
    override fun activeCount(lockName: String): Int = cachedActiveCount.get()

    /** Number of immediately acquirable slots for [lockName] (cache-based). */
    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    /** Returns a [LeaderGroupState] snapshot for [lockName] (cache-based). */
    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * Queries the current active slot count for [lockName] directly from the DB in real time.
     *
     * Unlike [activeCount], this executes `SELECT COUNT(*)` directly against the DB without using the cache.
     * Expired slots (`lockedUntil <= NOW()`) are excluded from the count.
     *
     * The internal cache ([activeCount]) is also synchronized after the query.
     *
     * ```kotlin
     * // Check the accurate slot count in a multi-JVM environment
     * val realCount = election.activeCountSuspend("batch-job")
     * println("active=$realCount / max=${election.maxLeaders}")
     * // ⚠️ Another instance may acquire/release a slot immediately after — do not use as a concurrency decision gate
     * ```
     *
     * Returns `0` on a best-effort basis when a DB error occurs.
     *
     * @throws IllegalArgumentException if [lockName] is invalid
     */
    suspend fun activeCountSuspend(lockName: String): Int {
        validateExposedR2dbcLockName(lockName)
        return try {
            suspendTransaction(db) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "activeCount DB 조회 오류 (0 반환): lockName=$lockName" }
            0
        }.also { cachedActiveCount.set(it) }
    }

    /**
     * Acquires an empty slot in the [lockName] group and runs the suspend [action].
     *
     * - Returns `null` without throwing if all slots are in use.
     * - Exceptions from [action] propagate as-is; the slot is always released.
     * - [CancellationException] is re-propagated and is not recorded as a FAILED history entry.
     *
     * ## Skip on slot contention
     * ```kotlin
     * val result = election.runIfLeader("batch-job") { processChunk() }
     * when (result) {
     *     null -> log.debug { "No slot — this execution handled by another instance" }
     *     else -> log.info { "Completed: $result" }
     * }
     * ```
     *
     * ## DB error vs normal contention
     * If a DB error occurs during slot traversal, traversal stops and `null` is returned.
     * (Same return value as normal contention, but distinguishable via warn log.)
     *
     * @throws IllegalArgumentException if [lockName] is invalid
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        validateExposedR2dbcLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = (options.leaderGroupOptions.waitTime / maxLeaders).coerceAtLeast(1.milliseconds)
        val start = Random.nextInt(maxLeaders)

        log.debug { "그룹 슬롯 획득을 요청합니다. lockName=$lockName, maxLeaders=$maxLeaders" }

        for (i in 0 until maxLeaders) {
            val slot = (start + i) % maxLeaders
            val lock = ExposedR2dbcGroupLock(db, lockName, slot, options.retryStrategy, options.lockOwner)

            when (lock.tryLock(perSlotWait, leaseTime)) {
                true -> { /* 획득 성공 — 아래 로직 계속 */ }
                false -> continue
                null -> {
                    log.warn { "DB 오류로 슬롯 순회 중단: lockName=$lockName" }
                    return null
                }
            }

            log.debug { "그룹 슬롯을 획득하여 작업을 수행합니다. lockName=$lockName, slot=$slot" }
            cachedActiveCount.incrementAndGet()

            val historyId = recordAcquired(lockName, lock.token, slot)
            val startedAt = Instant.now()
            val acquiredAtNanos = System.nanoTime()

            // T11 PR 6 (Issue #79) — per-slot ExtendDelegate / handle / watchdog 단일 reference 공유 (AC-15).
            val delegate = ExposedR2dbcSuspendSlotExtendDelegate(lock)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = EXPOSED_R2DBC_SUSPEND_GROUP_FACTORY_BEAN_NAME,
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
            var actionFailed = false

            try {
                val result = withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
                actionSucceeded = true
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                actionFailed = true
                throw e
            } finally {
                // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
                withContext(NonCancellable) {
                    watchdog.close()
                    cachedActiveCount.updateAndGet { it.coerceAtLeast(1) - 1 }
                    when {
                        actionSucceeded -> recordCompleted(historyId, lock.token, startedAt, slot)
                        actionFailed -> recordFailed(historyId, lock.token, startedAt, slot)
                    }
                    try {
                        lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)
                        log.debug { "그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" }
                    }
                }
            }
        }

        log.debug { "그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
        return null
    }

    private suspend fun recordAcquired(lockName: String, token: String, slot: Int): Long? {
        if (!options.recordHistory) return null
        val lockOwner = options.lockOwner
        val leaseTimeMs = options.leaderGroupOptions.leaseTime.inWholeMilliseconds
        return try {
            suspendTransaction(db) {
                val now = Instant.now()
                LeaderLockHistoryTable.insert {
                    it[LeaderLockHistoryTable.lockName] = lockName
                    it[LeaderLockHistoryTable.lockOwner] = lockOwner
                    it[LeaderLockHistoryTable.token] = token
                    it[LeaderLockHistoryTable.slot] = slot
                    it[LeaderLockHistoryTable.lockedUntil] = now.plusMillis(leaseTimeMs)
                    it[LeaderLockHistoryTable.status] = LeaderHistoryStatus.ACQUIRED.name
                    it[LeaderLockHistoryTable.startedAt] = now
                }[LeaderLockHistoryTable.id]
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "이력 ACQUIRED 기록 실패 (best-effort, 무시): lockName=$lockName, slot=$slot" }
            null
        }
    }

    private suspend fun recordCompleted(historyId: Long?, token: String, startedAt: Instant, slot: Int) =
        recordFinished(historyId, token, startedAt, slot, LeaderHistoryStatus.COMPLETED)

    private suspend fun recordFailed(historyId: Long?, token: String, startedAt: Instant, slot: Int) =
        recordFinished(historyId, token, startedAt, slot, LeaderHistoryStatus.FAILED)

    private suspend fun recordFinished(
        historyId: Long?,
        token: String,
        startedAt: Instant,
        slot: Int,
        status: LeaderHistoryStatus,
    ) {
        if (!options.recordHistory || historyId == null) return
        val finishedAt = Instant.now()
        try {
            suspendTransaction(db) {
                LeaderLockHistoryTable.update(
                    where = { (LeaderLockHistoryTable.id eq historyId) and (LeaderLockHistoryTable.token eq token) }
                ) {
                    it[LeaderLockHistoryTable.status] = status.name
                    it[LeaderLockHistoryTable.finishedAt] = finishedAt
                    it[LeaderLockHistoryTable.durationMs] = finishedAt.toEpochMilli() - startedAt.toEpochMilli()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "이력 ${status.name} 기록 실패 (best-effort): historyId=$historyId, slot=$slot" }
        }
    }
}
