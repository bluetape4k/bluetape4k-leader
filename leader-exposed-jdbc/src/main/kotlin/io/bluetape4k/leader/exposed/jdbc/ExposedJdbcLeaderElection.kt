package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.LeaderElection
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
import kotlinx.coroutines.CancellationException
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.leader.exposed.jdbc.lock.validateExposedLockName
import io.bluetape4k.leader.exposed.tables.HistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Exposed JDBC 기반 단일 리더 선출 구현체.
 *
 * `UPDATE + INSERT + SELECT` 패턴으로 DB 행 수준 락을 구현합니다.
 * 지원 DB는 모듈 README의 호환성 매트릭스를 참조하세요.
 *
 * ### 기본 사용
 * ```kotlin
 * val election = ExposedJdbcLeaderElection(db)
 * val result = election.runIfLeader("daily-job") { processData() }
 * // result == processData() 반환값 (리더 획득 성공) 또는 null (획득 실패)
 * ```
 *
 * ### 경합 시 skip
 * ```kotlin
 * // 다른 노드가 이미 리더면 즉시 null 반환 (예외 없음)
 * val report = election.runIfLeader("nightly-report") { generateReport() }
 *     ?: run {
 *         logger.info("리더가 아니므로 작업 건너뜀")
 *         return
 *     }
 * ```
 *
 * **private constructor** — [invoke] 팩터리를 통해서만 생성하세요.
 * 첫 호출 시 [ExposedJdbcSchemaInitializer.ensureSchema]가 실행되어 스키마가 자동으로 생성됩니다.
 *
 * @param db Exposed [Database] 인스턴스
 * @param options 단일 리더 선출 옵션
 */
