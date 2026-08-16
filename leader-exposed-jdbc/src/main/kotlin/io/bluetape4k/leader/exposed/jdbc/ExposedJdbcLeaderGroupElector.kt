package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcBackendErrorClassifier
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcSlotExtendDelegate
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcGroupLock
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.leader.exposed.jdbc.lock.currentTime
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
 * `ExposedJdbcLeaderGroupElector`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property db Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property historyRecorder Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ExposedJdbcLeaderGroupElector private constructor(
    private val db: Database,
    val options: ExposedJdbcLeaderGroupElectionOptions,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
) : LeaderGroupElector,
    LeaderBackendDiagnosticsProvider by ExposedJdbcLeaderBackendDiagnostics {

    companion object : KLogging() {

        internal const val EXPOSED_JDBC_GROUP_FACTORY_BEAN_NAME = "exposed-jdbc-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedJdbcBackendErrorClassifier)

        /**
         * `invoke` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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

    /**
     * `maxLeaders` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    override val maxLeaders: Int get() = options.maxLeaders

    /**
     * `activeCount` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun activeCount(lockName: String): Int =
        try {
            transaction(db) {
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
            val fallback = if (options.leaderGroupOptions.useDbTime) maxLeaders else 0
            log.warn(e) { "activeCount DB 오류 (${fallback} 반환): lockName=$lockName" }
            fallback
        }

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
     * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateExposedLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = (options.leaderGroupOptions.waitTime / maxLeaders).coerceAtLeast(1.milliseconds)
        val start = Random.nextInt(maxLeaders)

        log.debug { "그룹 슬롯 획득을 요청합니다. lockName=$lockName, maxLeaders=$maxLeaders" }

        for (i in 0 until maxLeaders) {
            val slot = (start + i) % maxLeaders
            val lock = ExposedJdbcGroupLock(
                db,
                lockName,
                slot,
                options.retryStrategy,
                options.lockOwner,
                options.leaderGroupOptions.useDbTime,
            )

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
                capturedError = e
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

        log.debug { "그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
        return null
    }

    /**
     * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
                val lock = ExposedJdbcGroupLock(
                    db,
                    lockName,
                    slot,
                    options.retryStrategy,
                    options.lockOwner,
                    options.leaderGroupOptions.useDbTime,
                )
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
                        try {
                            lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            log.warn(ex) { "슬롯 해제 실패 (action 오류 경로). slot=$slot" }
                        }
                        return@thenComposeAsync CompletableFuture.failedFuture(e)
                    }

                actionFuture.whenComplete { _, throwable ->
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
                    try {
                        lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)
                        log.debug { "비동기 그룹 슬롯 반납. lockName=$lockName, slot=$slot" }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "비동기 슬롯 해제 실패. lockName=$lockName, slot=$slot" }
                    }
                }
            }
        }, executor)
    }

}
