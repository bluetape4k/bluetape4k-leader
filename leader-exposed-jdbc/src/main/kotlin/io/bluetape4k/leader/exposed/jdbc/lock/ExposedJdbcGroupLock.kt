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
 * `ExposedJdbcGroupLock`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property db Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockName Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property slot Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property retryStrategy Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockOwner Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
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

    /**
     * `token` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val token: String = Base58.randomString(8)

    /**
     * `tryLock` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    // The retry loop must distinguish cancellation, interruption, transient database errors,
    // and the sleep interruption path to preserve the blocking API contract.
    @Suppress("ThrowsCount", "ReturnCount")
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean? {
        val deadline = MonotonicDeadline.fromNow(waitTime)
        var attempt = 0

        do {
            val acquired = try {
                tryAcquireOnce(leaseTime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                log.warn(e) { "DB 오류로 슬롯 순회 중단: lockName=$lockName, slot=$slot, attempt=$attempt" }
                return null
            }

            if (acquired) {
                log.debug { "그룹 슬롯 락 획득 성공: lockName=$lockName, slot=$slot, token=${token.take(8)}" }
                return true
            }

            val remaining = deadline.remainingMillisForSleep()
            if (remaining > 0L) {
                try {
                    Thread.sleep(retryStrategy.delayMs(attempt++, remaining))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.debug { "sleep interrupted; 재시도 중단: lockName=$lockName, slot=$slot" }
                    throw e
                }
            }
        } while (deadline.hasTimeRemaining())

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
     * `isHeldByCurrentInstance` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun isHeldByCurrentInstance(): Boolean =
        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "isHeldByCurrentInstance DB 오류 (false 반환): lockName=$lockName, slot=$slot" }
            false
        }

    /**
     * `unlock` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val lockNameVal = this@ExposedJdbcGroupLock.lockName
        val slotVal = this@ExposedJdbcGroupLock.slot
        val tokenVal = this@ExposedJdbcGroupLock.token
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)

        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "그룹 슬롯 해제 중 DB 오류: lockName=$lockName, slot=$slot" }
        }
    }

    /**
     * `extendDetailed` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
