package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderGroupElection
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcGroupLock
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.leader.exposed.jdbc.lock.validateExposedLockName
import io.bluetape4k.leader.exposed.tables.HistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.random.Random

/**
 * Exposed JDBC 기반 복수 리더 그룹 선출 구현체.
 *
 * `(lockName, slot)` 복합 PK 기반 슬롯 순회로 최대
 * [ExposedJdbcLeaderGroupElectionOptions.maxLeaders]개의 동시 리더를 허용합니다.
 * 슬롯 시작 위치를 랜덤화하여 핫스팟을 방지합니다.
 *
 * ### 기본 사용
 * ```kotlin
 * val election = ExposedJdbcLeaderGroupElection(
 *     db,
 *     ExposedJdbcLeaderGroupElectionOptions(
 *         leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     ),
 * )
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * // 최대 3개 노드 동시 실행, 나머지는 null 반환
 * ```
 *
 * ### 그룹 상태 조회
 * ```kotlin
 * val state = election.state("batch-job")
 * println("active=${state.activeCount} / max=${state.maxLeaders}")
 * println("available=${election.availableSlots("batch-job")}")
 * ```
 *
 * **private constructor** — [invoke] 팩터리를 사용하세요. 첫 호출 시 스키마가 자동으로 생성됩니다.
 *
 * @param db Exposed [Database] 인스턴스
 * @param options 그룹 리더 선출 옵션
 */
class ExposedJdbcLeaderGroupElection private constructor(
    private val db: Database,
    val options: ExposedJdbcLeaderGroupElectionOptions,
) : LeaderGroupElection {

    companion object : KLogging() {

        /**
         * [ExposedJdbcLeaderGroupElection] 인스턴스를 생성합니다.
         *
         * 첫 호출 시 리더 선출 테이블 스키마를 자동으로 생성합니다 (최초 1회).
         */
        @JvmStatic
        operator fun invoke(
            db: Database,
            options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
        ): ExposedJdbcLeaderGroupElection {
            ExposedJdbcSchemaInitializer.ensureSchema(db)
            return ExposedJdbcLeaderGroupElection(db, options)
        }
    }

    /** 동시 허용 리더 수 ([ExposedJdbcLeaderGroupElectionOptions.maxLeaders] 위임). */
    override val maxLeaders: Int get() = options.maxLeaders

    /**
     * [lockName]의 현재 활성 슬롯 수(만료되지 않은 lease 보유 행)를 반환합니다.
     *
     * DB 오류 발생 시 best-effort로 `0`을 반환합니다 (호출자에게 예외를 전파하지 않음).
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

    /** [lockName]에서 즉시 획득 가능한 슬롯 수. */
    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    /** [lockName]에 대한 [LeaderGroupState] 스냅샷을 반환합니다. */
    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * [lockName] 그룹의 빈 슬롯을 하나 획득하면 [action]을 실행합니다.
     *
     * - 모든 슬롯이 사용 중이면 `null`을 반환합니다 (예외 없음).
     * - [action] 예외는 그대로 전파되며, 슬롯은 항상 반납됩니다.
     * - [CancellationException]은 재전파되며 FAILED 이력에 기록되지 않습니다.
     *
     * @throws IllegalArgumentException [lockName]이 유효하지 않은 경우
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateExposedLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime
            .dividedBy(maxLeaders.toLong())
            .coerceAtLeast(Duration.ofMillis(1L))
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

            val historyId = recordAcquired(lockName, lock.token, slot)
            val startedAt = Instant.now()
            var actionSucceeded = false
            var actionFailed = false

            try {
                val result = action()
                actionSucceeded = true
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                actionFailed = true
                throw e
            } finally {
                when {
                    actionSucceeded -> recordCompleted(historyId, lock.token, startedAt, slot)
                    actionFailed -> recordFailed(historyId, lock.token, startedAt, slot)
                }
                runCatching { lock.unlock() }
                    .onSuccess { log.debug { "그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                    .onFailure { e -> log.warn(e) { "그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
            }
        }

        log.debug { "그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
        return null
    }

    /**
     * [lockName] 그룹의 슬롯을 비동기로 획득하여 [action]을 실행합니다.
     *
     * 슬롯 획득 실패 시 `null`로 완료된 [CompletableFuture]를 반환합니다.
     * action이 동기적으로 던지는 예외는 [CompletableFuture.failedFuture]로 래핑됩니다.
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
        val perSlotWait = options.leaderGroupOptions.waitTime
            .dividedBy(maxLeaders.toLong())
            .coerceAtLeast(Duration.ofMillis(1L))
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

                val historyId = recordAcquired(lockName, lock.token, slot)
                val startedAt = Instant.now()

                val actionFuture = runCatching { action() }
                    .getOrElse { e ->
                        recordFailed(historyId, lock.token, startedAt, slot)
                        runCatching { lock.unlock() }
                            .onFailure { ex -> log.warn(ex) { "슬롯 해제 실패 (action 오류 경로). slot=$slot" } }
                        return@thenComposeAsync CompletableFuture.failedFuture(e)
                    }

                actionFuture.whenCompleteAsync({ _, throwable ->
                    when {
                        throwable == null -> recordCompleted(historyId, lock.token, startedAt, slot)
                        // 취소(코루틴/CompletableFuture)는 FAILED로 기록하지 않음
                        throwable is java.util.concurrent.CancellationException -> { /* skip */ }
                        throwable is CancellationException -> { /* skip */ }
                        else -> recordFailed(historyId, lock.token, startedAt, slot)
                    }
                    runCatching { lock.unlock() }
                        .onSuccess { log.debug { "비동기 그룹 슬롯 반납. lockName=$lockName, slot=$slot" } }
                        .onFailure { e -> log.warn(e) { "비동기 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
                }, executor)
            }
        }, executor)
    }

    private fun recordAcquired(lockName: String, token: String, slot: Int): Long? {
        if (!options.recordHistory) return null
        return runCatching {
            transaction(db) {
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

    private fun recordCompleted(historyId: Long?, token: String, startedAt: Instant, slot: Int) =
        recordFinished(historyId, token, startedAt, slot, HistoryStatus.COMPLETED)

    private fun recordFailed(historyId: Long?, token: String, startedAt: Instant, slot: Int) =
        recordFinished(historyId, token, startedAt, slot, HistoryStatus.FAILED)

    private fun recordFinished(
        historyId: Long?,
        token: String,
        startedAt: Instant,
        slot: Int,
        status: HistoryStatus,
    ) {
        if (!options.recordHistory || historyId == null) return
        val finishedAt = Instant.now()
        runCatching {
            transaction(db) {
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
