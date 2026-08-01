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
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcUnlockOutcome
import io.bluetape4k.leader.exposed.r2dbc.lock.validateExposedR2dbcLockName
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
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
import kotlin.time.Duration.Companion.milliseconds
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * `ExposedR2DbcSuspendLeaderGroupElector`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property db Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property historyRecorder Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
// The suspend group elector intentionally implements the complete group-election contract,
// including blocking state queries, coroutine execution, history, and lease lifecycle hooks.
@Suppress("TooManyFunctions")
class ExposedR2DbcSuspendLeaderGroupElector private constructor(
    private val db: R2dbcDatabase,
    val options: ExposedR2dbcLeaderGroupElectionOptions,
    /**
     * `historyRecorder` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    @Suppress("unused")
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
) : SuspendLeaderGroupElector {

    companion object : KLoggingChannel() {

        internal const val EXPOSED_R2DBC_SUSPEND_GROUP_FACTORY_BEAN_NAME = "exposed-r2dbc-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedR2dbcBackendErrorClassifier)

        /**
         * `invoke` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
     * `maxLeaders` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
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
        }!!

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
     * `activeCount` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun activeCount(lockName: String): Int = cachedActiveCounts[lockName]?.value?.get() ?: 0

    /**
     * `availableSlots` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    /**
     * `state` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * `activeCountSuspend` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun activeCountSuspend(lockName: String): Int {
        validateExposedR2dbcLockName(lockName)
        val snapshot = cacheSnapshot(lockName)
        val refreshedCount = try {
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
            log.warn(e) { "activeCount DB 조회 오류 (기존 캐시 유지): lockName=$lockName" }
            return cachedActiveCounts[lockName]?.value?.get() ?: 0
        }
        return applyActiveCountRefresh(lockName, snapshot, refreshedCount)
    }

    /**
     * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
            val cachedActiveCount = incrementCachedActiveCount(lockName)

            val historyId = recordAcquired(lockName, lock.token, slot)
            val startedAt = Instant.now()
            val acquiredAtNanos = System.nanoTime()

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
                    when {
                        actionSucceeded -> recordCompleted(historyId, lock.token, startedAt, slot)
                        actionFailed -> recordFailed(historyId, lock.token, startedAt, slot)
                    }
                    try {
                        when (lock.unlockAndReport(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)) {
                            ExposedR2dbcUnlockOutcome.RELEASED,
                            ExposedR2dbcUnlockOutcome.NOT_HELD -> {
                                decrementCachedActiveCount(lockName, cachedActiveCount)
                                log.debug { "그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" }
                            }

                            ExposedR2dbcUnlockOutcome.FAILED ->
                                log.warn { "DB 해제 실패로 그룹 슬롯 캐시를 유지합니다. lockName=$lockName, slot=$slot" }
                        }
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
