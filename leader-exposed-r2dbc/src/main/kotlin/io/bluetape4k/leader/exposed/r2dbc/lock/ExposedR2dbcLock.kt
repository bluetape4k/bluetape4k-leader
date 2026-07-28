package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.remainingMinLeaseTime
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
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * `ExposedR2dbcLock`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property db Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockName Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property retryStrategy Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockOwner Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property useDbTime Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class ExposedR2dbcLock internal constructor(
    private val db: R2dbcDatabase,
    val lockName: String,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
    private val useDbTime: Boolean = false,
) {
    companion object: KLoggingChannel()

    /**
     * `token` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val token: String = Base58.randomString(length = 8)

    /**
     * `tryLock` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
            val now = currentTime()
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
     * `isHeldByCurrentInstance` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun isHeldByCurrentInstance(): Boolean =
        runR2dbcLockOperationPreservingCancellation(
            onFailure = { e ->
                log.warn(e) { "isHeldByCurrentInstance DB 오류 (false 반환): lockName=$lockName" }
                false
            },
        ) {
            suspendTransaction(db) {
                val now = currentTime()
                !LeaderLockTable
                    .selectAll()
                    .where {
                        (LeaderLockTable.lockName eq lockName) and
                                (LeaderLockTable.token eq token) and
                                (LeaderLockTable.lockedUntil greater now)
                    }
                    .empty()
            }
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
        val lockNameVal = this@ExposedR2dbcLock.lockName
        val tokenVal = this@ExposedR2dbcLock.token
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)

        runR2dbcLockOperationPreservingCancellation(
            onFailure = { e ->
                log.warn(e) { "락 해제 중 DB 오류: lockName=$lockName" }
            },
        ) {
            val matched = suspendTransaction(db) {
                if (remaining > Duration.ZERO) {
                    val now = currentTime()
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
        }
    }

    /**
     * `extendDetailed` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val lockNameVal = this@ExposedR2dbcLock.lockName
        val tokenVal = this@ExposedR2dbcLock.token

        return suspendTransaction(db) {
            val now = currentTime()
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
                log.debug { "Exposed R2DBC extend 실패 (NotHeld): lockName=$lockName" }
                ExtendOutcome.NotHeld
            }
        }
    }

    private suspend fun R2dbcTransaction.currentTime(): Instant =
        if (useDbTime) dbCurrentTimestamp() else Instant.now()
}

internal suspend fun <T> runR2dbcLockOperationPreservingCancellation(
    onFailure: (Exception) -> T,
    operation: suspend () -> T,
): T =
    try {
        operation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e)
    }

private suspend fun R2dbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { row -> row.get(0).toInstant() }
        ?.firstOrNull()
        ?: error("SELECT CURRENT_TIMESTAMP returned no rows")

private fun Any?.toInstant(): Instant =
    when (this) {
        is Instant -> this
        is OffsetDateTime -> toInstant()
        is ZonedDateTime -> toInstant()
        is LocalDateTime -> toInstant(ZoneOffset.UTC)
        else -> error("Unsupported CURRENT_TIMESTAMP value: ${this?.javaClass?.name ?: "null"}")
    }