class ExposedJdbcLeaderElection private constructor(
    private val db: Database,
    val options: ExposedJdbcLeaderElectionOptions,
) : LeaderElection {

    companion object : KLogging() {

        /**
         * [ExposedJdbcLeaderElection] 인스턴스를 생성합니다.
         *
         * 첫 호출 시 리더 선출 테이블 스키마를 자동으로 생성합니다 (최초 1회).
         */
        @JvmStatic
        operator fun invoke(
            db: Database,
            options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
        ): ExposedJdbcLeaderElection {
            ExposedJdbcSchemaInitializer.ensureSchema(db)
            return ExposedJdbcLeaderElection(db, options)
        }
    }

    /**
     * [lockName]에 대해 리더로 승격되면 [action]을 실행하고 결과를 반환합니다.
     *
     * - 리더 획득에 성공하면 [action] 결과를 반환합니다.
     * - 리더 획득에 실패하면 `null`을 반환합니다 (**예외 없음** — ShedLock 호환 skip-on-contention 계약).
     * - [action]이 던지는 예외는 그대로 전파되며, 락은 항상 해제됩니다.
     * - [CancellationException]은 재전파되며 FAILED 이력에 기록되지 않습니다.
     *
     * @param lockName 락 식별자 (영숫자/하이픈/언더스코어/콜론, 1-255자)
     * @param action 리더 획득 성공 시 실행할 작업
     * @return [action] 결과 또는 `null`
     * @throws IllegalArgumentException [lockName]이 유효하지 않은 경우
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateExposedLockName(lockName)

        val lock = ExposedJdbcLock(db, lockName, options.retryStrategy, options.lockOwner)
        log.debug { "리더 승격을 요청합니다. lockName=$lockName" }

        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            log.debug { "리더 승격 실패 (락 획득 불가). lockName=$lockName" }
            return null
        }

        log.debug { "리더로 승격하여 작업을 수행합니다. lockName=$lockName" }

        val historyId = recordAcquired(lockName, lock.token)
        val startedAt = Instant.now()
        var actionSucceeded = false
        var actionFailed = false

        try {
            val result = action()
            actionSucceeded = true
            return result
        } catch (e: CancellationException) {
            // CancellationException은 actionFailed 미설정 + 즉시 재전파 (코루틴 취소 계약)
            throw e
        } catch (e: Throwable) {
            actionFailed = true
            throw e
        } finally {
            when {
                actionSucceeded -> recordCompleted(historyId, lock.token, startedAt)
                actionFailed -> recordFailed(historyId, lock.token, startedAt)
                // CancellationException: 이력 미기록 (취소이므로 FAILED 아님)
            }
            runCatching { lock.unlock() }
                .onSuccess { log.debug { "리더 권한을 반납했습니다. lockName=$lockName" } }
                .onFailure { e -> log.warn(e) { "락 해제 실패. lockName=$lockName" } }
        }
    }

    /**
     * [lockName]에 대해 리더로 승격되면 비동기로 [action]을 실행합니다.
     *
     * 비동기 실행 성공/실패는 반환된 [CompletableFuture]에 반영되며, [action]이 동기적으로
     * 던지는 예외는 [CompletableFuture.failedFuture]로 래핑되어 전파됩니다.
     *
     * ```kotlin
     * val future: CompletableFuture<Report?> = election.runAsyncIfLeader(
     *     lockName = "report-job",
     *     executor = VirtualThreadExecutor,
     *     action = { generateReportAsync() }, // CompletableFuture<Report> 반환
     * )
     * ```
     *
     * @param lockName 락 식별자
     * @param executor 비동기 실행자
     * @param action 리더 획득 성공 시 실행할 비동기 작업
     * @return 작업 결과를 담은 [CompletableFuture] (리더 획득 실패 시 `null`로 완료)
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateExposedLockName(lockName)

        val lock = ExposedJdbcLock(db, lockName, options.retryStrategy, options.lockOwner)

        return CompletableFuture
            .supplyAsync({ lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime) }, executor)
            .thenComposeAsync({ acquired ->
                if (!acquired) {
                    log.debug { "리더 승격 실패 (비동기). lockName=$lockName" }
                    CompletableFuture.completedFuture(null)
                } else {
                    log.debug { "리더로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
                    val historyId = recordAcquired(lockName, lock.token)
                    val startedAt = Instant.now()

                    val actionFuture = runCatching { action() }
                        .getOrElse { e ->
                            recordFailed(historyId, lock.token, startedAt)
                            runCatching { lock.unlock() }
                                .onFailure { ex -> log.warn(ex) { "락 해제 실패 (action 오류 경로). lockName=$lockName" } }
                            return@thenComposeAsync CompletableFuture.failedFuture(e)
                        }

                    actionFuture.whenCompleteAsync({ _, throwable ->
                        when {
                            throwable == null -> recordCompleted(historyId, lock.token, startedAt)
                            // CompletableFuture.cancel 또는 코루틴 취소: FAILED 미기록 (취소이므로)
                            throwable is java.util.concurrent.CancellationException -> { /* skip */ }
                            throwable is CancellationException -> { /* skip */ }
                            else -> recordFailed(historyId, lock.token, startedAt)
                        }
                        runCatching { lock.unlock() }
                            .onSuccess { log.debug { "비동기 리더 권한을 반납했습니다. lockName=$lockName" } }
                            .onFailure { e -> log.warn(e) { "비동기 락 해제 실패. lockName=$lockName" } }
                    }, executor)
                }
            }, executor)
    }

    private fun recordAcquired(lockName: String, token: String): Long? {
        if (!options.recordHistory) return null
        return runCatching {
            transaction(db) {
                val now = Instant.now()
                LeaderLockHistoryTable.insert {
                    it[LeaderLockHistoryTable.lockName] = lockName
                    it[LeaderLockHistoryTable.lockOwner] = options.lockOwner
                    it[LeaderLockHistoryTable.token] = token
                    it[LeaderLockHistoryTable.lockedUntil] = now.plusMillis(options.leaderOptions.leaseTime.toMillis())
                    it[LeaderLockHistoryTable.status] = HistoryStatus.ACQUIRED.name
                    it[LeaderLockHistoryTable.startedAt] = now
                }[LeaderLockHistoryTable.id]
            }
        }.getOrElse { e ->
            log.warn(e) { "이력 ACQUIRED 기록 실패 (best-effort, 무시): lockName=$lockName" }
            null
        }
    }

    private fun recordCompleted(historyId: Long?, token: String, startedAt: Instant) =
        recordFinished(historyId, token, startedAt, HistoryStatus.COMPLETED)

    private fun recordFailed(historyId: Long?, token: String, startedAt: Instant) =
        recordFinished(historyId, token, startedAt, HistoryStatus.FAILED)

    private fun recordFinished(historyId: Long?, token: String, startedAt: Instant, status: HistoryStatus) {
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
            log.warn(e) { "이력 ${status.name} 기록 실패 (best-effort, 무시): historyId=$historyId" }
        }
    }
}
