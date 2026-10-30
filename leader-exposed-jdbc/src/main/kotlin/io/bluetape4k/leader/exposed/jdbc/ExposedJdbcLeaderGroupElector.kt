package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcBackendErrorClassifier
import io.bluetape4k.leader.exposed.jdbc.internal.ExposedJdbcSlotExtendDelegate
import io.bluetape4k.leader.exposed.jdbc.internal.unwrapCompletionCause
import io.bluetape4k.leader.exposed.jdbc.internal.validateExposedLockName
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcGroupLock
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcUnlockOutcome
import io.bluetape4k.leader.exposed.jdbc.lock.currentTime
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
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * `ExposedJdbcLeaderGroupElector`는 JDBC slot 행을 이용해 여러 leader의 blocking/async 실행과 slot lease를 관리합니다.
 *
 * @property db 그룹 슬롯 테이블을 조회·변경하고 트랜잭션을 실행할 Exposed JDBC 데이터베이스입니다.
 * @property options maxLeaders, 대기·lease 시간, 재시도, owner, DB 시간 사용 여부를 담은 그룹 선거 설정입니다.
 * @property historyRecorder null이면 그룹 슬롯의 획득·완료·실패 이력을 기록하지 않고, 값이 있으면 안전한 history recorder로 기록합니다.
 */
