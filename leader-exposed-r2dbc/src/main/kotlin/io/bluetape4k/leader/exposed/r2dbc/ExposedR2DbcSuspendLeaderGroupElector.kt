package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.exposed.r2dbc.internal.ExposedR2dbcBackendErrorClassifier
import io.bluetape4k.leader.exposed.r2dbc.internal.ExposedR2dbcSuspendSlotExtendDelegate
import io.bluetape4k.leader.exposed.r2dbc.internal.validateExposedR2dbcLockName
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcGroupLock
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcSchemaInitializer
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcUnlockOutcome
import io.bluetape4k.leader.exposed.r2dbc.lock.currentTime
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * `ExposedR2DbcSuspendLeaderGroupElector`는 R2DBC slot 행을 이용해 여러 leader의 suspend 실행과 slot lease를 관리합니다.
 *
 * @property db 그룹 슬롯을 조회·변경하고 suspend transaction을 실행할 Exposed R2DBC 데이터베이스입니다.
 * @property options maxLeaders, 대기·lease 시간, 재시도, owner, DB 시간 사용 여부를 담은 그룹 설정입니다.
 * @property historyRecorder null이면 그룹 history를 기록하지 않고, 값이 있으면 suspend-safe recorder로 기록합니다.
 */
// The suspend group elector intentionally implements the complete group-election contract,
// including blocking state queries, coroutine execution, history, and lease lifecycle hooks.
@Suppress("TooManyFunctions")
class ExposedR2DbcSuspendLeaderGroupElector private constructor(
    private val db: R2dbcDatabase,
    val options: ExposedR2dbcLeaderGroupElectionOptions,
    /** 그룹 슬롯의 획득·완료·실패 이력을 기록하며 null이면 기록을 생략합니다. */
    @Suppress("unused")
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
): SuspendLeaderGroupElector,
   LeaderBackendDiagnosticsProvider by ExposedR2dbcLeaderBackendDiagnostics {

    companion object: KLoggingChannel() {

        internal const val EXPOSED_R2DBC_SUSPEND_GROUP_FACTORY_BEAN_NAME = "exposed-r2dbc-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedR2dbcBackendErrorClassifier)

        /**
         * 데이터베이스 스키마를 한 번 보장한 뒤 suspend group elector를 생성합니다.
         * [options]의 [ExposedR2dbcLeaderGroupElectionOptions.maxLeaders] 슬롯 범위에서 선거합니다.
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

    /**
     * lock name별로 동시에 보유할 수 있는 활성 슬롯의 상한입니다. 생성 시 1 이상으로 검증됩니다.
     */
    override val maxLeaders: Int get() = options.maxLeaders

    /**
     * `cachedActiveCounts` 값은 lock 이름별 활성 슬롯 수를 보관합니다.
     */
    private class CachedActiveCount(initialValue: Int = 0) {
        val value = AtomicInteger(initialValue)
        val generation = AtomicLong(if (initialValue == 0) 0 else 1)
    }

    private data class CacheSnapshot(
        val entry: CachedActiveCount?,
        val generation: Long,
    )

    private val cachedActiveCounts = ConcurrentHashMap<String, CachedActiveCount>()
    private val availabilityEpoch = AtomicLong()
    private val unavailableLockEpochs = ConcurrentHashMap<String, Long>()

    private fun availabilitySnapshot(): Long = availabilityEpoch.get()

    private fun markUnavailable(lockName: String) {
        unavailableLockEpochs.compute(lockName) { _, current ->
            maxOf(current ?: 0L, availabilityEpoch.incrementAndGet())
        }
    }

    private fun markAvailable(lockName: String, startedAtEpoch: Long): Boolean {
        var available = true
        unavailableLockEpochs.compute(lockName) { _, failureEpoch ->
            failureEpoch?.takeIf { it > startedAtEpoch }?.also { available = false }
        }
        return available
    }

    /**
     * Local acquisitions and releases are serialized through the map entry.  The generation lets a
     * database refresh detect a local change that happened while its suspended query was in flight.
     */
    private fun incrementCachedActiveCount(lockName: String): CachedActiveCount =
        cachedActiveCounts.compute(lockName) { _, current ->
            val entry = current ?: CachedActiveCount()
            entry.value.incrementAndGet()
            entry.generation.incrementAndGet()
            entry
        } ?: error("active-count cache update returned no entry: lockName=$lockName")

    private fun decrementCachedActiveCount(lockName: String, expected: CachedActiveCount) {
        cachedActiveCounts.computeIfPresent(lockName) { _, current ->
            if (current !== expected) {
                current
            } else {
                val remaining = current.value.updateAndGet { it.coerceAtLeast(1) - 1 }
                current.generation.incrementAndGet()
                remaining.takeIf { it > 0 }?.let { current }
            }
        }
    }

    private fun cacheSnapshot(lockName: String): CacheSnapshot {
        val entry = cachedActiveCounts[lockName]
        return CacheSnapshot(entry, entry?.generation?.get() ?: 0L)
    }

    /**
     * Applies only a refresh based on the snapshot that started the query.  A concurrent local
     * acquisition/release wins over a stale database result; a zero result never creates a cache
     * entry and removes only the unchanged entry it refreshed.
     */
    private fun applyActiveCountRefresh(
        lockName: String,
        snapshot: CacheSnapshot,
        refreshedCount: Int,
    ): Int = cachedActiveCounts.compute(lockName) { _, current ->
        when {
            snapshot.entry == null -> when {
                current == null && refreshedCount > 0 -> CachedActiveCount(refreshedCount)
                else -> current
            }

            current !== snapshot.entry -> current
            current.generation.get() != snapshot.generation -> current
            refreshedCount > 0 -> {
                current.value.set(refreshedCount)
                current.generation.incrementAndGet()
                current
            }

            else -> null
        }
    }?.value?.get() ?: 0

    /**
     * 마지막 DB 조회와 로컬 캐시를 기준으로 [lockName]의 활성 슬롯 수를 반환합니다.
     * DB time 조회 실패가 남아 있는 동안에는 fail-closed로 [maxLeaders]를 반환합니다.
     */
    override fun activeCount(lockName: String): Int =
        if (unavailableLockEpochs.containsKey(lockName)) maxLeaders else cachedActiveCounts[lockName]?.value?.get() ?: 0

    /**
     * [lockName]에서 사용할 수 있는 슬롯 수를 `maxLeaders - activeCount(lockName)`으로 계산합니다.
     */
    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    /**
     * [lockName]의 최대 슬롯 수와 활성 슬롯 수를 [LeaderGroupState]로 묶어 반환합니다.
     */
    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * DB에서 [lockName]의 만료되지 않은 슬롯 수를 suspend transaction으로 새로 조회합니다.
     * DB time 모드에서 조회가 실패하면 [maxLeaders]를 반환하고, 조회 중 발생한 더 최신 오류는
     * 오래된 성공 결과로 해제하지 않습니다.
     */
    @Suppress("ReturnCount")
    suspend fun activeCountSuspend(lockName: String): Int {
        lockName.validateExposedR2dbcLockName()
        val snapshot = cacheSnapshot(lockName)
        val startedAtAvailabilityEpoch = availabilitySnapshot()
        val refreshedCount = try {
            suspendTransaction(db) {
                val now = currentTime(options.leaderGroupOptions.useDbTime)
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
            if (options.leaderGroupOptions.useDbTime) {
                markUnavailable(lockName)
                log.warn(e) { "activeCount DB 조회 오류 (fail-closed: $maxLeaders 반환): lockName=$lockName" }
                return maxLeaders
            }
            log.warn(e) { "activeCount DB 조회 오류 (기존 캐시 유지): lockName=$lockName" }
            return cachedActiveCounts[lockName]?.value?.get() ?: 0
        }
        if (!markAvailable(lockName, startedAtAvailabilityEpoch)) return maxLeaders
        return applyActiveCountRefresh(lockName, snapshot, refreshedCount)
    }

    /**
     * [lockName]의 슬롯을 순회하며 하나를 대기 시간 안에 suspend 방식으로 획득합니다.
     * 획득하지 못하거나 DB 오류가 있으면 null을 반환하고, action 종료·취소 뒤 슬롯과 캐시를 정리합니다.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        lockName.validateExposedR2dbcLockName()

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = (options.leaderGroupOptions.waitTime / maxLeaders).coerceAtLeast(1.milliseconds)
        val start = Random.nextInt(maxLeaders)

        log.debug { "그룹 슬롯 획득을 요청합니다. lockName=$lockName, maxLeaders=$maxLeaders" }

        for (i in 0 until maxLeaders) {
            val slot = (start + i) % maxLeaders
            val startedAtAvailabilityEpoch = availabilitySnapshot()
            val lock = ExposedR2dbcGroupLock(
                db,
                lockName,
                slot,
                options.retryStrategy,
                options.lockOwner,
                options.leaderGroupOptions.useDbTime,
                onAvailabilityChanged = { available ->
                    if (available) {
                        markAvailable(lockName, startedAtAvailabilityEpoch)
                    } else {
                        markUnavailable(lockName)
                    }
                },
            )

            when (lock.tryLock(perSlotWait, leaseTime)) {
                true -> { /* 획득 성공 — 아래 로직 계속 */
                }
                false -> continue
                null -> {
                    log.warn { "DB 오류로 슬롯 순회 중단: lockName=$lockName" }
                    return null
                }
            }

            log.debug { "그룹 슬롯을 획득하여 작업을 수행합니다. lockName=$lockName, slot=$slot" }
            val acquiredAtNanos = System.nanoTime()
            val startedAt = Instant.now()
            var cachedActiveCount: CachedActiveCount? = null
            var historyId: Long? = null
            var watchdog: AutoCloseable? = null
            var actionSucceeded = false
            var actionFailed = false

            try {
                // The cleanup guard starts immediately after acquisition so setup cancellation
                // cannot leak the database row or the local active-count cache.
                cachedActiveCount = incrementCachedActiveCount(lockName)
                historyId = recordAcquired(lockName, lock.token, slot)

                // T11 PR 6 (Issue #79) — per-slot ExtendDelegate / handle / watchdog 단일 reference 공유 (AC-15).
                val delegate: SuspendExtendDelegate = ExposedR2dbcSuspendSlotExtendDelegate(lock)
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
                watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)

                val result = withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
                actionSucceeded = true
                return result
            } catch (e: CancellationException) {
                actionFailed = true
                throw e
            } catch (e: Throwable) {
                actionFailed = true
                throw e
            } finally {
                // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
                withContext(NonCancellable) {
                    try {
                        try {
                            watchdog?.let { LeaderLeaseAutoExtender.closeSuspend(it) }
                        } catch (e: Exception) {
                            log.warn(e) { "그룹 슬롯 watchdog 종료 실패. lockName=$lockName, slot=$slot" }
                        }
                        try {
                            when {
                                actionSucceeded -> recordCompleted(historyId, lock.token, startedAt, slot)
                                actionFailed -> recordFailed(historyId, lock.token, startedAt, slot)
                            }
                        } catch (e: CancellationException) {
                            // History is best-effort; cancellation must not bypass unlock.
                            log.warn(e) { "그룹 슬롯 이력 종료 기록이 취소되었습니다. lockName=$lockName, slot=$slot" }
                        }
                    } finally {
                        try {
                            when (lock.unlockAndReport(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)) {
                                ExposedR2dbcUnlockOutcome.RELEASED,
                                ExposedR2dbcUnlockOutcome.NOT_HELD,
                                    -> {
                                    cachedActiveCount?.let { decrementCachedActiveCount(lockName, it) }
                                    log.debug { "그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" }
                                }

                                ExposedR2dbcUnlockOutcome.FAILED ->
                                    log.warn { "DB 해제 실패로 그룹 슬롯 캐시를 유지합니다. lockName=$lockName, slot=$slot" }
                            }
                        } catch (e: CancellationException) {
                            log.warn(e) { "그룹 슬롯 해제가 취소되었습니다. lockName=$lockName, slot=$slot" }
                        } catch (e: Exception) {
                            log.warn(e) { "그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" }
                        }
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
                    it[LeaderLockHistoryTable.status] = LeaderHistoryStatus.ACQUIRED
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
                    it[LeaderLockHistoryTable.status] = status
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
