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

/**
 * Exposed JDBC 기반 단일 리더 선출 구현체.
 *
 * `UPDATE + INSERT + SELECT` 패턴으로 DB 행 수준 락을 구현합니다.
 * 지원 DB는 모듈 README의 호환성 매트릭스를 참조하세요.
 *
 * ### 기본 사용
 * ```kotlin
 * val election = ExposedJdbcLeaderElector(db)
 * val result = election.runIfLeader("daily-job") { processData() }
 * // result == processData() 반환값 (리더 획득 성공) 또는 null (획득 실패 / action 예외)
 * ```
 *
 * ### 히스토리 기록
 * ```kotlin
 * val sink = ExposedLeaderHistorySink(db)
 * val recorder = SafeLeaderHistoryRecorder(sink)
 * val election = ExposedJdbcLeaderElector(db, historyRecorder = recorder)
 * ```
 *
 * ### 경합 시 skip
 * ```kotlin
 * val report = election.runIfLeader("nightly-report") { generateReport() }
 *     ?: run { logger.info("리더가 아니므로 작업 건너뜀"); return }
 * ```
 *
 * ## Behavior / Contract
 * - Lock 미획득(contention) 시 `null` 반환 — 예외 없음.
 * - Action이 [CancellationException] 또는 [InterruptedException]을 던지면 rethrow.
 * - Action이 다른 [Exception]을 던지면 `null` 반환 — 예외를 삼키지 않고 기록 후 null 반환.
 * - [historyRecorder]가 null이면 이력 기록 없이 동작.
 *
 * **private constructor** — [invoke] 팩터리를 통해서만 생성하세요.
 *
 * @param db Exposed [Database] 인스턴스
 * @param options 단일 리더 선출 옵션
 * @param historyRecorder 선택적 이력 기록기; null이면 이력 기록 안 함
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
         * [ExposedJdbcLeaderElector] 인스턴스를 생성합니다.
         *
         * 첫 호출 시 리더 선출 테이블 스키마를 자동으로 생성합니다 (최초 1회).
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
     * [lockName]에 대해 리더로 승격되면 [action]을 실행하고 결과를 반환합니다.
     *
     * - 리더 획득에 성공하면 [action] 결과를 반환합니다.
     * - 리더 획득에 실패하면 `null`을 반환합니다 (예외 없음 — ShedLock 호환 skip-on-contention 계약).
     * - [action]이 예외를 던지면 FAILED 이력 기록 후 예외를 그대로 재던집니다.
     * - 락은 정상/예외 어느 경로에서도 항상 해제됩니다.
     *
     * @param lockName 락 식별자 (영숫자/하이픈/언더스코어/콜론, 1-255자)
     * @param action 리더 획득 성공 시 실행할 작업
     * @return [action] 결과 또는 `null` (리더 획득 실패 시)
     * @throws IllegalArgumentException [lockName]이 유효하지 않은 경우
     * @throws Exception [action]이 던진 예외 (FAILED 이력 기록 후 재전파)
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
                val durationMs = (System.nanoTime() - acquiredAtNanos) / 1_000_000L
                effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                val finishedAt = Instant.now()
                val durationMs = (System.nanoTime() - acquiredAtNanos) / 1_000_000L
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
     * [lockName]에 대해 리더로 승격되면 비동기로 [action]을 실행합니다.
     *
     * @param lockName 락 식별자
     * @param executor 비동기 실행자
     * @param action 리더 획득 성공 시 실행할 비동기 작업
     * @return 작업 결과를 담은 [CompletableFuture] (리더 획득 실패 / action 예외 시 `null`로 완료)
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
                            val durationMs = (System.nanoTime() - acquiredAtNanos) / 1_000_000L
                            effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                            runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                                .onFailure { ex -> log.warn(ex) { "락 해제 실패 (action 오류 경로). lockName=$lockName" } }
                            return@thenComposeAsync CompletableFuture.completedFuture(null)
                        }

                    actionFuture.whenCompleteAsync({ _, throwable ->
                        watchdog.close()
                        val finishedAt = Instant.now()
                        val durationMs = (System.nanoTime() - acquiredAtNanos) / 1_000_000L
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
