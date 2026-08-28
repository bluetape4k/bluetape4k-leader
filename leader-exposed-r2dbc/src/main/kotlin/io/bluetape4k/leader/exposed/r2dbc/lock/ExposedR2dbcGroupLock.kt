package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.r2dbc.internal.MonotonicDeadline
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Clock

/**
 * `ExposedR2dbcGroupLock`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property db Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockName Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property slot Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property retryStrategy Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockOwner Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property useDbTime Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal enum class ExposedR2dbcUnlockOutcome {
    RELEASED,
    NOT_HELD,
    FAILED,
}

@Suppress("LongParameterList")
internal class ExposedR2dbcGroupLock internal constructor(
    private val db: R2dbcDatabase,
    val lockName: String,
    val slot: Int,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
    private val useDbTime: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * DB-time availability 상태를 갱신하는 동기 callback입니다.
     *
     * callback은 fail-closed 상태 전이의 일부이므로 일반 예외와
     * [CancellationException]을 삼키지 않고 호출자에게 전파합니다.
     */
    private val onAvailabilityChanged: (Boolean) -> Unit = {},
) {
    /**
     * 0.4.x에서 컴파일된 호출자의 생성자 디스크립터를 보존합니다.
     */
    internal constructor(
        db: R2dbcDatabase,
        lockName: String,
        slot: Int,
        retryStrategy: RetryStrategy,
        lockOwner: String? = null,
    ) : this(db, lockName, slot, retryStrategy, lockOwner, false, Clock.systemUTC())

    init {
        slot.requireZeroOrPositiveNumber("slot")
    }

    companion object: KLoggingChannel() {
        private val AVAILABILITY_CALLBACK_CLEANUP_TIMEOUT = 5.seconds
    }

    private fun markUnavailable() {
        if (useDbTime) onAvailabilityChanged(false)
    }

    private fun markAvailable() {
        if (useDbTime) onAvailabilityChanged(true)
    }

    /**
     * `token` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val token: String = Base58.randomString(length = 8)

    /**
     * `tryLock` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean? {
        val deadline = MonotonicDeadline.fromNow(waitTime)
        var attempt = 0

        do {
            currentCoroutineContext().ensureActive()

            val acquired = try {
                tryAcquireOnce(leaseTime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn(e) { "DB 오류로 슬롯 순회 중단: lockName=$lockName, slot=$slot, attempt=$attempt" }
                markUnavailablePreservingFailure(e)
                return null
            }

            // 슬롯 경합으로 획득에 실패했더라도 완료된 DB-time 연산은 정상 상태 신호입니다.
            // callback 실패는 DB 오류가 아니므로 호출자에게 그대로 관찰되어야 합니다.
            try {
                markAvailable()
            } catch (e: Throwable) {
                cleanupAfterAvailabilityCallbackFailure(acquired, e)
                throw e
            }
            if (acquired) {
                log.debug { "그룹 슬롯 락 획득 성공: lockName=$lockName, slot=$slot, token=${token.take(8)}" }
                return true
            }

            val remaining = deadline.remainingMillisForSleep()
            if (remaining > 0L) {
                // delay는 suspendTransaction 바깥에서 호출 (R2DBC 커넥션 풀 점유 방지)
                delay(timeMillis = retryStrategy.delayMs(attempt++, remaining))
            }
        } while (deadline.hasTimeRemaining())

        log.debug { "그룹 슬롯 락 획득 실패 (타임아웃): lockName=$lockName, slot=$slot" }
        return false
    }

    private suspend fun tryAcquireOnce(leaseTime: Duration): Boolean {
        val lockNameVal = this@ExposedR2dbcGroupLock.lockName
        val slotVal = this@ExposedR2dbcGroupLock.slot
        val lockOwnerVal = this@ExposedR2dbcGroupLock.lockOwner
        val tokenVal = this@ExposedR2dbcGroupLock.token

        return suspendTransaction(db) {
            val now = currentTime(useDbTime, clock)
            val lockedUntil = now.plusMillis(leaseTime.inWholeMilliseconds)

            val updated = LeaderGroupLockTable.update(
                where = {
                    (LeaderGroupLockTable.lockName eq lockNameVal) and
                            (LeaderGroupLockTable.slot eq slotVal) and
                            (LeaderGroupLockTable.lockedUntil less now)
                }
            ) {
                it[LeaderGroupLockTable.lockOwner] = lockOwnerVal
                it[LeaderGroupLockTable.token] = tokenVal
                it[LeaderGroupLockTable.lockedAt] = now
                it[LeaderGroupLockTable.lockedUntil] = lockedUntil
            }

            if (updated == 0) {
                // Step 2: 신규 슬롯 행 삽입 시도
                // PostgreSQL: INSERT ... ON CONFLICT DO NOTHING
                // MySQL: INSERT IGNORE INTO
                // H2 MySQL mode: INSERT IGNORE INTO
                // H2 default mode: UnsupportedOperationException (Kotlin 예외) → false 반환
                val inserted = try {
                    LeaderGroupLockTable.insertIgnore {
                        it[LeaderGroupLockTable.lockName] = lockNameVal
                        it[LeaderGroupLockTable.slot] = slotVal
                        it[LeaderGroupLockTable.lockOwner] = lockOwnerVal
                        it[LeaderGroupLockTable.token] = tokenVal
                        it[LeaderGroupLockTable.lockedAt] = now
                        it[LeaderGroupLockTable.lockedUntil] = lockedUntil
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsupportedByDialectException) {
                    // H2 default mode: insertIgnore 미지원 → 슬롯 경합 실패로 처리
                    log.debug { "insertIgnore 미지원 dialect (H2 default mode?): lockName=$lockName, slot=$slot" }
                    false
                }

                if (!inserted) return@suspendTransaction false

                // insertIgnore가 경합 행 때문에 무시됐을 수 있으므로 토큰 소유 여부 확인
                return@suspendTransaction !LeaderGroupLockTable
                    .selectAll()
                    .where {
                        (LeaderGroupLockTable.lockName eq lockNameVal) and
                                (LeaderGroupLockTable.slot eq slotVal) and
                                (LeaderGroupLockTable.token eq tokenVal) and
                                (LeaderGroupLockTable.lockedUntil greater now)
                    }
                    .empty()
            }

            // UPDATE가 성공한 경우 — 이미 토큰이 행에 기록됨
            true
        }
    }

    /**
     * `isHeldByCurrentInstance` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun isHeldByCurrentInstance(): Boolean {
        val held = runR2dbcLockOperationPreservingCancellation(
            onFailure = { e ->
                log.warn(e) { "isHeldByCurrentInstance DB 오류 (false 반환): lockName=$lockName, slot=$slot" }
                markUnavailablePreservingFailure(e)
                null
            },
        ) {
            suspendTransaction(db) {
                val now = currentTime(useDbTime, clock)
                !LeaderGroupLockTable
                    .selectAll()
                    .where {
                        (LeaderGroupLockTable.lockName eq lockName) and
                                (LeaderGroupLockTable.slot eq slot) and
                                (LeaderGroupLockTable.token eq token) and
                                (LeaderGroupLockTable.lockedUntil greater now)
                    }
                    .empty()
            }
        } ?: return false
        // callback 실패가 DB 오류 fallback 경계에 포함되지 않도록 분리합니다.
        markAvailable()
        return held
    }

    /**
     * `unlock` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        unlockAndReport(minLeaseTime, acquiredAtNanos)
    }

    /**
     * 선출기가 로컬 active-count를 제거하기 전에 토큰 소실과 DB 오류를 구분할 수 있도록 합니다.
     * 공개 `unlock`의 0.4.0 `Unit` 반환 계약은 유지합니다.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun unlockAndReport(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ): ExposedR2dbcUnlockOutcome {
        val lockNameVal = this@ExposedR2dbcGroupLock.lockName
        val slotVal = this@ExposedR2dbcGroupLock.slot
        val tokenVal = this@ExposedR2dbcGroupLock.token
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)

        val unlockResult = runR2dbcLockOperationPreservingCancellation(
            onFailure = { e ->
                log.warn(e) { "그룹 슬롯 해제 중 DB 오류: lockName=$lockName, slot=$slot" }
                markUnavailablePreservingFailure(e)
                null
            },
        ) {
            suspendTransaction(db) {
                if (remaining > Duration.ZERO) {
                    val now = currentTime(useDbTime, clock)
                    LeaderGroupLockTable.update(
                        where = {
                            (LeaderGroupLockTable.lockName eq lockNameVal) and
                                (LeaderGroupLockTable.slot eq slotVal) and
                                (LeaderGroupLockTable.token eq tokenVal) and
                                (LeaderGroupLockTable.lockedUntil greater now)
                        }
                    ) {
                        it[LeaderGroupLockTable.lockedUntil] = now.plusMillis(remaining.inWholeMilliseconds)
                    } to true
                } else {
                    LeaderGroupLockTable.deleteWhere {
                        (LeaderGroupLockTable.lockName eq lockNameVal) and
                            (LeaderGroupLockTable.slot eq slotVal) and
                            (LeaderGroupLockTable.token eq tokenVal)
                    } to false
                }
            }
        } ?: return ExposedR2dbcUnlockOutcome.FAILED
        val (matched, timeVerified) = unlockResult

        // callback 실패는 transaction 실패와 구분하여 그대로 전파합니다.
        if (timeVerified) markAvailable()
        return if (matched == 0) {
            log.warn { "그룹 슬롯 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockName=$lockName, slot=$slot" }
            ExposedR2dbcUnlockOutcome.NOT_HELD
        } else {
            log.debug { "그룹 슬롯 해제 성공: lockName=$lockName, slot=$slot" }
            ExposedR2dbcUnlockOutcome.RELEASED
        }
    }

    /**
     * `extendDetailed` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val lockNameVal = this@ExposedR2dbcGroupLock.lockName
        val slotVal = this@ExposedR2dbcGroupLock.slot
        val tokenVal = this@ExposedR2dbcGroupLock.token

        val outcome = runR2dbcLockOperationPreservingCancellation(
            onFailure = { e ->
                log.warn(e) { "그룹 슬롯 연장 중 DB 오류: lockName=$lockName, slot=$slot" }
                markUnavailablePreservingFailure(e)
                throw e
            },
        ) {
            suspendTransaction(db) {
                val now = currentTime(useDbTime, clock)
                val newLockedUntil = now.plusMillis(leaseTime.inWholeMilliseconds)
                val updated = LeaderGroupLockTable.update(
                    where = {
                        (LeaderGroupLockTable.lockName eq lockNameVal) and
                            (LeaderGroupLockTable.slot eq slotVal) and
                            (LeaderGroupLockTable.token eq tokenVal) and
                            (LeaderGroupLockTable.lockedUntil greater now)  // R6: expired row revival 차단
                    }
                ) {
                    it[LeaderGroupLockTable.lockedUntil] = newLockedUntil
                }
                if (updated > 0) {
                    ExtendOutcome.Extended(newLockedUntil)
                } else {
                    log.debug { "Exposed R2DBC group extend 실패 (NotHeld): lockName=$lockName, slot=$slot" }
                    ExtendOutcome.NotHeld
                }
            }
        }
        // callback 실패가 DB 오류 fallback 경계에 포함되지 않도록 분리합니다.
        markAvailable()
        return outcome
    }

    /**
     * DB-time success callback 실패 뒤에 이미 획득한 row가 남지 않도록 보상 해제합니다.
     * callback 예외를 원인으로 유지하고 보상 해제 실패는 suppressed 예외로만 남깁니다.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun cleanupAfterAvailabilityCallbackFailure(acquired: Boolean, failure: Throwable) {
        if (!acquired) return

        val outcome = try {
            withContext(NonCancellable) {
                withTimeoutOrNull(AVAILABILITY_CALLBACK_CLEANUP_TIMEOUT) {
                    unlockAndReport()
                }
            }
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressedSafely(cleanupFailure)
            return
        }
        when (outcome) {
            ExposedR2dbcUnlockOutcome.FAILED -> failure.addSuppressedSafely(
                IllegalStateException(
                    "availability callback 보상 슬롯 해제 실패: " +
                            "lockName=$lockName, slot=$slot",
                ),
            )
            null -> failure.addSuppressedSafely(
                IllegalStateException(
                    "availability callback 보상 슬롯 해제 시간 초과: " +
                            "lockName=$lockName, slot=$slot",
                ),
            )
            ExposedR2dbcUnlockOutcome.RELEASED,
            ExposedR2dbcUnlockOutcome.NOT_HELD,
            -> Unit
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun markUnavailablePreservingFailure(failure: Throwable) {
        try {
            markUnavailable()
        } catch (callbackFailure: Throwable) {
            callbackFailure.addSuppressedSafely(failure)
            throw callbackFailure
        }
    }

    private fun Throwable.addSuppressedSafely(cause: Throwable) {
        if (cause !== this && suppressed.none { it === cause }) addSuppressed(cause)
    }
}
