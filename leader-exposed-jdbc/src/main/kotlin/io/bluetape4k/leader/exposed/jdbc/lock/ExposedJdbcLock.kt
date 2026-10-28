package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.jdbc.internal.MonotonicDeadline
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration

/**
 * `ExposedJdbcLock`는 lock 이름과 owner token을 조건에 포함해 단일 JDBC lock 행을 획득·연장·반납합니다.
 *
 * @property db 단일 lock 행을 조회·변경할 Exposed JDBC 데이터베이스입니다.
 * @property lockName 영숫자로 시작하고 영숫자·`_`·`-`·`:`만 사용하는 최대 255자의 lock 키입니다.
 * @property retryStrategy 경합 또는 일시적 DB 오류 뒤 다음 시도까지의 지연을 계산합니다.
 * @property lockOwner 선택적 owner 식별자이며 null일 수 있고 저장 시 최대 255자입니다.
 * @property useDbTime true이면 lease 만료·연장 판정에 DB `CURRENT_TIMESTAMP`를 사용하고, false이면 애플리케이션 시계를 사용합니다.
 */
internal class ExposedJdbcLock internal constructor(
    private val db: Database,
    val lockName: String,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
    private val useDbTime: Boolean = false,
) {
    companion object: KLoggingChannel()

    /** 이 lock 인스턴스가 행 소유권을 확인할 때 사용하는 16자 Base58 임시 token입니다. 외부 식별자로 사용하지 않습니다. */
    val token: String = Base58.randomString(16)

    /**
     * [waitTime] 동안 retry strategy를 적용해 lease를 획득합니다.
     * 획득하면 true, timeout·취소·정상 경합이면 false를 반환하며, blocking DB 오류는 retry 후에도 false로 처리합니다.
     */
    // The retry loop must distinguish cancellation, interruption, transient database errors,
    // and the sleep interruption path to preserve the blocking API contract.
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean =
        tryLock(waitTime, leaseTime) { false }

    @Suppress("ThrowsCount", "ReturnCount")
    internal fun tryLock(
        waitTime: Duration,
        leaseTime: Duration,
        isCancelled: () -> Boolean,
    ): Boolean {
        val deadline = MonotonicDeadline.fromNow(waitTime)
        var attempt = 0

        do {
            if (isCancelled()) {
                log.debug { "락 획득 취소: lockName=$lockName" }
                return false
            }
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
                log.debug { "락 획득 성공: lockName=$lockName, token=$token" }
                return true
            }

            if (isCancelled()) {
                log.debug { "락 재시도 취소: lockName=$lockName" }
                return false
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
        val lockNameVal = this.lockName
        val lockOwnerVal = this.lockOwner
        val tokenVal = this.token

        return transaction(db) {
            val now = currentTime(useDbTime)
            val lockedUntil = now.plusMillis(leaseTime.inWholeMilliseconds)

            // Step 1: 만료된 락 갱신 시도
            val updated = LeaderLockTable.update(
                where = {
                    (LeaderLockTable.lockName eq lockNameVal) and
                            (LeaderLockTable.lockedUntil less now)
                }
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

    /** 현재 [lockName] 행이 이 인스턴스의 token을 가지고 있고 lease가 아직 유효한지 확인합니다. DB 오류는 false입니다. */
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
     * 이 인스턴스 token과 일치하는 행만 해제합니다.
     * [minLeaseTime]이 남아 있으면 삭제하지 않고 그 시간까지 lease를 유지하며, DB 오류는 로그 후 반환합니다.
     */
    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ) {
        val lockNameVal = this.lockName
        val tokenVal = this.token
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)

        try {
            val matched = transaction(db) {
                if (remaining > Duration.ZERO) {
                    val now = currentTime(useDbTime)
                    LeaderLockTable.update(
                        where = {
                            (LeaderLockTable.lockName eq lockNameVal) and
                                    (LeaderLockTable.token eq tokenVal)
                        }
                    ) {
                        it[LeaderLockTable.lockedUntil] = now.plusMillis(remaining.inWholeMilliseconds)
                    }
                } else {
                    LeaderLockTable.deleteWhere {
                        (LeaderLockTable.lockName eq lockNameVal) and
                                (LeaderLockTable.token eq tokenVal)
                    }
                }
            }
            if (matched == 0) {
                log.warn { "락 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockName=$lockName, token=$token" }
            } else {
                log.debug { "락 해제 성공: lockName=$lockName, token=$token" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "락 해제 중 DB 오류: lockName=$lockName" }
        }
    }

    /**
     * token이 일치하고 현재 lease가 만료되지 않은 행의 만료 시각을 [leaseTime]만큼 갱신합니다.
     * 갱신된 시각은 [ExtendOutcome.Extended]로, 소유하지 않거나 이미 만료된 경우 [ExtendOutcome.NotHeld]로 반환합니다.
     */
    fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val lockNameVal = this.lockName
        val tokenVal = this.token

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

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("lockName", lockName)
            .add("lockOwner", lockOwner)
            .add("useDbTime", useDbTime)
            .add("retryStrategy", retryStrategy)
            .toString()
    }
}
