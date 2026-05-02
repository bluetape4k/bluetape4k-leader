package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.leader.exposed.jdbc.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
    companion object : KLogging()

    /** 인스턴스별 고유 fencing token. */
    val token: String = UUID.randomUUID().toString()

    /**
     * [waitTime] 내에 슬롯 락 획득을 시도합니다.
     *
     * @return 락 획득 성공 시 `true`, 타임아웃 또는 오류 시 `false`
     */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.toMillis().coerceAtLeast(0L)
        var attempt = 0

        do {
            val acquired = runCatching {
                tryAcquireOnce(leaseTime)
            }.getOrElse { e ->
                log.warn(e) { "DB 오류 (재시도 유지): lockName=$lockName, slot=$slot, attempt=$attempt" }
                false
            }

            if (acquired) {
                log.debug { "그룹 슬롯 락 획득 성공: lockName=$lockName, slot=$slot, token=${token.take(8)}" }
                return true
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0L) {
                Thread.sleep(retryStrategy.delayMs(attempt++, remaining))
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
            val lockedUntil = now.plusMillis(leaseTime.toMillis())

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
                runCatching {
                    LeaderGroupLockTable.insert {
                        it[LeaderGroupLockTable.lockName] = lockNameVal
                        it[LeaderGroupLockTable.slot] = slotVal
                        it[LeaderGroupLockTable.lockOwner] = lockOwnerVal
                        it[LeaderGroupLockTable.token] = tokenVal
                        it[LeaderGroupLockTable.lockedAt] = now
                        it[LeaderGroupLockTable.lockedUntil] = lockedUntil
                    }
                }.onFailure { e ->
                    log.debug { "INSERT 실패 (PK 충돌 예상 또는 DB 오류): lockName=$lockName, slot=$slot, error=${e.message}" }
                    return@transaction false
                }
            }

            // SELECT으로 token 소유 확인 (복합 PK 조건)
            !LeaderGroupLockTable
                .selectAll()
                .where {
                    (LeaderGroupLockTable.lockName eq lockNameVal) and
                        (LeaderGroupLockTable.slot eq slotVal) and
                        (LeaderGroupLockTable.token eq tokenVal)
                }
                .empty()
        }
    }

    /**
     * 현재 인스턴스가 보유한 슬롯 락을 해제합니다.
     *
     * 토큰 불일치 시 경고 로그만 남기며 다른 소유자의 슬롯을 삭제하지 않습니다.
     */
    fun unlock() {
        val lockNameVal = this@ExposedJdbcGroupLock.lockName
        val slotVal = this@ExposedJdbcGroupLock.slot
        val tokenVal = this@ExposedJdbcGroupLock.token

        runCatching {
            val deleted = transaction(db) {
                LeaderGroupLockTable.deleteWhere {
                    (LeaderGroupLockTable.lockName eq lockNameVal) and
                        (LeaderGroupLockTable.slot eq slotVal) and
                        (LeaderGroupLockTable.token eq tokenVal)
                }
            }
            if (deleted == 0) {
                log.warn { "그룹 슬롯 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockName=$lockName, slot=$slot" }
            } else {
                log.debug { "그룹 슬롯 해제 성공: lockName=$lockName, slot=$slot" }
            }
        }.onFailure { e ->
            log.warn(e) { "그룹 슬롯 해제 중 DB 오류: lockName=$lockName, slot=$slot" }
        }
    }
}
