package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.leader.exposed.jdbc.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
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
 * Exposed JDBC UPDATE+INSERT+SELECT 패턴 기반 토큰 분산 락.
 *
 * ## 동작 방식
 * 단일 트랜잭션 내에서:
 * 1. **UPDATE**: `lockedUntil < NOW()` 조건으로 만료 락 갱신 시도
 * 2. **INSERT**: UPDATE 미성공 시 신규 행 삽입 시도 (PK 충돌 → runCatching)
 * 3. **SELECT**: 현재 인스턴스 token 소유 여부 확인
 *
 * ## 주의사항
 * - `Thread.sleep()`은 반드시 `transaction {}` **바깥**에서 호출 (HikariCP 풀 고갈 방지)
 * - [token]은 인스턴스 생성 시 1회 발급 — unlock 시 zombie 방지
 * - [tryLock]은 절대 예외를 throw하지 않음; DB 오류 → `false` 반환 + warn 로그
 *
 * @param db Exposed [Database] 인스턴스
 * @param lockName 락 식별자 (PK)
 * @param retryStrategy 재시도 대기 전략
 * @param lockOwner 락 보유자 식별자 (선택)
 */
internal class ExposedJdbcLock internal constructor(
    private val db: Database,
    val lockName: String,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
) {
    companion object : KLogging()

    /** 인스턴스별 고유 fencing token. unlock 시 zombie 방지에 사용됩니다. */
    val token: String = UUID.randomUUID().toString()

    /**
     * [waitTime] 내에 락 획득을 시도합니다.
     *
     * @param waitTime 락 획득 최대 대기 시간
     * @param leaseTime 락 보유(TTL) 최대 시간
     * @return 락 획득 성공 시 `true`, 타임아웃 또는 오류 시 `false`
     */
    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.toMillis()
        var attempt = 0

        do {
            val acquired = runCatching {
                tryAcquireOnce(leaseTime)
            }.getOrElse { e ->
                log.warn(e) { "DB 오류로 락 획득 실패: lockName=$lockName" }
                return false
            }

            if (acquired) {
                log.debug { "락 획득 성공: lockName=$lockName, token=$token" }
                return true
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0L) {
                // sleep은 transaction 바깥에서만 호출 (HikariCP 풀 고갈 방지)
                Thread.sleep(retryStrategy.delayMs(attempt++, remaining))
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "락 획득 실패 (타임아웃): lockName=$lockName" }
        return false
    }

    private fun tryAcquireOnce(leaseTime: Duration): Boolean {
        val lockNameVal = this@ExposedJdbcLock.lockName
        val lockOwnerVal = this@ExposedJdbcLock.lockOwner
        val tokenVal = this@ExposedJdbcLock.token

        return transaction(db) {
            val now = Instant.now()
            val lockedUntil = now.plusMillis(leaseTime.toMillis())

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
                // Step 2: 신규 행 삽입 시도 (PK 충돌 시 runCatching으로 흡수 → retry)
                runCatching {
                    LeaderLockTable.insert {
                        it[LeaderLockTable.lockName] = lockNameVal
                        it[LeaderLockTable.lockOwner] = lockOwnerVal
                        it[LeaderLockTable.token] = tokenVal
                        it[LeaderLockTable.lockedAt] = now
                        it[LeaderLockTable.lockedUntil] = lockedUntil
                    }
                }.onFailure {
                    return@transaction false
                }
            }

            // Step 3: token 소유 확인
            LeaderLockTable
                .selectAll()
                .where { (LeaderLockTable.lockName eq lockNameVal) and (LeaderLockTable.token eq tokenVal) }
                .count() > 0
        }
    }

    /**
     * 현재 인스턴스(token)가 유효한 락을 보유하고 있는지 확인합니다.
     *
     * 리스 만료 후 타 인스턴스가 재획득한 경우 `false`를 반환합니다.
     */
    fun isHeldByCurrentInstance(): Boolean = runCatching {
        transaction(db) {
            val now = Instant.now()
            LeaderLockTable
                .selectAll()
                .where {
                    (LeaderLockTable.lockName eq lockName) and
                        (LeaderLockTable.token eq token) and
                        (LeaderLockTable.lockedUntil greater now)
                }
                .count() > 0
        }
    }.getOrElse { false }

    /**
     * 현재 인스턴스가 보유한 락을 해제합니다.
     *
     * 토큰 불일치(리스 만료로 인한 타 인스턴스 재획득 등) 시 경고 로그만 남깁니다.
     */
    fun unlock() {
        val lockNameVal = this@ExposedJdbcLock.lockName
        val tokenVal = this@ExposedJdbcLock.token

        runCatching {
            val deleted = transaction(db) {
                LeaderLockTable.deleteWhere {
                    (LeaderLockTable.lockName eq lockNameVal) and (LeaderLockTable.token eq tokenVal)
                }
            }
            if (deleted == 0) {
                log.warn { "락 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockName=$lockName, token=$token" }
            } else {
                log.debug { "락 해제 성공: lockName=$lockName, token=$token" }
            }
        }.onFailure { e ->
            log.warn(e) { "락 해제 중 DB 오류: lockName=$lockName" }
        }
    }
}