class ExposedJdbcLeaderGroupElector private constructor(
    private val db: Database,
    val options: ExposedJdbcLeaderGroupElectionOptions,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
): LeaderGroupElector,
   LeaderBackendDiagnosticsProvider by ExposedJdbcLeaderBackendDiagnostics {

    companion object: KLogging() {

        internal const val EXPOSED_JDBC_GROUP_FACTORY_BEAN_NAME = "exposed-jdbc-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ExposedJdbcBackendErrorClassifier)

        /**
         * 데이터베이스 스키마를 한 번 보장한 뒤 그룹 elector를 생성합니다.
         * [options]의 [ExposedJdbcLeaderGroupElectionOptions.maxLeaders] 슬롯 범위에서 선거를 수행합니다.
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
     * 이 elector가 동시에 보유할 수 있는 활성 슬롯의 상한입니다. 값은 생성 시 1 이상으로 검증됩니다.
     */
    override val maxLeaders: Int get() = options.maxLeaders

    /**
     * [lockName]에서 현재 lease가 만료되지 않은 슬롯의 수를 DB 시간 기준으로 조회합니다.
     * DB 시간 조회가 실패하면 fail-closed로 [maxLeaders]를 반환합니다.
     */
    override fun activeCount(lockName: String): Int =
        try {
            transaction(db) {
                val now = currentTime(options.leaderGroupOptions.useDbTime)
                val whereOp = (LeaderGroupLockTable.lockName eq lockName) and
                        (LeaderGroupLockTable.lockedUntil greater now)
                LeaderGroupLockTable
                    .selectAll()
                    .where(whereOp)
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
     * [lockName]에서 사용할 수 있는 슬롯 수를 `maxLeaders - activeCount(lockName)`으로 계산합니다.
     */
    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    /**
     * [lockName]의 최대 슬롯 수와 현재 활성 슬롯 수를 [LeaderGroupState]로 묶어 반환합니다.
     */
    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * [lockName]의 슬롯을 순회하며 하나를 [options]의 대기 시간 안에 획득합니다.
     * 획득한 슬롯에서 [action]을 실행하고, 경합으로 슬롯을 얻지 못하면 null을 반환합니다.
     * action 예외는 전파하며 취소·예외 뒤에도 watchdog과 슬롯을 정리합니다.
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        lockName.validateExposedLockName()

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
                true -> { /* 획득 성공 — 아래 로직 계속 */
                }
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
            val effectiveKey: LeaderHistoryKey? = hKey
                ?: record?.let {
                    LeaderHistoryKey(
                        lockName = lockName,
                        token = lock.token,
                        slotId = slot.toString()
                    )
                }

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
                    actionSucceeded -> effectiveKey?.let {
                        historyRecorder?.recordCompleted(
                            it,
                            finishedAt,
                            durationMs
                        )
                    }
                    capturedError != null -> effectiveKey?.let {
                        historyRecorder?.recordFailed(
                            it,
                            finishedAt,
                            durationMs,
                            capturedError
                        )
                    }
                }
                when (lock.unlockAndReport(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)) {
                    ExposedJdbcUnlockOutcome.RELEASED ->
                        log.debug { "그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" }
                    ExposedJdbcUnlockOutcome.NOT_HELD ->
                        log.warn { "그룹 슬롯 반납 대상이 없습니다. lockName=$lockName, slot=$slot" }
                    ExposedJdbcUnlockOutcome.FAILED ->
                        log.warn { "그룹 슬롯 해제 실패(DB 오류). lockName=$lockName, slot=$slot" }
                }
            }
        }

        log.debug { "그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
        return null
    }

    /**
     * [lockName]의 그룹 슬롯 획득과 [action] 실행을 [executor]에 비동기로 위임합니다.
     * 획득하지 못하면 null을 완료값으로 반환하고, 취소·실패 시 획득한 슬롯과 history를 정리합니다.
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.validateExposedLockName()

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = (options.leaderGroupOptions.waitTime / maxLeaders).coerceAtLeast(1.milliseconds)
        val start = Random.nextInt(maxLeaders)

        val resultFuture = CompletableFuture<T?>()
        val actionFutureRef = AtomicReference<CompletableFuture<*>?>()
        val acquiredSlotRef = AtomicReference<Pair<ExposedJdbcGroupLock, Int>?>(null)
        val lifecycleStarted = AtomicBoolean()
        val rejectionCleanupClaimed = AtomicBoolean()
        val releaseIfUnclaimed: () -> Unit = {
            val acquired = acquiredSlotRef.get()
            val canUnlock = acquired != null &&
                    !lifecycleStarted.get() &&
                    rejectionCleanupClaimed.compareAndSet(false, true)
            if (canUnlock) {
                when (acquired.first.unlockAndReport()) {
                    ExposedJdbcUnlockOutcome.RELEASED ->
                        log.debug { "executor 거부 후 비동기 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=${acquired.second}" }
                    ExposedJdbcUnlockOutcome.NOT_HELD ->
                        log.warn { "executor 거부 후 반납할 그룹 슬롯이 없습니다. lockName=$lockName, slot=${acquired.second}" }
                    ExposedJdbcUnlockOutcome.FAILED ->
                        log.warn { "executor 거부 후 그룹 슬롯 해제에 실패했습니다. lockName=$lockName, slot=${acquired.second}" }
                }
            }
        }
        val acquisitionFuture = futureOf(executor) {
            var acquired: Pair<ExposedJdbcGroupLock, Int>? = null
            for (i in 0 until maxLeaders) {
                if (resultFuture.isCancelled) return@futureOf null
                val slot = (start + i) % maxLeaders
                val lock = ExposedJdbcGroupLock(
                    db,
                    lockName,
                    slot,
                    options.retryStrategy,
                    options.lockOwner,
                    options.leaderGroupOptions.useDbTime,
                )
                when (lock.tryLock(perSlotWait, leaseTime) { resultFuture.isCancelled }) {
                    true -> {
                        acquired = lock to slot
                        break
                    }
                    false -> continue
                    null -> {
                        log.warn { "DB 오류로 비동기 슬롯 순회 중단: lockName=$lockName, slot=$slot" }
                        return@futureOf null
                    }
                }
            }
            acquired?.also { acquiredSlotRef.set(it) }
            acquired
        }

        val pipelineFuture: CompletableFuture<T?> = try {
            acquisitionFuture.thenComposeAsync({ acquired ->
                if (acquired == null) {
                    log.debug { "그룹 슬롯 획득 실패 (비동기). lockName=$lockName" }
                    completableFutureOf(null)
                } else {
                    lifecycleStarted.set(true)
                    val (lock, slot) = acquired
                    if (resultFuture.isCancelled) {
                        when (lock.unlockAndReport()) {
                            ExposedJdbcUnlockOutcome.RELEASED ->
                                log.debug { "외부 취소 후 획득한 슬롯을 즉시 반납했습니다. lockName=$lockName, slot=$slot" }
                            ExposedJdbcUnlockOutcome.NOT_HELD ->
                                log.warn { "외부 취소 후 반납할 슬롯이 없습니다. lockName=$lockName, slot=$slot" }
                            ExposedJdbcUnlockOutcome.FAILED ->
                                log.warn { "외부 취소 후 슬롯 해제에 실패했습니다(DB 오류). lockName=$lockName, slot=$slot" }
                        }
                        return@thenComposeAsync failedCompletableFutureOf(
                            CancellationException("runAsyncIfLeader result was cancelled"),
                        )
                    }
                    log.debug { "그룹 슬롯 비동기 작업 수행. lockName=$lockName, slot=$slot" }

                    val startedAt = Instant.now()
                    val acquiredAtNanos = System.nanoTime()
                    var watchdog: AutoCloseable? = null
                    var effectiveKey: LeaderHistoryKey? = null
                    val terminal = AtomicBoolean()
                    val finishAction: (Throwable?) -> CompletableFuture<Throwable?> = { throwable ->
                        if (!terminal.compareAndSet(false, true)) {
                            completableFutureOf(null)
                        } else {
                            val closeFuture = watchdog?.let { LeaderLeaseAutoExtender.closeAsync(it) }
                                ?: completableFutureOf(null)
                            closeFuture.handle { _, closeFailure ->
                                var cleanupFailure = closeFailure?.unwrapCompletionCause()?.also { e ->
                                    log.warn(e) { "비동기 그룹 watchdog 종료 실패. lockName=$lockName, slot=$slot" }
                                }

                                runCatching {
                                    val finishedAt = Instant.now()
                                    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                                    when {
                                        throwable == null -> effectiveKey?.let {
                                            historyRecorder?.recordCompleted(it, finishedAt, durationMs)
                                        }
                                        else -> effectiveKey?.let {
                                            historyRecorder?.recordFailed(it, finishedAt, durationMs, throwable)
                                        }
                                    }
                                }.onFailure { e ->
                                    cleanupFailure = cleanupFailure?.also { it.addSuppressed(e) } ?: e
                                    log.warn(e) { "비동기 그룹 history 기록 실패. lockName=$lockName, slot=$slot" }
                                }

                                runCatching {
                                    when (lock.unlockAndReport(
                                        options.leaderGroupOptions.minLeaseTime,
                                        acquiredAtNanos
                                    )) {
                                        ExposedJdbcUnlockOutcome.RELEASED ->
                                            log.debug { "비동기 그룹 슬롯 반납. lockName=$lockName, slot=$slot" }
                                        ExposedJdbcUnlockOutcome.NOT_HELD ->
                                            log.warn { "비동기 그룹 슬롯 반납 대상이 없습니다. lockName=$lockName, slot=$slot" }
                                        ExposedJdbcUnlockOutcome.FAILED ->
                                            log.warn { "비동기 그룹 슬롯 해제 실패(DB 오류). lockName=$lockName, slot=$slot" }
                                    }
                                }.onFailure { e ->
                                    cleanupFailure = cleanupFailure?.also { it.addSuppressed(e) } ?: e
                                    log.warn(e) { "비동기 그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" }
                                }

                                if (throwable != null) {
                                    cleanupFailure?.let { throwable.addSuppressed(it) }
                                    null
                                } else {
                                    cleanupFailure
                                }
                            }
                        }
                    }

                    try {
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
                        val fallbackKey = record?.let {
                            LeaderHistoryKey(lockName = lockName, token = lock.token, slotId = slot.toString())
                        }
                        effectiveKey = fallbackKey
                        val key = record?.let { historyRecorder.recordAcquired(it) }
                        effectiveKey = key ?: fallbackKey

                        // Group elector에는 autoExtend 옵션이 없으므로 명시적 LockExtender만 사용합니다.
                        watchdog = LeaderLeaseAutoExtender.start(
                            false,
                            options.leaderGroupOptions.leaseTime,
                            ExposedJdbcSlotExtendDelegate(lock),
                            ERROR_CLASSIFIER,
                        )
                    } catch (e: Throwable) {
                        return@thenComposeAsync finishAction(e).thenCompose {
                            failedCompletableFutureOf(e)
                        }
                    }

                    if (resultFuture.isCancelled) {
                        val cancellation = java.util.concurrent.CancellationException(
                            "runAsyncIfLeader result was cancelled before action",
                        )
                        return@thenComposeAsync finishAction(cancellation).thenCompose {
                            failedCompletableFutureOf(cancellation)
                        }
                    }

                    val actionFuture = runCatching { action() }
                        .getOrElse { e ->
                            return@thenComposeAsync finishAction(e).thenCompose {
                                failedCompletableFutureOf(e)
                            }
                        }

                    val terminalFuture = actionFuture.handle { value, throwable ->
                        Triple(value, throwable, finishAction(throwable))
                    }.thenCompose { (value, throwable, cleanup) ->
                        cleanup.thenCompose { cleanupFailure ->
                            if (throwable != null) {
                                failedCompletableFutureOf<T?>(throwable)
                            } else if (cleanupFailure != null) {
                                failedCompletableFutureOf(cleanupFailure)
                            } else {
                                completableFutureOf(value)
                            }
                        }
                    }
                    actionFutureRef.set(actionFuture)
                    if (resultFuture.isCancelled) actionFuture.cancel(false)
                    terminalFuture
                }
            }, executor)
        } catch (e: Throwable) {
            acquisitionFuture.whenComplete { acquired, _ ->
                if (acquired != null) releaseIfUnclaimed()
            }
            failedCompletableFutureOf(e)
        }

        resultFuture.whenComplete { _, _ ->
            if (resultFuture.isCancelled) actionFutureRef.get()?.cancel(false)
        }
        pipelineFuture.whenComplete { value, throwable ->
            if (throwable != null) releaseIfUnclaimed()
            if (throwable == null) {
                resultFuture.complete(value)
            } else {
                resultFuture.completeExceptionally(throwable)
            }
        }
        return resultFuture
    }

}
