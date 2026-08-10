package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
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
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Instant
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * `ExposedJdbcLock`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property db Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockName Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property retryStrategy Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockOwner Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property useDbTime Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class ExposedJdbcLock internal constructor(
    private val db: Database,
    val lockName: String,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
    private val useDbTime: Boolean = false,
) {
    companion object: KLoggingChannel()

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
    @Suppress("ThrowsCount")
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
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
                log.warn(e) { "DB 오류 (재시도 유지): lockName=$lockName, attempt=$attempt" }
                false
            }

            if (acquired) {
                log.debug { "락 획득 성공: lockName=$lockName, token=${token.take(8)}" }
                return true
            }

            val remaining = deadline.remainingMillisForSleep()
            if (remaining > 0L) {
                // sleep은 transaction 바깥에서만 호출 (HikariCP 풀 고갈 방지)
                try {
                    Thread.sleep(retryStrategy.delayMs(attempt++, remaining))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.debug { "sleep interrupted; 재시도 중단: lockName=$lockName" }
                    throw e
                }
            }
        } while (deadline.hasTimeRemaining())

        log.debug { "락 획득 실패 (타임아웃): lockName=$lockName" }
        return false
    }

    private fun tryAcquireOnce(leaseTime: Duration): Boolean {
        val lockNameVal = this@ExposedJdbcLock.lockName
        val lockOwnerVal = this@ExposedJdbcLock.lockOwner
        val tokenVal = this@ExposedJdbcLock.token

        return transaction(db) {
            val now = currentTime(useDbTime)
            val lockedUntil = now.plusMillis(leaseTime.inWholeMilliseconds)

            // Step 1: 만료된 락 갱신 시도
            val updated = LeaderLockTable.update(
                where = { (LeaderLockTable.lockName eq lockNameVal) and (LeaderLockTable.lockedUntil less now) }
            ) {
                it[LeaderLockTable.lockOwner] = lockOwnerVal
                it[LeaderLockTable.token] = tokenVal
                it[LeaderLockTable.lockedAt] = now
                it[LeaderLockTable.lockedUntil] = lockedUntil
            }

            if (updated == 0) {
                // Step 2: 신규 행 삽입 시도 (PK 충돌만 흡수 → retry; 그 외 DB 오류는 재전파)
                try {
                    LeaderLockTable.insert {
                        it[LeaderLockTable.lockName] = lockNameVal
                        it[LeaderLockTable.lockOwner] = lockOwnerVal
                        it[LeaderLockTable.token] = tokenVal
                        it[LeaderLockTable.lockedAt] = now
                        it[LeaderLockTable.lockedUntil] = lockedUntil
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ExposedSQLException) {
                    // SQLState "23xxx" = integrity constraint violation (PK 충돌 = 정상 경합)
                    if (e.sqlState.startsWith("23")) {
                        log.debug { "INSERT 실패 (PK 충돌 — 정상 경합): lockName=$lockName" }
                        return@transaction false
                    }
                    throw e  // DB 연결 오류, 권한 부족, schema drift 등은 호출자에게 전파
                }
            }

            // Step 3: token 소유 + lease 유효성 확인 (R2DBC 형제 모듈과 대칭)
            !LeaderLockTable
                .selectAll()
                .where {
                    (LeaderLockTable.lockName eq lockNameVal) and
                            (LeaderLockTable.token eq tokenVal) and
                            (LeaderLockTable.lockedUntil greater now)
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
            transaction(db) {
                val now = currentTime(useDbTime)
                !LeaderLockTable
                    .selectAll()
                    .where {
                        (LeaderLockTable.lockName eq lockName) and
                                (LeaderLockTable.token eq token) and
                                (LeaderLockTable.lockedUntil greater now)
                    }
                    .empty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "isHeldByCurrentInstance DB 오류 (false 반환): lockName=$lockName" }
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
        val lockNameVal = this@ExposedJdbcLock.lockName
        val tokenVal = this@ExposedJdbcLock.token
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)

        try {
            val matched = transaction(db) {
                if (remaining > Duration.ZERO) {
                    val now = currentTime(useDbTime)
                    LeaderLockTable.update(
                        where = { (LeaderLockTable.lockName eq lockNameVal) and (LeaderLockTable.token eq tokenVal) }
                    ) {
                        it[LeaderLockTable.lockedUntil] = now.plusMillis(remaining.inWholeMilliseconds)
                    }
                } else {
                    LeaderLockTable.deleteWhere {
                        (LeaderLockTable.lockName eq lockNameVal) and (LeaderLockTable.token eq tokenVal)
                    }
                }
            }
            if (matched == 0) {
                log.warn { "락 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockName=$lockName, token=${token.take(8)}" }
            } else {
                log.debug { "락 해제 성공: lockName=$lockName, token=${token.take(8)}" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "락 해제 중 DB 오류: lockName=$lockName" }
        }
    }

    /**
     * `extendDetailed` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val lockNameVal = this@ExposedJdbcLock.lockName
        val tokenVal = this@ExposedJdbcLock.token

        return transaction(db) {
            val now = currentTime(useDbTime)
            val newLockedUntil = now.plusMillis(leaseTime.inWholeMilliseconds)
            val updated = LeaderLockTable.update(
                where = {
                    (LeaderLockTable.lockName eq lockNameVal) and
                        (LeaderLockTable.token eq tokenVal) and
                        (LeaderLockTable.lockedUntil greater now)  // R6: expired row revival 차단
                }
            ) {
                it[LeaderLockTable.lockedUntil] = newLockedUntil
            }
            if (updated > 0) {
                ExtendOutcome.Extended(newLockedUntil)
            } else {
                log.debug { "Exposed JDBC extend 실패 (NotHeld): lockName=$lockName" }
                ExtendOutcome.NotHeld
            }
        }
    }

}

// Keep the 0.4.x file-facade ABI while the shared current-time implementation lives in
// ExposedJdbcCurrentTime.kt. These private declarations intentionally retain compiler-generated
// accessors used by already-compiled callers.
@Suppress("unused")
private fun JdbcTransaction.currentTime(): Instant = dbCurrentTimestamp()

private fun JdbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { resultSet ->
        if (!resultSet.next()) {
            error("SELECT CURRENT_TIMESTAMP returned no rows")
        }
        resultSet.getObject(1).toInstant()
    } ?: error("SELECT CURRENT_TIMESTAMP returned no result set")

private fun Any?.toInstant(): Instant =
    when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        is OffsetDateTime -> toInstant()
        is ZonedDateTime -> toInstant()
        is LocalDateTime -> toInstant(ZoneOffset.UTC)
        else -> error("Unsupported CURRENT_TIMESTAMP value: ${this?.javaClass?.name ?: "null"}")
    }
