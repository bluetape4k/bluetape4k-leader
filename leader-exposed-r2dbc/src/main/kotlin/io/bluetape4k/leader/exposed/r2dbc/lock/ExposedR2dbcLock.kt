package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
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
 * Exposed R2DBC UPDATE+insertIgnore+SELECT 패턴 기반 suspend 토큰 분산 락.
 *
 * ## 동작 방식
 * 단일 트랜잭션 내에서:
 * 1. **UPDATE**: `lockedUntil < NOW()` 조건으로 만료 락 갱신 시도
 * 2. **insertIgnore**: UPDATE 미성공 시 `INSERT ... ON CONFLICT DO NOTHING`(PostgreSQL),
 *    `INSERT IGNORE`(MySQL/H2-MySQL) 으로 안전하게 신규 행 삽입 시도
 * 3. **SELECT**: 현재 인스턴스 token 소유 여부 확인
 *
 * ## 주의사항
 * - `delay()`는 반드시 `suspendTransaction {}` **바깥**에서 호출 (R2DBC 커넥션 풀 점유 방지)
 * - [token]은 인스턴스 생성 시 1회 발급 — unlock 시 zombie 방지
 * - H2 in-memory 사용 시 `MODE=MySQL`을 R2DBC URL에 설정해야 `insertIgnore`가 지원됩니다
 *   (예: `r2dbc:h2:mem:///test;MODE=MySQL;DB_CLOSE_DELAY=-1`)
 *
 * @param db Exposed [R2dbcDatabase] 인스턴스
 * @param lockName 락 식별자 (PK)
 * @param retryStrategy 재시도 대기 전략
 * @param lockOwner 락 보유자 식별자 (선택)
 */
internal class ExposedR2dbcLock internal constructor(
    private val db: R2dbcDatabase,
    val lockName: String,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
) {
    companion object: KLoggingChannel()

    /** 인스턴스별 고유 fencing token. unlock 시 zombie 방지에 사용됩니다. */
    val token: String = Base58.randomString(length = 8)

    /**
     * [waitTime] 내에 락 획득을 시도합니다.
     *
     * @param waitTime 락 획득 최대 대기 시간
     * @param leaseTime 락 보유(TTL) 최대 시간
     * @return 락 획득 성공 시 `true`, 타임아웃 또는 오류 시 `false`
     */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = System.currentTimeMillis() + waitTime.inWholeMilliseconds.coerceAtLeast(0L)
        var attempt = 0

        do {
            currentCoroutineContext().ensureActive()

            val acquired = try {
                tryAcquireOnce(leaseTime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn(e) { "DB 오류 (재시도 유지): lockName=$lockName, attempt=$attempt" }
                false
            }

            if (acquired) {
                log.debug { "락 획득 성공: lockName=$lockName, token=${token.take(8)}" }
                return true
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0L) {
                // delay는 suspendTransaction 바깥에서 호출 (R2DBC 커넥션 풀 점유 방지)
                delay(timeMillis = retryStrategy.delayMs(attempt++, remaining))
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "락 획득 실패 (타임아웃): lockName=$lockName" }
        return false
    }

    private suspend fun tryAcquireOnce(leaseTime: Duration): Boolean {
        val lockNameVal = this@ExposedR2dbcLock.lockName
        val lockOwnerVal = this@ExposedR2dbcLock.lockOwner
        val tokenVal = this@ExposedR2dbcLock.token

        return suspendTransaction(db) {
            val now = Instant.now()
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
                // Step 2: 신규 행 삽입 시도
                // PostgreSQL: INSERT ... ON CONFLICT DO NOTHING (안전, 예외 없음)
                // MySQL: INSERT IGNORE INTO (안전, 예외 없음)
                // H2 MySQL mode: INSERT IGNORE INTO (안전, 예외 없음)
                // H2 default mode: UnsupportedOperationException (Kotlin 예외, DB 중단 아님) → false 반환
                val inserted = try {
                    LeaderLockTable.insertIgnore {
                        it[LeaderLockTable.lockName] = lockNameVal
                        it[LeaderLockTable.lockOwner] = lockOwnerVal
                        it[LeaderLockTable.token] = tokenVal
                        it[LeaderLockTable.lockedAt] = now
                        it[LeaderLockTable.lockedUntil] = lockedUntil
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsupportedByDialectException) {
                    // H2 default mode: insertIgnore 미지원 → 경합 실패로 처리
                    log.debug { "insertIgnore 미지원 dialect (H2 default mode?): lockName=$lockName" }
                    false
                }

                if (!inserted) return@suspendTransaction false

                // insertIgnore가 경합 행 때문에 무시됐을 수 있으므로 토큰 소유 여부 확인
                return@suspendTransaction !LeaderLockTable
                    .selectAll()
                    .where {
                        (LeaderLockTable.lockName eq lockNameVal) and
                                (LeaderLockTable.token eq tokenVal) and
                                (LeaderLockTable.lockedUntil greater now)
                    }
                    .empty()
            }

            // UPDATE가 성공한 경우 — 이미 토큰이 행에 기록됨
            true
        }
    }

    /**
     * 현재 인스턴스(token)가 유효한 락을 보유하고 있는지 확인합니다.
     *
     * 리스 만료 후 타 인스턴스가 재획득한 경우 `false`를 반환합니다.
     */
    suspend fun isHeldByCurrentInstance(): Boolean = runCatching {
        suspendTransaction(db) {
            val now = Instant.now()
            !LeaderLockTable
                .selectAll()
                .where {
                    (LeaderLockTable.lockName eq lockName) and
                            (LeaderLockTable.token eq token) and
                            (LeaderLockTable.lockedUntil greater now)
                }
                .empty()
        }
    }.getOrElse { e ->
        log.warn(e) { "isHeldByCurrentInstance DB 오류 (false 반환): lockName=$lockName" }
        false
    }

    /**
     * 현재 인스턴스가 보유한 락을 해제합니다.
     *
     * 토큰 불일치(리스 만료로 인한 타 인스턴스 재획득 등) 시 경고 로그만 남깁니다.
     */
    suspend fun unlock() {
        val lockNameVal = this@ExposedR2dbcLock.lockName
        val tokenVal = this@ExposedR2dbcLock.token

        runCatching {
            val deleted = suspendTransaction(db) {
                LeaderLockTable.deleteWhere {
                    (LeaderLockTable.lockName eq lockNameVal) and (LeaderLockTable.token eq tokenVal)
                }
            }
            if (deleted == 0) {
                log.warn { "락 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockName=$lockName, token=${token.take(8)}" }
            } else {
                log.debug { "락 해제 성공: lockName=$lockName, token=${token.take(8)}" }
            }
        }.onFailure { e ->
            log.warn(e) { "락 해제 중 DB 오류: lockName=$lockName" }
        }
    }
}
