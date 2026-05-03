package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElection
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcGroupLock
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcSchemaInitializer
import io.bluetape4k.leader.exposed.r2dbc.lock.validateExposedR2dbcLockName
import io.bluetape4k.leader.exposed.tables.HistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Exposed R2DBC 기반 코루틴 복수 리더 그룹 선출 구현체.
 *
 * `(lockName, slot)` 복합 PK 기반 슬롯 순회로 최대
 * [ExposedR2dbcLeaderGroupElectionOptions.maxLeaders]개의 동시 리더를 허용합니다.
 * 슬롯 시작 위치를 랜덤화하여 핫스팟을 방지합니다.
 *
 * ## 기본 사용
 * ```kotlin
 * val election = ExposedR2dbcSuspendLeaderGroupElection(
 *     db,
 *     ExposedR2dbcLeaderGroupElectionOptions(
 *         leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     ),
 * )
 * val result = election.runIfLeader("batch-job") {
 *     delay(100)
 *     processChunk()
 * }
 * // 최대 3개 노드 동시 실행, 나머지는 null 반환
 * ```
 *
 * ## 상태 조회
 * [activeCount], [availableSlots], [state]는 캐시 기반 근사값을 반환합니다.
 * `runIfLeader` 호출 이후 외부에서 변경된 상태는 반영되지 않을 수 있습니다.
 *
 * **private constructor** — [invoke] 팩터리를 사용하세요. 첫 호출 시 스키마가 자동으로 생성됩니다.
 *
 * @param db Exposed [R2dbcDatabase] 인스턴스
 * @param options 그룹 리더 선출 옵션
 */
