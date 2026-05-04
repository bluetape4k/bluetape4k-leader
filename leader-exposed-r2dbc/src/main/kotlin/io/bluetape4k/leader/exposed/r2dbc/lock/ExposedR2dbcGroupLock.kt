package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Exposed R2DBC 기반 그룹 락 (복합 PK 슬롯 기반).
 *
 * [LeaderGroupLockTable]의 `(lockName, slot)` 복합 PK를 이용하여
 * 최대 N개의 동시 리더를 허용하는 세마포어를 구현합니다.
 *
 * ## 동작 방식
 * [ExposedR2dbcLock]과 동일한 UPDATE+insertIgnore+SELECT 패턴을 사용하며,
 * `slot` 번호가 추가된 복합 PK를 사용합니다.
 *
 * ## DB 오류 vs 정상 경합 실패 구분
 * - **정상 경합 실패** (다른 노드가 슬롯 점유 중): `tryAcquireOnce()`가 `false` 반환
 * - **DB 오류** (연결 끊김, 타임아웃 등): `tryLock()`이 `null` 반환 → caller가 슬롯 순회를 중단할 수 있음 (JDBC 형제와 동일 contract)
 *
 * @param db Exposed [R2dbcDatabase] 인스턴스
 * @param lockName 그룹 락 식별자
 * @param slot 슬롯 번호 (0-based)
 * @param retryStrategy 재시도 대기 전략
 * @param lockOwner 락 보유자 식별자 (선택)
 */
internal class ExposedR2dbcGroupLock internal constructor(
    private val db: R2dbcDatabase,
    val lockName: String,
    val slot: Int,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
) {
    init {
        require(slot >= 0) { "slot must be >= 0: $slot" }
    }

    companion object : KLoggingChannel()

    /** 인스턴스별 고유 fencing token. */
    val token: String = UUID.randomUUID().toString()

    /**
     * [waitTime] 내에 슬롯 락 획득을 시도합니다.
     *
     * @return 락 획득 성공 시 `true`, 경합 실패(타임아웃) 시 `false`, DB 오류 시 `null`
     */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean? {
        val deadline = System.currentTimeMillis() + waitTime.toMillis().coerceAtLeast(0L)
        var attempt = 0

        do {
            currentCoroutineContext().ensureActive()

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
                // delay는 suspendTransaction 바깥에서 호출 (R2DBC 커넥션 풀 점유 방지)
                delay(retryStrategy.delayMs(attempt++, remaining))
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "그룹 슬롯 락 획득 실패 (타임아웃): lockName=$lockName, slot=$slot" }
        return false
    }

    private suspend fun tryAcquireOnce(leaseTime: Duration): Boolean {
        val lockNameVal = this@ExposedR2dbcGroupLock.lockName
        val slotVal = this@ExposedR2dbcGroupLock.slot
        val lockOwnerVal = this@ExposedR2dbcGroupLock.lockOwner
        val tokenVal = this@ExposedR2dbcGroupLock.token

        return suspendTransaction(db) {
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
                // Step 2: 신규 슬롯 행 삽입 시도
                // PostgreSQL: INSERT ... ON CONFLICT DO NOTHING
                // MySQL: INSERT IGNORE INTO
                // H2 MySQL mode: INSERT IGNORE INTO
                // H2 default mode: UnsupportedOperationException (Kotlin 예외) → false 반환
                val inserted = try {
                    LeaderGroupLockTable.insertIgnore {
                        it[LeaderGroupLockTable.lockName] = lockNameVal
                        it[LeaderGroupLockTable.slot] = slotVal
                        it[LeaderGroupLockTable.lockOwner] = lockOwnerVal
                        it[LeaderGroupLockTable.token] = tokenVal
                        it[LeaderGroupLockTable.lockedAt] = now
                        it[LeaderGroupLockTable.lockedUntil] = lockedUntil
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.debug { "insertIgnore 실패: lockName=$lockName, slot=$slot, error=${e.message}" }
                    false
                }

                if (!inserted) return@suspendTransaction false

                // insertIgnore가 경합 행 때문에 무시됐을 수 있으므로 토큰 소유 여부 확인
                return@suspendTransaction !LeaderGroupLockTable
                    .selectAll()
                    .where {
                        (LeaderGroupLockTable.lockName eq lockNameVal) and
                            (LeaderGroupLockTable.slot eq slotVal) and
                            (LeaderGroupLockTable.token eq tokenVal) and
                            (LeaderGroupLockTable.lockedUntil greater now)
                    }
                    .empty()
            }

            // UPDATE가 성공한 경우 — 이미 토큰이 행에 기록됨
            true
        }
    }

    /**
     * 현재 인스턴스(token)가 유효한 슬롯 락을 보유하고 있는지 확인합니다.
     *
     * 리스 만료 후 타 인스턴스가 재획득한 경우 `false`를 반환합니다.
     */
    suspend fun isHeldByCurrentInstance(): Boolean = runCatching {
        suspendTransaction(db) {
            val now = Instant.now()
            !LeaderGroupLockTable
                .selectAll()
                .where {
                    (LeaderGroupLockTable.lockName eq lockName) and
                        (LeaderGroupLockTable.slot eq slot) and
                        (LeaderGroupLockTable.token eq token) and
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
    suspend fun unlock() {
        val lockNameVal = this@ExposedR2dbcGroupLock.lockName
        val slotVal = this@ExposedR2dbcGroupLock.slot
        val tokenVal = this@ExposedR2dbcGroupLock.token

        runCatching {
            val deleted = suspendTransaction(db) {
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
