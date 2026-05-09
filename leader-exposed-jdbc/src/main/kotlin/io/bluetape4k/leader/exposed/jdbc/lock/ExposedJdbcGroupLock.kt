package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.codec.Base58
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
 * Exposed JDBC 기반 그룹 락 (복합 PK 슬롯 기반).
 *
 * [LeaderGroupLockTable]의 `(lockName, slot)` 복합 PK를 이용하여
 * 최대 N개의 동시 리더를 허용하는 세마포어를 구현합니다.
 *
 * ## 동작 방식
 * [ExposedJdbcLock]과 동일한 UPDATE+INSERT+SELECT 패턴을 사용하며,
 * `slot` 번호가 추가된 복합 PK를 사용합니다.
 *
 * @param db Exposed [Database] 인스턴스
 * @param lockName 그룹 락 식별자
 * @param slot 슬롯 번호 (0-based)
 * @param retryStrategy 재시도 대기 전략
 * @param lockOwner 락 보유자 식별자 (선택)
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

    /** 인스턴스별 고유 fencing token. */
    val token: String = Base58.randomString(8)

    /**
     * [waitTime] 내에 슬롯 락 획득을 시도합니다.
     *
     * @return 락 획득 성공 시 `true`, 경합 실패(타임아웃) 시 `false`, DB 오류 시 `null`
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
     * 현재 인스턴스(token)가 유효한 슬롯 락을 보유하고 있는지 확인합니다.
     *
     * 리스 만료 후 타 인스턴스가 재획득한 경우 `false`를 반환합니다.
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
     * 현재 인스턴스가 보유한 슬롯 락을 해제합니다.
     *
     * 토큰 불일치 시 경고 로그만 남기며 다른 소유자의 슬롯을 삭제하지 않습니다.
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
}