class ExposedR2dbcSuspendLeaderGroupElection private constructor(
    private val db: R2dbcDatabase,
    val options: ExposedR2dbcLeaderGroupElectionOptions,
) : SuspendLeaderGroupElection {

    companion object : KLoggingChannel() {

        /**
         * [ExposedR2dbcSuspendLeaderGroupElection] 인스턴스를 생성합니다.
         *
         * 첫 호출 시 리더 선출 테이블 스키마를 자동으로 생성합니다 (최초 1회).
         */
        suspend operator fun invoke(
            db: R2dbcDatabase,
            options: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
        ): ExposedR2dbcSuspendLeaderGroupElection {
            ExposedR2dbcSchemaInitializer.ensureSchema(db)
            return ExposedR2dbcSuspendLeaderGroupElection(db, options)
        }
    }

    /** 동시 허용 리더 수 ([ExposedR2dbcLeaderGroupElectionOptions.maxLeaders] 위임). */
    override val maxLeaders: Int get() = options.maxLeaders

    /**
     * 마지막으로 관측된 활성 슬롯 수. 초기값 0, `runIfLeader` 이후 갱신.
     *
     * [activeCount] / [availableSlots] / [state]는 이 캐시에서 반환됩니다.
     * DB 직접 조회가 필요한 경우 suspend 버전인 [activeCountSuspend]를 사용하세요.
     */
    private val cachedActiveCount = AtomicInteger(0)

    /**
     * [lockName]의 현재 활성 슬롯 수(캐시 기반, 근사값).
     *
     * 마지막 [runIfLeader] 호출 이후 외부 변경은 반영되지 않을 수 있습니다.
     */
    override fun activeCount(lockName: String): Int = cachedActiveCount.get()

    /** [lockName]에서 즉시 획득 가능한 슬롯 수 (캐시 기반). */
    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    /** [lockName]에 대한 [LeaderGroupState] 스냅샷을 반환합니다 (캐시 기반). */
    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * [lockName]의 현재 활성 슬롯 수를 DB에서 직접 조회합니다.
     *
     * DB 오류 발생 시 best-effort로 `0`을 반환합니다.
     *
     * @throws IllegalArgumentException [lockName]이 유효하지 않은 경우
     */
    suspend fun activeCountSuspend(lockName: String): Int {
        validateExposedR2dbcLockName(lockName)
        return runCatching {
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
        }.getOrElse { e ->
            log.warn(e) { "activeCount DB 조회 오류 (0 반환): lockName=$lockName" }
            0
        }.also { cachedActiveCount.set(it) }
    }

    /**
     * [lockName] 그룹의 빈 슬롯을 하나 획득하면 suspend [action]을 실행합니다.
     *
     * - 모든 슬롯이 사용 중이면 `null`을 반환합니다 (예외 없음).
     * - [action] 예외는 그대로 전파되며, 슬롯은 항상 반납됩니다.
     * - [CancellationException]은 재전파되며 FAILED 이력에 기록되지 않습니다.
     *
     * @throws IllegalArgumentException [lockName]이 유효하지 않은 경우
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        validateExposedR2dbcLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime
            .dividedBy(maxLeaders.toLong())
            .coerceAtLeast(Duration.ofMillis(1L))
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
            var actionSucceeded = false
            var actionFailed = false

            try {
                val result = action()
                actionSucceeded = true
                return result
            } catch (e: CancellationException) {
                actionFailed = true
                throw e
            } catch (e: Throwable) {
                actionFailed = true
                throw e
            } finally {
                withContext(NonCancellable) {
                    cachedActiveCount.updateAndGet { it.coerceAtLeast(1) - 1 }
                    when {
                        actionSucceeded -> recordCompleted(historyId, lock.token, startedAt, slot)
                        actionFailed -> recordFailed(historyId, lock.token, startedAt, slot)
                    }
                    runCatching { lock.unlock() }
                        .onSuccess { log.debug { "그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                        .onFailure { e -> log.warn(e) { "그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
                }
            }
        }

        log.debug { "그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
        return null
    }

    private suspend fun recordAcquired(lockName: String, token: String, slot: Int): Long? {
        if (!options.recordHistory) return null
        return runCatching {
            suspendTransaction(db) {
                val now = Instant.now()
                LeaderLockHistoryTable.insert {
                    it[LeaderLockHistoryTable.lockName] = lockName
                    it[LeaderLockHistoryTable.lockOwner] = options.lockOwner
                    it[LeaderLockHistoryTable.token] = token
                    it[LeaderLockHistoryTable.slot] = slot
                    it[LeaderLockHistoryTable.lockedUntil] = now.plusMillis(options.leaderGroupOptions.leaseTime.toMillis())
                    it[LeaderLockHistoryTable.status] = HistoryStatus.ACQUIRED.name
                    it[LeaderLockHistoryTable.startedAt] = now
                }[LeaderLockHistoryTable.id]
            }
        }.getOrElse { e ->
            log.warn(e) { "이력 ACQUIRED 기록 실패 (best-effort, 무시): lockName=$lockName, slot=$slot" }
            null
        }
    }

    private suspend fun recordCompleted(historyId: Long?, token: String, startedAt: Instant, slot: Int) =
        recordFinished(historyId, token, startedAt, slot, HistoryStatus.COMPLETED)

    private suspend fun recordFailed(historyId: Long?, token: String, startedAt: Instant, slot: Int) =
        recordFinished(historyId, token, startedAt, slot, HistoryStatus.FAILED)

    private suspend fun recordFinished(
        historyId: Long?,
        token: String,
        startedAt: Instant,
        slot: Int,
        status: HistoryStatus,
    ) {
        if (!options.recordHistory || historyId == null) return
        val finishedAt = Instant.now()
        runCatching {
            suspendTransaction(db) {
                LeaderLockHistoryTable.update(
                    where = { (LeaderLockHistoryTable.id eq historyId) and (LeaderLockHistoryTable.token eq token) }
                ) {
                    it[LeaderLockHistoryTable.status] = status.name
                    it[LeaderLockHistoryTable.finishedAt] = finishedAt
                    it[LeaderLockHistoryTable.durationMs] = Duration.between(startedAt, finishedAt).toMillis()
                }
            }
        }.getOrElse { e ->
            log.warn(e) { "이력 ${status.name} 기록 실패 (best-effort): historyId=$historyId, slot=$slot" }
        }
    }
}
