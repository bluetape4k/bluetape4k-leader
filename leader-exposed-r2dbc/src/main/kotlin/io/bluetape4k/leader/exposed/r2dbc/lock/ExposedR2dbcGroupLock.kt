package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import java.time.Instant

/**
 * Exposed R2DBC-based group lock (composite PK slot-based).
 *
 * Implements a semaphore allowing up to N simultaneous leaders using the
 * `(lockName, slot)` composite PK of [LeaderGroupLockTable].
 *
 * ## Behavior
 * Uses the same UPDATE+insertIgnore+SELECT pattern as [ExposedR2dbcLock],
 * with a composite PK that includes the `slot` number.
 *
 * ## Distinguishing DB errors from normal contention failures
 * - **Normal contention failure** (another node holds the slot): `tryAcquireOnce()` returns `false`
 * - **DB error** (connection lost, timeout, etc.): `tryLock()` returns `null` → caller can abort slot traversal
 *   (same contract as its JDBC counterpart)
 *
 * @param db Exposed [R2dbcDatabase] instance
 * @param lockName group lock identifier
 * @param slot slot number (0-based)
 * @param retryStrategy retry wait strategy
 * @param lockOwner lock holder identifier (optional)
 */
internal class ExposedR2dbcGroupLock internal constructor(
    private val db: R2dbcDatabase,
    val lockName: String,
    val slot: Int,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
) {
    init {
        slot.requireZeroOrPositiveNumber("slot")
    }

    companion object: KLoggingChannel()

    /** Unique fencing token per instance. */
    val token: String = Base58.randomString(length = 8)

    /**
     * Attempts to acquire the slot lock within [waitTime].
     *
     * @return `true` on successful lock acquisition, `false` on contention failure (timeout), `null` on DB error
     */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean? {
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds.coerceAtLeast(0L)
        var attempt = 0

        do {
            currentCoroutineContext().ensureActive()

            val acquired = try {
                tryAcquireOnce(leaseTime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn(e) { "DB 오류로 슬롯 순회 중단: lockName=$lockName, slot=$slot, attempt=$attempt" }
                return null
            }

            if (acquired) {
                log.debug { "그룹 슬롯 락 획득 성공: lockName=$lockName, slot=$slot, token=${token.take(8)}" }
                return true
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0L) {
                // delay는 suspendTransaction 바깥에서 호출 (R2DBC 커넥션 풀 점유 방지)
                delay(timeMillis = retryStrategy.delayMs(attempt++, remaining))
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "그룹 슬롯 락 획득 실패 (타임아웃): lockName=$lockName, slot=$slot" }
        return false
    }

    private suspend fun tryAcquireOnce(leaseTime: Duration): Boolean {
        val lockNameVal = this@ExposedR2dbcGroupLock.lockName
        val slotVal = this@ExposedR2dbcGroupLock.slot
        val lockOwnerVal = this@ExposedR2dbcGroupLock.lockOwner
        val tokenVal = this@ExposedR2dbcGroupLock.token

        return suspendTransaction(db) {
            val now = Instant.now()
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
     * Checks whether the current instance (token) holds a valid slot lock.
     *
     * Returns `false` if the lease has expired and another instance has re-acquired it.
     */
    suspend fun isHeldByCurrentInstance(): Boolean = runCatching {
        suspendTransaction(db) {
            val now = Instant.now()
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
    }.getOrElse { e ->
        log.warn(e) { "isHeldByCurrentInstance DB 오류 (false 반환): lockName=$lockName, slot=$slot" }
        false
    }

    /**
     * Releases the slot lock held by the current instance.
     *
     * Logs a warning on token mismatch without deleting another owner's slot.
     */
    suspend fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val lockNameVal = this@ExposedR2dbcGroupLock.lockName
        val slotVal = this@ExposedR2dbcGroupLock.slot
        val tokenVal = this@ExposedR2dbcGroupLock.token
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)

        runCatching {
            val matched = suspendTransaction(db) {
                if (remaining > Duration.ZERO) {
                    LeaderGroupLockTable.update(
                        where = {
                            (LeaderGroupLockTable.lockName eq lockNameVal) and
                                (LeaderGroupLockTable.slot eq slotVal) and
                                (LeaderGroupLockTable.token eq tokenVal)
                        }
                    ) {
                        it[LeaderGroupLockTable.lockedUntil] = Instant.now().plusMillis(remaining.inWholeMilliseconds)
                    }
                } else {
                    LeaderGroupLockTable.deleteWhere {
                        (LeaderGroupLockTable.lockName eq lockNameVal) and
                            (LeaderGroupLockTable.slot eq slotVal) and
                            (LeaderGroupLockTable.token eq tokenVal)
                    }
                }
            }
            if (matched == 0) {
                log.warn { "그룹 슬롯 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockName=$lockName, slot=$slot" }
            } else {
                log.debug { "그룹 슬롯 해제 성공: lockName=$lockName, slot=$slot" }
            }
        }.onFailure { e ->
            log.warn(e) { "그룹 슬롯 해제 중 DB 오류: lockName=$lockName, slot=$slot" }
        }
    }

    /**
     * Atomically extends the slot lock's `lockedUntil` by [leaseTime] and returns an [ExtendOutcome].
     *
     * ## R6 guard (Issue #79 PR 6)
     * Adds `lockedUntil > now()` to the `WHERE` clause to block split-brain scenarios where a stale token
     * could revive an expired row that another instance has already re-acquired.
     *
     * ## SQL
     * ```sql
     * UPDATE leader_group_lock
     * SET locked_until = ? -- now + leaseTime
     * WHERE lock_name = ? AND slot = ? AND token = ? AND locked_until > ?  -- now
     * ```
     *
     * ## Return values
     * - `affectedRows == 1` → [ExtendOutcome.Extended] (`observedExpireAt = now + leaseTime`)
     * - `affectedRows == 0` → [ExtendOutcome.NotHeld] (token mismatch / lease expired / takeover)
     * - R2DBC exceptions are wrapped as [ExtendOutcome.BackendError] by the caller (delegate) —
     *   this function rethrows them as-is.
     *
     * Token-based lock — [ExtendOutcome.WrongThread] never occurs.
     */
    suspend fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val lockNameVal = this@ExposedR2dbcGroupLock.lockName
        val slotVal = this@ExposedR2dbcGroupLock.slot
        val tokenVal = this@ExposedR2dbcGroupLock.token

        return suspendTransaction(db) {
            val now = Instant.now()
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
}
