package io.bluetape4k.leader.exposed.jdbc.lock

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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Instant

/**
 * Group lock backed by Exposed JDBC using a composite-PK slot model.
 *
 * Implements a semaphore that allows up to N simultaneous leaders by using
 * the `(lockName, slot)` composite primary key of [LeaderGroupLockTable].
 *
 * ## Behavior
 * Uses the same UPDATE+INSERT+SELECT pattern as [ExposedJdbcLock] with
 * an additional `slot` number in the composite primary key.
 *
 * @param db Exposed [Database] instance
 * @param lockName group lock identifier
 * @param slot slot number (0-based)
 * @param retryStrategy back-off strategy for retries
 * @param lockOwner optional lock owner identifier
 */
internal class ExposedJdbcGroupLock internal constructor(
    private val db: Database,
    val lockName: String,
    val slot: Int,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
) {
    init {
        slot.requireZeroOrPositiveNumber("slot")
    }

    companion object : KLoggingChannel()

    /** Per-instance unique fencing token. */
    val token: String = Base58.randomString(8)

    /**
     * Attempts to acquire the slot lock within [waitTime].
     *
     * @return `true` when the lock is acquired, `false` on contention or timeout, `null` on DB error
     */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean? {
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds.coerceAtLeast(0L)
        var attempt = 0

        do {
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
                try {
                    Thread.sleep(retryStrategy.delayMs(attempt++, remaining))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.debug { "sleep interrupted; 재시도 중단: lockName=$lockName, slot=$slot" }
                    return false
                }
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "그룹 슬롯 락 획득 실패 (타임아웃): lockName=$lockName, slot=$slot" }
        return false
    }

    private fun tryAcquireOnce(leaseTime: Duration): Boolean {
        val lockNameVal = this@ExposedJdbcGroupLock.lockName
        val slotVal = this@ExposedJdbcGroupLock.slot
        val lockOwnerVal = this@ExposedJdbcGroupLock.lockOwner
        val tokenVal = this@ExposedJdbcGroupLock.token

        return transaction(db) {
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
                // PK 충돌만 흡수 → retry; 그 외 DB 오류는 재전파
                try {
                    LeaderGroupLockTable.insert {
                        it[LeaderGroupLockTable.lockName] = lockNameVal
                        it[LeaderGroupLockTable.slot] = slotVal
                        it[LeaderGroupLockTable.lockOwner] = lockOwnerVal
                        it[LeaderGroupLockTable.token] = tokenVal
                        it[LeaderGroupLockTable.lockedAt] = now
                        it[LeaderGroupLockTable.lockedUntil] = lockedUntil
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ExposedSQLException) {
                    if (e.sqlState.startsWith("23")) {
                        log.debug { "INSERT 실패 (PK 충돌 — 정상 경합): lockName=$lockName, slot=$slot" }
                        return@transaction false
                    }
                    throw e
                }
            }

            // SELECT으로 token 소유 + lease 유효성 확인 (R2DBC 형제 모듈과 대칭)
            !LeaderGroupLockTable
                .selectAll()
                .where {
                    (LeaderGroupLockTable.lockName eq lockNameVal) and
                        (LeaderGroupLockTable.slot eq slotVal) and
                        (LeaderGroupLockTable.token eq tokenVal) and
                        (LeaderGroupLockTable.lockedUntil greater now)
                }
                .empty()
        }
    }

    /**
     * Returns whether the current instance (token) holds a valid slot lock.
     *
     * Returns `false` when the lease has expired and another instance has re-acquired the slot.
     */
    fun isHeldByCurrentInstance(): Boolean = runCatching {
        val lockNameVal = lockName
        val slotVal = slot
        val tokenVal = token
        transaction(db) {
            val now = Instant.now()
            !LeaderGroupLockTable
                .selectAll()
                .where {
                    (LeaderGroupLockTable.lockName eq lockNameVal) and
                        (LeaderGroupLockTable.slot eq slotVal) and
                        (LeaderGroupLockTable.token eq tokenVal) and
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
     * Logs a warning on token mismatch and does not delete another owner's slot.
     */
    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val lockNameVal = this@ExposedJdbcGroupLock.lockName
        val slotVal = this@ExposedJdbcGroupLock.slot
        val tokenVal = this@ExposedJdbcGroupLock.token
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)

        runCatching {
            val matched = transaction(db) {
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
     * ## R6 guard (Issue #79 PR 5)
     * Adds `lockedUntil > now()` to the `WHERE` clause to prevent a split-brain where a stale token
     * revives an expired row after another instance has re-acquired it.
     *
     * ## SQL
     * ```sql
     * UPDATE leader_group_lock
     * SET locked_until = ? -- now + leaseTime
     * WHERE lock_name = ? AND slot = ? AND token = ? AND locked_until > ?  -- now
     * ```
     *
     * ## Return value
     * - `affectedRows == 1` → [ExtendOutcome.Extended] (`observedExpireAt = now + leaseTime`)
     * - `affectedRows == 0` → [ExtendOutcome.NotHeld] (token mismatch / lease expired / takeover)
     * - DB exceptions propagate as-is; the caller (delegate) wraps them as [ExtendOutcome.BackendError]
     *
     * [ExtendOutcome.WrongThread] never occurs because this is a token-based lock.
     */
    fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val lockNameVal = this@ExposedJdbcGroupLock.lockName
        val slotVal = this@ExposedJdbcGroupLock.slot
        val tokenVal = this@ExposedJdbcGroupLock.token

        return transaction(db) {
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
                log.debug { "Exposed JDBC group extend 실패 (NotHeld): lockName=$lockName, slot=$slot" }
                ExtendOutcome.NotHeld
            }
        }
    }
}
