package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.r2dbc.internal.MonotonicDeadline
import io.bluetape4k.leader.exposed.r2dbc.internal.classifyAcquisitionFailure
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import kotlin.time.Duration

/**
 * `ExposedR2dbcLock`는 lock 이름과 owner token을 조건에 포함해 단일 R2DBC lock 행을 suspend 방식으로 관리합니다.
 *
 * @property db 단일 lock 행을 조회·변경할 Exposed R2DBC 데이터베이스입니다.
 * @property lockName 영숫자로 시작하고 영숫자·`_`·`-`·`:`만 사용하는 최대 255자의 lock 키입니다.
 * @property retryStrategy 경합 또는 transient DB 오류 뒤 다음 시도까지의 지연을 계산합니다.
 * @property lockOwner 선택적 owner 식별자이며 null일 수 있고 저장 시 최대 255자입니다.
 * @property useDbTime true이면 lease 판정에 DB `CURRENT_TIMESTAMP`를 사용하고, false이면 애플리케이션 시계를 사용합니다.
 */
internal class ExposedR2dbcLock internal constructor(
    private val db: R2dbcDatabase,
    val lockName: String,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
    private val useDbTime: Boolean = false,
) {
    companion object: KLoggingChannel()

    /** 이 lock 인스턴스가 행 소유권을 확인할 때 사용하는 8자 Base58 임시 token입니다. 외부 식별자로 사용하지 않습니다. */
    val token: String = Base58.randomString(length = 8)

    /**
     * [waitTime] 동안 suspend retry를 적용해 lease를 획득합니다.
     * 획득하면 true, timeout·경합·재시도하지 않는 DB 오류면 false를 반환합니다. coroutine 취소는 전파됩니다.
     */
    @Suppress("TooGenericExceptionCaught") // 드라이버의 예외를 기존 분류기로 구분합니다.
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        val deadline = MonotonicDeadline.fromNow(waitTime)
        var attempt = 0

        do {
            currentCoroutineContext().ensureActive()

            val acquired = try {
                tryAcquireOnce(leaseTime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = classifyAcquisitionFailure(e)
                log.warn(e) { "DB 오류: kind=$kind, lockName=$lockName, attempt=$attempt" }
                if (kind != BackendErrorKind.TRANSIENT) break
                false
            }

            if (acquired) {
                log.debug { "락 획득 성공: lockName=$lockName, token=${token.take(8)}" }
                return true
            }

            val remaining = deadline.remainingMillisForSleep()
            if (remaining > 0L) {
                // delay는 suspendTransaction 바깥에서 호출 (R2DBC 커넥션 풀 점유 방지)
                delay(timeMillis = retryStrategy.delayMs(attempt++, remaining))
            }
        } while (deadline.hasTimeRemaining())

        log.debug { "락 획득 실패: lockName=$lockName" }
        return false
    }

    private suspend fun tryAcquireOnce(leaseTime: Duration): Boolean {
        val lockNameVal = this@ExposedR2dbcLock.lockName
        val lockOwnerVal = this@ExposedR2dbcLock.lockOwner
        val tokenVal = this@ExposedR2dbcLock.token

        return try {
            suspendTransaction(db) {
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
        } catch (e: CancellationException) {
            cleanupAcquisitionAfterCancellation(lockNameVal, tokenVal, e)
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun cleanupAcquisitionAfterCancellation(
        lockNameVal: String,
        tokenVal: String,
        cancellation: CancellationException,
    ) {
        try {
            withContext(NonCancellable) {
                suspendTransaction(db) {
                    LeaderLockTable.deleteWhere {
                        (LeaderLockTable.lockName eq lockNameVal) and (LeaderLockTable.token eq tokenVal)
                    }
                }
            }
        } catch (cleanupFailure: Exception) {
            cancellation.addSuppressedSafely(cleanupFailure)
            log.warn(cleanupFailure) {
                "취소된 R2DBC 락 획득 보상 정리 실패: " +
                        "lockName=$lockNameVal, token=${tokenVal.take(8)}, " +
                        "failure=${cleanupFailure::class.simpleName}"
            }
        }
    }

    private fun Throwable.addSuppressedSafely(cause: Throwable) {
        if (cause !== this && suppressed.none { it === cause }) {
            addSuppressed(cause)
        }
    }

    /** 현재 [lockName] 행이 이 인스턴스 token을 가지고 있고 lease가 아직 유효한지 확인합니다. DB 오류는 false입니다. */
    suspend fun isHeldByCurrentInstance(): Boolean =
        runR2dbcLockOperationPreservingCancellation(
            onFailure = { e ->
                log.warn(e) { "isHeldByCurrentInstance DB 오류 (false 반환): lockName=$lockName" }
                false
            },
        ) {
            suspendTransaction(db) {
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
        }

    /**
     * 이 인스턴스 token과 일치하는 행만 해제합니다.
     * [minLeaseTime]이 남아 있으면 삭제하지 않고 그 시간까지 lease를 유지하며 DB 오류는 로그로 처리합니다.
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
        }
    }

    /**
     * token이 일치하고 현재 lease가 만료되지 않은 행의 만료 시각을 [leaseTime]만큼 갱신합니다.
     * 갱신된 시각은 [ExtendOutcome.Extended]로, 소유하지 않거나 이미 만료된 경우 [ExtendOutcome.NotHeld]로 반환합니다.
     */
    suspend fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val lockNameVal = this@ExposedR2dbcLock.lockName
        val tokenVal = this@ExposedR2dbcLock.token

        return suspendTransaction(db) {
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
                log.debug { "Exposed R2DBC extend 실패 (NotHeld): lockName=$lockName" }
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

/**
 * 1.0.0에서 노출된 JVM facade를 유지하는 호환용 cancellation 보존 연산 함수입니다.
 */
@Suppress("unused")
@JvmName("runR2dbcLockOperationPreservingCancellation")
public suspend fun <T> legacyRunR2dbcLockOperationPreservingCancellation(
    onFailure: (Exception) -> T,
    operation: suspend () -> T,
): T = runR2dbcLockOperationPreservingCancellation(onFailure, operation)

// Keep the 1.0.0 file-facade accessors while current-time behavior lives in
// ExposedR2dbcCurrentTime.kt. These private declarations are retained solely for
// already-compiled callers that resolve the generated access$currentTime methods.
@Suppress("unused")
private suspend fun R2dbcTransaction.currentTime(): Instant = currentTime(useDbTime = true)

@Suppress("unused")
private suspend fun R2dbcTransaction.dbCurrentTimestamp(): Instant = currentTime(useDbTime = true)

//// Keep the 0.4.x file-facade ABI while the shared current-time implementation lives in
//// ExposedR2dbcCurrentTime.kt. This private declaration intentionally retains compiler-generated
//// accessors used by already-compiled callers.
//@Suppress("unused")
//private suspend fun R2dbcTransaction.currentTime(): Instant = dbCurrentTimestamp()
//
//private suspend fun R2dbcTransaction.dbCurrentTimestamp(): Instant =
//    exec("SELECT CURRENT_TIMESTAMP") { row -> row.get(0).toInstant() }
//        ?.firstOrNull()
//        ?: error("SELECT CURRENT_TIMESTAMP returned no rows")
//
//private fun Any?.toInstant(): Instant =
//    when (this) {
//        is Instant -> this
//        is OffsetDateTime -> toInstant()
//        is ZonedDateTime -> toInstant()
//        is LocalDateTime -> toInstant(ZoneOffset.UTC)
//        else -> error("Unsupported CURRENT_TIMESTAMP value: ${this?.javaClass?.name ?: "null"}")
//    }
//
