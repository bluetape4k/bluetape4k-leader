package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.r2dbc.AbstractExposedR2dbcLeaderTest
import io.bluetape4k.leader.exposed.r2dbc.TestR2dbcDB
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ExposedR2dbcGroupLockTest: AbstractExposedR2dbcLeaderTest() {

    companion object: KLoggingChannel()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 빈 DB에서 슬롯0 첫 번째 락 획득이 성공한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())

        val acquired = lock.tryLock(2.seconds, 10.seconds)

        acquired.shouldNotBeNull().shouldBeTrue()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 서로 다른 슬롯은 독립적으로 획득된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val lock0 = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        val lock1 = ExposedR2dbcGroupLock(db, lockName, slot = 1, RetryStrategy.Jitter())

        val acquired0 = lock0.tryLock(2.seconds, 10.seconds)
        val acquired1 = lock1.tryLock(2.seconds, 10.seconds)

        acquired0.shouldNotBeNull().shouldBeTrue()
        acquired1.shouldNotBeNull().shouldBeTrue()
        lock0.unlock()
        lock1.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 동일 슬롯에 이미 활성 락이 있으면 경합자가 실패한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        holder.tryLock(1.seconds, 30.seconds)

        val contender = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 10L))
        val acquired = contender.tryLock(100.milliseconds, 5.seconds)

        acquired.shouldNotBeNull().shouldBeFalse()
        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 연산이 경합과 미소유 결과에서도 가용 상태를 회복한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedR2dbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )
        holder.tryLock(1.seconds, 30.seconds).shouldNotBeNull().shouldBeTrue()

        val availabilitySignals = mutableListOf<Boolean>()
        val contender = ExposedR2dbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
            useDbTime = true,
            onAvailabilityChanged = availabilitySignals::add,
        )

        contender.tryLock(100.milliseconds, 5.seconds).shouldNotBeNull().shouldBeFalse()
        availabilitySignals.last() shouldBeEqualTo true

        availabilitySignals.clear()
        contender.isHeldByCurrentInstance().shouldBeFalse()
        availabilitySignals shouldBeEqualTo listOf(true)

        availabilitySignals.clear()
        contender.extendDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
        availabilitySignals shouldBeEqualTo listOf(true)

        availabilitySignals.clear()
        contender.unlockAndReport() shouldBeEqualTo ExposedR2dbcUnlockOutcome.NOT_HELD
        // Delete-only unlock은 CURRENT_TIMESTAMP가 정상이라는 증거를 만들지 않으므로
        // fail-closed DB-time admission 경로를 다시 열면 안 됩니다.
        availabilitySignals shouldBeEqualTo emptyList()

        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 오류가 각 lock 연산에서 unavailable 신호를 발생시킨다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)

        suspend fun dropSchema() {
            suspendTransaction(db) { exec("DROP TABLE $GROUP_LOCK_TABLE_NAME") }
            ExposedR2dbcSchemaInitializer.resetFor(db)
        }

        suspend fun restoreSchema() {
            ExposedR2dbcSchemaInitializer.ensureSchema(db)
            cleanTables(db)
        }

        suspend fun newLock(signals: MutableList<Boolean>): ExposedR2dbcGroupLock {
            return ExposedR2dbcGroupLock(
                db,
                randomName(),
                slot = 0,
                retryStrategy = RetryStrategy.Jitter(),
                useDbTime = true,
                onAvailabilityChanged = signals::add,
            )
        }

        val tryLockSignals = mutableListOf<Boolean>()
        dropSchema()
        val failedTryLock = newLock(tryLockSignals)
        failedTryLock.tryLock(Duration.ZERO, 1.seconds).shouldBeNull()
        tryLockSignals shouldBeEqualTo listOf(false)
        restoreSchema()
        failedTryLock.unlockAndReport() shouldBeEqualTo ExposedR2dbcUnlockOutcome.NOT_HELD
        // CURRENT_TIMESTAMP 없이 성공한 DELETE는 DB-time admission을 다시 열면 안 됩니다.
        tryLockSignals shouldBeEqualTo listOf(false)

        val heldSignals = mutableListOf<Boolean>()
        dropSchema()
        newLock(heldSignals).isHeldByCurrentInstance().shouldBeFalse()
        heldSignals shouldBeEqualTo listOf(false)
        restoreSchema()

        val unlockSignals = mutableListOf<Boolean>()
        dropSchema()
        newLock(unlockSignals).unlockAndReport() shouldBeEqualTo ExposedR2dbcUnlockOutcome.FAILED
        unlockSignals shouldBeEqualTo listOf(false)
        restoreSchema()

        val extendSignals = mutableListOf<Boolean>()
        dropSchema()
        assertFailsWith<Exception> { newLock(extendSignals).extendDetailed(1.seconds) }
        extendSignals shouldBeEqualTo listOf(false)
        restoreSchema()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - leaseTime 만료 후 동일 슬롯을 다른 인스턴스가 takeover한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val leaseTime = 200.milliseconds
        val expiredLock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        expiredLock.tryLock(1.seconds, leaseTime)

        delay(timeMillis = leaseTime.inWholeMilliseconds * 2 + 50)

        val newLock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        val acquired = newLock.tryLock(2.seconds, 10.seconds)

        acquired.shouldNotBeNull().shouldBeTrue()
        newLock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - DB 시간 모드에서는 JVM clock skew가 슬롯 소유권을 깨뜨리지 않는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val oldJvmClock = Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC)
        val futureJvmClock = Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), ZoneOffset.UTC)

        val holder = ExposedR2dbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
            clock = oldJvmClock,
        )
        holder.tryLock(1.seconds, 30.seconds).shouldNotBeNull().shouldBeTrue()

        val contender = ExposedR2dbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
            useDbTime = true,
            clock = futureJvmClock,
        )
        contender.tryLock(100.milliseconds, 5.seconds).shouldNotBeNull().shouldBeFalse()

        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 만료된 동일 token에는 minLeaseTime을 적용하지 않는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val now = Instant.parse("2026-01-02T03:04:05Z")
        val lock = ExposedR2dbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        lock.tryLock(1.seconds, 30.seconds).shouldNotBeNull().shouldBeTrue()

        val expiredUntil = now.minusSeconds(1)
        suspendTransaction(db) {
            LeaderGroupLockTable.update(
                where = { (LeaderGroupLockTable.lockName eq lockName) and (LeaderGroupLockTable.slot eq 0) },
            ) {
                it[LeaderGroupLockTable.lockedUntil] = expiredUntil
            }
        }

        lock.unlock(minLeaseTime = 1.minutes)

        suspendTransaction(db) {
            LeaderGroupLockTable
                .selectAll()
                .where { (LeaderGroupLockTable.lockName eq lockName) and (LeaderGroupLockTable.slot eq 0) }
                .first()[LeaderGroupLockTable.lockedUntil]
        }.shouldBeEqualTo(expiredUntil)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 동일 token으로 해제 성공 후 재획득이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val lock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 10.seconds)

        lock.unlock()

        val reacquire = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        reacquire.tryLock(1.seconds, 10.seconds).shouldNotBeNull().shouldBeTrue()
        reacquire.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 이미 해제된 슬롯 락에 재호출해도 예외가 발생하지 않는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 10.seconds)
        lock.unlock()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 슬롯 락 획득 후 true 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 10.seconds).shouldNotBeNull().shouldBeTrue()

        lock.isHeldByCurrentInstance().shouldBeTrue()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - unlock 이후 false 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 10.seconds)
        lock.unlock()

        lock.isHeldByCurrentInstance().shouldBeFalse()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 다른 인스턴스 token으로는 false를 반환한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        holder.tryLock(1.seconds, 30.seconds).shouldNotBeNull().shouldBeTrue()

        val other = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        other.isHeldByCurrentInstance().shouldBeFalse()

        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - leaseTime 만료 시 false 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val leaseTime = 150.milliseconds
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(1.seconds, leaseTime)

        delay(timeMillis = leaseTime.inWholeMilliseconds * 2)

        lock.isHeldByCurrentInstance().shouldBeFalse()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 코루틴 10개 동일 슬롯 경합 시 단 하나만 획득에 성공한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)

        val jobs = (1..10).map {
            async {
                val lock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 10L))
                if (lock.tryLock(200.milliseconds, 5.seconds) == true) {
                    successCount.incrementAndGet()
                    // action delay > waitTime(200ms) → 나머지 경합자들이 모두 타임아웃
                    delay(300.milliseconds)
                    lock.unlock()
                }
            }
        }
        jobs.awaitAll()

        log.debug { "동일 슬롯 동시 경합 성공 횟수: ${successCount.get()}" }
        successCount.get() shouldBeGreaterOrEqualTo 1
        successCount.get() shouldBeEqualTo 1
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 슬롯0과 슬롯1 동시 경합 시 각각 단 하나씩 성공한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val slot0Count = java.util.concurrent.atomic.AtomicInteger(0)
        val slot1Count = java.util.concurrent.atomic.AtomicInteger(0)

        val jobs0 = (1..5).map {
            async {
                val lock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 10L))
                if (lock.tryLock(200.milliseconds, 5.seconds) == true) {
                    slot0Count.incrementAndGet()
                    // action delay > waitTime(200ms) → 나머지 경합자들이 모두 타임아웃
                    delay(300.milliseconds)
                    lock.unlock()
                }
            }
        }
        val jobs1 = (1..5).map {
            async {
                val lock = ExposedR2dbcGroupLock(db, lockName, slot = 1, RetryStrategy.Fixed(fixedMs = 10L))
                if (lock.tryLock(200.milliseconds, 5.seconds) == true) {
                    slot1Count.incrementAndGet()
                    // action delay > waitTime(200ms) → 나머지 경합자들이 모두 타임아웃
                    delay(300.milliseconds)
                    lock.unlock()
                }
            }
        }
        (jobs0 + jobs1).awaitAll()

        log.debug { "슬롯0 성공: ${slot0Count.get()}, 슬롯1 성공: ${slot1Count.get()}" }
        slot0Count.get() shouldBeEqualTo 1
        slot1Count.get() shouldBeEqualTo 1
    }
}
